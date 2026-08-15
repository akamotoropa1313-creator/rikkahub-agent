package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CodexAppServerAccountApiTest {
    private val codec = CodexAppServerJsonRpc()

    @Test fun `read uses minimal and refresh wire and decodes account variants`() = runBlocking {
        fixture().use { f ->
            val ordinary = async { f.api.readAccount() }; val read = f.takeRequest()
            assertEquals("account/read", read.method); assertEquals(JsonObject(emptyMap()), read.params)
            f.respond(read, buildJsonObject { put("account", null); put("requiresOpenaiAuth", true) })
            assertEquals(null, ordinary.await().account); assertTrue(ordinary.await().requiresOpenaiAuth)

            val refresh = async { f.api.readAccount(true) }; val refreshed = f.takeRequest()
            assertEquals(buildJsonObject { put("refreshToken", true) }, refreshed.params)
            f.respond(refreshed, account("chatgpt") { put("email", null); put("planType", "futurePlan") })
            val chat = refresh.await().account as CodexAppServerAccount.ChatGpt
            assertEquals(null, chat.email); assertTrue(chat.planType is CodexAppServerPlanType.Unknown)

            val cases = listOf(
                account("apiKey") {} to CodexAppServerAccount.ApiKey::class,
                account("amazonBedrock") { put("usesCodexManagedCredentials", true) } to CodexAppServerAccount.AmazonBedrock::class,
                account("future") { put("opaque", 1) } to CodexAppServerAccount.Unknown::class,
            )
            for ((response, expected) in cases) {
                val call = async { f.api.readAccount() }; f.respond(f.takeRequest(), response)
                assertEquals(expected, call.await().account!!::class)
            }
        }
    }

    @Test fun `login cancel and logout use exact wire`() = runBlocking {
        fixture().use { f ->
            val login = async { f.api.startChatGptLogin() }; val start = f.takeRequest()
            assertEquals("account/login/start", start.method)
            assertEquals(buildJsonObject { put("type", "chatgpt") }, start.params)
            f.respond(start, buildJsonObject { put("type", "chatgpt"); put("loginId", "opaque-id"); put("authUrl", "https://example.test/auth?secret=1") })
            val started = login.await(); assertEquals("opaque-id", started.loginId)
            assertFalse(started.toString().contains("secret=1"))

            val cancel = async { f.api.cancelLogin(started.loginId) }; val cancelRequest = f.takeRequest()
            assertEquals("account/login/cancel", cancelRequest.method)
            assertEquals(buildJsonObject { put("loginId", "opaque-id") }, cancelRequest.params)
            f.respond(cancelRequest, buildJsonObject { put("status", "canceled") })
            assertEquals(CodexAppServerCancelLoginResult.Canceled, cancel.await())

            val logout = async { f.api.logout() }; val logoutRequest = f.takeRequest()
            assertEquals("account/logout", logoutRequest.method); assertEquals(JsonObject(emptyMap()), logoutRequest.params)
            f.respond(logoutRequest, JsonObject(emptyMap())); logout.await()
        }
    }

    @Test fun `invalid login response and blank cancellation fail safely`() = runBlocking {
        fixture().use { f ->
            val wrong = async { f.api.startChatGptLogin() }; val request = f.takeRequest()
            f.respond(request, buildJsonObject { put("type", "chatgptDeviceCode"); put("authUrl", "secret") })
            expect<CodexAppServerUnexpectedLoginVariantException> { wrong.await() }
            val writes = f.transport.successfulWriteCount()
            expect<IllegalArgumentException> { f.api.cancelLogin(" ") }
            assertEquals(writes, f.transport.successfulWriteCount())
        }
    }

    @Test fun `operations require ready state`() = runBlocking {
        val transport = FakeCodexAppServerTransport(); val connection = CodexAppServerConnection(CodexAppServerRequestDispatcher(transport), CodexAppServerClientInfo("test", "1")); val api = CodexAppServerAccountApi(connection)
        expect<CodexAppServerNotReadyException> { api.readAccount() }
        expect<CodexAppServerNotReadyException> { api.startChatGptLogin() }
        expect<CodexAppServerNotReadyException> { api.cancelLogin("id") }
        expect<CodexAppServerNotReadyException> { api.logout() }; connection.close()
    }

    private suspend fun fixture(): Fixture {
        val transport = FakeCodexAppServerTransport(); val connection = CodexAppServerConnection(CodexAppServerRequestDispatcher(transport), CodexAppServerClientInfo("test", "1"))
        val init = CoroutineScope(currentCoroutineContext()).async { connection.initialize() }; val request = takeRequest(transport)
        transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, buildJsonObject { put("userAgent", "fake"); put("codexHome", "/tmp"); put("platformFamily", "unix"); put("platformOs", "linux") })))
        init.await(); transport.takeClientLine(); return Fixture(transport, connection, CodexAppServerAccountApi(connection))
    }
    private fun account(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject { put("account", buildJsonObject { put("type", type); fields() }); put("requiresOpenaiAuth", false) }
    private suspend fun takeRequest(t: FakeCodexAppServerTransport) = (codec.decode(t.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value
    private suspend inline fun <reified T: Throwable> expect(crossinline block: suspend () -> Unit): T = try { block(); fail("Expected ${T::class.java.simpleName}"); error("unreachable") } catch (e: Throwable) { if (e is T) e else if (e.cause is T) e.cause as T else throw e }
    private inner class Fixture(val transport: FakeCodexAppServerTransport, val connection: CodexAppServerConnection, val api: CodexAppServerAccountApi): AutoCloseable {
        suspend fun takeRequest() = this@CodexAppServerAccountApiTest.takeRequest(transport)
        fun respond(request: JsonRpcRequest, result: kotlinx.serialization.json.JsonElement) = transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, result)))
        override fun close() = connection.close()
    }
}
