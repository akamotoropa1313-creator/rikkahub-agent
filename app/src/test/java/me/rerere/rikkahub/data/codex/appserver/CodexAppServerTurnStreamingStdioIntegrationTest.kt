package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerTurnStreamingStdioIntegrationTest {
    private val json = CodexAppServerJsonRpc().json

    @Test
    fun `controlled stdio interrupt accepts completion before response and stays ready`() {
        runBlocking {
            val process = AppServerTestProcess()
            val manager = WorkspaceManager(createTempDirectory("turn-interrupt").toFile(), shellRunner = AppServerRecordingRunner(process))
            manager.ensureWorkspace("workspace")
            val connection = WorkspaceCodexAppServerConnectionFactory(manager, "0.1.0").create("workspace")
            try {
                val initializing = async(Dispatchers.Default) { connection.initialize() }
                awaitFlushes(process, 1)
                val initialize = line(process, 0)
                process.writeStdout(response(initialize, buildJsonObject { put("userAgent", "codex/test"); put("codexHome", "/tmp"); put("platformFamily", "unix"); put("platformOs", "linux") }))
                initializing.await(); awaitFlushes(process, 2)

                val threadApi = CodexAppServerThreadApi(connection)
                val startingThread = async(Dispatchers.Default) { threadApi.startThread() }
                awaitFlushes(process, 3)
                val threadRequest = line(process, 2)
                process.writeStdout(response(threadRequest, buildJsonObject { put("thread", buildJsonObject { put("id", "thread-1") }); put("model", "gpt"); put("modelProvider", "openai"); put("cwd", "/workspace") }))
                startingThread.await()

                val api = CodexAppServerTurnApi(connection)
                val completed = kotlinx.coroutines.CompletableDeferred<CodexAppServerTurnEvent.TurnCompleted>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    api.events.collect { if (it is CodexAppServerTurnEvent.TurnCompleted) completed.complete(it) }
                }
                val startingTurn = async(Dispatchers.Default) { api.startTurn("thread-1", listOf(CodexAppServerTurnInput.Text("Stop this"))) }
                awaitFlushes(process, 4)
                val turnRequest = line(process, 3)
                process.writeStdout(notification("turn/started", turnParams("inProgress")))
                process.writeStdout(notification("item/agentMessage/delta", delta("partial")))
                process.writeStdout(response(turnRequest, buildJsonObject { put("turn", turn("inProgress")) }))
                startingTurn.await()

                val interrupting = async(Dispatchers.Default) { api.interruptTurn("thread-1", "turn-1") }
                awaitFlushes(process, 5)
                val interruptRequest = line(process, 4)
                assertEquals("turn/interrupt", interruptRequest["method"]!!.jsonPrimitive.content)
                assertEquals(
                    buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1") },
                    interruptRequest["params"]!!.jsonObject,
                )
                process.writeStdout(notification("turn/completed", turnParams("interrupted")))
                assertEquals(CodexAppServerTurnStatus.Interrupted, withTimeout(2_000) { completed.await() }.turn.status)
                assertTrue(!interrupting.isCompleted)
                process.writeStdout(response(interruptRequest, JsonObject(emptyMap())))
                assertEquals(JsonObject(emptyMap()), interrupting.await().rawResult)
                assertTrue(connection.state.value is CodexAppServerConnectionState.Ready)
                assertTrue(process.isAlive)
                assertEquals(0, process.destroyCalls)
                collector.cancelAndJoin()
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `controlled stdio preserves early and ordered streaming through the full stack`() {
        runBlocking {
            val process = AppServerTestProcess(); val manager = WorkspaceManager(createTempDirectory("turn-stream").toFile(), shellRunner = AppServerRecordingRunner(process))
            manager.ensureWorkspace("workspace"); val connection = WorkspaceCodexAppServerConnectionFactory(manager, "0.1.0").create("workspace")
            val initializing = async(Dispatchers.Default) { connection.initialize() }; awaitFlushes(process, 1); val initialize = line(process, 0)
            process.writeStdout(response(initialize, buildJsonObject { put("userAgent", "codex/test"); put("codexHome", "/tmp"); put("platformFamily", "unix"); put("platformOs", "linux") }))
            initializing.await(); awaitFlushes(process, 2)

            val threadApi = CodexAppServerThreadApi(connection); val startingThread = async(Dispatchers.Default) { threadApi.startThread() }; awaitFlushes(process, 3); val threadRequest = line(process, 2)
            process.writeStdout(response(threadRequest, buildJsonObject { put("thread", buildJsonObject { put("id", "thread-1") }); put("model", "gpt"); put("modelProvider", "openai"); put("cwd", "/workspace") }))
            assertEquals("thread-1", startingThread.await().thread.id)

            val api = CodexAppServerTurnApi(connection); val events = mutableListOf<CodexAppServerTurnEvent>(); val allEvents = kotlinx.coroutines.CompletableDeferred<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { api.events.collect { events += it; if (events.size == 20) allEvents.complete(Unit) } }
            val startingTurn = async(Dispatchers.Default) { api.startTurn("thread-1", listOf(CodexAppServerTurnInput.Text("Hello Codex"))) }
            awaitFlushes(process, 4); val request = line(process, 3); assertEquals("turn/start", request["method"]!!.jsonPrimitive.content)
            assertEquals("Hello Codex", request["params"]!!.jsonObject["input"]!!.let { it as JsonArray }[0].jsonObject["text"]!!.jsonPrimitive.content)
            process.writeStdout(notification("turn/started", turnParams("inProgress")))
            process.writeStdout(notification("item/started", itemParams(agent(""), "startedAtMs", 1)))
            process.writeStdout(response(request, buildJsonObject { put("turn", turn("inProgress")) }))
            assertEquals("turn-1", startingTurn.await().turn.id)
            listOf("Hello", ", ", "world").forEach { process.writeStdout(notification("item/agentMessage/delta", delta(it))) }
            val finalAgent = agent("Hello, final!"); process.writeStdout(notification("item/completed", itemParams(finalAgent, "completedAtMs", 2)))
            process.writeStdout(notification("item/started", itemParams(reasoning(emptyList(), emptyList()), "startedAtMs", 3)))
            process.writeStdout(notification("item/reasoning/summaryPartAdded", indexed("summaryIndex", 0)))
            process.writeStdout(notification("item/reasoning/summaryTextDelta", indexed("summaryIndex", 0, "draft summary")))
            process.writeStdout(notification("item/reasoning/textDelta", indexed("contentIndex", 0, "draft raw")))
            val finalReasoning = reasoning(listOf("Final summary"), listOf("Final content")); process.writeStdout(notification("item/completed", itemParams(finalReasoning, "completedAtMs", 4)))
            process.writeStdout(notification("item/started", itemParams(command(null, "inProgress"), "startedAtMs", 5)))
            process.writeStdout(notification("item/commandExecution/outputDelta", commandDelta("draft ")))
            process.writeStdout(notification("item/commandExecution/outputDelta", commandDelta("output")))
            process.writeStdout(notification("item/completed", itemParams(command("final authoritative output", "completed"), "completedAtMs", 6)))
            val provisional = change("draft.txt", "add", "+draft\n")
            val finalChange = change("final.txt", "delete", "-final\n")
            process.writeStdout(notification("item/started", itemParams(fileChange(listOf(provisional), "inProgress"), "startedAtMs", 7)))
            process.writeStdout(notification("item/fileChange/patchUpdated", patchUpdated(listOf(provisional))))
            val turnDiff = "--- a/final.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-final\n"
            process.writeStdout(notification("turn/diff/updated", buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1"); put("diff", turnDiff) }))
            process.writeStdout(notification("item/completed", itemParams(fileChange(listOf(finalChange), "completed"), "completedAtMs", 8)))
            process.writeStdout(notification("turn/completed", turnParams("completed")))
            withTimeout(2_000) { allEvents.await() }
            assertTrue(events[0] is CodexAppServerTurnEvent.TurnStarted); assertTrue(events[1] is CodexAppServerTurnEvent.ItemStarted)
            assertEquals(listOf("Hello", ", ", "world"), events.filterIsInstance<CodexAppServerTurnEvent.AgentMessageDelta>().map { it.delta })
            val completed = events.filterIsInstance<CodexAppServerTurnEvent.ItemCompleted>()
            assertEquals("Hello, final!", (completed[0].item as CodexAppServerItemSnapshot.AgentMessage).text); assertEquals(finalAgent, completed[0].item.raw)
            val reasoning = completed[1].item as CodexAppServerItemSnapshot.Reasoning; assertEquals(listOf("Final summary"), reasoning.summary); assertEquals(listOf("Final content"), reasoning.content); assertEquals(finalReasoning, reasoning.raw)
            assertEquals(listOf("draft ", "output"), events.filterIsInstance<CodexAppServerTurnEvent.CommandExecutionOutputDelta>().map { it.delta })
            val finalCommand = completed[2].item as CodexAppServerItemSnapshot.CommandExecution
            assertEquals("echo test", finalCommand.command); assertEquals(CodexAppServerCommandExecutionSource.Agent, finalCommand.source); assertEquals("final authoritative output", finalCommand.aggregatedOutput)
            val patch = events.filterIsInstance<CodexAppServerTurnEvent.FileChangePatchUpdated>().single(); assertEquals("draft.txt", patch.changes.single().path)
            assertEquals(turnDiff, events.filterIsInstance<CodexAppServerTurnEvent.TurnDiffUpdated>().single().diff)
            val finalFile = completed[3].item as CodexAppServerItemSnapshot.FileChange; assertEquals("final.txt", finalFile.changes.single().path); assertEquals("-final\n", finalFile.changes.single().diff)
            assertTrue(events.last() is CodexAppServerTurnEvent.TurnCompleted)
            collector.cancelAndJoin(); connection.close()
        }
    }

    private suspend fun awaitFlushes(process: AppServerTestProcess, count: Int) { assertTrue(withContext(Dispatchers.IO) { if (count == 1) process.stdin.flushed.await(2, TimeUnit.SECONDS) else withTimeout(2_000) { while (process.stdin.flushes < count) yield(); true } }) }
    private fun line(process: AppServerTestProcess, index: Int) = json.parseToJsonElement(process.stdin.text().lineSequence().filter(String::isNotEmpty).toList()[index]).jsonObject
    private fun response(request: JsonObject, result: JsonObject) = "{\"id\":${request["id"]},\"result\":$result}\n"
    private fun notification(method: String, params: JsonObject) = "{\"method\":${JsonPrimitive(method)},\"params\":$params}\n"
    private fun turn(status: String) = buildJsonObject { put("id", "turn-1"); put("status", status) }
    private fun turnParams(status: String) = buildJsonObject { put("threadId", "thread-1"); put("turn", turn(status)) }
    private fun agent(text: String) = buildJsonObject { put("type", "agentMessage"); put("id", "agent-1"); put("text", text) }
    private fun reasoning(summary: List<String>, content: List<String>) = buildJsonObject { put("type", "reasoning"); put("id", "reasoning-1"); put("summary", JsonArray(summary.map(::JsonPrimitive))); put("content", JsonArray(content.map(::JsonPrimitive))) }
    private fun command(output: String?, status: String) = buildJsonObject { put("type", "commandExecution"); put("id", "command-1"); put("command", "echo test"); put("cwd", "/workspace"); put("status", status); put("commandActions", JsonArray(emptyList())); output?.let { put("aggregatedOutput", it) } }
    private fun commandDelta(value: String) = buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1"); put("itemId", "command-1"); put("delta", value) }
    private fun change(path: String, kind: String, diff: String) = buildJsonObject { put("path", path); put("kind", buildJsonObject { put("type", kind) }); put("diff", diff) }
    private fun fileChange(changes: List<JsonObject>, status: String) = buildJsonObject { put("type", "fileChange"); put("id", "file-1"); put("status", status); put("changes", JsonArray(changes)) }
    private fun patchUpdated(changes: List<JsonObject>) = buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1"); put("itemId", "file-1"); put("changes", JsonArray(changes)) }
    private fun itemParams(item: JsonObject, key: String, value: Long) = buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1"); put("item", item); put(key, value) }
    private fun delta(value: String) = buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1"); put("itemId", "agent-1"); put("delta", value) }
    private fun indexed(key: String, value: Long, delta: String? = null) = buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1"); put("itemId", "reasoning-1"); put(key, value); delta?.let { put("delta", it) } }
}
