package me.rerere.rikkahub.ui.components.codex

import me.rerere.rikkahub.data.codex.appserver.CodexAppServerStaleBindingReason
import me.rerere.rikkahub.service.CodexConversationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexControlSheetTest {
    @Test fun `reconnect is offered for detached disconnected or ordinary failed runtime`() {
        assertTrue(codexReconnectEligible(CodexConversationUiState.Disconnected, true, false, false))
        assertTrue(codexReconnectEligible(CodexConversationUiState.Failed("transport"), true, false, false))
    }

    @Test fun `reconnect requires binding detached runtime and idle operation lease`() {
        assertFalse(codexReconnectEligible(CodexConversationUiState.Disconnected, false, false, false))
        assertFalse(codexReconnectEligible(CodexConversationUiState.Failed("transport"), true, true, false))
        assertFalse(codexReconnectEligible(CodexConversationUiState.Failed("transport"), true, false, true))
        assertFalse(codexReconnectEligible(CodexConversationUiState.StaleBinding(CodexAppServerStaleBindingReason.ThreadNotLoaded), true, false, false))
        assertFalse(codexReconnectEligible(CodexConversationUiState.WorkspaceMismatch("a", "b"), true, false, false))
    }

    @Test fun `reset is offered only for a recoverable stale binding while idle`() {
        val stale = CodexConversationUiState.StaleBinding(CodexAppServerStaleBindingReason.ThreadNotLoaded)
        assertTrue(codexResetEligible(stale, true, false))
        assertTrue(codexResetEligible(CodexConversationUiState.WorkspaceMismatch("a", "b"), true, false))
        assertFalse(codexResetEligible(stale, false, false))
        assertFalse(codexResetEligible(stale, true, true))
        assertFalse(codexResetEligible(CodexConversationUiState.Failed("transport"), true, false))
    }

    @Test fun `stale binding messages are actionable and hide internal type names`() {
        assertEquals(
            "Codex側に保存済みスレッドがありません。セッションをリセットしてください。",
            codexStaleBindingMessage(CodexAppServerStaleBindingReason.ThreadNotLoaded),
        )
        assertEquals(
            "選択したモデルの実行先が以前のスレッドと異なります。セッションをリセットしてください。",
            codexStaleBindingMessage(CodexAppServerStaleBindingReason.HarnessRouteChanged(null, "rikkahub")),
        )
    }
}
