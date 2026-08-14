package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onStart
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerTurnStreamingStdioIntegrationTest {
    private val json = CodexAppServerJsonRpc().json

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

            val api = CodexAppServerTurnApi(connection); val ready = CompletableDeferred<Unit>(); val events = mutableListOf<CodexAppServerTurnEvent>()
            val collector = launch { api.events.onStart { ready.complete(Unit) }.collect { events += it } }; ready.await()
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
            process.writeStdout(notification("turn/completed", turnParams("completed")))
            withTimeout(2_000) { while (events.size < 12) yield() }
            assertTrue(events[0] is CodexAppServerTurnEvent.TurnStarted); assertTrue(events[1] is CodexAppServerTurnEvent.ItemStarted)
            assertEquals(listOf("Hello", ", ", "world"), events.filterIsInstance<CodexAppServerTurnEvent.AgentMessageDelta>().map { it.delta })
            val completed = events.filterIsInstance<CodexAppServerTurnEvent.ItemCompleted>()
            assertEquals("Hello, final!", (completed[0].item as CodexAppServerItemSnapshot.AgentMessage).text); assertSame(finalAgent, completed[0].item.raw)
            val reasoning = completed[1].item as CodexAppServerItemSnapshot.Reasoning; assertEquals(listOf("Final summary"), reasoning.summary); assertEquals(listOf("Final content"), reasoning.content); assertSame(finalReasoning, reasoning.raw)
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
    private fun itemParams(item: JsonObject, key: String, value: Long) = buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1"); put("item", item); put(key, value) }
    private fun delta(value: String) = buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1"); put("itemId", "agent-1"); put("delta", value) }
    private fun indexed(key: String, value: Long, delta: String? = null) = buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1"); put("itemId", "reasoning-1"); put(key, value); delta?.let { put("delta", it) } }
}
