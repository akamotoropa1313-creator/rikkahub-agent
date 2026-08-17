package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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

    @Test fun `null no-auth read and known ChatGPT plan decode independently`() = runBlocking {
        fixture().use { f ->
            val noAuth = async { f.api.readAccount() }; f.respond(f.takeRequest(), buildJsonObject { put("account", null); put("requiresOpenaiAuth", false) })
            assertFalse(noAuth.await().requiresOpenaiAuth)
            val chatCall = async { f.api.readAccount() }; f.respond(f.takeRequest(), account("chatgpt") { put("email", "a@example.test"); put("planType", "plus") })
            val chat = chatCall.await().account as CodexAppServerAccount.ChatGpt
            assertEquals("a@example.test", chat.email); assertEquals(CodexAppServerPlanType.Plus, chat.planType)
        }
    }

    @Test fun `cancel statuses remain forward compatible`() = runBlocking {
        fixture().use { f ->
            listOf("notFound", "futureStatus").forEach { status ->
                val call = async { f.api.cancelLogin("exact-id") }; val request = f.takeRequest()
                assertEquals(buildJsonObject { put("loginId", "exact-id") }, request.params)
                f.respond(request, buildJsonObject { put("status", status) })
                if (status == "notFound") assertEquals(CodexAppServerCancelLoginResult.NotFound, call.await())
                else assertEquals(CodexAppServerCancelLoginResult.Unknown(status), call.await())
            }
        }
    }

    @Test fun `amazon bedrock managed credentials defaults strictly`() = runBlocking {
        supervisorScope { fixture().use { f ->
            listOf(null to false, JsonPrimitive(false) to false, JsonPrimitive(true) to true).forEach { (wire, expected) ->
                val call = async { f.api.readAccount() }
                f.respond(f.takeRequest(), account("amazonBedrock") { wire?.let { put("usesCodexManagedCredentials", it) } })
                assertEquals(expected, (call.await().account as CodexAppServerAccount.AmazonBedrock).usesCodexManagedCredentials)
            }
            listOf(JsonNull, JsonPrimitive("false"), JsonPrimitive("true"), JsonPrimitive(0), buildJsonObject {}, JsonArray(emptyList())).forEach { malformed ->
                val call = async { f.api.readAccount() }
                f.respond(f.takeRequest(), account("amazonBedrock") { put("usesCodexManagedCredentials", malformed) })
                expect<CodexAppServerAccountProtocolException> { call.await() }
            }
        } }
    }

    @Test fun `all official plan wire values map to known typed variants`() {
        val expected = linkedMapOf(
            "free" to CodexAppServerPlanType.Free, "go" to CodexAppServerPlanType.Go,
            "plus" to CodexAppServerPlanType.Plus, "pro" to CodexAppServerPlanType.Pro,
            "prolite" to CodexAppServerPlanType.ProLite, "team" to CodexAppServerPlanType.Team,
            "self_serve_business_prolite" to CodexAppServerPlanType.SelfServeBusinessProLite,
            "self_serve_business_usage_based" to CodexAppServerPlanType.SelfServeBusinessUsageBased,
            "business" to CodexAppServerPlanType.Business, "ent26" to CodexAppServerPlanType.Ent26,
            "enterprise_cbp_automation" to CodexAppServerPlanType.EnterpriseCbpAutomation,
            "enterprise_cbp_usage_based" to CodexAppServerPlanType.EnterpriseCbpUsageBased,
            "enterprise" to CodexAppServerPlanType.Enterprise, "edu" to CodexAppServerPlanType.Edu,
        )
        expected.forEach { (wire, plan) -> assertEquals("wire=$wire", plan, wire.toPlanType()) }
        assertEquals(CodexAppServerPlanType.Unknown("futurePlan"), "futurePlan".toPlanType())
    }

    @Test fun `missing and blank login fields fail protocol validation`() = runBlocking {
        supervisorScope { fixture().use { f ->
            val results = listOf(
                buildJsonObject { put("type", "chatgpt"); put("authUrl", "https://example.test") },
                buildJsonObject { put("type", "chatgpt"); put("loginId", " "); put("authUrl", "https://example.test") },
                buildJsonObject { put("type", "chatgpt"); put("loginId", "id") },
                buildJsonObject { put("type", "chatgpt"); put("loginId", "id"); put("authUrl", " ") },
            )
            results.forEach { result ->
                val call = async { f.api.startChatGptLogin() }; f.respond(f.takeRequest(), result)
                expect<CodexAppServerAccountProtocolException> { call.await() }
            }
        } }
    }

    @Test fun `invalid login response and blank cancellation fail safely`() = runBlocking {
        supervisorScope { fixture().use { f ->
            val wrong = async { f.api.startChatGptLogin() }; val request = f.takeRequest()
            f.respond(request, buildJsonObject { put("type", "chatgptDeviceCode"); put("authUrl", "secret") })
            expect<CodexAppServerUnexpectedLoginVariantException> { wrong.await() }
            val writes = f.transport.successfulWriteCount()
            expect<IllegalArgumentException> { f.api.cancelLogin(" ") }
            assertEquals(writes, f.transport.successfulWriteCount())
        } }
    }

    @Test fun `operations require ready state`() = runBlocking {
        val transport = FakeCodexAppServerTransport(); val connection = CodexAppServerConnection(CodexAppServerRequestDispatcher(transport), testClientInfo()); val api = CodexAppServerAccountApi(connection)
        val writes = transport.successfulWriteCount()
        expect<CodexAppServerNotReadyException> { api.readAccount() }
        expect<CodexAppServerNotReadyException> { api.startChatGptLogin() }
        expect<CodexAppServerNotReadyException> { api.cancelLogin("id") }
        expect<CodexAppServerNotReadyException> { api.logout() }
        assertEquals(writes, transport.successfulWriteCount()); connection.close()
    }

    private suspend fun fixture(): Fixture {
        val transport = FakeCodexAppServerTransport(); val connection = CodexAppServerConnection(CodexAppServerRequestDispatcher(transport), testClientInfo())
        val init = CoroutineScope(currentCoroutineContext()).async { connection.initialize() }; val request = takeRequest(transport)
        transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, buildJsonObject { put("userAgent", "fake"); put("codexHome", "/tmp"); put("platformFamily", "unix"); put("platformOs", "linux") })))
        init.await(); transport.takeClientLine(); return Fixture(transport, connection, CodexAppServerAccountApi(connection))
    }
    private fun testClientInfo() = CodexAppServerClientInfo(name = "test", title = "Test", version = "1")
    private fun account(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject { put("account", buildJsonObject { put("type", type); fields() }); put("requiresOpenaiAuth", false) }
    private suspend fun takeRequest(t: FakeCodexAppServerTransport) = (codec.decode(t.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value
    private suspend inline fun <reified T: Throwable> expect(crossinline block: suspend () -> Unit): T = try { block(); fail("Expected ${T::class.java.simpleName}"); error("unreachable") } catch (e: Throwable) { if (e is T) e else if (e.cause is T) e.cause as T else throw e }
    private inner class Fixture(val transport: FakeCodexAppServerTransport, val connection: CodexAppServerConnection, val api: CodexAppServerAccountApi): AutoCloseable {
        suspend fun takeRequest() = this@CodexAppServerAccountApiTest.takeRequest(transport)
        fun respond(request: JsonRpcRequest, result: kotlinx.serialization.json.JsonElement) = transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, result)))
        override fun close() = connection.close()
    }
}
