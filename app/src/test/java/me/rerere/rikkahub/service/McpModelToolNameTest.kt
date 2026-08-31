package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpModelToolNameTest {
    @Test fun `valid legacy name stays unchanged`() {
        assertEquals("mcp__12345678_server__read_file", mcpModelToolName("12345678-abcd", "server", "read_file"))
    }

    @Test fun `unusual and long names become unique valid names`() {
        val first = mcpModelToolName("12345678-abcd", "日本語 server", "tool/" + "x".repeat(80))
        val second = mcpModelToolName("12345678-abcd", "日本語 server", "tool/" + "y".repeat(80))
        assertTrue(first.matches(Regex("^[A-Za-z0-9_-]{1,64}$")))
        assertTrue(second.matches(Regex("^[A-Za-z0-9_-]{1,64}$")))
        assertNotEquals(first, second)
    }
}
