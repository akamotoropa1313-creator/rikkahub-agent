package me.rerere.rikkahub.data.codex.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class CodexHarnessModelTargetTest {
    @Test
    fun `legacy null codex model keeps app server default semantics`() {
        val target = legacyCodexModelTarget(null)
        val account = assertIs<CodexHarnessModelTarget.ChatGptAccount>(target)
        assertEquals(null, account.model)
    }

    @Test
    fun `legacy explicit codex model migrates losslessly`() {
        val target = legacyCodexModelTarget("gpt-5.6-sol")
        val account = assertIs<CodexHarnessModelTarget.ChatGptAccount>(target)
        assertEquals("gpt-5.6-sol", account.model)
    }

    @Test
    fun `rikkahub target retains existing model uuid`() {
        val id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val target = CodexHarnessModelTarget.RikkaHubProvider(id)
        assertEquals(id, target.modelId)
    }
}
