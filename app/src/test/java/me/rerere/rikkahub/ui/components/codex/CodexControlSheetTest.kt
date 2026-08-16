package me.rerere.rikkahub.ui.components.codex

import me.rerere.rikkahub.service.CodexConversationUiState
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
        assertFalse(codexReconnectEligible(CodexConversationUiState.StaleBinding("stale"), true, false, false))
        assertFalse(codexReconnectEligible(CodexConversationUiState.WorkspaceMismatch("a", "b"), true, false, false))
    }
}
