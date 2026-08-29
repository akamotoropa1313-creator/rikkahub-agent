package me.rerere.rikkahub.ui.components.codex

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerAccount
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerAccountSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerPlanType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAccountCardTest {
    private val empty = buildJsonObject {}
    @Test fun `required and no-auth states present correct action`() {
        val required = codexAccountPresentation(snapshot(null, true), false, true, false)
        assertTrue(required.showSignIn)
        val neutral = codexAccountPresentation(snapshot(null, false), false, true, false)
        assertFalse(neutral.showSignIn); assertTrue("OpenAIへのサインインは不要です" in neutral.lines)
    }
    @Test fun `chatgpt null email and unknown account are safe`() {
        val chat = CodexAppServerAccount.ChatGpt("person@example.test", CodexAppServerPlanType.Plus, empty)
        val presentation = codexAccountPresentation(snapshot(chat), false, true, false)
        assertTrue(presentation.lines.containsAll(listOf("person@example.test", "Plus")))
        assertTrue(presentation.lines.any { "RikkaHub" in it })
        assertFalse(presentation.showLogout)
        val noEmail = codexAccountPresentation(snapshot(chat.copy(email = null)), false, true, false)
        assertFalse(noEmail.lines.any { it == "null" })
        val unknownRaw = buildJsonObject { put("accessToken", "must-not-render"); put("authUrl", "https://secret") }
        val unknown = codexAccountPresentation(snapshot(CodexAppServerAccount.Unknown("future", unknownRaw)), false, true, false)
        assertTrue("認証済みアカウント" in unknown.lines)
        assertFalse(unknown.lines.joinToString().contains("secret")); assertFalse(unknown.lines.joinToString().contains("accessToken"))
    }
    @Test fun `pending and submitting control actions without exposing identifiers`() {
        val pending = codexAccountPresentation(snapshot(null, true), true, true, false)
        assertTrue(pending.showCancel); assertFalse(pending.showSignIn)
        assertFalse(codexAccountPresentation(snapshot(null, true), false, true, true).actionsEnabled)
        assertFalse(pending.lines.joinToString().contains("login-1"))
    }
    private fun snapshot(account: CodexAppServerAccount?, required: Boolean = false) = CodexAppServerAccountSnapshot(account, required, empty)
}
