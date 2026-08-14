package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerThreadApiStdioIntegrationTest {
    private val json = CodexAppServerJsonRpc().json

    @Test fun `controlled stdio stack starts and resumes a thread without notifications`() = runBlocking {
        val process = AppServerTestProcess()
        val manager = WorkspaceManager(
            createTempDirectory("thread-api-stdio").toFile(),
            shellRunner = AppServerRecordingRunner(process),
        )
        manager.ensureWorkspace("workspace")
        val connection = WorkspaceCodexAppServerConnectionFactory(manager, "0.1.0").create("workspace")
        val api = CodexAppServerThreadApi(connection)

        val initializing = async(Dispatchers.Default) { connection.initialize() }
        awaitFlushes(process, 1)
        val initialize = line(process, 0)
        assertEquals("initialize", initialize["method"]!!.jsonPrimitive.content)
        process.writeStdout(response(initialize, buildJsonObject {
            put("userAgent", "codex/test"); put("codexHome", "/tmp/codex")
            put("platformFamily", "unix"); put("platformOs", "linux")
        }))
        initializing.await()
        awaitFlushes(process, 2)
        assertEquals("initialized", line(process, 1)["method"]!!.jsonPrimitive.content)
        assertTrue(connection.state.value is CodexAppServerConnectionState.Ready)

        val starting = async(Dispatchers.Default) { api.startThread() }
        awaitFlushes(process, 3)
        val start = line(process, 2)
        assertEquals("thread/start", start["method"]!!.jsonPrimitive.content)
        assertEquals(emptySet<String>(), start["params"]!!.jsonObject.keys)
        process.writeStdout(response(start, openResult("thread-opaque")))
        assertEquals("thread-opaque", starting.await().thread.id)

        val resuming = async(Dispatchers.Default) { api.resumeThread("thread-opaque") }
        awaitFlushes(process, 4)
        val resume = line(process, 3)
        assertEquals("thread/resume", resume["method"]!!.jsonPrimitive.content)
        assertEquals("thread-opaque", resume["params"]!!.jsonObject["threadId"]!!.jsonPrimitive.content)
        val turns = JsonArray(listOf(buildJsonObject { put("unknownFutureItem", "preserved") }))
        process.writeStdout(response(resume, openResult("thread-opaque", turns)))
        assertEquals(turns, resuming.await().thread.raw["turns"])

        connection.close()
    }

    private suspend fun awaitFlushes(process: AppServerTestProcess, count: Int) {
        assertTrue(withContext(Dispatchers.IO) {
            if (count == 1) process.stdin.flushed.await(2, TimeUnit.SECONDS)
            else withTimeout(2_000) { while (process.stdin.flushes < count) yield(); true }
        })
    }

    private fun line(process: AppServerTestProcess, index: Int) = json.parseToJsonElement(
        process.stdin.text().lineSequence().filter(String::isNotEmpty).toList()[index]
    ).jsonObject

    private fun response(request: kotlinx.serialization.json.JsonObject, result: kotlinx.serialization.json.JsonObject) =
        "{\"id\":${request["id"]},\"result\":$result}\n"

    private fun openResult(id: String, turns: JsonArray? = null) = buildJsonObject {
        put("thread", buildJsonObject { put("id", id); turns?.let { put("turns", it) } })
        put("model", "gpt"); put("modelProvider", "openai"); put("cwd", "/workspace")
    }
}
