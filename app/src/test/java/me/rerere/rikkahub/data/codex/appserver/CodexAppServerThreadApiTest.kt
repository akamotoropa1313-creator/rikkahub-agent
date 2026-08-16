package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
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

    @Test
    fun `history list strictly decodes metadata status cursors and preserves future fields`() = runBlocking {
        val page = decodeThreadList(buildJsonObject {
            put("data", JsonArray(listOf(buildJsonObject {
                put("id", "t1"); put("preview", "hello"); put("createdAt", 123L); put("recencyAt", JsonNull)
                put("status", buildJsonObject { put("type", "active"); put("activeFlags", JsonArray(listOf(JsonPrimitive("waiting")))) })
                put("future", buildJsonObject { put("kept", true) })
            })))
            put("nextCursor", "next"); put("backwardsCursor", JsonNull)
        })
        assertEquals("next", page.nextCursor); assertEquals(null, page.backwardsCursor)
        assertEquals(123L, page.data.single().createdAt)
        assertTrue(page.data.single().status is CodexAppServerThreadStatus.Active)
        assertTrue("future" in page.data.single().raw)
        listOf(JsonPrimitive("123"), JsonPrimitive(123.5)).forEach { invalid ->
            expect<CodexAppServerThreadProtocolException> { decodeThreadList(buildJsonObject {
                put("data", JsonArray(listOf(buildJsonObject { put("id", "t"); put("createdAt", invalid) })))
            }) }
        }
        expect<CodexAppServerThreadProtocolException> { decodeThreadList(buildJsonObject {
            put("data", JsonArray(listOf(buildJsonObject { put("id", "t"); put("status", "active") })))
        }) }
        expect<CodexAppServerThreadProtocolException> { decodeThreadList(buildJsonObject {
            put("data", JsonArray(listOf(buildJsonObject {
                put("id", "t"); put("status", buildJsonObject { put("type", "active") })
            })))
        }) }
        val futureStatus = decodeThreadList(buildJsonObject {
            put("data", JsonArray(listOf(buildJsonObject {
                put("id", "future"); put("status", buildJsonObject { put("type", "futureStatus"); put("newField", true) })
            })))
        }).data.single().status
        assertTrue(futureStatus is CodexAppServerThreadStatus.Unknown)
    }

    @Test
    fun `persisted user message decodes known inputs and preserves future inputs`() = runBlocking {
        val item = decodeItemSnapshot(buildJsonObject {
            put("id", "user-1")
            put("type", "userMessage")
            put("clientId", "client-1")
            put("content", JsonArray(listOf(
                buildJsonObject {
                    put("type", "text"); put("text", "What changed?"); put("text_elements", JsonArray(emptyList()))
                },
                buildJsonObject { put("type", "image"); put("url", "data:image/png;base64,AA==") },
                buildJsonObject { put("type", "futureInput"); put("future", true) },
            )))
        })
        val user = item as CodexAppServerItemSnapshot.UserMessage
        assertEquals("client-1", user.clientId)
        assertEquals("What changed?", (user.content[0] as CodexAppServerUserInput.Text).text)
        assertTrue(user.content[1] is CodexAppServerUserInput.Image)
        assertTrue(user.content[2] is CodexAppServerUserInput.Other)

        expect<CodexAppServerTurnProtocolException> { decodeItemSnapshot(buildJsonObject {
            put("id", "bad"); put("type", "userMessage"); put("clientId", JsonNull); put("content", "bad")
        }) }
        expect<CodexAppServerTurnProtocolException> { decodeItemSnapshot(buildJsonObject {
            put("id", "bad"); put("type", "userMessage"); put("clientId", JsonNull)
            put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", 3); put("text_elements", JsonArray(emptyList())) })))
        }) }
    }

    @Test
    fun `persisted user message accepts omitted optional stable fields but rejects malformed present values`() = runBlocking {
        val item = decodeItemSnapshot(buildJsonObject {
            put("id", "user-optional")
            put("type", "userMessage")
            put("content", JsonArray(listOf(buildJsonObject {
                put("type", "text")
                put("text", "Optional fields omitted")
            })))
        }) as CodexAppServerItemSnapshot.UserMessage

        assertEquals(null, item.clientId)
        val text = item.content.single() as CodexAppServerUserInput.Text
        assertEquals("Optional fields omitted", text.text)
        assertTrue(text.textElements.isEmpty())

        expect<CodexAppServerTurnProtocolException> { decodeItemSnapshot(buildJsonObject {
            put("id", "bad-client")
            put("type", "userMessage")
            put("clientId", 7)
            put("content", JsonArray(emptyList()))
        }) }
        expect<CodexAppServerTurnProtocolException> { decodeItemSnapshot(buildJsonObject {
            put("id", "bad-elements")
            put("type", "userMessage")
            put("content", JsonArray(listOf(buildJsonObject {
                put("type", "text")
                put("text", "bad")
                put("text_elements", "not-an-array")
            })))
        }) }
    }

    @Test
    fun `history list and read use exact stable wire`() = runBlocking {
        fixture().use { f ->
            val list = async { f.api.listThreads(cwd = "/repo", searchTerm = "query") }
            val request = f.takeRequest()
            assertEquals("thread/list", request.method)
            assertEquals(setOf("limit", "sortKey", "sortDirection", "cwd", "archived", "searchTerm"), request.params!!.jsonObject.keys)
            f.respond(request, buildJsonObject { put("data", JsonArray(emptyList())); put("nextCursor", JsonNull); put("backwardsCursor", JsonNull) })
            list.await()

            val read = async { f.api.readThread("t1") }
            val readRequest = f.takeRequest()
            assertEquals("thread/read", readRequest.method)
            assertEquals(buildJsonObject { put("threadId", "t1"); put("includeTurns", true) }, readRequest.params)
            f.respond(readRequest, buildJsonObject { put("thread", buildJsonObject { put("id", "t1"); put("turns", JsonArray(emptyList())) }) })
            assertEquals("t1", read.await().id)
        }
    }

    @Test
    fun `default start uses exact minimal wire and decodes raw result`() {
        runBlocking {
            fixture().use { f ->
                val call = async { f.api.startThread() }
                val (raw, request) = f.takeRawRequest()
                assertEquals("thread/start", raw["method"]?.jsonPrimitive?.content)
                assertFalse("jsonrpc" in raw)
                assertFalse(raw["id"]!!.jsonPrimitive.isString)
                assertEquals(JsonObject(emptyMap()), raw["params"])
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
    }

    @Test
    fun `start serializes stable camel case subset and omits absent values`() {
        runBlocking {
            fixture().use { f ->
                val params = CodexAppServerThreadStartParams(
                    model = "gpt", modelProvider = "openai", cwd = "/repo",
                    config = mapOf("reasoning" to JsonPrimitive("high")), serviceName = "rikkahub",
                    baseInstructions = "base", developerInstructions = "dev",
                    personality = CodexAppServerPersonality.PRAGMATIC, ephemeral = false,
                    serviceTier = "future-tier",
                )
                val call = async { f.api.startThread(params) }
                val request = f.takeRequest()
                val objectValue = request.params!!.jsonObject
                assertEquals(setOf("model", "modelProvider", "cwd", "config", "serviceName", "baseInstructions", "developerInstructions", "personality", "ephemeral", "serviceTier"), objectValue.keys)
                assertEquals("future-tier", objectValue["serviceTier"]?.jsonPrimitive?.content)
                assertEquals(false, objectValue["ephemeral"]?.jsonPrimitive?.content?.toBoolean())
                f.respond(request, result("id")); call.await()
            }
        }
    }

    @Test
    fun `resume sends only opaque thread id and preserves raw turns`() {
        runBlocking {
            fixture().use { f ->
                val call = async { f.api.resumeThread("not-a-uuid") }
                val (raw, request) = f.takeRawRequest()
                assertEquals("thread/resume", raw["method"]?.jsonPrimitive?.content)
                assertFalse("jsonrpc" in raw)
                assertFalse(raw["id"]!!.jsonPrimitive.isString)
                assertEquals(buildJsonObject { put("threadId", "not-a-uuid") }, raw["params"])
                assertEquals("thread/resume", request.method)
                assertEquals(setOf("threadId"), request.params!!.jsonObject.keys)
                assertFalse("history" in request.params!!.jsonObject)
                assertFalse("path" in request.params!!.jsonObject)
                val turns = JsonArray(listOf(buildJsonObject { put("futureItem", "untouched") }))
                f.respond(request, buildJsonObject {
                    put("thread", buildJsonObject { put("id", "not-a-uuid"); put("turns", turns) })
                    put("model", "gpt"); put("modelProvider", "openai"); put("cwd", "/workspace")
                })
                assertEquals(turns, call.await().thread.raw["turns"])
            }
        }
    }

    @Test
    fun `resume overrides serialize only supported fields`() {
        runBlocking {
            fixture().use { f ->
                val call = async { f.api.resumeThread("id", CodexAppServerThreadResumeParams(model = "gpt", modelProvider = "p", cwd = "/x", config = mapOf("x" to JsonPrimitive(1)), baseInstructions = "b", developerInstructions = "d", personality = CodexAppServerPersonality.FRIENDLY)) }
                val request = f.takeRequest()
                assertEquals(setOf("threadId", "model", "modelProvider", "cwd", "config", "baseInstructions", "developerInstructions", "personality"), request.params!!.jsonObject.keys)
                f.respond(request, result("id")); call.await()
            }
        }
    }

    @Test
    fun `all personality values serialize with official wire spelling`() {
        runBlocking {
            CodexAppServerPersonality.entries.forEach { personality ->
                fixture().use { f ->
                    val call = async { f.api.startThread(CodexAppServerThreadStartParams(personality = personality)) }
                    val request = f.takeRequest()
                    assertEquals(personality.wireValue, request.params!!.jsonObject["personality"]!!.jsonPrimitive.content)
                    f.respond(request, result("id")); call.await()
                }
            }
        }
    }

    @Test
    fun `concurrent starts correlate out of order without single flight`() {
        runBlocking {
            fixture().use { f ->
                val calls = (1..3).map { async { f.api.startThread() } }
                val requests = (1..3).map { f.takeRequest() }
                listOf(2, 0, 1).forEach { index -> f.respond(requests[index], result("thread-$index")) }
                assertEquals(listOf("thread-0", "thread-1", "thread-2"), calls.map { it.await().thread.id })
            }
        }
    }

    @Test
    fun `malformed results and resume mismatch fail explicitly`() {
        runBlocking {
            supervisorScope {
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
                listOf("model", "modelProvider", "cwd").forEach { field ->
                    listOf(null, JsonNull, JsonPrimitive(7), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())).forEach { bad ->
                        fixture().use { f ->
                            val call = async { f.api.startThread() }
                            val response = result("id").toMutableMap().apply {
                                if (bad == null) remove(field) else put(field, bad)
                            }.let(::JsonObject)
                            f.respond(f.takeRequest(), response)
                            expect<CodexAppServerThreadProtocolException> { call.await() }
                        }
                    }
                }
                fixture().use { f ->
                    val call = async { f.api.resumeThread("wanted") }; f.respond(f.takeRequest(), result("other"))
                    expect<CodexAppServerThreadIdMismatchException> { call.await() }
                }
            }
        }
    }

    @Test
    fun `blank resume rejects before write`() {
        runBlocking {
            fixture().use { f ->
                val writes = f.transport.successfulWriteCount()
                expect<IllegalArgumentException> { f.api.resumeThread("  ") }
                assertEquals(writes, f.transport.successfulWriteCount())
                assertEquals(0, f.dispatcher.pendingRequestCount())
            }
        }
    }

    @Test
    fun `timeout and cancellation remove pending and never retry`() {
        runBlocking {
            fixture().use { f ->
                val writes = f.transport.successfulWriteCount()
                val timed = async { f.api.startThread(timeout = 10.milliseconds) }
                f.takeRequest(); expect<kotlinx.coroutines.TimeoutCancellationException> { timed.await() }
                assertEquals(0, f.dispatcher.pendingRequestCount()); assertEquals(writes + 1, f.transport.successfulWriteCount())
                val cancelled = async { f.api.startThread() }; f.takeRequest(); cancelled.cancelAndJoin()
                assertEquals(0, f.dispatcher.pendingRequestCount()); assertEquals(writes + 2, f.transport.successfulWriteCount())
            }
        }
    }

    @Test
    fun `json rpc errors remain dispatcher errors`() {
        runBlocking {
            supervisorScope {
                fixture().use { f ->
                    val call = async { f.api.startThread() }; val request = f.takeRequest()
                    f.transport.injectServerLine(codec.encode(JsonRpcErrorResponse(request.id, JsonRpcError(42, "no"))))
                    val error = expect<CodexAppServerResponseException> { call.await() }
                    assertEquals(42, error.error.code); assertEquals("no", error.error.message)
                }
            }
        }
    }

    @Test
    fun `resume json rpc error preserves code and message`() {
        runBlocking {
            supervisorScope {
                fixture().use { f ->
                    val call = async { f.api.resumeThread("id") }; val request = f.takeRequest()
                    f.transport.injectServerLine(codec.encode(JsonRpcErrorResponse(request.id, JsonRpcError(73, "resume denied"))))
                    val error = expect<CodexAppServerResponseException> { call.await() }
                    assertEquals(73, error.error.code); assertEquals("resume denied", error.error.message)
                }
            }
        }
    }

    @Test
    fun `resume timeout removes pending and does not retry`() {
        runBlocking {
            fixture().use { f ->
                val writes = f.transport.successfulWriteCount()
                val call = async { f.api.resumeThread("id", timeout = 10.milliseconds) }
                val request = f.takeRequest(); assertEquals("thread/resume", request.method)
                expect<kotlinx.coroutines.TimeoutCancellationException> { call.await() }
                assertEquals(0, f.dispatcher.pendingRequestCount())
                assertEquals(writes + 1, f.transport.successfulWriteCount())
            }
        }
    }

    @Test
    fun `notifications before after or absent never gate response and remain observable`() {
        runBlocking {
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
    }

    @Test
    fun `thread calls require ready connection`() {
        runBlocking {
            val transport = FakeCodexAppServerTransport(); val dispatcher = CodexAppServerRequestDispatcher(transport)
            val connection = CodexAppServerConnection(dispatcher, CodexAppServerClientInfo(name = "test", version = "1"))
            expect<CodexAppServerNotReadyException> { CodexAppServerThreadApi(connection).startThread() }
            connection.close()
            expect<CodexAppServerNotReadyException> { CodexAppServerThreadApi(connection).resumeThread("id") }
        }
    }

    @Test
    fun `initializing and failed connections reject thread calls`() {
        runBlocking {
            val initializingTransport = FakeCodexAppServerTransport()
            val initializingDispatcher = CodexAppServerRequestDispatcher(initializingTransport)
            val initializingConnection = CodexAppServerConnection(
                initializingDispatcher, CodexAppServerClientInfo(name = "test", version = "1")
            )
            val initialize = async { initializingConnection.initialize() }
            initializingTransport.takeClientLine()
            expect<CodexAppServerNotReadyException> { CodexAppServerThreadApi(initializingConnection).startThread() }
            initialize.cancelAndJoin(); initializingConnection.close()

            val failedTransport = FakeCodexAppServerTransport()
            val failedDispatcher = CodexAppServerRequestDispatcher(failedTransport)
            val failedConnection = CodexAppServerConnection(
                failedDispatcher, CodexAppServerClientInfo(name = "test", version = "1")
            )
            failedTransport.injectFailure(IllegalStateException("broken"))
            withTimeout(1_000) { while (failedConnection.state.value !is CodexAppServerConnectionState.Failed) kotlinx.coroutines.yield() }
            expect<CodexAppServerNotReadyException> { CodexAppServerThreadApi(failedConnection).resumeThread("id") }
            failedConnection.close()
        }
    }

    private suspend fun fixture(): Fixture {
        val transport = FakeCodexAppServerTransport(); val dispatcher = CodexAppServerRequestDispatcher(transport)
        val connection = CodexAppServerConnection(dispatcher, CodexAppServerClientInfo(name = "test", version = "1"))
        val initialized = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async { connection.initialize() }
        val init = decodeRequest(transport.takeClientLine())
        transport.injectServerLine(codec.encode(JsonRpcResponse(init.id, buildJsonObject {
            put("userAgent", "fake"); put("codexHome", "/tmp/codex")
            put("platformFamily", "unix"); put("platformOs", "linux")
        })))
        initialized.await(); decodeNotification(transport.takeClientLine())
        return Fixture(transport, dispatcher, connection, CodexAppServerThreadApi(connection))
    }

    private fun result(id: String, extra: Boolean = false) = buildJsonObject {
        put("thread", buildJsonObject { put("id", id) }); put("model", "gpt")
        put("modelProvider", "openai"); put("cwd", "/workspace")
        if (extra) put("unknown", "future")
    }
    private fun decodeRequest(line: String) = (codec.decode(line).getOrThrow() as JsonRpcMessage.Request).value
    private fun decodeNotification(line: String) = (codec.decode(line).getOrThrow() as JsonRpcMessage.Notification).value
    private suspend inline fun <reified T : Throwable> expect(crossinline block: suspend () -> Unit): T =
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
            error("unreachable")
        } catch (error: Throwable) {
            when {
                error is T -> error
                error.cause is T -> error.cause as T
                else -> throw error
            }
        }

    private inner class Fixture(val transport: FakeCodexAppServerTransport, val dispatcher: CodexAppServerRequestDispatcher, val connection: CodexAppServerConnection, val api: CodexAppServerThreadApi) : AutoCloseable {
        suspend fun takeRequest() = decodeRequest(transport.takeClientLine())
        suspend fun takeRawRequest(): Pair<JsonObject, JsonRpcRequest> {
            val raw = codec.json.parseToJsonElement(transport.takeClientLine()).jsonObject
            return raw to (codec.decode(raw.toString()).getOrThrow() as JsonRpcMessage.Request).value
        }
        fun respond(request: JsonRpcRequest, result: kotlinx.serialization.json.JsonElement) = transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, result)))
        override fun close() = connection.close()
    }
}