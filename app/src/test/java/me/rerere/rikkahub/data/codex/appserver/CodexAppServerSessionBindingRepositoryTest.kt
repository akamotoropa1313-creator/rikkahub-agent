package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CodexAppServerSessionBindingRepositoryTest {
    @Test fun `persistent bind stores exact deterministic fields and rebind resets observations`() = runBlocking {
        val f = Fixture()
        val first = f.repo.bindPersistentThread("a", "w", "src/main", thread("thread-1", JsonPrimitive(false)))
        assertEquals(CodexAppServerSessionBindingEntity("a", "w", "thread-1", "src/main", null, null, 100, 100, null), first)
        f.repo.recordTurnStarted("a", "thread-1", "turn-1")
        f.repo.markResumed("a", "thread-1")
        f.now = 200
        val rebound = f.repo.bindPersistentThread("a", "w", "", thread("thread-2", JsonPrimitive(false)))
        assertEquals(100, rebound.createdAtMs)
        assertEquals(200, rebound.updatedAtMs)
        assertNull(rebound.lastObservedTurnId); assertNull(rebound.lastObservedTurnStatus); assertNull(rebound.lastResumedAtMs)
    }

    @Test fun `only literal JSON boolean false is durable`() = runBlocking {
        val malformed = listOf(
            JsonPrimitive(true), null, JsonPrimitive("false"), JsonPrimitive("true"), JsonNull,
            JsonPrimitive(0), JsonObject(emptyMap()), JsonArray(emptyList()),
        )
        malformed.forEachIndexed { index, value ->
            val f = Fixture()
            expectFailure { f.repo.bindPersistentThread("a", "w", "", thread("t$index", value)) }
            assertNull(f.dao.getByConversationId("a"))
        }
    }

    @Test fun `invalid identifiers cwd and missing local references are rejected`() = runBlocking {
        val cases = listOf(
            Triple("", "w", ""), Triple("a", "", ""), Triple("a", "w", "/absolute"),
            Triple("a", "w", "../escape"),
        )
        cases.forEach { (conversation, workspace, cwd) ->
            expectFailure { Fixture().repo.bindPersistentThread(conversation, workspace, cwd, thread("t", JsonPrimitive(false))) }
        }
        expectFailure { Fixture(conversations = emptySet()).repo.bindPersistentThread("a", "w", "", thread("t", JsonPrimitive(false))) }
        expectFailure { Fixture(workspaces = emptySet()).repo.bindPersistentThread("a", "w", "", thread("t", JsonPrimitive(false))) }
        expectFailure { Fixture().repo.bindPersistentThread("a", "w", "", thread("", JsonPrimitive(false))) }
    }

    @Test fun `thread ownership cannot be stolen`() = runBlocking {
        val f = Fixture(conversations = setOf("a", "b"))
        f.repo.bindPersistentThread("a", "w", "", thread("thread-1", JsonPrimitive(false)))
        expectFailure { f.repo.bindPersistentThread("b", "w", "", thread("thread-1", JsonPrimitive(false))) }
        assertEquals("a", f.dao.getByThreadId("thread-1")?.conversationId)
    }

    @Test fun `turn status wire values are stored`() = runBlocking {
        val f = Fixture(); f.repo.bindPersistentThread("a", "w", "", thread("t", JsonPrimitive(false)))
        f.repo.recordTurnStarted("a", "t", "one"); assertEquals("inProgress", f.dao.getByConversationId("a")?.lastObservedTurnStatus)
        listOf("completed", "interrupted", "failed", "future/status").forEach {
            f.repo.recordTurnCompleted("a", "t", "turn", it)
            assertEquals(it, f.dao.getByConversationId("a")?.lastObservedTurnStatus)
        }
    }

    @Test fun `late same-turn start cannot regress terminal but a different turn can start`() = runBlocking {
        val f = Fixture(); f.repo.bindPersistentThread("a", "w", "", thread("t", JsonPrimitive(false)))
        f.repo.recordTurnCompleted("a", "t", "turn-1", "completed")
        f.repo.recordTurnStarted("a", "t", "turn-1")
        assertEquals("turn-1", f.dao.getByConversationId("a")?.lastObservedTurnId)
        assertEquals("completed", f.dao.getByConversationId("a")?.lastObservedTurnStatus)

        f.repo.recordTurnStarted("a", "t", "turn-2")
        assertEquals("turn-2", f.dao.getByConversationId("a")?.lastObservedTurnId)
        assertEquals("inProgress", f.dao.getByConversationId("a")?.lastObservedTurnStatus)
    }

    @Test fun `stale old-thread events cannot mutate replacement binding`() = runBlocking {
        val f = Fixture(); f.repo.bindPersistentThread("a", "w", "", thread("old", JsonPrimitive(false)))
        f.repo.bindPersistentThread("a", "w", "", thread("new", JsonPrimitive(false)))
        expectFailure { f.repo.recordTurnStarted("a", "old", "late-start") }
        expectFailure { f.repo.recordTurnCompleted("a", "old", "late-complete", "completed") }
        assertNull(f.dao.getByConversationId("a")?.lastObservedTurnId)
    }

    @Test fun `clear removes only requested conversation`() = runBlocking {
        val f = Fixture(conversations = setOf("a", "b")); f.repo.bindPersistentThread("a", "w", "", thread("1", JsonPrimitive(false)))
        f.repo.bindPersistentThread("b", "w", "", thread("2", JsonPrimitive(false))); f.repo.clearBinding("a")
        assertNull(f.dao.getByConversationId("a")); assertEquals("2", f.dao.getByConversationId("b")?.threadId)
    }

    private class Fixture(
        conversations: Set<String> = setOf("a"), workspaces: Set<String> = setOf("w"),
    ) {
        var now = 100L
        val dao = FakeBindingDao()
        val repo = CodexAppServerSessionBindingRepository(dao, FakeLocalState(conversations, workspaces)) { now }
    }

    private class FakeLocalState(private val conversations: Set<String>, private val workspaces: Set<String>) : CodexAppServerLocalState {
        override suspend fun conversationExists(id: String) = id in conversations
        override suspend fun getWorkspace(id: String) = if (id in workspaces) WorkspaceEntity(id, id, "/root/$id", createdAt = 0, updatedAt = 0) else null
    }

    private class FakeBindingDao : CodexAppServerSessionBindingDao {
        private val rows = linkedMapOf<String, CodexAppServerSessionBindingEntity>()
        override suspend fun getByConversationId(conversationId: String) = rows[conversationId]
        override fun observeByConversationId(conversationId: String): Flow<CodexAppServerSessionBindingEntity?> = MutableStateFlow(rows[conversationId])
        override suspend fun getByThreadId(threadId: String) = rows.values.singleOrNull { it.threadId == threadId }
        override suspend fun upsert(binding: CodexAppServerSessionBindingEntity) { check(rows.values.none { it.threadId == binding.threadId && it.conversationId != binding.conversationId }); rows[binding.conversationId] = binding }
        override suspend fun insertIfAbsent(binding: CodexAppServerSessionBindingEntity): Long {
            if (binding.conversationId in rows || rows.values.any { it.threadId == binding.threadId }) return -1
            upsert(binding); return rows.size.toLong()
        }
        override suspend fun updateLastObservedTurn(conversationId: String, expectedThreadId: String, turnId: String, status: String, updatedAtMs: Long): Int = update(conversationId, expectedThreadId) { it.copy(lastObservedTurnId = turnId, lastObservedTurnStatus = status, updatedAtMs = updatedAtMs) }
        override suspend fun updateLastObservedTurnStarted(conversationId: String, expectedThreadId: String, turnId: String, updatedAtMs: Long): Int {
            val row = rows[conversationId]?.takeIf { it.threadId == expectedThreadId } ?: return 0
            if (row.lastObservedTurnId == turnId && row.lastObservedTurnStatus != null && row.lastObservedTurnStatus != "inProgress") return 0
            return update(conversationId, expectedThreadId) { it.copy(lastObservedTurnId = turnId, lastObservedTurnStatus = "inProgress", updatedAtMs = updatedAtMs) }
        }
        override suspend fun updateLastResumed(conversationId: String, expectedThreadId: String, resumedAtMs: Long): Int = update(conversationId, expectedThreadId) { it.copy(lastResumedAtMs = resumedAtMs, updatedAtMs = resumedAtMs) }
        override suspend fun deleteByConversationId(conversationId: String) = if (rows.remove(conversationId) != null) 1 else 0
        private fun update(id: String, thread: String, transform: (CodexAppServerSessionBindingEntity) -> CodexAppServerSessionBindingEntity): Int { val row = rows[id]?.takeIf { it.threadId == thread } ?: return 0; rows[id] = transform(row); return 1 }
    }

    private fun thread(id: String, ephemeral: kotlinx.serialization.json.JsonElement?) = CodexAppServerThreadSnapshot(id, JsonObject(buildMap { ephemeral?.let { put("ephemeral", it) } }))
    private suspend fun expectFailure(block: suspend () -> Unit) { try { block(); fail("Expected failure") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
}
