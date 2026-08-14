package me.rerere.rikkahub.ui.components.codex

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexExecutionCardsTest {
    @Test fun `diff classifier retains exact text`() {
        mapOf("+added" to UnifiedDiffLineKind.Added, "-removed" to UnifiedDiffLineKind.Removed, "@@ -1 +1 @@" to UnifiedDiffLineKind.Hunk, "--- a/file" to UnifiedDiffLineKind.Header, " context\tü " to UnifiedDiffLineKind.Context, "" to UnifiedDiffLineKind.Context).forEach { (text, kind) ->
            assertEquals(text, classifyUnifiedDiffLine(text).text); assertEquals(kind, classifyUnifiedDiffLine(text).kind)
        }
    }
}
