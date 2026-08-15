package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CodexAppServerMcpApiTest {
    private val codec=CodexAppServerJsonRpc()
    private fun status(auth:String="unknown")=buildJsonObject { putJsonArray("data") { addJsonObject { put("name","srv");put("pluginId","plugin");put("serverInfo",JsonNull);putJsonObject("tools"){};putJsonArray("resources"){};putJsonArray("resourceTemplates"){};put("authStatus",auth);put("future",9) } };put("nextCursor",JsonNull) }

    @Test fun `status options typed decoding and forward compatibility`(): Unit = runBlocking { fixture().use { f ->
        val call=async { f.api.listStatus(cursor="c",limit=2,detail=CodexMcpServerStatusDetail.ToolsAndAuthOnly,threadId="t") };val r=f.request()
        assertEquals("mcpServerStatus/list",r.method);assertEquals(buildJsonObject { put("cursor","c");put("limit",2);put("detail","toolsAndAuthOnly");put("threadId","t") },r.params)
        f.respond(r,status());val item=call.await().data.single();assertEquals("plugin",item.pluginId);assertSame(CodexMcpAuthStatus.UnknownStatus,item.authStatus);assertEquals(JsonPrimitive(9),item.raw["future"])
        val future=async { f.api.listStatus() };val fr=f.request();assertEquals(buildJsonObject{},fr.params);f.respond(fr,status("future"));assertEquals(CodexMcpAuthStatus.Unknown("future"),future.await().data.single().authStatus)
    } }

    @Test fun `missing required status inventory fails`(): Unit = runBlocking { fixture().use { f ->
        for(field in listOf("tools","resources","resourceTemplates")){ val call=async { f.api.listStatus() };val r=f.request();val item=status()["data"]!!.jsonArray.single().jsonObject.toMutableMap().also{it.remove(field)};f.respond(r,buildJsonObject { put("data",JsonArray(listOf(JsonObject(item))));put("nextCursor",JsonNull) });assertFails<CodexAppServerMcpProtocolException>{call.await()} }
    } }

    @Test fun `resource tool and reload exact wire and results`(): Unit = runBlocking { fixture().use { f ->
        val resource=async { f.api.readResource("srv","file:///a","thread") };val rr=f.request();assertEquals("mcpServer/resource/read",rr.method);assertEquals(buildJsonObject{put("server","srv");put("uri","file:///a");put("threadId","thread")},rr.params);val content=buildJsonObject{put("uri","file:///a");put("blob","AA==");put("future",true)};f.respond(rr,buildJsonObject{put("contents",JsonArray(listOf(content)))});assertEquals(content,resource.await().contents.single().raw)
        val args=buildJsonObject { putJsonArray("array"){add(1);add(JsonNull);add(true)};putJsonObject("nested"){put("n",2.5)} }
        val tool=async { f.api.callTool("thread","srv","run",args,buildJsonObject{put("trace","x")}) };val tr=f.request();assertEquals("mcpServer/tool/call",tr.method);assertEquals(args,tr.params!!.jsonObject["arguments"]);f.respond(tr,buildJsonObject{putJsonArray("content"){addJsonObject{put("type","text");put("text","no")};addJsonObject{put("type","future")}};putJsonObject("structuredContent"){put("ok",false)};put("isError",true);putJsonObject("_meta"){put("x",1)}});val result=tool.await();assertTrue(result.isError);assertEquals(2,result.content.size);assertNotNull(result.structuredContent);assertNotNull(result.metadata)
        val reload=async { f.api.reload() };val reloadRequest=f.request();assertEquals("config/mcpServer/reload",reloadRequest.method);assertEquals(buildJsonObject{},reloadRequest.params);f.respond(reloadRequest,buildJsonObject{});reload.await()
    } }

    @Test fun `OAuth params and redaction are exact`(): Unit = runBlocking { fixture().use { f ->
        val minimal=async { f.api.beginOAuthLogin("srv") };val minimalRequest=f.request();assertEquals(buildJsonObject { put("name","srv") },minimalRequest.params);f.respond(minimalRequest,buildJsonObject{put("authorizationUrl","https://minimal.test")});minimal.await()
        for(reg in CodexMcpClientRegistration.entries){ val call=async { f.api.beginOAuthLogin("srv",threadId="t",clientRegistration=reg,scopes=listOf("read"),timeoutSecs=30) };val r=f.request();assertEquals("mcpServer/oauth/login",r.method);assertEquals(reg.wireValue,r.params!!.jsonObject["clientRegistration"]!!.jsonPrimitive.content);assertEquals(JsonArray(listOf(JsonPrimitive("read"))),r.params!!.jsonObject["scopes"]);assertEquals(30L,r.params!!.jsonObject["timeoutSecs"]!!.jsonPrimitive.long);f.respond(r,buildJsonObject{put("authorizationUrl","https://secret.test/?state=secret")});val result=call.await();assertFalse(result.toString().contains("secret.test"));assertTrue(result.authorizationUrlForLaunch().contains("secret.test")) }
    } }

    @Test fun `validation and Ready gating prevent writes`(): Unit = runBlocking {
        fixture().use { f -> val n=f.transport.successfulWriteCount();assertFails<IllegalArgumentException>{f.api.readResource(" ","u")};assertFails<IllegalArgumentException>{f.api.readResource("s"," ")};assertFails<IllegalArgumentException>{f.api.callTool("","s","t")};assertEquals(n,f.transport.successfulWriteCount()) }
        val t=FakeCodexAppServerTransport();val c=CodexAppServerConnection(CodexAppServerRequestDispatcher(t),CodexAppServerClientInfo(name = "test", version = "1"));val a=CodexAppServerMcpApi(c);assertFails<CodexAppServerNotReadyException>{a.listStatus()};assertFails<CodexAppServerNotReadyException>{a.reload()};assertFails<CodexAppServerNotReadyException>{a.readResource("s","u")};assertFails<CodexAppServerNotReadyException>{a.callTool("t","s","x")};assertFails<CodexAppServerNotReadyException>{a.beginOAuthLogin("s")};assertEquals(0,t.successfulWriteCount());c.close()
    }

    @Test fun `MCP event projection decodes known notifications and malformed diagnostics`(): Unit = runBlocking { fixture().use { f ->
        val event=async(start=CoroutineStart.UNDISPATCHED){f.api.events.first()};f.transport.injectServerLine(codec.encode(JsonRpcNotification("mcpServer/oauthLogin/completed",buildJsonObject{put("name","srv");put("threadId",JsonNull);put("success",true)})));assertTrue(event.await() is CodexAppServerMcpEvent.OAuthLoginCompleted)
        val malformed=async(start=CoroutineStart.UNDISPATCHED){f.api.events.first()};f.transport.injectServerLine(codec.encode(JsonRpcNotification("item/mcpToolCall/progress",buildJsonObject{})));assertTrue(malformed.await() is CodexAppServerMcpEvent.MalformedNotification)
    } }

    private suspend inline fun <reified T:Throwable> assertFails(crossinline b:suspend()->Unit):T=try{b();fail("expected ${T::class.java.simpleName}");error("unreachable")}catch(e:Throwable){if(e is T)e else if(e.cause is T)e.cause as T else throw e}
    private suspend fun fixture():Fixture{val t=FakeCodexAppServerTransport();val d=CodexAppServerRequestDispatcher(t);val c=CodexAppServerConnection(d,CodexAppServerClientInfo(name = "test", version = "1"));val init=CoroutineScope(currentCoroutineContext()).async{c.initialize()};val r=(codec.decode(t.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value;t.injectServerLine(codec.encode(JsonRpcResponse(r.id,buildJsonObject{put("userAgent","fake");put("codexHome","/tmp");put("platformFamily","unix");put("platformOs","linux")})));init.await();t.takeClientLine();return Fixture(t,c,CodexAppServerMcpApi(c))}
    private inner class Fixture(val transport:FakeCodexAppServerTransport,val connection:CodexAppServerConnection,val api:CodexAppServerMcpApi):AutoCloseable{suspend fun request()=(codec.decode(transport.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value;fun respond(r:JsonRpcRequest,v:JsonElement)=transport.injectServerLine(codec.encode(JsonRpcResponse(r.id,v)));override fun close()=connection.close()}
}
