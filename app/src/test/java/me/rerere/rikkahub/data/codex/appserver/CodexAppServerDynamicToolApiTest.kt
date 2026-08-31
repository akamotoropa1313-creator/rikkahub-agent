package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerDynamicToolApiTest {
    @Test fun `call preserves exact request context`() = runBlocking {
        val arguments = buildJsonObject { put("command", "pwd") }
        val event = flowOf<CodexAppServerEvent>(CodexAppServerEvent.ServerRequest(
            JsonRpcId.NumberId(42), "item/tool/call", buildJsonObject {
                put("threadId", "thread-1"); put("turnId", "turn-1"); put("callId", "call-1")
                put("tool", "termux_run_command"); put("arguments", arguments)
            },
        )).toCodexAppServerDynamicToolEvents().first() as CodexAppServerDynamicToolEvent.Call
        assertEquals(JsonRpcId.NumberId(42), event.requestId)
        assertEquals("thread-1", event.request.threadId)
        assertEquals("turn-1", event.request.turnId)
        assertEquals("call-1", event.request.callId)
        assertEquals("termux_run_command", event.request.tool)
        assertEquals(arguments, event.request.arguments)
    }

    @Test fun `malformed call becomes diagnostic event`() = runBlocking {
        val event = flowOf<CodexAppServerEvent>(CodexAppServerEvent.ServerRequest(
            JsonRpcId.StringId("bad"), "item/tool/call", JsonObject(emptyMap()),
        )).toCodexAppServerDynamicToolEvents().first()
        assertTrue(event is CodexAppServerDynamicToolEvent.MalformedCall)
    }
}
