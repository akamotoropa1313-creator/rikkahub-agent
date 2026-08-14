package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class CodexAppServerThreadApiTest {
    private val codec = CodexAppServerJsonRpc()

    @Test fun `default start uses exact minimal wire and decodes raw result`() = runBlocking {
        fixture().use { f ->
            val call = async { f.api.startThread() }
            val request = f.takeRequest()
            assertEquals("thread/start", request.method)
            assertTrue(request.id is JsonRpcId.NumberId)
            assertEquals(JsonObject(emptyMap()), request.params)
            f.respond(request, result("opaque-id", extra = true))
            val opened = call.await()
            assertEquals("opaque-id", opened.thread.id)
            assertSame(opened.rawResult["thread"], opened.thread.raw)
            assertEquals("future", opened.rawResult["unknown"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `start serializes stable camel case subset and omits absent values`() = runBlocking {
        fixture().use { f ->
            val params = CodexAppServerThreadStartParams(
                model = "gpt", modelProvider = "openai", cwd = "/repo",
                config = mapOf("reasoning" to JsonPrimitive("high")), serviceName = "rikkahub",
                baseInstructions = "base", developerInstructions = "dev",
                personality = "pragmatic", ephemeral = false,
            )
            val call = async { f.api.startThread(params) }
            val request = f.takeRequest()
            val objectValue = request.params!!.jsonObject
            assertEquals(setOf("model", "modelProvider", "cwd", "config", "serviceName", "baseInstructions", "developerInstructions", "personality", "ephemeral"), objectValue.keys)
            assertEquals(false, objectValue["ephemeral"]?.jsonPrimitive?.content?.toBoolean())
            f.respond(request, result("id")); call.await()
        }
    }

    @Test fun `resume sends only opaque thread id and preserves raw turns`() = runBlocking {
        fixture().use { f ->
            val call = async { f.api.resumeThread("not-a-uuid") }
            val request = f.takeRequest()
            assertEquals("thread/resume", request.method)
            assertEquals(setOf("threadId"), request.params!!.jsonObject.keys)
            assertFalse("history" in request.params!!.jsonObject)
            assertFalse("path" in request.params!!.jsonObject)
            val turns = JsonArray(listOf(buildJsonObject { put("futureItem", "untouched") }))
            f.respond(request, buildJsonObject { put("thread", buildJsonObject { put("id", "not-a-uuid"); put("turns", turns) }) })
            assertEquals(turns, call.await().thread.raw["turns"])
        }
    }

    @Test fun `resume overrides serialize only supported fields`() = runBlocking {
        fixture().use { f ->
            val call = async { f.api.resumeThread("id", CodexAppServerThreadResumeParams(model = "gpt", modelProvider = "p", cwd = "/x", config = mapOf("x" to JsonPrimitive(1)), baseInstructions = "b", developerInstructions = "d", personality = "p")) }
            val request = f.takeRequest()
            assertEquals(setOf("threadId", "model", "modelProvider", "cwd", "config", "baseInstructions", "developerInstructions", "personality"), request.params!!.jsonObject.keys)
            f.respond(request, result("id")); call.await()
        }
    }

    @Test fun `concurrent starts correlate out of order without single flight`() = runBlocking {
        fixture().use { f ->
            val calls = (1..3).map { async { f.api.startThread() } }
            val requests = (1..3).map { f.takeRequest() }
            listOf(2, 0, 1).forEach { index -> f.respond(requests[index], result("thread-$index")) }
            assertEquals(listOf("thread-0", "thread-1", "thread-2"), calls.map { it.await().thread.id })
        }
    }

    @Test fun `malformed results and resume mismatch fail explicitly`() = runBlocking {
        val malformed = listOf(
            JsonNull, JsonPrimitive("bad"), JsonArray(emptyList()), JsonObject(emptyMap()),
            buildJsonObject { put("thread", JsonPrimitive("bad")) },
            buildJsonObject { put("thread", JsonObject(emptyMap())) },
            buildJsonObject { put("thread", buildJsonObject { put("id", 3) }) },
            result(" "),
            buildJsonObject { put("thread", buildJsonObject { put("id", "id") }); put("model", 4) },
        )
        malformed.forEach { raw ->
            fixture().use { f ->
                val call = async { f.api.startThread() }; f.respond(f.takeRequest(), raw)
                expect<CodexAppServerThreadProtocolException> { call.await() }
            }
        }
        fixture().use { f ->
            val call = async { f.api.resumeThread("wanted") }; f.respond(f.takeRequest(), result("other"))
            expect<CodexAppServerThreadIdMismatchException> { call.await() }
        }
    }

    @Test fun `blank resume rejects before write`() = runBlocking {
        fixture().use { f ->
            val writes = f.transport.successfulWriteCount()
            expect<IllegalArgumentException> { f.api.resumeThread("  ") }
            assertEquals(writes, f.transport.successfulWriteCount())
            assertEquals(0, f.dispatcher.pendingRequestCount())
        }
    }

    @Test fun `timeout and cancellation remove pending and never retry`() = runBlocking {
        fixture().use { f ->
            val timed = async { f.api.startThread(timeout = 10.milliseconds) }
            f.takeRequest(); expect<kotlinx.coroutines.TimeoutCancellationException> { timed.await() }
            assertEquals(0, f.dispatcher.pendingRequestCount()); assertEquals(2, f.transport.successfulWriteCount())
            val cancelled = async { f.api.startThread() }; f.takeRequest(); cancelled.cancelAndJoin()
            assertEquals(0, f.dispatcher.pendingRequestCount()); assertEquals(3, f.transport.successfulWriteCount())
        }
    }

    @Test fun `json rpc errors remain dispatcher errors`() = runBlocking {
        fixture().use { f ->
            val call = async { f.api.startThread() }; val request = f.takeRequest()
            f.transport.injectServerLine(codec.encode(JsonRpcErrorResponse(request.id, JsonRpcError(42, "no"))))
            val error = expect<CodexAppServerResponseException> { call.await() }
            assertEquals(42, error.error.code); assertEquals("no", error.error.message)
        }
    }

    @Test fun `notifications before after or absent never gate response and remain observable`() = runBlocking {
        listOf("before", "after", "absent").forEach { order ->
            fixture().use { f ->
                val observed = if (order == "absent") null else async { withTimeout(1_000) { f.connection.events.first() } }
                val call = async { f.api.startThread() }; val request = f.takeRequest()
                val notification = codec.encode(JsonRpcNotification("thread/started", buildJsonObject { put("thread", buildJsonObject { put("id", order) }) }))
                if (order == "before") f.transport.injectServerLine(notification)
                f.respond(request, result(order)); assertEquals(order, call.await().thread.id)
                if (order == "after") f.transport.injectServerLine(notification)
                observed?.let { assertTrue(it.await() is CodexAppServerEvent.UnknownNotification) }
            }
        }
    }

    @Test fun `thread calls require ready connection`() = runBlocking {
        val transport = FakeCodexAppServerTransport(); val dispatcher = CodexAppServerRequestDispatcher(transport)
        val connection = CodexAppServerConnection(dispatcher, CodexAppServerClientInfo("test", "1"))
        expect<CodexAppServerNotReadyException> { CodexAppServerThreadApi(connection).startThread() }
        connection.close()
        expect<CodexAppServerNotReadyException> { CodexAppServerThreadApi(connection).resumeThread("id") }
    }

    private suspend fun fixture(): Fixture {
        val transport = FakeCodexAppServerTransport(); val dispatcher = CodexAppServerRequestDispatcher(transport)
        val connection = CodexAppServerConnection(dispatcher, CodexAppServerClientInfo("test", "1"))
        val initialized = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async { connection.initialize() }
        val init = decodeRequest(transport.takeClientLine())
        transport.injectServerLine(codec.encode(JsonRpcResponse(init.id, buildJsonObject { put("userAgent", "fake") })))
        initialized.await(); decodeNotification(transport.takeClientLine())
        return Fixture(transport, dispatcher, connection, CodexAppServerThreadApi(connection))
    }

    private fun result(id: String, extra: Boolean = false) = buildJsonObject {
        put("thread", buildJsonObject { put("id", id) }); put("model", "gpt")
        if (extra) put("unknown", "future")
    }
    private fun decodeRequest(line: String) = (codec.decode(line).getOrThrow() as JsonRpcMessage.Request).value
    private fun decodeNotification(line: String) = (codec.decode(line).getOrThrow() as JsonRpcMessage.Notification).value
    private suspend inline fun <reified T : Throwable> expect(crossinline block: suspend () -> Unit): T = try { block(); fail("Expected ${T::class.java.simpleName}"); error("unreachable") } catch (e: Throwable) { val actual = e.cause ?: e; if (actual !is T) throw e; actual }

    private inner class Fixture(val transport: FakeCodexAppServerTransport, val dispatcher: CodexAppServerRequestDispatcher, val connection: CodexAppServerConnection, val api: CodexAppServerThreadApi) : AutoCloseable {
        suspend fun takeRequest() = decodeRequest(transport.takeClientLine())
        fun respond(request: JsonRpcRequest, result: kotlinx.serialization.json.JsonElement) = transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, result)))
        override fun close() = connection.close()
    }
}
