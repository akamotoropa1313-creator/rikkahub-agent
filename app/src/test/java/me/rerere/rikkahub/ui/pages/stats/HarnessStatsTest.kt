package me.rerere.rikkahub.ui.pages.stats

import me.rerere.rikkahub.data.db.dao.HarnessTokenStats
import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessStatsTest {
    @Test
    fun `configured zero usage harness stays visible and unknown stored harness is preserved`() {
        val merged = mergeHarnessUsage(
            persisted = listOf(
                HarnessTokenStats(
                    harnessId = "future-harness",
                    runCount = 2,
                    promptTokens = 40,
                    completionTokens = 5,
                )
            ),
            configuredHarnessIds = listOf("codex"),
        )

        assertEquals(listOf("codex", "future-harness"), merged.map { it.harnessId })
        assertEquals(0, merged.first().runCount)
        assertEquals(2, merged.last().runCount)
        assertEquals(40L, merged.last().promptTokens)
    }
}
