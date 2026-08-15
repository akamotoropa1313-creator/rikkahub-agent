package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

class CodexAppServerApprovalApiTest {
    private val codec = CodexAppServerJsonRpc()

    @Test fun `command request decodes stable fields ids actions network and amendments`() = runBlocking {
        fixture().use { f ->
            val events = collect(f)
            val action = buildJsonObject { put("type", "read"); put("command", "cat a"); put("name", "a"); put("path", "/a") }
            val network = buildJsonObject { put("host", "example.com"); put("protocol", "https") }
            val amendment = buildJsonObject { put("host", "example.com"); put("action", "allow") }
            val params = JsonObject(commandParams() + mapOf(
                "approvalId" to JsonPrimitive("callback-99"), "environmentId" to JsonPrimitive("env"),
                "reason" to JsonPrimitive("needed"), "command" to JsonPrimitive("cat a"), "cwd" to JsonPrimitive("/work"),
                "commandActions" to JsonArray(listOf(action)), "networkApprovalContext" to network,
                "proposedExecpolicyAmendment" to JsonArray(listOf(JsonPrimitive("allow cat"))),
                "proposedNetworkPolicyAmendments" to JsonArray(listOf(amendment)), "future" to JsonPrimitive(true)))
            f.request(JsonRpcId.StringId("rpc-request-7"), COMMAND, params)
            val event = events.next() as CodexAppServerApprovalEvent.CommandExecutionRequest
            assertEquals(JsonRpcId.StringId("rpc-request-7"), event.requestId)
            with(event.request) {
                assertEquals("thread", threadId); assertEquals("turn", turnId); assertEquals("command-1", itemId)
                assertEquals(9_223_372_036_854_000_000L, startedAtMs); assertEquals("callback-99", approvalId)
                assertEquals("env", environmentId); assertEquals("needed", reason); assertEquals("cat a", command); assertEquals("/work", cwd)
                assertTrue(commandActions!!.single() is CodexAppServerCommandAction.Read)
                assertEquals(CodexAppServerNetworkProtocol.Https, networkApprovalContext!!.protocol)
                assertEquals(listOf("allow cat"), proposedExecpolicyAmendment)
                assertEquals(CodexAppServerNetworkPolicyRuleAction.Allow, proposedNetworkPolicyAmendments!!.single().action)
                assertSame(params, rawParams)
            }
            events.close()
        }
    }

    @Test fun `network-only and unknown protocol requests decode with nullable optional fields`() = runBlocking {
        fixture().use { f -> val events = collect(f)
            val params = JsonObject(commandParams() + ("networkApprovalContext" to buildJsonObject { put("host", "h"); put("protocol", "future") }) + ("command" to JsonNull) + ("cwd" to JsonNull) + ("commandActions" to JsonNull))
            f.request(JsonRpcId.NumberId(42), COMMAND, params)
            val request = (events.next() as CodexAppServerApprovalEvent.CommandExecutionRequest).request
            assertEquals(CodexAppServerNetworkProtocol.Unknown("future"), request.networkApprovalContext!!.protocol)
            assertEquals(null, request.command); assertEquals(null, request.cwd); assertEquals(null, request.commandActions)
            events.close()
        }
    }

