package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CodexAppServerSkillsApiTest {
    private val codec = CodexAppServerJsonRpc()

    @Test fun `list default and non-default params use exact wire`(): Unit = runBlocking {
        fixture().use { f ->
            suspend fun verify(cwds: List<String> = emptyList(), reload: Boolean = false, expected: JsonObject) {
                val call = async { f.api.list(cwds, reload) }; val request = f.request()
                assertEquals("skills/list", request.method); assertEquals(expected, request.params)
                f.respond(request, buildJsonObject { putJsonArray("data") {} }); assertTrue(call.await().data.isEmpty())
            }
            verify(expected = buildJsonObject {})
            verify(listOf("/a", "/b"), expected = buildJsonObject { putJsonArray("cwds") { add("/a"); add("/b") } })
            verify(reload = true, expected = buildJsonObject { put("forceReload", true) })
            verify(listOf("/a"), true, buildJsonObject { putJsonArray("cwds") { add("/a") }; put("forceReload", true) })
        }
    }

    @Test fun `metadata optional fields errors and future additions are preserved`(): Unit = runBlocking {
        fixture().use { f ->
            val call = async { f.api.list() }; val request = f.request()
            val skill = buildJsonObject {
                put("name", "demo"); put("description", "Demo"); put("path", "/skills/demo")
                put("scope", "user"); put("enabled", true); put("future", 7)
                putJsonObject("interface") { put("displayName", "Demo") }
                putJsonObject("dependencies") { putJsonArray("tools") {} }
            }
            f.respond(request, buildJsonObject { putJsonArray("data") { addJsonObject {
                put("cwd", "/repo"); putJsonArray("skills") { add(skill) }
                putJsonArray("errors") { addJsonObject { put("path", "/bad"); put("message", "invalid") } }
            } } })
            val entry = call.await().data.single(); val decoded = entry.skills.single()
            assertNull(decoded.shortDescription); assertNotNull(decoded.interfaceMetadata); assertNotNull(decoded.dependencies)
            assertEquals(JsonPrimitive(7), decoded.raw["future"]); assertEquals("invalid", entry.errors.single().message)
        }
    }

    @Test fun `config selectors and enabled values use exact wire`(): Unit = runBlocking {
        fixture().use { f ->
            val pathCall = async { f.api.writeConfig(false, path = "/skill") }; val path = f.request()
            assertEquals("skills/config/write", path.method)
            assertEquals(buildJsonObject { put("path", "/skill"); put("enabled", false) }, path.params)
            f.respond(path, buildJsonObject { put("effectiveEnabled", false) }); assertFalse(pathCall.await().effectiveEnabled)
            val nameCall = async { f.api.writeConfig(true, name = "demo") }; val name = f.request()
            assertEquals(buildJsonObject { put("name", "demo"); put("enabled", true) }, name.params)
            f.respond(name, buildJsonObject { put("effectiveEnabled", true) }); assertTrue(nameCall.await().effectiveEnabled)
        }
    }

    @Test fun `invalid input and malformed responses fail safely`(): Unit = runBlocking {
        fixture().use { f ->
            val writes = f.transport.successfulWriteCount()
            assertFails<IllegalArgumentException> { f.api.list(listOf(" ")) }
            assertFails<IllegalArgumentException> { f.api.writeConfig(true) }
            assertEquals(writes, f.transport.successfulWriteCount())
            supervisorScope {
                val malformed = async { f.api.list() }; f.respond(f.request(), JsonArray(emptyList()))
                assertFails<CodexAppServerSkillsProtocolException> { malformed.await() }
            }
            supervisorScope {
                val badSkill = async { f.api.list() }; val req = f.request()
                f.respond(req, buildJsonObject { putJsonArray("data") { addJsonObject { put("cwd", "/"); putJsonArray("skills") { addJsonObject {} }; putJsonArray("errors") {} } } })
                assertFails<CodexAppServerSkillsProtocolException> { badSkill.await() }
            }
        }
    }

    @Test fun `every operation is Ready gated`(): Unit = runBlocking {
        val t = FakeCodexAppServerTransport(); val c = CodexAppServerConnection(CodexAppServerRequestDispatcher(t), CodexAppServerClientInfo(name = "test", version = "1")); val api = CodexAppServerSkillsApi(c)
        assertFails<CodexAppServerNotReadyException> { api.list() }; assertFails<CodexAppServerNotReadyException> { api.writeConfig(true, name = "x") }
        assertEquals(0, t.successfulWriteCount()); c.close()
    }

    private suspend inline fun <reified T: Throwable> assertFails(crossinline block: suspend () -> Unit): T = try { block(); fail("expected ${T::class.java.simpleName}"); error("unreachable") } catch (e: Throwable) { if (e is T) e else if (e.cause is T) e.cause as T else throw e }
    private suspend fun fixture(): Fixture { val t=FakeCodexAppServerTransport(); val d=CodexAppServerRequestDispatcher(t); val c=CodexAppServerConnection(d,CodexAppServerClientInfo(name = "test", version = "1")); val init=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async{c.initialize()}; val r=(codec.decode(t.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value; t.injectServerLine(codec.encode(JsonRpcResponse(r.id, buildJsonObject { put("userAgent","fake");put("codexHome","/tmp");put("platformFamily","unix");put("platformOs","linux") })));init.await();t.takeClientLine();return Fixture(t,c,CodexAppServerSkillsApi(c)) }
    private inner class Fixture(val transport:FakeCodexAppServerTransport,val connection:CodexAppServerConnection,val api:CodexAppServerSkillsApi):AutoCloseable { suspend fun request()=(codec.decode(transport.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value; fun respond(r:JsonRpcRequest,v:JsonElement)=transport.injectServerLine(codec.encode(JsonRpcResponse(r.id,v)));override fun close()=connection.close() }
}
