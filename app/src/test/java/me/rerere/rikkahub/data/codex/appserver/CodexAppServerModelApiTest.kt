package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class CodexAppServerModelApiTest {
    private val codec = CodexAppServerJsonRpc()

    @Test
    fun `exact request preserves open effort order and raw fields`() = runBlocking<Unit> {
        fixture().use { f ->
            val call = async { f.api.list("cursor", 7, false) }
            val raw = codec.json.parseToJsonElement(f.transport.takeClientLine()).jsonObject
            assertEquals("model/list", raw["method"]!!.let { it as JsonPrimitive }.content)
            assertFalse("jsonrpc" in raw)
            val request = decodeRequest(raw.toString())
            assertEquals(
                buildJsonObject {
                    put("cursor", "cursor")
                    put("limit", 7)
                    put("includeHidden", false)
                },
                request.params,
            )
            f.respond(request, page(null))
            val result = call.await()
            val model = result.data.single()
            assertEquals("catalog-wire-model", model.id)
            assertEquals("wire-model", model.model)
            assertEquals(listOf("max", "low", "focused"), model.supportedReasoningEfforts.map { it.reasoningEffort })
            assertEquals("focused", model.defaultReasoningEffort)
            assertTrue(model.supportsPersonality)
            assertTrue(model.isDefault)
            assertEquals(JsonPrimitive(42), model.raw["future"])
            assertEquals(JsonPrimitive("future-result"), result.raw["futureResult"])
        }
    }

    @Test
    fun `default params are empty and includeHidden remains opt in`() = runBlocking<Unit> {
        fixture().use { f ->
            val call = async { f.api.list() }
            val request = f.request()
            assertEquals(JsonObject(emptyMap()), request.params)
            f.respond(request, page(null))
            call.await()
        }
    }

    @Test
    fun `service tier metadata preserves modern and legacy open strings`() = runBlocking<Unit> {
        fixture().use { f ->
            val call = async { f.api.list() }
            val tiers = JsonArray(listOf(
                buildJsonObject { put("id", "priority"); put("name", "Fast"); put("description", "Faster") },
                buildJsonObject { put("id", "future-tier"); put("name", "Future"); put("description", "Unknown remains open") },
            ))
            var response = page(null).replaceModelField("serviceTiers", tiers)
            response = response.replaceModelField("defaultServiceTier", JsonPrimitive("future-tier"))
            response = response.replaceModelField("additionalSpeedTiers", JsonArray(listOf(JsonPrimitive("fast"))))
            f.respond(f.request(), response)
            val model = call.await().data.single()
            assertEquals(listOf("priority", "future-tier"), model.serviceTiers!!.map { it.id })
            assertEquals("Fast", model.serviceTiers!!.first().name)
            assertEquals("future-tier", model.defaultServiceTier)
            assertEquals(listOf("fast"), model.additionalSpeedTiers)
            assertEquals(tiers, model.raw["serviceTiers"])
        }
    }

    @Test
    fun `defaultServiceTier accepts string null and omitted while unknown metadata is preserved`() = runBlocking<Unit> {
        listOf<JsonElement?>(JsonPrimitive("default"), JsonNull, null).forEach { tier ->
            fixture().use { f ->
                val call = async { f.api.list() }
                var response = page(null)
                if (tier != null) response = response.replaceModelField("defaultServiceTier", tier)
                response = response.replaceModelField("futureModelMetadata", buildJsonObject { put("multiAgentVersion", 99) })
                f.respond(f.request(), response)
                val model = call.await().data.single()
                assertEquals((tier as? JsonPrimitive)?.takeIf { it.isString }?.content, model.defaultServiceTier)
                assertTrue(model.raw.containsKey("futureModelMetadata"))
            }
        }
    }

    @Test
    fun `visible pagination preserves server page and effort ordering`() = runBlocking<Unit> {
        fixture().use { f ->
            val call = async { f.api.listAllVisible() }
            val first = f.request()
            assertEquals(false, (first.params!!.jsonObject["includeHidden"] as JsonPrimitive).content.toBoolean())
            f.respond(first, page("next", model = "model-a", efforts = listOf("focused", "low")))
            val second = f.request()
            assertEquals("next", (second.params!!.jsonObject["cursor"] as JsonPrimitive).content)
            f.respond(second, page(null, model = "model-b", efforts = listOf("future", "max")))
            val models = call.await()
            assertEquals(listOf("model-a", "model-b"), models.map { it.model })
            assertEquals(listOf("focused", "low"), models[0].supportedReasoningEfforts.map { it.reasoningEffort })
            assertEquals(listOf("future", "max"), models[1].supportedReasoningEfforts.map { it.reasoningEffort })
        }
    }

    @Test
    fun `visible pagination removes overlapping model ids`() = runBlocking<Unit> {
        fixture().use { f ->
            val call = async { f.api.listAllVisible() }
            f.respond(f.request(), page("next", model = "model-a"))
            f.respond(f.request(), page(null, model = "model-a"))

            assertEquals(listOf("model-a"), call.await().map { it.model })
        }
    }

    @Test
    fun `pagination detects repeated cursor and page cap`() = runBlocking<Unit> {
        supervisorScope {
            fixture().use { f ->
                val call = async { f.api.listAllVisible() }
                val first = f.request()
                f.respond(first, page("again"))
                val second = f.request()
                f.respond(second, page("again"))
                expect<CodexAppServerModelProtocolException> { call.await() }
            }
            fixture().use { f ->
                val call = async { f.api.listAllVisible(maxPages = 1) }
                f.respond(f.request(), page("more"))
                expect<CodexAppServerModelProtocolException> { call.await() }
            }
        }
    }

    @Test
    fun `malformed catalog fields fail explicitly`() = runBlocking<Unit> {
        supervisorScope {
            val malformed = listOf<JsonElement>(
                JsonNull,
                JsonPrimitive("bad"),
                JsonObject(emptyMap()),
                buildJsonObject { put("data", "bad") },
                buildJsonObject { putJsonArray("data") { add(JsonPrimitive("bad")) }; put("nextCursor", JsonNull) },
                page(null).replaceModelField("model", JsonPrimitive(3)),
                page(null).replaceModelField("hidden", JsonPrimitive("false")),
                page(null).replaceModelField("supportedReasoningEfforts", JsonPrimitive("bad")),
                page(null).replaceEffortField("reasoningEffort", JsonPrimitive(7)),
                page(null).replaceModelField("serviceTiers", JsonPrimitive("bad")),
                page(null).replaceModelField("serviceTiers", JsonArray(listOf(JsonPrimitive("bad")))),
                page(null).replaceModelField("serviceTiers", JsonArray(listOf(buildJsonObject { put("id", 7); put("name", "Fast"); put("description", "x") }))),
                page(null).replaceModelField("additionalSpeedTiers", JsonPrimitive("fast")),
                page(null).replaceModelField("additionalSpeedTiers", JsonArray(listOf(JsonPrimitive(7)))),
                buildJsonObject { put("data", page(null)["data"]!!); put("nextCursor", 8) },
            )
            malformed.forEach { value ->
                fixture().use { f ->
                    val call = async { f.api.list() }
                    f.respond(f.request(), value)
                    expect<CodexAppServerModelProtocolException> { call.await() }
                }
            }
        }
    }

    @Test
    fun `rpc errors propagate without being rewritten`() = runBlocking<Unit> {
        supervisorScope {
            fixture().use { f ->
                val call = async { f.api.list() }
                val request = f.request()
                f.transport.injectServerLine(codec.encode(JsonRpcErrorResponse(request.id, JsonRpcError(429, "catalog busy"))))
                val failure = expect<CodexAppServerResponseException> { call.await() }
                assertEquals(429, failure.error.code)
                assertEquals("catalog busy", failure.error.message)
                assertEquals(0, f.dispatcher.pendingRequestCount())
            }
        }
    }

    @Test
    fun `timeout and cancellation clean dispatcher pending requests without retry`() = runBlocking<Unit> {
        fixture().use { f ->
            val before = f.transport.successfulWriteCount()
            val timed = async { f.api.list(timeout = 10.milliseconds) }
            f.transport.takeClientLine()
            expect<kotlinx.coroutines.TimeoutCancellationException> { timed.await() }
            assertEquals(0, f.dispatcher.pendingRequestCount())
            assertEquals(before + 1, f.transport.successfulWriteCount())

            val cancelled = async { f.api.list() }
            f.transport.takeClientLine()
            cancelled.cancelAndJoin()
            assertEquals(0, f.dispatcher.pendingRequestCount())
            assertEquals(before + 2, f.transport.successfulWriteCount())
        }
    }

    @Test
    fun `invalid limits fail before writing`() = runBlocking<Unit> {
        fixture().use { f ->
            val before = f.transport.successfulWriteCount()
            expect<IllegalArgumentException> { f.api.list(limit = 0) }
            expect<IllegalArgumentException> { f.api.listAllVisible(maxPages = 0) }
            assertEquals(before, f.transport.successfulWriteCount())
        }
    }

    private fun page(
        next: String?,
        model: String = "wire-model",
        efforts: List<String> = listOf("max", "low", "focused"),
    ) = buildJsonObject {
        putJsonArray("data") {
            addJsonObject {
                put("id", "catalog-$model")
                put("model", model)
                put("displayName", "Future model")
                put("description", "description")
                put("hidden", false)
                put("defaultReasoningEffort", efforts.last())
                put("supportsPersonality", true)
                put("isDefault", true)
                put("future", 42)
                putJsonArray("supportedReasoningEfforts") {
                    efforts.forEach { value ->
                        addJsonObject {
                            put("reasoningEffort", value)
                            put("description", "Description $value")
                        }
                    }
                }
            }
        }
        if (next == null) put("nextCursor", JsonNull) else put("nextCursor", next)
        put("futureResult", "future-result")
    }

    private fun JsonObject.replaceModelField(name: String, value: JsonElement): JsonObject {
        val model = (this["data"] as JsonArray).single().jsonObject.toMutableMap().apply { put(name, value) }
        return JsonObject(toMutableMap().apply { put("data", JsonArray(listOf(JsonObject(model)))) })
    }

    private fun JsonObject.replaceEffortField(name: String, value: JsonElement): JsonObject {
        val model = (this["data"] as JsonArray).single().jsonObject
        val efforts = model["supportedReasoningEfforts"] as JsonArray
        val first = efforts.first().jsonObject.toMutableMap().apply { put(name, value) }
        val replaced = JsonArray(listOf(JsonObject(first)) + efforts.drop(1))
        return replaceModelField("supportedReasoningEfforts", replaced)
    }

    private suspend fun fixture(): Fixture {
        val transport = FakeCodexAppServerTransport()
        val dispatcher = CodexAppServerRequestDispatcher(transport)
        val connection = CodexAppServerConnection(
            dispatcher,
            CodexAppServerClientInfo(name = "test", title = "Test", version = "1"),
        )
        val init = CoroutineScope(currentCoroutineContext()).async { connection.initialize() }
        val request = decodeRequest(transport.takeClientLine())
        transport.injectServerLine(
            codec.encode(
                JsonRpcResponse(
                    request.id,
                    buildJsonObject {
                        put("userAgent", "fake")
                        put("codexHome", "/tmp")
                        put("platformFamily", "unix")
                        put("platformOs", "linux")
                    },
                ),
            ),
        )
        init.await()
        transport.takeClientLine()
        return Fixture(transport, dispatcher, connection, CodexAppServerModelApi(connection))
    }

    private fun decodeRequest(line: String) = (codec.decode(line).getOrThrow() as JsonRpcMessage.Request).value

    private suspend inline fun <reified T : Throwable> expect(crossinline block: suspend () -> Unit): T =
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
            error("unreachable")
        } catch (failure: Throwable) {
            when {
                failure is T -> failure
                failure.cause is T -> failure.cause as T
                else -> throw failure
            }
        }

    private inner class Fixture(
        val transport: FakeCodexAppServerTransport,
        val dispatcher: CodexAppServerRequestDispatcher,
        val connection: CodexAppServerConnection,
        val api: CodexAppServerModelApi,
    ) : AutoCloseable {
        suspend fun request() = decodeRequest(transport.takeClientLine())
        fun respond(request: JsonRpcRequest, value: JsonElement) =
            transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, value)))
        override fun close() = connection.close()
    }
}
