package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CodexAppServerReviewSessionTest {
    private val codec = CodexAppServerJsonRpc()

    @Test
    fun `inline review rejects foreign review thread before durable turn mutation`() = runBlocking {
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
        val transport = FakeCodexAppServerTransport()
        val dispatcher = CodexAppServerRequestDispatcher(transport)
        val connection = CodexAppServerConnection(dispatcher, CodexAppServerClientInfo(name = "test", version = "1"))

        val initialize = async { connection.initialize() }
        val initRequest = decodeRequest(transport.takeClientLine())
        transport.injectServerLine(codec.encode(JsonRpcResponse(initRequest.id, buildJsonObject {
            put("userAgent", "fake")
            put("codexHome", "/tmp")
            put("platformFamily", "unix")
            put("platformOs", "linux")
        })))
        initialize.await()
        transport.takeClientLine() // initialized notification

        val session = CodexAppServerConversationSession(binding, connection, repository)
        try {
            val call = async { session.startReview(CodexAppServerReviewTarget.UncommittedChanges) }
            val request = decodeRequest(transport.takeClientLine())
            assertEquals("review/start", request.method)
            transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, buildJsonObject {
                put("turn", buildJsonObject { put("id", "review-turn"); put("status", "inProgress") })
                put("reviewThreadId", "foreign-thread")
            })))

            expect<CodexAppServerTurnProtocolException> { call.await() }
            val after = dao.getByConversationId("conversation-1")!!
            assertEquals("thread-1", after.threadId)
            assertEquals(null, after.lastObservedTurnId)
            assertEquals(null, after.lastObservedTurnStatus)
            assertEquals(0, dispatcher.pendingRequestCount())
        } finally {
            session.close()
        }
    }

    private fun decodeRequest(line: String) = (codec.decode(line).getOrThrow() as JsonRpcMessage.Request).value

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
