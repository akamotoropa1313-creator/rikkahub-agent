package me.rerere.rikkahub.ui.components.codex

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexUiLocaleTest {
    @Test
    fun `Japanese locale translates RikkaHub-owned Codex labels`() {
        assertEquals("Codex コントロールセンター", localizeCodexUiText("Codex Control Center", true))
        assertEquals("安全性と権限", localizeCodexUiText("Safety & permissions", true))
        assertEquals("必要時に確認", localizeCodexUiText("On request", true))
        assertEquals(
            "App Server が管理するスレッドを使用します。通常のプロバイダーモデルは使用されません",
            localizeCodexUiText("Uses an App Server-managed thread; the normal provider model is not used", true),
        )
    }

    @Test
    fun `English locale preserves existing labels`() {
        assertEquals("Codex Control Center", localizeCodexUiText("Codex Control Center", false))
        assertEquals("On request", localizeCodexUiText("On request", false))
    }

    @Test
    fun `unknown server supplied text is not translated`() {
        assertEquals("future-server-value", localizeCodexUiText("future-server-value", true))
    }
}
