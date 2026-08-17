package me.rerere.rikkahub.service

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerClientInfo
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConnection
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConversationSession
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerEvent
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerJsonRpc
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerLocalState
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerRequestDispatcher
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerResponseException
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerReviewTarget
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerSessionBindingRepository
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnStatus
import me.rerere.rikkahub.data.codex.appserver.FakeCodexAppServerTransport
import me.rerere.rikkahub.data.codex.appserver.JsonRpcError
import me.rerere.rikkahub.data.codex.appserver.JsonRpcErrorResponse
import me.rerere.rikkahub.data.codex.appserver.JsonRpcMessage
import me.rerere.rikkahub.data.codex.appserver.JsonRpcNotification
import me.rerere.rikkahub.data.codex.appserver.JsonRpcRequest
import me.rerere.rikkahub.data.codex.appserver.JsonRpcResponse
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CodexReviewStartRaceTest {
    private val codec = CodexAppServerJsonRpc()

    @Test
    fun `review notification may precede start response without duplicate final text`() = runBlocking {
        val persisted = CopyOnWriteArrayList<String>()
        val harness = harness(this) { _, _, text -> persisted += text }
        try {
            val call = async(start = CoroutineStart.UNDISPATCHED) { harness.runtime.startReview(CodexAppServerReviewTarget.UncommittedChanges) }
            val request = decodeRequest(harness.transport.takeClientLine())
            assertEquals("review/start", request.method)

            harness.transport.injectServerLine(codec.encode(JsonRpcNotification("turn/started", turnParams("inProgress"))))
            harness.transport.injectServerLine(codec.encode(JsonRpcNotification("item/started", itemParams(enteredReview()))))
            await { harness.runtime.activeTurnId() == "turn-1" }

            harness.respond(request, reviewResult("turn-1", "inProgress", "thread-1"))
            val result = call.await()
            assertEquals("turn-1", result.turn.id)
            assertEquals("thread-1", result.reviewThreadId)
            assertEquals("turn-1", harness.runtime.review.value.activeTurnId)

            val exit = completedItemParams(exitedReview("canonical review"))
            harness.transport.injectServerLine(codec.encode(JsonRpcNotification("item/completed", exit)))
            harness.transport.injectServerLine(codec.encode(JsonRpcNotification("item/completed", exit)))
            await { persisted.size == 1 }
            assertEquals(listOf("canonical review"), persisted)

            harness.transport.injectServerLine(codec.encode(JsonRpcNotification("turn/completed", turnParams("completed"))))
            assertEquals(CodexAppServerTurnStatus.Completed, harness.runtime.awaitTurnTerminal("turn-1"))
            harness.runtime.finishTurn("turn-1")
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `review response may precede notifications and remains one ordinary active turn`() = runBlocking {
        val harness = harness(this) { _, _, _ -> }
        try {
            val call = async(start = CoroutineStart.UNDISPATCHED) { harness.runtime.startReview(CodexAppServerReviewTarget.Custom("Audit correctness")) }
            val request = decodeRequest(harness.transport.takeClientLine())
            harness.respond(request, reviewResult("turn-1", "inProgress", "thread-1"))
            assertEquals("turn-1", call.await().turn.id)
            assertEquals("turn-1", harness.runtime.activeTurnId())

            harness.transport.injectServerLine(codec.encode(JsonRpcNotification("turn/started", turnParams("inProgress"))))
            harness.transport.injectServerLine(codec.encode(JsonRpcNotification("item/started", itemParams(enteredReview()))))
            await { harness.runtime.state.value is CodexConversationUiState.Running }
            assertEquals("turn-1", (harness.runtime.state.value as CodexConversationUiState.Running).turnId)

            harness.transport.injectServerLine(codec.encode(JsonRpcNotification("turn/completed", turnParams("completed"))))
            assertEquals(CodexAppServerTurnStatus.Completed, harness.runtime.awaitTurnTerminal("turn-1"))
            harness.runtime.finishTurn("turn-1")
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `review method not found is local unsupported state and keeps connection usable`() = runBlocking {
        val harness = harness(this) { _, _, _ -> }
        try {
            val failure = supervisorScope {
                val call = async(start = CoroutineStart.UNDISPATCHED) { harness.runtime.startReview(CodexAppServerReviewTarget.UncommittedChanges) }
                val request = decodeRequest(harness.transport.takeClientLine())
                harness.transport.injectServerLine(codec.encode(JsonRpcErrorResponse(
                    request.id,
                    JsonRpcError(-32601L, "method not found"),
                )))
                expect<CodexAppServerResponseException> { call.await() }
            }
            assertEquals(-32601L, failure.error.code)
            assertEquals("Native code review is not supported by this App Server", harness.runtime.review.value.error)
            assertTrue(harness.runtime.capabilities.value.connected)
            assertTrue(harness.runtime.state.value is CodexConversationUiState.Ready)
        } finally {
            harness.runtime.close()
        }
    }

    private suspend fun harness(
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
        val transport = FakeCodexAppServerTransport()
        val connection = CodexAppServerConnection(
            CodexAppServerRequestDispatcher(transport),
            CodexAppServerClientInfo(name = "test", version = "1"),
        )
        val initializing = scope.async(start = CoroutineStart.UNDISPATCHED) { connection.initialize() }
        val initRequest = decodeRequest(transport.takeClientLine())
        transport.injectServerLine(codec.encode(JsonRpcResponse(initRequest.id, buildJsonObject {
            put("userAgent", "fake")
            put("codexHome", "/tmp")
            put("platformFamily", "unix")
            put("platformOs", "linux")
        })))
        initializing.await()
        transport.takeClientLine() // initialized notification

        val session = CodexAppServerConversationSession(binding, connection, repository)
        return Harness(CodexChatRuntime(session, scope, onAgentText = onAgentText), transport)
    }

    private data class Harness(val runtime: CodexChatRuntime, val transport: FakeCodexAppServerTransport) {
        fun respond(request: JsonRpcRequest, result: kotlinx.serialization.json.JsonElement) {
            transport.injectServerLine(CodexAppServerJsonRpc().encode(JsonRpcResponse(request.id, result)))
        }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(2_000) {
        while (!predicate()) yield()
    }

    private fun decodeRequest(line: String) = (codec.decode(line).getOrThrow() as JsonRpcMessage.Request).value

    private fun reviewResult(turnId: String, status: String, reviewThreadId: String) = buildJsonObject {
        put("turn", buildJsonObject { put("id", turnId); put("status", status) })
        put("reviewThreadId", reviewThreadId)
    }

    private fun turnParams(status: String) = buildJsonObject {
        put("threadId", "thread-1")
        put("turn", buildJsonObject { put("id", "turn-1"); put("status", status) })
    }

    private fun enteredReview() = buildJsonObject {
        put("type", "enteredReviewMode")
        put("id", "review-enter")
        put("review", "reviewing")
    }

    private fun exitedReview(text: String) = buildJsonObject {
        put("type", "exitedReviewMode")
        put("id", "review-exit")
        put("review", text)
    }

    private fun itemParams(item: kotlinx.serialization.json.JsonObject) = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", "turn-1")
        put("item", item)
        put("startedAtMs", 10)
    }

    private fun completedItemParams(item: kotlinx.serialization.json.JsonObject) = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", "turn-1")
        put("item", item)
        put("completedAtMs", 20)
    }

    private suspend inline fun <reified T : Throwable> expect(crossinline block: suspend () -> Unit): T = try {
        block()
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    } catch (failure: Throwable) {
        when {
            failure is T -> failure
            failure.cause is T -> failure.cause as T
            else -> throw failure
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
}
