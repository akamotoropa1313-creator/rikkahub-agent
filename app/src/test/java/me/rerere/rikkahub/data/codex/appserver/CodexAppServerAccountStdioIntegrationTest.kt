package me.rerere.rikkahub.data.codex.appserver

import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Test

class CodexAppServerAccountStdioIntegrationTest {
    private val json = CodexAppServerJsonRpc().json

    @Test fun `controlled workspace stdio completes browser login account update and logout`() = runBlocking {
        controlled().use { f ->
            f.initialize()
            val api = CodexAppServerAccountApi(f.connection)
            val read = async(Dispatchers.Default) { api.readAccount() }; val readWire = f.awaitRequest(2)
            assertWire(readWire, "account/read", buildJsonObject {})
            f.respond(readWire, buildJsonObject { put("account", JsonNull); put("requiresOpenaiAuth", true) })
            assertTrue(read.await().requiresOpenaiAuth)

            val raw = Channel<CodexAppServerEvent.UnknownNotification>(Channel.UNLIMITED)
            val typed = Channel<CodexAppServerAccountEvent>(Channel.UNLIMITED)
            val rawJob = launch(start=CoroutineStart.UNDISPATCHED) { f.connection.events.collect { if(it is CodexAppServerEvent.UnknownNotification) raw.send(it) } }
            val typedJob = launch(start=CoroutineStart.UNDISPATCHED) { api.events.collect { typed.send(it) } }
            try {
                var launched:String?=null
                val handoff = CodexAppServerOAuthHandoff(api) { launched=it }
                val begin = async(Dispatchers.Default) { handoff.beginChatGptLogin() }; val login = f.awaitRequest(3)
                assertWire(login, "account/login/start", buildJsonObject { put("type","chatgpt") })
                val url="https://example.test/auth?opaque=1"
                f.respond(login, buildJsonObject { put("type","chatgpt");put("loginId","login-1");put("authUrl",url) })
                assertEquals("login-1", begin.await().loginId); assertEquals(url,launched)
                assertEquals(4, f.process.stdin.flushes) // initialize, initialized, read, login only
                assertTrue(f.connection.state.value is CodexAppServerConnectionState.Ready)

                f.notify("account/login/completed", buildJsonObject { put("loginId","login-1");put("success",true);put("error",JsonNull);put("onboardingEntrypoint",JsonNull) })
                assertEquals("account/login/completed", withTimeout(2_000){raw.receive()}.method)
                val completed=withTimeout(2_000){typed.receive()} as CodexAppServerAccountEvent.LoginCompleted
                assertEquals("login-1",completed.loginId);assertTrue(completed.success);assertEquals(4,f.process.stdin.flushes)

                f.notify("account/updated", buildJsonObject { put("authMode","chatgpt");put("planType","plus") })
                assertEquals("account/updated",withTimeout(2_000){raw.receive()}.method)
                val updated=withTimeout(2_000){typed.receive()} as CodexAppServerAccountEvent.Updated
                assertEquals(CodexAppServerAuthMode.ChatGpt,updated.authMode);assertEquals(CodexAppServerPlanType.Plus,updated.planType)

                val reread=async(Dispatchers.Default){api.readAccount()};val rereadWire=f.awaitRequest(4)
                assertWire(rereadWire,"account/read",buildJsonObject{})
                f.respond(rereadWire,buildJsonObject{put("account",buildJsonObject{put("type","chatgpt");put("email",JsonNull);put("planType","plus")});put("requiresOpenaiAuth",true)})
                val chat=reread.await().account as CodexAppServerAccount.ChatGpt;assertNull(chat.email);assertEquals(CodexAppServerPlanType.Plus,chat.planType)
                val logout=async(Dispatchers.Default){api.logout()};val logoutWire=f.awaitRequest(5)
                assertWire(logoutWire,"account/logout",buildJsonObject{});f.respond(logoutWire,buildJsonObject{});logout.await()
                assertTrue(f.connection.state.value is CodexAppServerConnectionState.Ready)
            } finally { rawJob.cancelAndJoin();typedJob.cancelAndJoin();raw.close();typed.close() }
        }
    }

    @Test fun `controlled workspace stdio explicitly cancels exact pending login`() = runBlocking {
        controlled().use { f ->
            f.initialize();val api=CodexAppServerAccountApi(f.connection);var launched:String?=null
            val begin=async(Dispatchers.Default){CodexAppServerOAuthHandoff(api){launched=it}.beginChatGptLogin()};val start=f.awaitRequest(2)
            assertWire(start,"account/login/start",buildJsonObject{put("type","chatgpt")})
            f.respond(start,buildJsonObject{put("type","chatgpt");put("loginId","login-cancel-1");put("authUrl","https://example.test/cancel")})
            assertEquals("login-cancel-1",begin.await().loginId);assertEquals("https://example.test/cancel",launched)
            val cancel=async(Dispatchers.Default){api.cancelLogin("login-cancel-1")};val wire=f.awaitRequest(3)
            assertWire(wire,"account/login/cancel",buildJsonObject{put("loginId","login-cancel-1")})
            f.respond(wire,buildJsonObject{put("status","canceled")});assertEquals(CodexAppServerCancelLoginResult.Canceled,cancel.await())
            assertEquals(4,f.process.stdin.flushes);assertTrue(f.connection.state.value is CodexAppServerConnectionState.Ready)
        }
    }

    private fun controlled():Fixture { val process=AppServerTestProcess();val manager=WorkspaceManager(createTempDirectory("account-stdio").toFile(),shellRunner=AppServerRecordingRunner(process));manager.ensureWorkspace("workspace");return Fixture(process,WorkspaceCodexAppServerConnectionFactory(manager,"0.1.0").create("workspace")) }
    private fun assertWire(wire:JsonObject,method:String,params:JsonObject){assertEquals(method,wire["method"]!!.jsonPrimitive.content);assertEquals(params,wire["params"]);assertFalse("jsonrpc" in wire)}
    private inner class Fixture(val process:AppServerTestProcess,val connection:CodexAppServerConnection):AutoCloseable{
        suspend fun initialize(){val call=CoroutineScope(currentCoroutineContext()).async(Dispatchers.Default){connection.initialize()};val wire=awaitRequest(0);assertEquals("initialize",wire["method"]!!.jsonPrimitive.content);respond(wire,buildJsonObject{put("userAgent","codex/test");put("codexHome","/tmp");put("platformFamily","unix");put("platformOs","linux")});call.await();withTimeout(2_000){while(process.stdin.flushes<2)yield()};assertEquals("initialized",line(1)["method"]!!.jsonPrimitive.content)}
        suspend fun awaitRequest(index:Int):JsonObject{assertTrue(withContext(Dispatchers.IO){process.stdin.awaitFlushCount(index+1,2_000)});return line(index)}
        fun line(index:Int)=json.parseToJsonElement(process.stdin.text().lineSequence().filter(String::isNotEmpty).toList()[index]).jsonObject
        fun respond(request:JsonObject,result:JsonObject)=process.writeStdout("{\"id\":${request["id"]},\"result\":$result}\n")
        fun notify(method:String,params:JsonObject)=process.writeStdout("{\"method\":${JsonPrimitive(method)},\"params\":$params}\n")
        override fun close()=connection.close()
    }
}
