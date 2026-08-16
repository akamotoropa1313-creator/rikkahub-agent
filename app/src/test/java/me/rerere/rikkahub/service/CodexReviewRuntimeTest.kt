package me.rerere.rikkahub.service

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import me.rerere.rikkahub.data.codex.appserver.JsonRpcId
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexReviewRuntimeTest {
    @Test
    fun `review canonical exit text is persisted once and draft agent deltas are suppressed`() = runBlocking {
        val persisted = CopyOnWriteArrayList<Triple<String, String, String>>()
        val harness = harness(this) { turnId, itemId, text -> persisted += Triple(turnId, itemId, text) }
        try {
            harness.transport.emit(notification("turn/started", turnParams("inProgress")))
            await { harness.runtime.state.value is CodexConversationUiState.Running }

            harness.transport.emit(notification("item/started", itemParams(enteredReview("review-enter"), "startedAtMs", 10)))
            harness.transport.emit(notification("item/agentMessage/delta", deltaParams("draft-message", "draft review text")))
            repeat(8) { yield() }
            assertTrue("review draft must not be persisted", persisted.isEmpty())

            val exited = itemParams(exitedReview("review-exit", "canonical review"), "completedAtMs", 20)
            harness.transport.emit(notification("item/completed", exited))
            await { persisted.size == 1 }
            assertEquals(Triple("turn-1", "review-exit", "canonical review"), persisted.single())

            // Late/duplicate completion notifications remain idempotent.
            harness.transport.emit(notification("item/completed", exited))
            repeat(8) { yield() }
            assertEquals(1, persisted.size)
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `approval during review remains on the same ordinary turn lifecycle`() = runBlocking {
        val harness = harness(this) { _, _, _ -> }
        try {
            harness.transport.emit(notification("turn/started", turnParams("inProgress")))
            harness.transport.emit(notification("item/started", itemParams(enteredReview("review-enter"), "startedAtMs", 10)))
            await { harness.runtime.state.value is CodexConversationUiState.Running }

            val requestId = JsonRpcId.StringId("review-approval")
            harness.transport.emit(serverRequest(requestId, "item/commandExecution/requestApproval", commandApprovalParams("command-1")))
            await {
                val state = harness.runtime.state.value
                state is CodexConversationUiState.WaitingForApproval && state.requestId == requestId
            }
            val waiting = harness.runtime.state.value as CodexConversationUiState.WaitingForApproval
            assertEquals("turn-1", waiting.event.turnIdForTest())

            harness.transport.emit(notification("serverRequest/resolved", resolved("review-approval")))
            await { harness.runtime.state.value is CodexConversationUiState.Running }
            assertEquals("turn-1", (harness.runtime.state.value as CodexConversationUiState.Running).turnId)
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `review stop controller emits only one interrupt claim for an active turn`() {
        val stop = CodexTurnStopController()
        stop.requestStop()
        assertTrue(stop.onTurnKnown("review-turn"))
        assertFalse(stop.onTurnKnown("review-turn"))
        assertFalse(stop.onTurnKnown("review-turn"))
        stop.finishTurn("review-turn")

        // A later turn starts from a clean controller and is not interrupted unless Stop is requested again.
        assertFalse(stop.onTurnKnown("next-turn"))
        stop.requestStop()
        assertTrue(stop.onTurnKnown("next-turn"))
        assertFalse(stop.onTurnKnown("next-turn"))
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) yield()
        }
    }

    private fun harness(
        scope: CoroutineScope,
        onAgentText: suspend (String, String, String) -> Unit,
    ): Harness {
        val binding = CodexAppServerSessionBindingEntity(
            conversationId = "conversation-1",
            workspaceId = "workspace-1",
            threadId = "thread-1",
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
        val connection = CodexAppServerConnection(
            CodexAppServerRequestDispatcher(transport),
            CodexAppServerClientInfo(version = "test"),
        )
        val session = CodexAppServerConversationSession(binding, connection, repository)
        return Harness(CodexChatRuntime(session, scope, onAgentText = onAgentText), transport)
    }

    private data class Harness(val runtime: CodexChatRuntime, val transport: FakeTransport)

    private class FakeTransport : CodexAppServerTransport {
        private val inbound = Channel<CodexAppServerTransportEvent>(Channel.UNLIMITED)
        override val events: Flow<CodexAppServerTransportEvent> = inbound.receiveAsFlow()
        override suspend fun sendLine(line: String) = Unit
        suspend fun emit(line: String) { inbound.send(CodexAppServerTransportEvent.Line(line)) }
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
        override suspend fun updateLastObservedTurn(conversationId: String, expectedThreadId: String, turnId: String, status: String, updatedAtMs: Long): Int {
            val current = binding ?: return 0
            if (current.conversationId != conversationId || current.threadId != expectedThreadId) return 0
            binding = current.copy(lastObservedTurnId = turnId, lastObservedTurnStatus = status, updatedAtMs = updatedAtMs)
            return 1
        }
        override suspend fun updateLastObservedTurnStarted(conversationId: String, expectedThreadId: String, turnId: String, updatedAtMs: Long): Int =
            updateLastObservedTurn(conversationId, expectedThreadId, turnId, "inProgress", updatedAtMs)
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

    private fun notification(method: String, params: JsonObject) =
        "{\"method\":${JsonPrimitive(method)},\"params\":$params}"

    private fun serverRequest(id: JsonRpcId, method: String, params: JsonObject): String {
        val encoded = when (id) {
            is JsonRpcId.StringId -> JsonPrimitive(id.value)
            is JsonRpcId.NumberId -> JsonPrimitive(id.value)
        }
        return "{\"id\":$encoded,\"method\":${JsonPrimitive(method)},\"params\":$params}"
    }

    private fun turnParams(status: String) = buildJsonObject {
        put("threadId", "thread-1")
        put("turn", buildJsonObject { put("id", "turn-1"); put("status", status) })
    }

    private fun enteredReview(id: String) = buildJsonObject {
        put("type", "enteredReviewMode")
        put("id", id)
        put("review", "reviewing")
    }

    private fun exitedReview(id: String, text: String) = buildJsonObject {
        put("type", "exitedReviewMode")
        put("id", id)
        put("review", text)
    }

    private fun itemParams(item: JsonObject, timeKey: String, time: Long) = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", "turn-1")
        put("item", item)
        put(timeKey, time)
    }

    private fun deltaParams(itemId: String, delta: String) = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", "turn-1")
        put("itemId", itemId)
        put("delta", delta)
    }

    private fun commandApprovalParams(itemId: String) = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", "turn-1")
        put("itemId", itemId)
        put("startedAtMs", 20)
        put("approvalId", "callback-1")
        put("command", "echo test")
        put("cwd", "/workspace")
        put("commandActions", JsonArray(emptyList()))
    }

    private fun resolved(requestId: String) = buildJsonObject {
        put("threadId", "thread-1")
        put("requestId", requestId)
    }

    private fun me.rerere.rikkahub.data.codex.appserver.CodexAppServerApprovalEvent.turnIdForTest(): String? = when (this) {
        is me.rerere.rikkahub.data.codex.appserver.CodexAppServerApprovalEvent.CommandExecutionRequest -> request.turnId
        is me.rerere.rikkahub.data.codex.appserver.CodexAppServerApprovalEvent.FileChangeRequest -> request.turnId
        else -> null
    }
}
