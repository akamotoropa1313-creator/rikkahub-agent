package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CodexAppServerSessionRecoveryTest {
    @Test fun `not bound and stale bindings never create a connection`() = runBlocking {
        var creates = 0
        val empty = Fixture(local = Local(setOf("a"), setOf("w")), creator = CodexAppServerConnectionCreator { _, _ -> creates++; error("unused") })
        assertEquals(CodexAppServerSessionRecoveryResult.NotBound, empty.recovery.recover("a")); assertEquals(0, creates)
        empty.bind(); empty.local.conversations.clear()
        assertTrue(empty.recovery.recover("a") is CodexAppServerSessionRecoveryResult.StaleBinding); assertEquals(0, creates)
        empty.local.conversations += "a"; empty.local.workspaces.clear()
        assertTrue(empty.recovery.recover("a") is CodexAppServerSessionRecoveryResult.StaleBinding); assertEquals(0, creates)
    }

    @Test fun `blank conversation is rejected without creation`() = runBlocking {
        var creates = 0; val f = Fixture(creator = CodexAppServerConnectionCreator { _, _ -> creates++; error("unused") })
        expectFailure { f.recovery.recover(" ") }; assertEquals(0, creates)
    }

    @Test fun `concurrent rebind or clear during resume closes stale recovered connection`() = runBlocking {
        listOf("rebind", "clear").forEach { race -> supervisorScope {
            val transport = FakeCodexAppServerTransport()
            val connection = connection(transport)
            val f = Fixture(creator = CodexAppServerConnectionCreator { root, cwd -> assertEquals("/root/w", root); assertEquals("src", cwd); connection })
            f.bind()
            val recovering = async { f.recovery.recover("a") }
            respondInitialize(transport)
            val resume = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
            assertEquals("thread/resume", resume["method"]?.jsonPrimitive?.content)
            assertEquals(JsonObject(mapOf("threadId" to JsonPrimitive("thread-1"))), resume["params"])
            if (race == "rebind") f.repo.bindPersistentThread("a", "w", "src", snapshot("thread-2")) else f.repo.clearBinding("a")
            transport.injectServerLine("""{"id":${resume["id"]},"result":{"thread":{"id":"thread-1","ephemeral":false},"model":"m","modelProvider":"p","cwd":"src"}}""")
            expectFailure { recovering.await() }
            assertTrue(connection.state.value == CodexAppServerConnectionState.Closed)
            if (race == "rebind") {
                val replacement = f.repo.getBinding("a")!!
                assertEquals("thread-2", replacement.threadId); assertNull(replacement.lastResumedAtMs)
            } else assertNull(f.repo.getBinding("a"))
        } }
    }

    @Test fun `successful recovery retains resumed snapshot and close is idempotent`() = runBlocking {
        val transport = FakeCodexAppServerTransport(); val connection = connection(transport); val f = Fixture(creator = CodexAppServerConnectionCreator { _, _ -> connection }); f.bind()
        val call = async { f.recovery.recover("a") }; respondInitialize(transport)
        val resume = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        transport.injectServerLine("""{"id":${resume["id"]},"result":{"thread":{"id":"thread-1","ephemeral":false},"model":"m","modelProvider":"p","cwd":"src"}}""")
        val session = (call.await() as CodexAppServerSessionRecoveryResult.Recovered).session
        assertEquals("thread-1", session.binding.threadId); assertEquals(100, session.binding.lastResumedAtMs)
        assertTrue(connection.state.value is CodexAppServerConnectionState.Ready)
        session.close(); session.close(); assertEquals(CodexAppServerConnectionState.Closed, connection.state.value)
    }

    private suspend fun respondInitialize(transport: FakeCodexAppServerTransport) {
        val request = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        assertEquals("initialize", request["method"]?.jsonPrimitive?.content)
        transport.injectServerLine("""{"id":${request["id"]},"result":{"userAgent":"test","codexHome":"/home","platformFamily":"unix","platformOs":"linux"}}""")
        val initialized = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        assertEquals("initialized", initialized["method"]?.jsonPrimitive?.content)
    }

    private fun connection(transport: FakeCodexAppServerTransport) = CodexAppServerConnection(CodexAppServerRequestDispatcher(transport), CodexAppServerClientInfo(name = "test", version = "1"))
    private fun snapshot(id: String) = CodexAppServerThreadSnapshot(id, JsonObject(mapOf("id" to JsonPrimitive(id), "ephemeral" to JsonPrimitive(false))))

    private class Fixture(val local: Local = Local(mutableSetOf("a"), mutableSetOf("w")), creator: CodexAppServerConnectionCreator) {
        val dao = Dao(); var now = 100L
        val repo = CodexAppServerSessionBindingRepository(dao, local) { now }
        val recovery = CodexAppServerSessionRecovery(repo, local, creator)
        suspend fun bind() = repo.bindPersistentThread("a", "w", "src", snapshot("thread-1"))
        private fun snapshot(id: String) = CodexAppServerThreadSnapshot(id, JsonObject(mapOf("id" to JsonPrimitive(id), "ephemeral" to JsonPrimitive(false))))
    }
    private class Local(val conversations: MutableSet<String> = mutableSetOf("a"), val workspaces: MutableSet<String> = mutableSetOf("w")) : CodexAppServerLocalState {
        constructor(conversations: Set<String>, workspaces: Set<String>) : this(conversations.toMutableSet(), workspaces.toMutableSet())
        override suspend fun conversationExists(id: String) = id in conversations
        override suspend fun getWorkspace(id: String) = if (id in workspaces) WorkspaceEntity(id, id, "/root/$id", createdAt = 0, updatedAt = 0) else null
    }
    private class Dao : CodexAppServerSessionBindingDao {
        val rows = linkedMapOf<String, CodexAppServerSessionBindingEntity>()
        override suspend fun getByConversationId(conversationId: String) = rows[conversationId]
        override fun observeByConversationId(conversationId: String): Flow<CodexAppServerSessionBindingEntity?> = MutableStateFlow(rows[conversationId])
        override suspend fun getByThreadId(threadId: String) = rows.values.singleOrNull { it.threadId == threadId }
        override suspend fun upsert(binding: CodexAppServerSessionBindingEntity) { rows[binding.conversationId] = binding }
        override suspend fun updateLastObservedTurn(conversationId: String, expectedThreadId: String, turnId: String, status: String, updatedAtMs: Long) = update(conversationId, expectedThreadId) { it.copy(lastObservedTurnId = turnId, lastObservedTurnStatus = status, updatedAtMs = updatedAtMs) }
        override suspend fun updateLastResumed(conversationId: String, expectedThreadId: String, resumedAtMs: Long) = update(conversationId, expectedThreadId) { it.copy(lastResumedAtMs = resumedAtMs, updatedAtMs = resumedAtMs) }
        override suspend fun deleteByConversationId(conversationId: String) = if (rows.remove(conversationId) != null) 1 else 0
        private fun update(id: String, thread: String, block: (CodexAppServerSessionBindingEntity) -> CodexAppServerSessionBindingEntity): Int { val row = rows[id]?.takeIf { it.threadId == thread } ?: return 0; rows[id] = block(row); return 1 }
    }
    private suspend fun expectFailure(block: suspend () -> Unit) { try { block(); fail("Expected failure") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
}