    @Test fun `representative malformed command payloads become diagnostics and stream survives`() = runBlocking {
        fixture().use { f -> val events = collect(f)
            val malformed = listOf<JsonElementCase>(
                JsonElementCase(JsonPrimitive("bad")), JsonElementCase(JsonObject(commandParams() - "threadId")),
                JsonElementCase(JsonObject(commandParams() + ("turnId" to JsonNull))), JsonElementCase(JsonObject(commandParams() + ("itemId" to JsonPrimitive(1)))),
                JsonElementCase(JsonObject(commandParams() - "startedAtMs")), JsonElementCase(JsonObject(commandParams() + ("startedAtMs" to JsonPrimitive("1")))),
                JsonElementCase(JsonObject(commandParams() + ("startedAtMs" to JsonPrimitive(1.5)))),
                JsonElementCase(JsonObject(commandParams() + ("networkApprovalContext" to JsonPrimitive("bad")))),
                JsonElementCase(JsonObject(commandParams() + ("networkApprovalContext" to buildJsonObject { put("host", 1); put("protocol", "https") }))),
                JsonElementCase(JsonObject(commandParams() + ("networkApprovalContext" to buildJsonObject { put("host", "h"); put("protocol", 1) }))),
                JsonElementCase(JsonObject(commandParams() + ("commandActions" to JsonPrimitive("bad")))),
                JsonElementCase(JsonObject(commandParams() + ("commandActions" to buildJsonArray { add(buildJsonObject { put("type", "read") }) }))),
                JsonElementCase(JsonObject(commandParams() + ("proposedExecpolicyAmendment" to JsonPrimitive("bad")))),
                JsonElementCase(JsonObject(commandParams() + ("proposedExecpolicyAmendment" to JsonArray(listOf(JsonPrimitive(1)))))),
                JsonElementCase(JsonObject(commandParams() + ("proposedNetworkPolicyAmendments" to JsonPrimitive("bad")))),
                JsonElementCase(JsonObject(commandParams() + ("proposedNetworkPolicyAmendments" to buildJsonArray { add(buildJsonObject { put("host", 1); put("action", "allow") }) }))),
            )
            malformed.forEachIndexed { i, value -> f.request(JsonRpcId.NumberId(i.toLong()), COMMAND, value.value) }
            f.request(JsonRpcId.StringId("valid"), FILE, fileParams())
            repeat(malformed.size) { assertTrue(events.next() is CodexAppServerApprovalEvent.MalformedRequest) }
            assertTrue(events.next() is CodexAppServerApprovalEvent.FileChangeRequest)
            events.close()
        }
    }

    @Test fun `file requests preserve ids raw and nullable presentation fields`() = runBlocking {
        fixture().use { f -> val events = collect(f)
            val present = JsonObject(fileParams() + ("reason" to JsonPrimitive("why")) + ("grantRoot" to JsonPrimitive("/root")))
            f.request(JsonRpcId.StringId("s"), FILE, present); f.request(JsonRpcId.NumberId(7), FILE, JsonObject(fileParams() + ("reason" to JsonNull) + ("grantRoot" to JsonNull)))
            val first = events.next() as CodexAppServerApprovalEvent.FileChangeRequest
            assertEquals(JsonRpcId.StringId("s"), first.requestId); assertEquals("why", first.request.reason); assertEquals("/root", first.request.grantRoot); assertSame(present, first.request.rawParams)
            val second = events.next() as CodexAppServerApprovalEvent.FileChangeRequest
            assertEquals(JsonRpcId.NumberId(7), second.requestId); assertEquals(null, second.request.reason); assertEquals(null, second.request.grantRoot)
            f.request(JsonRpcId.NumberId(8), FILE, JsonObject(fileParams() + ("startedAtMs" to JsonPrimitive(1.2))))
            assertTrue(events.next() is CodexAppServerApprovalEvent.MalformedRequest); events.close()
        }
    }

    @Test fun `all command decisions have exact response wire and cancel sends no interrupt`() = runBlocking {
        fixture().use { f ->
            val amendment = CodexAppServerNetworkPolicyAmendment("host", CodexAppServerNetworkPolicyRuleAction.Allow, JsonObject(emptyMap()))
            val cases = listOf(
                CodexAppServerCommandApprovalDecision.Accept to JsonPrimitive("accept"),
                CodexAppServerCommandApprovalDecision.AcceptForSession to JsonPrimitive("acceptForSession"),
                CodexAppServerCommandApprovalDecision.Decline to JsonPrimitive("decline"),
                CodexAppServerCommandApprovalDecision.Cancel to JsonPrimitive("cancel"),
                CodexAppServerCommandApprovalDecision.AcceptWithExecpolicyAmendment(listOf("rule")) to buildJsonObject { put("acceptWithExecpolicyAmendment", buildJsonObject { put("execpolicy_amendment", JsonArray(listOf(JsonPrimitive("rule")))) }) },
                CodexAppServerCommandApprovalDecision.ApplyNetworkPolicyAmendment(amendment) to buildJsonObject { put("applyNetworkPolicyAmendment", buildJsonObject { put("network_policy_amendment", buildJsonObject { put("host", "host"); put("action", "allow") }) }) },
            )
            cases.forEachIndexed { i, (decision, expected) ->
                val id: JsonRpcId = if (i == 0) JsonRpcId.StringId("rpc-request-7") else JsonRpcId.NumberId(i.toLong())
                f.api.respondCommandApproval(id, decision)
                assertResponse(f.transport.takeClientLine(), id, expected)
            }
            assertEquals(2 + cases.size, f.transport.successfulWriteCount()) // initialize + initialized + responses
        }
    }

