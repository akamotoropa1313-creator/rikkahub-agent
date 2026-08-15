package me.rerere.rikkahub.data.codex.appserver

import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Test

class CodexAppServerStage12StdioIntegrationTest {
    private val json=CodexAppServerJsonRpc().json

    @Test fun `controlled workspace skills list and explicit turn use app server only`()=runBlocking { controlled().use { f ->
        f.initialize();val skills=CodexAppServerSkillsApi(f.connection);val turns=CodexAppServerTurnApi(f.connection)
        val list=async(Dispatchers.Default){skills.list()};val listWire=f.awaitRequest(2);f.assertWire(listWire,"skills/list",buildJsonObject{})
        f.respond(listWire,buildJsonObject{putJsonArray("data"){addJsonObject{put("cwd","/repo");putJsonArray("skills"){addJsonObject{put("name","demo");put("description","Demo");put("path","/skills/demo");put("scope","user");put("enabled",true)}};putJsonArray("errors"){}}}});assertEquals("demo",list.await().data.single().skills.single().name)
        val invocation=explicitSkillInvocation("demo","/skills/demo","work");val turn=async(Dispatchers.Default){turns.startTurn("thread",invocation.input)};val turnWire=f.awaitRequest(3);f.assertWire(turnWire,"turn/start",buildJsonObject{put("threadId","thread");putJsonArray("input"){addJsonObject{put("type","text");put("text","\$demo work")};addJsonObject{put("type","skill");put("name","demo");put("path","/skills/demo")}}});f.respond(turnWire,buildJsonObject{putJsonObject("turn"){put("id","turn");put("status","inProgress")}});assertEquals("turn",turn.await().turn.id);assertEquals(4,f.process.stdin.flushes);assertTrue(f.connection.state.value is CodexAppServerConnectionState.Ready)
    } }

    @Test fun `controlled workspace MCP calls each emit exactly one app server request`()=runBlocking { controlled().use { f ->
        f.initialize();val api=CodexAppServerMcpApi(f.connection)
        val status=async(Dispatchers.Default){api.listStatus()};val sw=f.awaitRequest(2);f.assertWire(sw,"mcpServerStatus/list",buildJsonObject{});f.respond(sw,buildJsonObject{putJsonArray("data"){addJsonObject{put("name","srv");put("pluginId",JsonNull);put("serverInfo",JsonNull);putJsonObject("tools"){};putJsonArray("resources"){};putJsonArray("resourceTemplates"){};put("authStatus","unsupported")}};put("nextCursor",JsonNull)});status.await()
        val resource=async(Dispatchers.Default){api.readResource("srv","file:///r")};val rw=f.awaitRequest(3);f.assertWire(rw,"mcpServer/resource/read",buildJsonObject{put("server","srv");put("uri","file:///r")});f.respond(rw,buildJsonObject{putJsonArray("contents"){addJsonObject{put("uri","file:///r");put("text","ok")}}});resource.await()
        val args=buildJsonObject{putJsonArray("values"){add(1);add(JsonNull);add(true)}};val before=f.process.stdin.flushes;val tool=async(Dispatchers.Default){api.callTool("thread","srv","run",args)};val tw=f.awaitRequest(4);f.assertWire(tw,"mcpServer/tool/call",buildJsonObject{put("threadId","thread");put("server","srv");put("tool","run");put("arguments",args)});f.respond(tw,buildJsonObject{putJsonArray("content"){addJsonObject{put("type","text");put("text","ok")}}});assertFalse(tool.await().isError);assertEquals(before+1,f.process.stdin.flushes)
        val reload=async(Dispatchers.Default){api.reload()};val reloadWire=f.awaitRequest(5);f.assertWire(reloadWire,"config/mcpServer/reload",buildJsonObject{});f.respond(reloadWire,buildJsonObject{});reload.await();assertEquals(6,f.process.stdin.flushes);assertTrue(f.connection.state.value is CodexAppServerConnectionState.Ready)
    } }

    private fun controlled():Fixture{val p=AppServerTestProcess();val m=WorkspaceManager(createTempDirectory("stage12-stdio").toFile(),shellRunner=AppServerRecordingRunner(p));m.ensureWorkspace("workspace");return Fixture(p,WorkspaceCodexAppServerConnectionFactory(m,"0.1.0").create("workspace"))}
    private inner class Fixture(val process:AppServerTestProcess,val connection:CodexAppServerConnection):AutoCloseable{suspend fun initialize(){val call=CoroutineScope(currentCoroutineContext()).async(Dispatchers.Default){connection.initialize()};val w=awaitRequest(0);respond(w,buildJsonObject{put("userAgent","codex/test");put("codexHome","/tmp");put("platformFamily","unix");put("platformOs","linux")});call.await();assertTrue(withContext(Dispatchers.IO){process.stdin.awaitFlushCount(2,2_000)})};suspend fun awaitRequest(i:Int):JsonObject{assertTrue(withContext(Dispatchers.IO){process.stdin.awaitFlushCount(i+1,2_000)});return line(i)};fun line(i:Int)=json.parseToJsonElement(process.stdin.text().lineSequence().filter(String::isNotEmpty).toList()[i]).jsonObject;fun respond(r:JsonObject,v:JsonObject)=process.writeStdout("{\"id\":${r["id"]},\"result\":$v}\n");fun assertWire(w:JsonObject,m:String,p:JsonObject){assertEquals(m,w["method"]!!.jsonPrimitive.content);assertEquals(p,w["params"]);assertFalse("jsonrpc" in w)};override fun close()=connection.close()}
}
