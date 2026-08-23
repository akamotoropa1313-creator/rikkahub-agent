package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexHarnessResponsesRequestTranslatorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `translates instructions messages tools and function results`() {
        val request = json.parseToJsonElement(
            """
            {
              "model": "claude-sonnet-4-6",
              "stream": true,
              "instructions": "Use the workspace tools carefully.",
              "input": [
                {"type":"message","role":"user","content":[{"type":"input_text","text":"list files"}]},
                {"type":"function_call","call_id":"call_1","name":"shell","arguments":"{\"cmd\":\"ls\"}"},
                {"type":"function_call_output","call_id":"call_1","output":"README.md"}
              ],
              "tools": [
                {
                  "type":"function",
                  "name":"shell",
                  "description":"Run a shell command",
                  "parameters": {
                    "type":"object",
                    "properties":{"cmd":{"type":"string"}},
                    "required":["cmd"]
                  }
                }
              ]
            }
            """.trimIndent()
        ).let { it as kotlinx.serialization.json.JsonObject }

        val translated = CodexHarnessResponsesRequestTranslator.translate(request)

        assertEquals("claude-sonnet-4-6", translated.requestedModel)
        assertTrue(translated.stream)
        assertEquals(MessageRole.SYSTEM, translated.messages[0].role)
        assertEquals("Use the workspace tools carefully.", (translated.messages[0].parts.single() as UIMessagePart.Text).text)
        assertEquals(MessageRole.USER, translated.messages[1].role)
        assertEquals("list files", (translated.messages[1].parts.single() as UIMessagePart.Text).text)

        val toolPart = translated.messages[2].parts.single() as UIMessagePart.Tool
        assertEquals("call_1", toolPart.toolCallId)
        assertEquals("shell", toolPart.toolName)
        assertEquals("{\"cmd\":\"ls\"}", toolPart.input)
        assertEquals("README.md", (toolPart.output.single() as UIMessagePart.Text).text)

        assertEquals(1, translated.tools.size)
        val tool = translated.tools.single()
        assertEquals("shell", tool.name)
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals(listOf("cmd"), schema.required)
        assertTrue("cmd" in schema.properties)
    }

    @Test
    fun `standalone function output remains visible instead of being dropped`() {
        val request = json.parseToJsonElement(
            """{"input":[{"type":"function_call_output","call_id":"orphan","output":"done"}]}"""
        ) as kotlinx.serialization.json.JsonObject

        val translated = CodexHarnessResponsesRequestTranslator.translate(request)

        assertEquals(MessageRole.TOOL, translated.messages.single().role)
        assertEquals("[orphan] done", (translated.messages.single().parts.single() as UIMessagePart.Text).text)
        assertFalse(translated.stream)
    }
}
