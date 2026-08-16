package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CodexAppServerModelApiTest {
    private val codec = CodexAppServerJsonRpc()

    @Test fun `exact request preserves open effort order and raw fields`() = runBlocking { fixture().use { f ->
        val call = async { f.api.list("cursor", 7, false) }
        val request = f.request()
        assertEquals("model/list", request.method)
        assertEquals(buildJsonObject { put("cursor", "cursor"); put("limit", 7); put("includeHidden", false) }, request.params)
        f.respond(request, page(null))
        val model = call.await().data.single()
        assertEquals("wire-model", model.model)
        assertEquals(listOf("max", "low", "focused"), model.supportedReasoningEfforts.map { it.reasoningEffort })
        assertEquals(JsonPrimitive(42), model.raw["future"])
    } }

    @Test fun `default params are empty and malformed result fails visibly`() = runBlocking { fixture().use { f ->
        val call = async { f.api.list() }; val request = f.request()
        assertEquals(buildJsonObject {}, request.params)
        f.respond(request, buildJsonObject {})
        expectProtocolFailure { call.await() }
    } }

    @Test fun `pagination detects repeated cursor`() = runBlocking { fixture().use { f ->
        val call = async { f.api.listAllVisible() }
        val first = f.request(); f.respond(first, page("again")); val second = f.request(); f.respond(second, page("again"))
        expectProtocolFailure { call.await() }
    } }

    private fun page(next: String?) = buildJsonObject {
        putJsonArray("data") { addJsonObject {
            put("id", "catalog-id"); put("model", "wire-model"); put("displayName", "Future model")
            put("description", "description"); put("hidden", false); put("defaultReasoningEffort", "focused")
            put("supportsPersonality", true); put("isDefault", true); put("future", 42)
            putJsonArray("supportedReasoningEfforts") {
                listOf("max", "low", "focused").forEach { value -> addJsonObject { put("reasoningEffort", value); put("description", value) } }
            }
        } }
        if (next == null) put("nextCursor", JsonNull) else put("nextCursor", next)
    }

    private suspend fun fixture(): Fixture {
        val transport = FakeCodexAppServerTransport(); val connection = CodexAppServerConnection(CodexAppServerRequestDispatcher(transport), CodexAppServerClientInfo("test", "1"))
        val init = CoroutineScope(currentCoroutineContext()).async { connection.initialize() }
        val request = (codec.decode(transport.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value
        transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, buildJsonObject { put("userAgent", "fake"); put("codexHome", "/tmp"); put("platformFamily", "unix"); put("platformOs", "linux") })))
        init.await(); transport.takeClientLine()
        return Fixture(transport, connection, CodexAppServerModelApi(connection))
    }
    private suspend fun expectProtocolFailure(block: suspend () -> Unit) {
        try { block(); fail("expected CodexAppServerModelProtocolException") }
        catch (failure: Throwable) {
            if (failure !is CodexAppServerModelProtocolException && failure.cause !is CodexAppServerModelProtocolException) throw failure
        }
    }
    private inner class Fixture(val transport: FakeCodexAppServerTransport, val connection: CodexAppServerConnection, val api: CodexAppServerModelApi): AutoCloseable {
        suspend fun request() = (codec.decode(transport.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value
        fun respond(request: JsonRpcRequest, value: JsonElement) = transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, value)))
        override fun close() = connection.close()
    }
}
