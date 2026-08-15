package me.rerere.rikkahub.data.codex.appserver

import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellContext
import me.rerere.workspace.WorkspaceShellRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class CodexAppServerSessionRecoveryStdioIntegrationTest {
    private val json = CodexAppServerJsonRpc().json

    @Test fun `two workspace process lifetimes start bind and recover by exact thread id`() = runBlocking {
        val first = AppServerTestProcess(); val second = AppServerTestProcess()
        val runner = QueueRunner(first, second); val manager = manager(runner)
        val factory = WorkspaceCodexAppServerConnectionFactory(manager, "test")
        val dao = StdioBindingDao(); val local = StdioLocalState(); var now = 10L
        val repository = CodexAppServerSessionBindingRepository(dao, local) { now }

        val original = factory.create("workspace-1", "project")
        val initializing = async(Dispatchers.Default) { original.initialize() }
        respondInitialize(first); initializing.await()
        val starting = async(Dispatchers.Default) { CodexAppServerThreadApi(original).startThread() }
        val start = awaitLine(first, 2); assertEquals("thread/start", start["method"]?.jsonPrimitive?.content)
        first.writeStdout(success(start, "thread-1")); val thread = starting.await().thread
        repository.bindPersistentThread("conversation-1", "workspace-1", "project", thread)
        original.close(); assertFalse(first.isAlive); assertEquals(1, runner.starts)

        now = 20L
        val recovering = async(Dispatchers.Default) { CodexAppServerSessionRecovery(repository, local, factory).recover("conversation-1") }
        respondInitialize(second)
        val resume = awaitLine(second, 2)
        assertEquals("thread/resume", resume["method"]?.jsonPrimitive?.content)
        assertEquals(JsonObject(mapOf("threadId" to JsonPrimitive("thread-1"))), resume["params"])
        second.writeStdout(success(resume, "thread-1"))
        val session = (recovering.await() as CodexAppServerSessionRecoveryResult.Recovered).session
        assertEquals(2, runner.starts); assertTrue(session.connection.state.value is CodexAppServerConnectionState.Ready)
        assertEquals("thread-1", session.binding.threadId); assertEquals(20L, session.binding.lastResumedAtMs)
        assertEquals(3, second.stdin.flushes)
        session.close(); assertFalse(second.isAlive)
    }

    @Test fun `workspace resume RPC error closes second process and preserves binding`() = runBlocking { supervisorScope {
        val second = AppServerTestProcess(); val runner = QueueRunner(second); val manager = manager(runner)
        val dao = StdioBindingDao(); val local = StdioLocalState(); val repository = CodexAppServerSessionBindingRepository(dao, local) { 10 }
        repository.bindPersistentThread("conversation-1", "workspace-1", "project", snapshot("thread-1"))
        val recovering = async(Dispatchers.Default) {
            CodexAppServerSessionRecovery(repository, local, WorkspaceCodexAppServerConnectionFactory(manager, "test")).recover("conversation-1")
        }
        respondInitialize(second); val resume = awaitLine(second, 2)
        assertEquals(JsonObject(mapOf("threadId" to JsonPrimitive("thread-1"))), resume["params"])
        second.writeStdout("""{"id":${resume["id"]},"error":{"code":88,"message":"missing rollout"}}""" + "\n")
        val error = try { recovering.await(); error("expected RPC error") } catch (error: CodexAppServerResponseException) { error }
        assertEquals(88, error.error.code); assertFalse(second.isAlive)
        assertEquals("thread-1", repository.getBinding("conversation-1")?.threadId)
        assertNull(repository.getBinding("conversation-1")?.lastResumedAtMs)
        assertEquals(3, second.stdin.flushes)
    } }

    private fun manager(runner: QueueRunner) = WorkspaceManager(createTempDirectory("session-recovery").toFile(), shellRunner = runner).also {
        it.ensureWorkspace("workspace-1"); java.io.File(it.filesDir("workspace-1"), "project").mkdirs()
    }
    private suspend fun respondInitialize(process: AppServerTestProcess) {
        val request = awaitLine(process, 0); assertEquals("initialize", request["method"]?.jsonPrimitive?.content)
        process.writeStdout("""{"id":${request["id"]},"result":{"userAgent":"test","codexHome":"/tmp","platformFamily":"unix","platformOs":"linux"}}""" + "\n")
        assertEquals("initialized", awaitLine(process, 1)["method"]?.jsonPrimitive?.content)
    }
    private fun awaitLine(process: AppServerTestProcess, index: Int): JsonObject {
        check(process.stdin.awaitFlushCount(index + 1, 2_000))
        return json.parseToJsonElement(process.stdin.text().lineSequence().filter(String::isNotEmpty).toList()[index]).jsonObject
    }
    private fun success(request: JsonObject, id: String) = """{"id":${request["id"]},"result":{"thread":{"id":"$id","ephemeral":false},"model":"m","modelProvider":"p","cwd":"project"}}""" + "\n"
    private fun snapshot(id: String) = CodexAppServerThreadSnapshot(id, JsonObject(mapOf("id" to JsonPrimitive(id), "ephemeral" to JsonPrimitive(false))))
}

private class QueueRunner(vararg processes: Process) : WorkspaceShellRunner {
    private val queue = ArrayDeque(processes.toList()); var starts = 0
    override fun start(context: WorkspaceShellContext): Process { starts++; return queue.removeFirst() }
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult = error("unused")
}
private class StdioLocalState : CodexAppServerLocalState {
    override suspend fun conversationExists(id: String) = id == "conversation-1"
    override suspend fun getWorkspace(id: String) = if (id == "workspace-1") WorkspaceEntity(id, id, "workspace-1", createdAt = 0, updatedAt = 0) else null
}
private class StdioBindingDao : CodexAppServerSessionBindingDao {
    private val rows = linkedMapOf<String, CodexAppServerSessionBindingEntity>()
    override suspend fun getByConversationId(conversationId: String) = rows[conversationId]
    override fun observeByConversationId(conversationId: String): Flow<CodexAppServerSessionBindingEntity?> = MutableStateFlow(rows[conversationId])
    override suspend fun getByThreadId(threadId: String) = rows.values.singleOrNull { it.threadId == threadId }
    override suspend fun upsert(binding: CodexAppServerSessionBindingEntity) { rows[binding.conversationId] = binding }
    override suspend fun updateLastObservedTurn(conversationId: String, expectedThreadId: String, turnId: String, status: String, updatedAtMs: Long) = update(conversationId, expectedThreadId) { it.copy(lastObservedTurnId = turnId, lastObservedTurnStatus = status, updatedAtMs = updatedAtMs) }
    override suspend fun updateLastResumed(conversationId: String, expectedThreadId: String, resumedAtMs: Long) = update(conversationId, expectedThreadId) { it.copy(lastResumedAtMs = resumedAtMs, updatedAtMs = resumedAtMs) }
    override suspend fun deleteByConversationId(conversationId: String) = if (rows.remove(conversationId) != null) 1 else 0
    private fun update(id: String, thread: String, block: (CodexAppServerSessionBindingEntity) -> CodexAppServerSessionBindingEntity): Int { val row = rows[id]?.takeIf { it.threadId == thread } ?: return 0; rows[id] = block(row); return 1 }
}
