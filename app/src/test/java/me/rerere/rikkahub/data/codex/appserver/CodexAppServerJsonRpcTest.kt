package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerJsonRpcTest {
    private val codec = CodexAppServerJsonRpc()

    @Test
    fun requestEncodePreservesIdUnicodeAndNestedParams() {
        val encoded = codec.encode(
            JsonRpcRequest(
                id = JsonRpcId.StringId("要求-42"),
                method = "thread/start",
                params = buildJsonObject {
                    put("prompt", "こんにちは 🌍")
                    put("workspace", buildJsonObject { put("cwd", "/workspace/日本語") })
                },
            )
        )
        val decoded = codec.decode(encoded).getOrThrow() as JsonRpcMessage.Request

        assertFalse(codec.json.parseToJsonElement(encoded).jsonObject.containsKey("jsonrpc"))
        assertEquals(JsonRpcId.StringId("要求-42"), decoded.value.id)
        assertEquals("こんにちは 🌍", decoded.value.params!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        assertEquals(
            "/workspace/日本語",
            decoded.value.params!!.jsonObject["workspace"]!!.jsonObject["cwd"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun notificationEncodeOmitsJsonRpcField() {
        val encoded = codec.encode(
            JsonRpcNotification(
                method = "turn/started",
                params = buildJsonObject { put("turnId", "turn-1") },
            )
        )

        assertFalse(codec.json.parseToJsonElement(encoded).jsonObject.containsKey("jsonrpc"))
    }

    @Test
    fun requestWithoutJsonRpcDecodes() {
        val message = codec.decode(
            """{"method":"thread/start","id":10,"params":{"cwd":"/workspace"}}"""
        ).getOrThrow() as JsonRpcMessage.Request

        assertEquals(JsonRpcId.NumberId(10), message.value.id)
        assertEquals("thread/start", message.value.method)
    }

    @Test
    fun responseDecodePreservesNumericIdAndNestedResult() {
        val message = codec.decode(
            """{"id":17,"result":{"thread":{"id":"abc"}}}"""
        ).getOrThrow() as JsonRpcMessage.Response

        assertEquals(JsonRpcId.NumberId(17), message.value.id)
        assertEquals("abc", message.value.result.jsonObject["thread"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorResponseDecodeIncludesStructuredData() {
        val message = codec.decode(
            """{"id":"x","error":{"code":-32602,"message":"Bad params","data":{"field":"cwd"}}}"""
        ).getOrThrow() as JsonRpcMessage.ErrorResponse

        assertEquals(JsonRpcId.StringId("x"), message.value.id)
        assertEquals(-32602L, message.value.error.code)
        assertEquals("cwd", message.value.error.data!!.jsonObject["field"]!!.jsonPrimitive.content)
    }

    @Test
    fun notificationDecodeAndEventMapping() {
        val message = codec.decode(
            """{"method":"item/updated","params":{"text":"進行中"}}"""
        ).getOrThrow()
        val event = message.toCodexAppServerEventOrNull() as CodexAppServerEvent.UnknownNotification

        assertEquals("item/updated", event.method)
        assertEquals("進行中", event.params!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun optionalJsonRpcTwoPointZeroIsAccepted() {
        val request = codec.decode(
            """{"jsonrpc":"2.0","method":"thread/start","id":"compat"}"""
        )
        val response = codec.decode(
            """{"jsonrpc":"2.0","id":"compat","result":null}"""
        )

        assertTrue(request.getOrThrow() is JsonRpcMessage.Request)
        assertTrue(response.getOrThrow() is JsonRpcMessage.Response)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val message = codec.decode(
            """{"id":"r1","result":null,"future_field":{"enabled":true}}"""
        ).getOrThrow() as JsonRpcMessage.Response

        assertEquals(JsonRpcId.StringId("r1"), message.value.id)
        assertEquals(JsonNull, message.value.result)
    }

    @Test
    fun malformedJsonFailsWithoutThrowingFromDecode() {
        val result = codec.decode("""{"id":1,"result": """)

        assertTrue(result.isFailure)
    }

    @Test
    fun resultAndErrorAreDistinguishedAndMutuallyExclusive() {
        assertTrue(codec.decode("""{"id":1,"result":{}}""").getOrThrow() is JsonRpcMessage.Response)
        assertTrue(codec.decode("""{"id":1,"error":{"code":1,"message":"no"}}""").getOrThrow() is JsonRpcMessage.ErrorResponse)
        assertTrue(
            codec.decode(
                """{"id":1,"result":{},"error":{"code":1,"message":"no"}}"""
            ).isFailure
        )
    }

    @Test
    fun serverOriginatedRequestHasAFirstClassEvent() {
        val message = codec.decode(
            """{"id":"approval-1","method":"command/approve","params":{"command":"git status"}}"""
        ).getOrThrow()
        val event = message.toCodexAppServerEventOrNull() as CodexAppServerEvent.ServerRequest

        assertEquals(JsonRpcId.StringId("approval-1"), event.id)
        assertEquals("command/approve", event.method)
    }
}
