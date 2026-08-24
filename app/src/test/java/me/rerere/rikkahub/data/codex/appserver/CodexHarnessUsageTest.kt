package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexHarnessUsageTest {
    @Test
    fun `adapter persists last turn rather than cumulative thread totals`() {
        val empty = JsonObject(emptyMap())
        val snapshot = CodexTokenUsageSnapshot(
            turnId = "turn-7",
            tokenUsage = CodexThreadTokenUsage(
                total = CodexTokenUsageBreakdown(1_000, 800, 300, 20, 200, 50, empty),
                last = CodexTokenUsageBreakdown(100, 70, 30, 4, 30, 8, empty),
                modelContextWindow = 200_000,
                raw = empty,
            ),
        )

        val usage = snapshot.toAgentHarnessUsage()

        assertEquals("codex", usage.harnessId)
        assertEquals("turn-7", usage.runId)
        assertEquals(70L, usage.inputTokens)
        assertEquals(30L, usage.outputTokens)
        assertEquals(30L, usage.cachedInputTokens)
        assertEquals(8L, usage.reasoningOutputTokens)
        assertEquals(4L, usage.cacheWriteInputTokens)
    }
}
