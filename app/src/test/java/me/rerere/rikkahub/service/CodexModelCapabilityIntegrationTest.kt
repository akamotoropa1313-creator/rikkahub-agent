package me.rerere.rikkahub.service

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerClientInfo
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConnection
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConversationSession
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerLocalState
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerRequestDispatcher
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerSessionBindingRepository
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTransport
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTransportEvent
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnStatus
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexModelCapabilityIntegrationTest {
    @Test
    fun `model refresh failure remains capability local and preserves runtime session`() = runBlocking {
        val harness = harness(this)
        try {
            val originalSession = harness.runtime.session
            val failure = runCatching { harness.runtime.refreshModels() }.exceptionOrNull()
            assertNotNull(failure)
            assertSame(originalSession, harness.runtime.session)
            assertTrue(harness.runtime.capabilities.value.connected)
            assertNotNull(harness.runtime.capabilities.value.modelsError)
            assertTrue(harness.runtime.capabilities.value.models.isEmpty())
            assertTrue(harness.runtime.state.value !is CodexConversationUiState.Failed)
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `model refresh is rejected while a turn is active without mutating catalog state`() = runBlocking {
        val harness = harness(this)
        try {
            harness.runtime.acceptStartResponse("turn-1", CodexAppServerTurnStatus.InProgress)
            val failure = runCatching { harness.runtime.refreshModels() }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(emptyList<Any>(), harness.runtime.capabilities.value.models)
            assertEquals(null, harness.runtime.capabilities.value.modelsError)
        } finally {
            harness.runtime.close()
        }
    }

    private fun harness(scope: CoroutineScope): Harness {
        val binding = CodexAppServerSessionBindingEntity(
            conversationId = "conversation-1",
            workspaceId = "workspace-1",
            threadId = "thread-1",
            workspaceCwd = "",
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        val dao = FakeBindingDao(binding)
        val repository = CodexAppServerSessionBindingRepository(
            dao,
            object : CodexAppServerLocalState {
                override suspend fun conversationExists(id: String) = true
                override suspend fun getWorkspace(id: String): WorkspaceEntity? = null
            },
        ) { 2L }
        val transport = FakeTransport()
        val connection = CodexAppServerConnection(
            CodexAppServerRequestDispatcher(transport),
            CodexAppServerClientInfo(version = "test"),
        )
        val session = CodexAppServerConversationSession(binding, connection, repository)
        return Harness(CodexChatRuntime(session, scope, onAgentText = { _, _, _ -> }), transport)
    }

    private data class Harness(val runtime: CodexChatRuntime, val transport: FakeTransport)

    private class FakeTransport : CodexAppServerTransport {
        private val inbound = Channel<CodexAppServerTransportEvent>(Channel.UNLIMITED)
        val sentLines = CopyOnWriteArrayList<String>()
        override val events: Flow<CodexAppServerTransportEvent> = inbound.receiveAsFlow()
        override suspend fun sendLine(line: String) { sentLines += line }
        override fun close() {
            inbound.trySend(CodexAppServerTransportEvent.Closed)
            inbound.close()
        }
    }

    private class FakeBindingDao(initial: CodexAppServerSessionBindingEntity) : CodexAppServerSessionBindingDao {
        private var binding: CodexAppServerSessionBindingEntity? = initial
        override suspend fun getByConversationId(conversationId: String) = binding?.takeIf { it.conversationId == conversationId }
        override fun observeByConversationId(conversationId: String) = flowOf(binding?.takeIf { it.conversationId == conversationId })
        override suspend fun getByThreadId(threadId: String) = binding?.takeIf { it.threadId == threadId }
        override suspend fun upsert(binding: CodexAppServerSessionBindingEntity) { this.binding = binding }
        override suspend fun insertIfAbsent(binding: CodexAppServerSessionBindingEntity): Long {
            if (this.binding != null) return -1L
            this.binding = binding
            return 1L
        }
        override suspend fun updateLastObservedTurn(
            conversationId: String,
            expectedThreadId: String,
            turnId: String,
            status: String,
            updatedAtMs: Long,
        ): Int {
            val current = binding ?: return 0
            if (current.conversationId != conversationId || current.threadId != expectedThreadId) return 0
            binding = current.copy(lastObservedTurnId = turnId, lastObservedTurnStatus = status, updatedAtMs = updatedAtMs)
            return 1
        }
        override suspend fun updateLastObservedTurnStarted(
            conversationId: String,
            expectedThreadId: String,
            turnId: String,
            updatedAtMs: Long,
        ): Int = updateLastObservedTurn(conversationId, expectedThreadId, turnId, "inProgress", updatedAtMs)
        override suspend fun updateLastResumed(conversationId: String, expectedThreadId: String, resumedAtMs: Long): Int {
            val current = binding ?: return 0
            if (current.conversationId != conversationId || current.threadId != expectedThreadId) return 0
            binding = current.copy(lastResumedAtMs = resumedAtMs, updatedAtMs = resumedAtMs)
            return 1
        }
        override suspend fun deleteByConversationId(conversationId: String): Int {
            if (binding?.conversationId != conversationId) return 0
            binding = null
            return 1
        }
    }
}
