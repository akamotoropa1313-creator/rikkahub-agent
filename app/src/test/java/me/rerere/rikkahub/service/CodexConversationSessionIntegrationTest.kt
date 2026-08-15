package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodexConversationSessionIntegrationTest {
    @Test
    fun `conversation session forwards asynchronous runtime states`() = runBlocking {
        val owner = owner(this)
        val harness = harness(this, threadId = "thread-forward")
        try {
            owner.replaceCodexRuntime(harness.runtime)
            assertTrue(owner.codexState.value is CodexConversationUiState.Ready)

            harness.transport.emitLine(notification("turn/started", turnParams("thread-forward", "turn-1", "inProgress")))
            val running = awaitOwnerState(owner) { it is CodexConversationUiState.Running } as CodexConversationUiState.Running
            assertEquals("turn-1", running.turnId)

            harness.transport.emitLine(notification("turn/completed", turnParams("thread-forward", "turn-1", "completed")))
            val terminal = awaitOwnerState(owner) { it is CodexConversationUiState.Terminal } as CodexConversationUiState.Terminal
            assertEquals(CodexAppServerTurnStatus.Completed, terminal.status)
        } finally {
            owner.cleanup()
        }
    }

    @Test
    fun `failed runtime detaches while failed state remains visible and replacement is allowed`() = runBlocking {
        val owner = owner(this)
        lateinit var harness: Harness
        harness = harness(this, threadId = "thread-failed") { failed, _ ->
            owner.publishCodexState(CodexConversationUiState.Failed("transport failed"))
            if (owner.detachCodexRuntime(failed)) failed.close()
        }
        try {
            owner.replaceCodexRuntime(harness.runtime)
            assertSame(harness.runtime, owner.codexRuntime)

            harness.transport.emitFailure(IllegalStateException("transport failed"))
            awaitOwnerState(owner) { it is CodexConversationUiState.Failed }
            withTimeout(2_000) {
                while (owner.codexRuntime != null) kotlinx.coroutines.yield()
            }
            assertNull(owner.codexRuntime)
            assertTrue(owner.codexState.value is CodexConversationUiState.Failed)

            val replacement = harness(this, threadId = "thread-recovered")
            owner.replaceCodexRuntime(replacement.runtime)
            assertSame(replacement.runtime, owner.codexRuntime)
            val ready = awaitOwnerState(owner) { it is CodexConversationUiState.Ready } as CodexConversationUiState.Ready
            assertEquals("thread-recovered", ready.threadId)
        } finally {
            owner.cleanup()
        }
    }

    @Test
    fun `turn orchestration job stays active until terminal notification`() = runBlocking {
        val harness = harness(this, threadId = "thread-generation")
        try {
            val generation: Job = launch {
                harness.runtime.acceptStartResponse("turn-1", CodexAppServerTurnStatus.InProgress)
                harness.runtime.awaitTurnTerminal("turn-1")
                harness.runtime.finishTurn("turn-1")
            }

            awaitRuntimeState(harness.runtime) { it is CodexConversationUiState.Running }
            assertTrue("InProgress response must not complete the generation job", generation.isActive)

            harness.transport.emitLine(notification("turn/completed", turnParams("thread-generation", "turn-1", "completed")))
            withTimeout(2_000) { generation.join() }
            assertTrue(generation.isCompleted)
        } finally {
            harness.runtime.close()
        }
    }

    private fun owner(scope: CoroutineScope): ConversationSession {
        val id = Uuid.random()
        return ConversationSession(id, Conversation.ofId(id), scope, {})
    }

    private suspend fun awaitOwnerState(
        owner: ConversationSession,
        predicate: (CodexConversationUiState) -> Boolean,
    ): CodexConversationUiState = withTimeout(2_000) { owner.codexState.first(predicate) }

    private suspend fun awaitRuntimeState(
        runtime: CodexChatRuntime,
        predicate: (CodexConversationUiState) -> Boolean,
    ): CodexConversationUiState = withTimeout(2_000) { runtime.state.first(predicate) }

    private fun harness(
        scope: CoroutineScope,
        threadId: String,
        onFailure: (CodexChatRuntime, Throwable) -> Unit = { _, _ -> },
    ): Harness {
        val binding = CodexAppServerSessionBindingEntity(
            conversationId = "conversation-$threadId",
            workspaceId = "workspace-1",
            threadId = threadId,
            workspaceCwd = "",
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        val repository = CodexAppServerSessionBindingRepository(
            FakeBindingDao(binding),
            object : CodexAppServerLocalState {
                override suspend fun conversationExists(id: String) = true
                override suspend fun getWorkspace(id: String): WorkspaceEntity? = null
            },
        ) { 2L }
        val transport = FakeTransport()
        val session = CodexAppServerConversationSession(
            binding,
            CodexAppServerConnection(
                CodexAppServerRequestDispatcher(transport),
                CodexAppServerClientInfo(version = "test"),
            ),
            repository,
        )
        return Harness(
            CodexChatRuntime(session, scope, onAgentText = { _, _, _ -> }, onFailure = onFailure),
            transport,
        )
    }

    private data class Harness(val runtime: CodexChatRuntime, val transport: FakeTransport)

    private class FakeTransport : CodexAppServerTransport {
        private val inbound = Channel<CodexAppServerTransportEvent>(Channel.UNLIMITED)
        override val events: Flow<CodexAppServerTransportEvent> = inbound.receiveAsFlow()
        override suspend fun sendLine(line: String) = Unit
        suspend fun emitLine(line: String) { inbound.send(CodexAppServerTransportEvent.Line(line)) }
        suspend fun emitFailure(cause: Throwable) { inbound.send(CodexAppServerTransportEvent.Failure(cause)) }
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

    private fun notification(method: String, params: JsonObject) = "{\"method\":\"$method\",\"params\":$params}"

    private fun turnParams(threadId: String, turnId: String, status: String) = buildJsonObject {
        put("threadId", threadId)
        put("turn", buildJsonObject {
            put("id", turnId)
            put("status", status)
        })
    }
}
