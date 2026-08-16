package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexTokenUsageRecoveryIntegrationTest {
    @Test
    fun `cold resume captures usage emitted immediately after response`() = runBlocking {
        val transport = FakeCodexAppServerTransport()
        val connection = CodexAppServerConnection(
            CodexAppServerRequestDispatcher(transport),
            CodexAppServerClientInfo(name = "test", version = "1"),
        )
        val local = LocalState()
        val dao = Dao()
        val repository = CodexAppServerSessionBindingRepository(dao, local) { 100L }
        repository.bindPersistentThread(
            "conversation",
            "workspace",
            "src",
            CodexAppServerThreadSnapshot(
                "thread-1",
                JsonObject(mapOf("id" to JsonPrimitive("thread-1"), "ephemeral" to JsonPrimitive(false))),
            ),
        )
        val recovery = CodexAppServerSessionRecovery(
            repository,
            local,
            CodexAppServerConnectionCreator { _, _ -> connection },
        )

        val recovering = async { recovery.recover("conversation") }
        respondInitialize(transport)
        val resume = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        assertEquals("thread/resume", resume["method"]?.jsonPrimitive?.content)

        // Deliberately enqueue the replay directly behind the response. A tracker created only
        // after resume returns can miss this on the connection's no-replay event stream.
        transport.injectServerLine(
            """{"id":${resume["id"]},"result":{"thread":{"id":"thread-1"},"model":"m","modelProvider":"p","cwd":"src"}}""",
        )
        transport.injectServerLine(usageLine(total = 12_000, last = 4_321))

        val session = (recovering.await() as CodexAppServerSessionRecoveryResult.Recovered).session
        val telemetry = withTimeout(2_000) {
            session.tokenUsageTracker.state.first { it.latest != null }
        }
        assertEquals("turn-old", telemetry.latest?.turnId)
        assertEquals(12_000L, telemetry.latest?.tokenUsage?.total?.totalTokens)
        assertEquals(4_321L, telemetry.latest?.tokenUsage?.last?.totalTokens)
        session.close()
    }

    @Test
    fun `malformed usage is isolated and keeps the latest valid snapshot`() = runBlocking {
        val transport = FakeCodexAppServerTransport()
        val connection = CodexAppServerConnection(
            CodexAppServerRequestDispatcher(transport),
            CodexAppServerClientInfo(name = "test", version = "1"),
        )
        val tracker = CodexTokenUsageTracker(connection, "thread-1")
        try {
            transport.injectServerLine(usageLine(total = 10, last = 5))
            withTimeout(2_000) { tracker.state.first { it.latest != null } }
            transport.injectServerLine(
                """{"method":"thread/tokenUsage/updated","params":{"threadId":"thread-1","turnId":"turn-bad","tokenUsage":{"total":{},"last":{},"modelContextWindow":null}}}""",
            )
            val warned = withTimeout(2_000) { tracker.state.first { it.warning != null } }
            assertEquals(10L, warned.latest?.tokenUsage?.total?.totalTokens)
            assertEquals("turn-old", warned.latest?.turnId)
            assertNotNull(warned.warning)
            assertTrue(connection.state.value !is CodexAppServerConnectionState.Failed)
        } finally {
            tracker.close()
            connection.close()
        }
    }

    private suspend fun respondInitialize(transport: FakeCodexAppServerTransport) {
        val request = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        assertEquals("initialize", request["method"]?.jsonPrimitive?.content)
        transport.injectServerLine(
            """{"id":${request["id"]},"result":{"userAgent":"test","codexHome":"/home","platformFamily":"unix","platformOs":"linux"}}""",
        )
        val initialized = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        assertEquals("initialized", initialized["method"]?.jsonPrimitive?.content)
    }

    private fun usageLine(total: Long, last: Long) =
        """{"method":"thread/tokenUsage/updated","params":{"threadId":"thread-1","turnId":"turn-old","tokenUsage":{"total":{"totalTokens":$total,"inputTokens":8,"cachedInputTokens":2,"cacheWriteInputTokens":1,"outputTokens":2,"reasoningOutputTokens":1},"last":{"totalTokens":$last,"inputTokens":4,"cachedInputTokens":1,"cacheWriteInputTokens":1,"outputTokens":1,"reasoningOutputTokens":1},"modelContextWindow":200000}}}"""

    private class LocalState : CodexAppServerLocalState {
        override suspend fun conversationExists(id: String) = id == "conversation"
        override suspend fun getWorkspace(id: String) =
            if (id == "workspace") WorkspaceEntity(id, id, "/root/workspace", createdAt = 0, updatedAt = 0) else null
    }

    private class Dao : CodexAppServerSessionBindingDao {
        private var row: CodexAppServerSessionBindingEntity? = null
        private val state = MutableStateFlow<CodexAppServerSessionBindingEntity?>(null)

        override suspend fun getByConversationId(conversationId: String) = row?.takeIf { it.conversationId == conversationId }
        override fun observeByConversationId(conversationId: String): Flow<CodexAppServerSessionBindingEntity?> = state
        override suspend fun getByThreadId(threadId: String) = row?.takeIf { it.threadId == threadId }
        override suspend fun upsert(binding: CodexAppServerSessionBindingEntity) { row = binding; state.value = binding }
        override suspend fun insertIfAbsent(binding: CodexAppServerSessionBindingEntity): Long {
            if (row != null) return -1
            upsert(binding)
            return 1
        }
        override suspend fun updateLastObservedTurn(
            conversationId: String,
            expectedThreadId: String,
            turnId: String,
            status: String,
            updatedAtMs: Long,
        ): Int = update(conversationId, expectedThreadId) {
            it.copy(lastObservedTurnId = turnId, lastObservedTurnStatus = status, updatedAtMs = updatedAtMs)
        }
        override suspend fun updateLastObservedTurnStarted(
            conversationId: String,
            expectedThreadId: String,
            turnId: String,
            updatedAtMs: Long,
        ): Int = updateLastObservedTurn(conversationId, expectedThreadId, turnId, "inProgress", updatedAtMs)
        override suspend fun updateLastResumed(conversationId: String, expectedThreadId: String, resumedAtMs: Long): Int =
            update(conversationId, expectedThreadId) { it.copy(lastResumedAtMs = resumedAtMs, updatedAtMs = resumedAtMs) }
        override suspend fun deleteByConversationId(conversationId: String): Int {
            if (row?.conversationId != conversationId) return 0
            row = null
            state.value = null
            return 1
        }
        private fun update(
            conversationId: String,
            expectedThreadId: String,
            transform: (CodexAppServerSessionBindingEntity) -> CodexAppServerSessionBindingEntity,
        ): Int {
            val current = row?.takeIf { it.conversationId == conversationId && it.threadId == expectedThreadId } ?: return 0
            row = transform(current)
            state.value = row
            return 1
        }
    }
}
