package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexHarnessResponsesSseEncoderTest {
    @Test
    fun `text stream produces canonical responses lifecycle`() {
        val encoder = CodexHarnessResponsesSseEncoder(
            model = "test-model",
            responseId = "resp_test",
        )

        val events = buildList {
            addAll(encoder.accept(StreamChunk.TextStart("msg_1")))
            addAll(encoder.accept(StreamChunk.TextDelta("msg_1", "hel")))
            addAll(encoder.accept(StreamChunk.TextDelta("msg_1", "lo")))
            addAll(encoder.accept(StreamChunk.TextEnd("msg_1")))
            addAll(encoder.accept(StreamChunk.Usage(TokenUsage(10, 2, 3, 12))))
            addAll(encoder.accept(StreamChunk.Finish(finishReason = "stop", model = "test-model")))
        }

        assertEquals("response.created", events[0].type)
        assertEquals("response.in_progress", events[1].type)
        assertTrue(events.any { it.type == "response.output_text.delta" })
        val done = events.single { it.type == "response.output_text.done" }
        assertEquals("hello", done.payload["text"]?.jsonPrimitive?.content)

        val completed = events.last()
        assertEquals("response.completed", completed.type)
        val response = completed.payload["response"]!!.jsonObject
        assertEquals("resp_test", response["id"]?.jsonPrimitive?.content)
        assertEquals("completed", response["status"]?.jsonPrimitive?.content)
        assertEquals("hello", response["output"]!!.jsonArray.single().jsonObject["content"]!!
            .jsonArray.single().jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals(10, response["usage"]!!.jsonObject["input_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals(3, response["usage"]!!.jsonObject["input_tokens_details"]!!.jsonObject
            ["cached_tokens"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `tool stream preserves call id name and accumulated arguments`() {
        val encoder = CodexHarnessResponsesSseEncoder(
            model = "test-model",
            responseId = "resp_tool",
        )

        val events = buildList {
            addAll(encoder.accept(StreamChunk.ToolCallStart("call_1", "shell")))
            addAll(encoder.accept(StreamChunk.ToolCallDelta("call_1", inputDelta = "{\"cmd\":")))
            addAll(encoder.accept(StreamChunk.ToolCallDelta("call_1", inputDelta = "\"ls\"}")))
            addAll(encoder.accept(StreamChunk.ToolCallEnd("call_1")))
            addAll(encoder.accept(StreamChunk.Finish(finishReason = "tool_calls")))
        }

        val argumentsDone = events.single { it.type == "response.function_call_arguments.done" }
        assertEquals("{\"cmd\":\"ls\"}", argumentsDone.payload["arguments"]?.jsonPrimitive?.content)

        val response = events.last().payload["response"]!!.jsonObject
        val call = response["output"]!!.jsonArray.single().jsonObject
        assertEquals("function_call", call["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", call["call_id"]?.jsonPrimitive?.content)
        assertEquals("shell", call["name"]?.jsonPrimitive?.content)
        assertEquals("{\"cmd\":\"ls\"}", call["arguments"]?.jsonPrimitive?.content)
    }
}