    @Test fun `file decisions preserve string and numeric response ids`() = runBlocking {
        fixture().use { f ->
            listOf(CodexAppServerFileChangeApprovalDecision.Accept to "accept", CodexAppServerFileChangeApprovalDecision.AcceptForSession to "acceptForSession", CodexAppServerFileChangeApprovalDecision.Decline to "decline", CodexAppServerFileChangeApprovalDecision.Cancel to "cancel").forEachIndexed { i, (decision, wire) ->
                listOf<JsonRpcId>(JsonRpcId.StringId("id-$i"), JsonRpcId.NumberId(i.toLong())).forEach { id -> f.api.respondFileChangeApproval(id, decision); assertResponse(f.transport.takeClientLine(), id, JsonPrimitive(wire)) }
            }
        }
    }

    @Test fun `unknown outbound network action fails before any write`() = runBlocking {
        fixture().use { f -> val before = f.transport.successfulWriteCount()
            val amendment = CodexAppServerNetworkPolicyAmendment("h", CodexAppServerNetworkPolicyRuleAction.Unknown("future"), JsonObject(emptyMap()))
            expect<IllegalArgumentException> { f.api.respondCommandApproval(JsonRpcId.NumberId(1), CodexAppServerCommandApprovalDecision.ApplyNetworkPolicyAmendment(amendment)) }
            assertEquals(before, f.transport.successfulWriteCount())
        }
    }

    @Test fun `raw and typed requests coexist and no response is automatic`() = runBlocking {
        fixture().use { f ->
            val raw = mutableListOf<CodexAppServerEvent>(); val typed = mutableListOf<CodexAppServerApprovalEvent>()
            val rawJob = launch(start = CoroutineStart.UNDISPATCHED) { f.connection.events.collect { raw += it } }
            val typedJob = launch(start = CoroutineStart.UNDISPATCHED) { f.api.events.collect { typed += it } }
            val before = f.transport.successfulWriteCount()
            f.request(JsonRpcId.StringId("rpc-request-7"), COMMAND, JsonObject(commandParams() + ("approvalId" to JsonPrimitive("callback-99"))))
            f.request(JsonRpcId.NumberId(9), FILE, fileParams())
            f.notify(RESOLVED, buildJsonObject { put("threadId", "thread"); put("requestId", "rpc-request-7") })
            withTimeout(1_000) { while (raw.size < 3 || typed.size < 3) yield() }
            assertEquals(before, f.transport.successfulWriteCount()); assertTrue(raw[0] is CodexAppServerEvent.ServerRequest)
            assertEquals(JsonRpcId.StringId("rpc-request-7"), (typed[0] as CodexAppServerApprovalEvent.CommandExecutionRequest).requestId)
            f.api.respondCommandApproval(JsonRpcId.StringId("rpc-request-7"), CodexAppServerCommandApprovalDecision.Accept)
            assertResponse(f.transport.takeClientLine(), JsonRpcId.StringId("rpc-request-7"), JsonPrimitive("accept"))
            rawJob.cancelAndJoin(); typedJob.cancelAndJoin()
        }
    }

    @Test fun `resolved supports both id types without fabricating responses`() = runBlocking {
        fixture().use { f -> val events = collect(f); val before = f.transport.successfulWriteCount()
            f.notify(RESOLVED, buildJsonObject { put("threadId", "t"); put("requestId", 42) })
            f.notify(RESOLVED, buildJsonObject { put("threadId", "t"); put("requestId", "x") })
            val number = events.next() as CodexAppServerApprovalEvent.Resolved; val string = events.next() as CodexAppServerApprovalEvent.Resolved
            assertEquals(JsonRpcId.NumberId(42), number.requestId); assertEquals(JsonRpcId.StringId("x"), string.requestId)
            assertEquals(before, f.transport.successfulWriteCount()); events.close()
        }
    }

