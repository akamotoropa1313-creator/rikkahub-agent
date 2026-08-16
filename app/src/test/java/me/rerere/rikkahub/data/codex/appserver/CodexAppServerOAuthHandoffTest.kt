package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CodexAppServerOAuthHandoffTest {
    private val codec = CodexAppServerJsonRpc()

    @Test fun `real handoff launches exact URL without extra protocol traffic`() = runBlocking {
        fixture().use { f ->
            var launched: String? = null
            val handoff = CodexAppServerOAuthHandoff(f.api) { launched = it }
            val call = async { handoff.beginChatGptLogin() }
            val request = f.takeRequest()
            assertEquals("account/login/start", request.method)
            assertEquals(buildJsonObject { put("type", "chatgpt") }, request.params)
            val url = "https://example.test/auth?state=opaque-secret&order=2"
            f.respond(request, loginResult("login-1", url))
            val pending = call.await()
            assertEquals(url, launched); assertEquals("login-1", pending.loginId)
            assertFalse(pending.toString().contains(url)); assertEquals(3, f.transport.successfulWriteCount())
            assertTrue(f.connection.state.value is CodexAppServerConnectionState.Ready)
        }
    }

    @Test fun `launcher failure is safe and has no automatic side effects`() = runBlocking {
        supervisorScope { fixture().use { f ->
            val handoff = CodexAppServerOAuthHandoff(f.api) { throw IllegalStateException("no browser") }
            val call = async { handoff.beginChatGptLogin() }; val request = f.takeRequest()
            val url = "https://example.test/auth?secret=never-log"
            f.respond(request, loginResult("login-failed", url))
            val error = expect<CodexAppServerBrowserLaunchException> { call.await() }
            assertEquals("login-failed", error.loginId); assertFalse(error.toString().contains(url))
            assertEquals(3, f.transport.successfulWriteCount())
            assertTrue(f.connection.state.value is CodexAppServerConnectionState.Ready)
        } }
    }

    @Test fun `invalid auth URL after start preserves exact login id without leaking URL`() = runBlocking {
        supervisorScope { fixture().use { f ->
            val handoff = CodexAppServerOAuthHandoff(f.api) { fail("launcher must not run for invalid URL") }
            val call = async { handoff.beginChatGptLogin() }; val request = f.takeRequest()
            val url = "http://example.test/auth?secret=never-log"
            f.respond(request, loginResult("login-invalid-url", url))
            val error = expect<CodexAppServerLoginHandoffException> { call.await() }
            assertEquals("login-invalid-url", error.loginId)
            assertFalse(error.toString().contains(url))
            assertTrue(error.cause is CodexAppServerInvalidAuthUrlException)
            assertEquals(3, f.transport.successfulWriteCount())
            assertTrue(f.connection.state.value is CodexAppServerConnectionState.Ready)
        } }
    }

    @Test fun `launcher cancellation remains cancellation while preserving exact live login id`() = runBlocking {
        supervisorScope { fixture().use { f ->
            val cancellation = CancellationException("caller cancelled")
            val handoff = CodexAppServerOAuthHandoff(f.api) { throw cancellation }
            val call = async { handoff.beginChatGptLogin() }; val request = f.takeRequest()
            f.respond(request, loginResult("login-cancelled", "https://example.test/auth"))
            val observed = expect<CodexAppServerLoginHandoffCancellationException> { call.await() }
            assertEquals("login-cancelled", observed.loginId)
            assertTrue(observed is CancellationException)
            assertEquals(cancellation, observed.cause)
            assertEquals(3, f.transport.successfulWriteCount())
            assertTrue(f.connection.state.value is CodexAppServerConnectionState.Ready)
        } }
    }

    @Test fun `url policy permits https and loopback only without rewriting`() {
        listOf("https://example.test/auth?state=secret", "http://localhost:1455/callback", "http://127.0.0.1/x", "http://[::1]/x").forEach(::validateCodexAppServerAuthUrl)
        listOf("not a url", "/relative", "rikkahub://oauth", "http://example.test/auth").forEach { value ->
            expectSync<CodexAppServerInvalidAuthUrlException> { validateCodexAppServerAuthUrl(value) }
        }
    }

    private suspend fun fixture(): Fixture {
        val transport = FakeCodexAppServerTransport()
        val connection = CodexAppServerConnection(CodexAppServerRequestDispatcher(transport), CodexAppServerClientInfo(name="test", title="Test", version="1"))
        val init = CoroutineScope(currentCoroutineContext()).async { connection.initialize() }
        val request = takeRequest(transport)
        transport.injectServerLine(codec.encode(JsonRpcResponse(request.id, buildJsonObject { put("userAgent","fake"); put("codexHome","/tmp"); put("platformFamily","unix"); put("platformOs","linux") })))
        init.await(); transport.takeClientLine()
        return Fixture(transport, connection, CodexAppServerAccountApi(connection))
    }
    private fun loginResult(id:String,url:String)=buildJsonObject { put("type","chatgpt");put("loginId",id);put("authUrl",url) }
    private suspend fun takeRequest(t:FakeCodexAppServerTransport)=(codec.decode(t.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value
    private suspend inline fun <reified T:Throwable> expect(crossinline block:suspend()->Unit):T=try{block();fail("Expected ${T::class.java.simpleName}");error("unreachable")}catch(e:Throwable){if(e is T)e else if(e.cause is T)e.cause as T else throw e}
    private inline fun <reified T:Throwable> expectSync(block:()->Unit):T=try{block();fail("Expected ${T::class.java.simpleName}");error("unreachable")}catch(e:Throwable){if(e is T)e else throw e}
    private inner class Fixture(val transport:FakeCodexAppServerTransport,val connection:CodexAppServerConnection,val api:CodexAppServerAccountApi):AutoCloseable{
        suspend fun takeRequest()=this@CodexAppServerOAuthHandoffTest.takeRequest(transport)
        fun respond(request:JsonRpcRequest,result:JsonObject)=transport.injectServerLine(codec.encode(JsonRpcResponse(request.id,result)))
        override fun close()=connection.close()
    }
}
