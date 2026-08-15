package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    @Test fun `completion persisted before late started write remains terminal`() = runBlocking {
        val transport = FakeCodexAppServerTransport(); val connection = connection(transport)
        val f = Fixture(creator = CodexAppServerConnectionCreator { _, _ -> connection }); f.bind()
        val recovering = async { f.recovery.recover("a") }; respondInitialize(transport)
        val resume = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        transport.injectServerLine("""{"id":${resume["id"]},"result":{"thread":{"id":"thread-1"},"model":"m","modelProvider":"p","cwd":"src"}}""")
        val session = (recovering.await() as CodexAppServerSessionRecoveryResult.Recovered).session
        f.dao.gateStartedUpdate = true
        val starting = async { session.startTurn(listOf(CodexAppServerTurnInput.Text("race"))) }
        val request = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        transport.injectServerLine("""{"id":${request["id"]},"result":{"turn":{"id":"turn-1","status":"inProgress"}}}""")
        f.dao.startedUpdateEntered.await()
        transport.injectServerLine("""{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}""")
        f.dao.observeByConversationId("a").first { it?.lastObservedTurnStatus == "completed" }
        f.dao.startedUpdateRelease.complete(Unit)
        starting.await()
        assertEquals("turn-1", f.repo.getBinding("a")?.lastObservedTurnId)
        assertEquals("completed", f.repo.getBinding("a")?.lastObservedTurnStatus)
        session.close()
    }

    @Test fun `concurrent openers atomically create binding and loser closes`() = runBlocking { supervisorScope {
        val firstTransport = FakeCodexAppServerTransport(); val secondTransport = FakeCodexAppServerTransport()
        val firstConnection = connection(firstTransport); val secondConnection = connection(secondTransport)
        var created = 0
        val creator = CodexAppServerConnectionCreator { _, _ ->
            if (created++ == 0) firstConnection else secondConnection
        }
        val f = Fixture(creator = creator)
        val opener = CodexAppServerConversationSessionOpener(f.repo, f.local, creator, f.recovery)
        val first = async { opener.open("a", "w", "src") }
        respondInitialize(firstTransport)
        val firstStart = Json.parseToJsonElement(firstTransport.takeClientLine()).jsonObject
        val second = async { opener.open("a", "w", "src") }
        respondInitialize(secondTransport)
        val secondStart = Json.parseToJsonElement(secondTransport.takeClientLine()).jsonObject
        assertEquals("thread/start", firstStart["method"]?.jsonPrimitive?.content)
        assertEquals("thread/start", secondStart["method"]?.jsonPrimitive?.content)

        firstTransport.injectServerLine("""{"id":${firstStart["id"]},"result":{"thread":{"id":"winner","ephemeral":false},"model":"m","modelProvider":"p","cwd":"src"}}""")
        val winner = (first.await() as CodexAppServerConversationSessionOpenResult.Started).session
        secondTransport.injectServerLine("""{"id":${secondStart["id"]},"result":{"thread":{"id":"loser","ephemeral":false},"model":"m","modelProvider":"p","cwd":"src"}}""")
        val conflict = captureFailure { second.await() }
        assertTrue(conflict is CodexAppServerBindingConflictException)
        assertEquals("winner", f.repo.getBinding("a")?.threadId)
        assertEquals(CodexAppServerConnectionState.Closed, secondConnection.state.value)
        assertTrue(firstConnection.state.value is CodexAppServerConnectionState.Ready)
        winner.close()
    } }

    @Test fun `conversation opener starts explicit persistent thread and tracks terminal turn`() = runBlocking {
        val transport = FakeCodexAppServerTransport()
        val connection = connection(transport)
        val f = Fixture(creator = CodexAppServerConnectionCreator { root, cwd ->
            assertEquals("/root/w", root)
            assertEquals("src", cwd)
            connection
        })
        val opener = CodexAppServerConversationSessionOpener(f.repo, f.local, f.creator, f.recovery)
        val opening = async { opener.open("a", "w", "src") }
        respondInitialize(transport)
        val startThread = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        assertEquals("thread/start", startThread["method"]?.jsonPrimitive?.content)
        assertEquals(false, startThread["params"]?.jsonObject?.get("ephemeral")?.jsonPrimitive?.content?.toBoolean())
        transport.injectServerLine("""{"id":${startThread["id"]},"result":{"thread":{"id":"thread-1","ephemeral":false},"model":"m","modelProvider":"p","cwd":"src"}}""")
        val session = (opening.await() as CodexAppServerConversationSessionOpenResult.Started).session
        assertEquals("thread-1", f.repo.getBinding("a")?.threadId)

        val turn = async { session.startTurn(explicitSkillInvocation("demo", "/skills/demo", "work").input) }
        val startTurn = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        assertEquals("turn/start", startTurn["method"]?.jsonPrimitive?.content)
        assertEquals(
            Json.parseToJsonElement("""[{"type":"text","text":"${'$'}demo work"},{"type":"skill","name":"demo","path":"/skills/demo"}]"""),
            startTurn["params"]?.jsonObject?.get("input"),
        )
        transport.injectServerLine("""{"id":${startTurn["id"]},"result":{"turn":{"id":"turn-1","status":"inProgress"}}}""")
        turn.await()
        assertEquals("inProgress", f.repo.getBinding("a")?.lastObservedTurnStatus)
        transport.injectServerLine("""{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}""")
        f.dao.observeByConversationId("a").first {
            it?.lastObservedTurnStatus == "completed"
        }
        assertEquals("completed", f.repo.getBinding("a")?.lastObservedTurnStatus)
        session.close()
    }

    @Test fun `conversation opener recovers existing binding without thread start`() = runBlocking {
        val transport = FakeCodexAppServerTransport(); val connection = connection(transport)
        val f = Fixture(creator = CodexAppServerConnectionCreator { _, _ -> connection }); f.bind()
        val opener = CodexAppServerConversationSessionOpener(f.repo, f.local, f.creator, f.recovery)
        val opening = async { opener.open("a", "w", "src") }
        respondInitialize(transport)
        val resume = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        assertEquals("thread/resume", resume["method"]?.jsonPrimitive?.content)
        transport.injectServerLine("""{"id":${resume["id"]},"result":{"thread":{"id":"thread-1"},"model":"m","modelProvider":"p","cwd":"src"}}""")
        val result = opening.await() as CodexAppServerConversationSessionOpenResult.Recovered
        result.session.close()
    }

    @Test fun `delayed completion after rebind is observable and cannot mutate replacement`() = runBlocking {
        val transport = FakeCodexAppServerTransport(); val connection = connection(transport)
        val f = Fixture(creator = CodexAppServerConnectionCreator { _, _ -> connection }); f.bind()
        val recovering = async { f.recovery.recover("a") }; respondInitialize(transport)
        val resume = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        transport.injectServerLine("""{"id":${resume["id"]},"result":{"thread":{"id":"thread-1"},"model":"m","modelProvider":"p","cwd":"src"}}""")
        val session = (recovering.await() as CodexAppServerSessionRecoveryResult.Recovered).session
        f.repo.bindPersistentThread("a", "w", "src", snapshot("thread-2"))
        transport.injectServerLine("""{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"late","status":"failed"}}}""")
        val failure = session.failure.first { it != null }
        assertTrue(failure is CodexAppServerBindingChangedException)
        assertEquals("thread-2", f.repo.getBinding("a")?.threadId)
        assertNull(f.repo.getBinding("a")?.lastObservedTurnStatus)
        assertEquals(CodexAppServerConnectionState.Closed, connection.state.value)
    }

    @Test fun `unexpected durable collector failure terminates before publication`() = runBlocking {
        val transport = FakeCodexAppServerTransport(); val connection = connection(transport)
        val f = Fixture(creator = CodexAppServerConnectionCreator { _, _ -> connection }); f.bind()
        val recovering = async { f.recovery.recover("a") }; respondInitialize(transport)
        val resume = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
        transport.injectServerLine("""{"id":${resume["id"]},"result":{"thread":{"id":"thread-1"},"model":"m","modelProvider":"p","cwd":"src"}}""")
        val session = (recovering.await() as CodexAppServerSessionRecoveryResult.Recovered).session
        f.dao.turnUpdateFailure = IllegalStateException("database unavailable")
        transport.injectServerLine("""{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn","status":"failed"}}}""")
        assertEquals("database unavailable", session.failure.first { it != null }?.message)
        assertEquals(CodexAppServerConnectionState.Closed, connection.state.value)
    }

    @Test fun `not bound and stale bindings never create a connection`() = runBlocking {
        var creates = 0
        val empty = Fixture(local = Local(mutableSetOf("a"), mutableSetOf("w")), creator = CodexAppServerConnectionCreator { _, _ -> creates++; error("unused") })
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
        assertEquals("thread-1", session.binding.threadId); assertEquals(100L, session.binding.lastResumedAtMs)
        assertTrue(connection.state.value is CodexAppServerConnectionState.Ready)
        session.close(); session.close(); assertEquals(CodexAppServerConnectionState.Closed, connection.state.value)
    }

    @Test fun `resume RPC protocol and id mismatch failures close and preserve binding`() = runBlocking {
        listOf("rpc", "malformed", "mismatch").forEach { kind -> supervisorScope {
            val transport = FakeCodexAppServerTransport(); val connection = connection(transport)
            val f = Fixture(creator = CodexAppServerConnectionCreator { _, _ -> connection }); f.bind()
            val call = async { f.recovery.recover("a") }; respondInitialize(transport)
            val request = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
            val response = when (kind) {
                "rpc" -> """{"id":${request["id"]},"error":{"code":77,"message":"cannot resume"}}"""
                "malformed" -> """{"id":${request["id"]},"result":{"thread":{}}}"""
                else -> """{"id":${request["id"]},"result":{"thread":{"id":"thread-2"},"model":"m","modelProvider":"p","cwd":"src"}}"""
            }
            transport.injectServerLine(response)
            val error = captureFailure { call.await() }
            when (kind) {
                "rpc" -> assertTrue(error is CodexAppServerResponseException)
                "malformed" -> assertTrue(error is CodexAppServerThreadProtocolException)
                else -> assertTrue(error is CodexAppServerThreadIdMismatchException)
            }
            assertEquals(CodexAppServerConnectionState.Closed, connection.state.value)
            assertEquals("thread-1", f.repo.getBinding("a")?.threadId)
            assertNull(f.repo.getBinding("a")?.lastResumedAtMs)
            assertEquals(3, transport.successfulWriteCount())
        } }
    }

    @Test fun `cancellation during initialize or resume closes and preserves binding`() = runBlocking {
        listOf("initialize", "resume").forEach { boundary ->
            val transport = FakeCodexAppServerTransport(); val connection = connection(transport)
            val f = Fixture(creator = CodexAppServerConnectionCreator { _, _ -> connection }); f.bind()
            val call = async { f.recovery.recover("a") }
            if (boundary == "initialize") {
                val initialize = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
                assertEquals("initialize", initialize["method"]?.jsonPrimitive?.content)
            } else {
                respondInitialize(transport)
                val resume = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
                assertEquals("thread/resume", resume["method"]?.jsonPrimitive?.content)
            }
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            assertEquals(CodexAppServerConnectionState.Closed, connection.state.value)
            assertEquals("thread-1", f.repo.getBinding("a")?.threadId)
            assertNull(f.repo.getBinding("a")?.lastResumedAtMs)
        }
    }

    @Test fun `mark resumed conditional failure closes without ownership handoff`() = runBlocking {
        supervisorScope {
            val transport = FakeCodexAppServerTransport(); val connection = connection(transport)
            val f = Fixture(creator = CodexAppServerConnectionCreator { _, _ -> connection }); f.bind(); f.dao.failResumeUpdate = true
            val call = async { f.recovery.recover("a") }; respondInitialize(transport)
            val resume = Json.parseToJsonElement(transport.takeClientLine()).jsonObject
            transport.injectServerLine("""{"id":${resume["id"]},"result":{"thread":{"id":"thread-1"},"model":"m","modelProvider":"p","cwd":"src"}}""")
            val error = captureFailure { call.await() }
            assertTrue(error is CodexAppServerBindingChangedException)
            assertEquals(CodexAppServerConnectionState.Closed, connection.state.value)
            assertNull(f.repo.getBinding("a")?.lastResumedAtMs)
        }
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

    private class Fixture(val local: Local = Local(mutableSetOf("a"), mutableSetOf("w")), val creator: CodexAppServerConnectionCreator) {
        val dao = Dao(); var now = 100L
        val repo = CodexAppServerSessionBindingRepository(dao, local) { now }
        val recovery = CodexAppServerSessionRecovery(repo, local, creator)
        suspend fun bind() = repo.bindPersistentThread("a", "w", "src", snapshot("thread-1"))
        private fun snapshot(id: String) = CodexAppServerThreadSnapshot(id, JsonObject(mapOf("id" to JsonPrimitive(id), "ephemeral" to JsonPrimitive(false))))
    }
    private class Local(val conversations: MutableSet<String> = mutableSetOf("a"), val workspaces: MutableSet<String> = mutableSetOf("w")) : CodexAppServerLocalState {
        override suspend fun conversationExists(id: String) = id in conversations
        override suspend fun getWorkspace(id: String) = if (id in workspaces) WorkspaceEntity(id, id, "/root/$id", createdAt = 0, updatedAt = 0) else null
    }
    private class Dao : CodexAppServerSessionBindingDao {
        val rows = linkedMapOf<String, CodexAppServerSessionBindingEntity>()
        val states = linkedMapOf<String, MutableStateFlow<CodexAppServerSessionBindingEntity?>>()
        var failResumeUpdate = false
        var turnUpdateFailure: Throwable? = null
        var gateStartedUpdate = false
        val startedUpdateEntered = CompletableDeferred<Unit>()
        val startedUpdateRelease = CompletableDeferred<Unit>()
        override suspend fun getByConversationId(conversationId: String) = rows[conversationId]
        override fun observeByConversationId(conversationId: String): Flow<CodexAppServerSessionBindingEntity?> = states.getOrPut(conversationId) { MutableStateFlow(rows[conversationId]) }
        override suspend fun getByThreadId(threadId: String) = rows.values.singleOrNull { it.threadId == threadId }
        override suspend fun upsert(binding: CodexAppServerSessionBindingEntity) { rows[binding.conversationId] = binding; states[binding.conversationId]?.value = binding }
        override suspend fun insertIfAbsent(binding: CodexAppServerSessionBindingEntity): Long {
            if (binding.conversationId in rows || rows.values.any { it.threadId == binding.threadId }) return -1
            upsert(binding); return rows.size.toLong()
        }
        override suspend fun updateLastObservedTurn(conversationId: String, expectedThreadId: String, turnId: String, status: String, updatedAtMs: Long): Int {
            turnUpdateFailure?.let { throw it }
            return update(conversationId, expectedThreadId) { it.copy(lastObservedTurnId = turnId, lastObservedTurnStatus = status, updatedAtMs = updatedAtMs) }
        }
        override suspend fun updateLastObservedTurnStarted(conversationId: String, expectedThreadId: String, turnId: String, updatedAtMs: Long): Int {
            if (gateStartedUpdate) {
                startedUpdateEntered.complete(Unit)
                startedUpdateRelease.await()
            }
            val row = rows[conversationId]?.takeIf { it.threadId == expectedThreadId } ?: return 0
            if (row.lastObservedTurnId == turnId && row.lastObservedTurnStatus != null && row.lastObservedTurnStatus != "inProgress") return 0
            return update(conversationId, expectedThreadId) { it.copy(lastObservedTurnId = turnId, lastObservedTurnStatus = "inProgress", updatedAtMs = updatedAtMs) }
        }
        override suspend fun updateLastResumed(conversationId: String, expectedThreadId: String, resumedAtMs: Long) = if (failResumeUpdate) 0 else update(conversationId, expectedThreadId) { it.copy(lastResumedAtMs = resumedAtMs, updatedAtMs = resumedAtMs) }
        override suspend fun deleteByConversationId(conversationId: String) = if (rows.remove(conversationId) != null) { states[conversationId]?.value = null; 1 } else 0
        private fun update(id: String, thread: String, block: (CodexAppServerSessionBindingEntity) -> CodexAppServerSessionBindingEntity): Int { val row = rows[id]?.takeIf { it.threadId == thread } ?: return 0; rows[id] = block(row); states[id]?.value = rows[id]; return 1 }
    }
    private suspend fun expectFailure(block: suspend () -> Unit) { try { block(); fail("Expected failure") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
    private suspend fun captureFailure(block: suspend () -> Unit): Throwable = try { block(); fail("Expected failure"); error("unreachable") } catch (error: Throwable) { error }
}
