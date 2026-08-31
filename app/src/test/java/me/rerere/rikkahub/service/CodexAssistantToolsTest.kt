package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CodexAssistantToolsTest {
    private val tool = Tool(
        name = "termux_run_command", description = "Run command",
        parameters = { InputSchema.Obj(JsonObject(emptyMap()), listOf("command")) },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    @Test fun `profile exposes all selected tools and schema`() {
        val second = tool.copy(name = "read_clipboard")
        val profile = CodexAssistantToolProfile.from(listOf(second, tool), "assistant:false")
        assertEquals(listOf("read_clipboard", "termux_run_command"), profile.specs.map { it.name })
        assertEquals("object", profile.specs.last().inputSchema.jsonObject["type"]?.jsonPrimitive?.content)
        assertSame(tool, profile.tool("termux_run_command")); assertNull(profile.tool("disabled"))
    }

    @Test fun `execution identity refreshes closures without changing thread fingerprint`() {
        val first = CodexAssistantToolProfile.from(listOf(tool), "assistant-a:false")
        val second = CodexAssistantToolProfile.from(listOf(tool), "assistant-b:false")
        assertEquals(first.fingerprint, second.fingerprint); assertNotEquals(first, second)
    }
}
