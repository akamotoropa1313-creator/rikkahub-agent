package me.rerere.rikkahub.service

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerClientInfo
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConnection
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConversationSession
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerLocalState
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerRequestDispatcher
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerSessionBindingRepository
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTransport
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTransportEvent
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnStatus
import me.rerere.rikkahub.data.codex.appserver.JsonRpcId
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexStage20RuntimeIntegrationTest {
    @Test
    fun `usage remains live while waiting for approval and malformed telemetry does not fail turn`() = runBlocking {
        val harness = harness(this)
        try {
            harness.transport.emit(turnStarted())
            awaitState(harness.runtime) { it is CodexConversationUiState.Running }

            harness.transport.emit(usage(total = 10, last = 5))
            val running = awaitState(harness.runtime) {
                it is CodexConversationUiState.Running && it.telemetry.latest?.tokenUsage?.total?.totalTokens == 10L
            } as CodexConversationUiState.Running
            assertEquals(5L, running.telemetry.latest?.tokenUsage?.last?.totalTokens)

            harness.transport.emit(commandApproval())
            val waiting = awaitState(harness.runtime) {
                it is CodexConversationUiState.WaitingForApproval && it.telemetry.latest?.tokenUsage?.total?.totalTokens == 10L
            } as CodexConversationUiState.WaitingForApproval
            assertEquals(JsonRpcId.StringId("approval-1"), waiting.requestId)

            harness.transport.emit(usage(total = 20, last = 7))
            val updatedWaiting = awaitState(harness.runtime) {
                it is CodexConversationUiState.WaitingForApproval && it.telemetry.latest?.tokenUsage?.total?.totalTokens == 20L
            } as CodexConversationUiState.WaitingForApproval
            assertEquals(7L, updatedWaiting.telemetry.latest?.tokenUsage?.last?.totalTokens)

            harness.transport.emit(malformedUsage())
            val warned = awaitState(harness.runtime) {
                it is CodexConversationUiState.WaitingForApproval && it.telemetry.warning != null
            } as CodexConversationUiState.WaitingForApproval
            assertEquals(20L, warned.telemetry.latest?.tokenUsage?.total?.totalTokens)
            assertNotNull(warned.telemetry.warning)
            assertTrue(harness.runtime.activeTurnId() == "turn-1")
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `terminal response is enriched by same-turn completion without a second terminal callback`() = runBlocking {
        val callbacks = AtomicInteger()
        val harness = harness(this, callbacks)
        try {
            harness.runtime.acceptStartResponse("turn-1", CodexAppServerTurnStatus.Completed)
            val first = harness.runtime.state.value as CodexConversationUiState.Terminal
            assertNull(first.diagnostics)
            assertEquals(1, callbacks.get())

            harness.transport.emit(
                """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","itemsView":"full","error":null,"startedAt":100,"completedAt":102,"durationMs":2345}}}""",
            )
            val enriched = awaitState(harness.runtime) {
                it is CodexConversationUiState.Terminal && it.diagnostics?.durationMs == 2_345L
            } as CodexConversationUiState.Terminal
            assertEquals(CodexAppServerTurnStatus.Completed, enriched.status)
            assertEquals(100L, enriched.diagnostics?.startedAt)
            assertEquals(102L, enriched.diagnostics?.completedAt)
            assertEquals(1, callbacks.get())

            harness.runtime.acceptStartResponse("turn-1", CodexAppServerTurnStatus.InProgress)
            val afterLateStart = harness.runtime.state.value as CodexConversationUiState.Terminal
            assertEquals(2_345L, afterLateStart.diagnostics?.durationMs)
            assertEquals(1, callbacks.get())
        } finally {
            harness.runtime.close()
        }
    }

    private suspend fun awaitState(
        runtime: CodexChatRuntime,
        predicate: (CodexConversationUiState) -> Boolean,
    ): CodexConversationUiState = withTimeout(2_000) { runtime.state.first(predicate) }

    private fun harness(scope: CoroutineScope, callbacks: AtomicInteger = AtomicInteger()): Harness {
        val binding = CodexAppServerSessionBindingEntity(
            conversationId = "conversation-1",
            workspaceId = "workspace-1",
            threadId = "thread-1",
            workspaceCwd = "",
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        val repository = CodexAppServerSessionBindingRepository(
            Dao(binding),
            object : CodexAppServerLocalState {
                override suspend fun conversationExists(id: String) = true
                override suspend fun getWorkspace(id: String): WorkspaceEntity? = null
            },
        ) { 2L }
        val transport = Transport()
        val connection = CodexAppServerConnection(
            CodexAppServerRequestDispatcher(transport),
            CodexAppServerClientInfo(name = "test", version = "1"),
        )
        val session = CodexAppServerConversationSession(binding, connection, repository)
        val runtime = CodexChatRuntime(
            session,
            scope,
            onAgentText = { _, _, _ -> },
            onTurnTerminal = { callbacks.incrementAndGet() },
        )
        return Harness(runtime, transport)
    }

    private data class Harness(val runtime: CodexChatRuntime, val transport: Transport)

    private class Transport : CodexAppServerTransport {
        private val inbound = Channel<CodexAppServerTransportEvent>(Channel.UNLIMITED)
        val sentLines = CopyOnWriteArrayList<String>()
        override val events: Flow<CodexAppServerTransportEvent> = inbound.receiveAsFlow()
        override suspend fun sendLine(line: String) { sentLines += line }
        suspend fun emit(line: String) { inbound.send(CodexAppServerTransportEvent.Line(line)) }
        override fun close() { inbound.trySend(CodexAppServerTransportEvent.Closed); inbound.close() }
    }

    private class Dao(initial: CodexAppServerSessionBindingEntity) : CodexAppServerSessionBindingDao {
        private var binding: CodexAppServerSessionBindingEntity? = initial
        override suspend fun getByConversationId(conversationId: String) = binding?.takeIf { it.conversationId == conversationId }
        override fun observeByConversationId(conversationId: String) = flowOf(binding?.takeIf { it.conversationId == conversationId })
        override suspend fun getByThreadId(threadId: String) = binding?.takeIf { it.threadId == threadId }
        override suspend fun upsert(binding: CodexAppServerSessionBindingEntity) { this.binding = binding }
        override suspend fun insertIfAbsent(binding: CodexAppServerSessionBindingEntity): Long {
            if (this.binding != null) return -1
            this.binding = binding
            return 1
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
        override suspend fun updateLastResumed(conversationId: String, expectedThreadId: String, resumedAtMs: Long): Int = 1
        override suspend fun deleteByConversationId(conversationId: String): Int = 0
    }

    private fun turnStarted() =
        """{"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress"}}}"""

    private fun usage(total: Long, last: Long) =
        """{"method":"thread/tokenUsage/updated","params":{"threadId":"thread-1","turnId":"turn-1","tokenUsage":{"total":{"totalTokens":$total,"inputTokens":8,"cachedInputTokens":2,"cacheWriteInputTokens":1,"outputTokens":2,"reasoningOutputTokens":1},"last":{"totalTokens":$last,"inputTokens":4,"cachedInputTokens":1,"cacheWriteInputTokens":1,"outputTokens":1,"reasoningOutputTokens":1},"modelContextWindow":200000}}}"""

    private fun malformedUsage() =
        """{"method":"thread/tokenUsage/updated","params":{"threadId":"thread-1","turnId":"turn-1","tokenUsage":{"total":{},"last":{},"modelContextWindow":null}}}"""

    private fun commandApproval() =
        """{"id":"approval-1","method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"command-1","startedAtMs":20,"approvalId":"callback-1","command":"echo test","cwd":"/workspace","commandActions":[]}}"""
}