    @Test fun `responses require ready connection and never write`() = runBlocking {
        val transport = FakeCodexAppServerTransport(); val connection = CodexAppServerConnection(
            CodexAppServerRequestDispatcher(transport),
            CodexAppServerClientInfo(name = "test", version = "1"),
        ); val api = CodexAppServerApprovalApi(connection)
        expect<CodexAppServerNotReadyException> { api.respondCommandApproval(JsonRpcId.NumberId(1), CodexAppServerCommandApprovalDecision.Accept) }
        expect<CodexAppServerNotReadyException> { api.respondFileChangeApproval(JsonRpcId.StringId("x"), CodexAppServerFileChangeApprovalDecision.Decline) }
        assertEquals(0, transport.successfulWriteCount()); connection.close()
    }

    private fun assertResponse(line:String, id:JsonRpcId, decision:kotlinx.serialization.json.JsonElement) {
        val raw=codec.json.parseToJsonElement(line).jsonObject
        assertEquals(setOf("id","result"), raw.keys); assertFalse("jsonrpc" in raw); assertFalse("method" in raw); assertFalse("params" in raw)
        assertEquals(id, (codec.decode(line).getOrThrow() as JsonRpcMessage.Response).value.id)
        assertEquals(buildJsonObject { put("decision", decision) }, raw["result"]!!.jsonObject)
    }
    private suspend fun fixture(): Fixture { val t=FakeCodexAppServerTransport(); val d=CodexAppServerRequestDispatcher(t); val c=CodexAppServerConnection(d,CodexAppServerClientInfo(name = "test", version = "1")); val init=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async{c.initialize()}; val request=(codec.decode(t.takeClientLine()).getOrThrow() as JsonRpcMessage.Request).value; t.injectServerLine(codec.encode(JsonRpcResponse(request.id,buildJsonObject{put("userAgent","fake");put("codexHome","/tmp");put("platformFamily","unix");put("platformOs","linux")})));init.await();t.takeClientLine();return Fixture(t,c,CodexAppServerApprovalApi(c)) }
    private fun commandParams()=buildJsonObject{put("threadId","thread");put("turnId","turn");put("itemId","command-1");put("startedAtMs",9_223_372_036_854_000_000L)}
    private fun fileParams()=buildJsonObject{put("threadId","thread");put("turnId","turn");put("itemId","file-1");put("startedAtMs",8_000_000_000L)}
    private suspend fun collect(f:Fixture):EventCollector { val channel=kotlinx.coroutines.channels.Channel<CodexAppServerApprovalEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED); val job=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch(start=CoroutineStart.UNDISPATCHED){f.api.events.collect{channel.send(it)}}; return EventCollector(channel,job) }
    private suspend inline fun <reified T:Throwable> expect(crossinline block:suspend()->Unit):T=try{block();fail("Expected ${T::class.java.simpleName}");error("unreachable")}catch(e:Throwable){if(e is T)e else throw e}
    private data class JsonElementCase(val value:kotlinx.serialization.json.JsonElement)
    private inner class Fixture(val transport:FakeCodexAppServerTransport,val connection:CodexAppServerConnection,val api:CodexAppServerApprovalApi):AutoCloseable { fun request(id:JsonRpcId,method:String,params:kotlinx.serialization.json.JsonElement)=transport.injectServerLine(codec.encode(JsonRpcRequest(id,method,params)));fun notify(method:String,params:kotlinx.serialization.json.JsonElement)=transport.injectServerLine(codec.encode(JsonRpcNotification(method,params)));override fun close()=connection.close() }
    private class EventCollector(val channel:kotlinx.coroutines.channels.Channel<CodexAppServerApprovalEvent>,val job:kotlinx.coroutines.Job){suspend fun next()=withTimeout(1_000){channel.receive()};suspend fun close(){job.cancelAndJoin();channel.close()}}
    companion object { const val COMMAND="item/commandExecution/requestApproval";const val FILE="item/fileChange/requestApproval";const val RESOLVED="serverRequest/resolved" }
}
