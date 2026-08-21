package me.rerere.rikkahub.data.codex.appserver

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexHarnessModelTargetTest {
    @Test
    fun `legacy null codex model keeps app server default semantics`() {
        val target = legacyCodexModelTarget(null)
        val account = target as CodexHarnessModelTarget.ChatGptAccount
        assertNull(account.model)
    }

    @Test
    fun `legacy explicit codex model migrates losslessly`() {
        val target = legacyCodexModelTarget("gpt-5.6-sol")
        val account = target as CodexHarnessModelTarget.ChatGptAccount
        assertEquals("gpt-5.6-sol", account.model)
    }

    @Test
    fun `rikkahub target retains existing model uuid`() {
        val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val target = CodexHarnessModelTarget.RikkaHubProvider(id)
        assertEquals(id, target.modelId)
    }
}
