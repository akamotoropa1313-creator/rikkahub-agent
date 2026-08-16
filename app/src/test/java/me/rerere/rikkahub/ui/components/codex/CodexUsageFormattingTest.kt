package me.rerere.rikkahub.ui.components.codex

import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.codex.appserver.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexUsageFormattingTest {
    private val raw = buildJsonObject {}
    private fun part(n: Long) = CodexTokenUsageBreakdown(n, 2, 1, null, 3, 1, raw)
    @Test fun `formatters preserve total versus current context semantics`() {
        val usage = CodexThreadTokenUsage(part(99_000), part(42_381), 200_000, raw)
        assertEquals("42,381 / 200,000 tokens", currentContextText(usage))
        assertEquals("Ctx 42.4k / 200k", compactContextText(usage))
        assertEquals("99,000", formatTokenCount(usage.total.totalTokens))
    }
    @Test fun `duration formats milliseconds seconds and minutes`() {
        assertEquals("842 ms", formatDuration(842)); assertEquals("1.4 s", formatDuration(1_400)); assertEquals("1 min 12 s", formatDuration(72_000))
    }
}
