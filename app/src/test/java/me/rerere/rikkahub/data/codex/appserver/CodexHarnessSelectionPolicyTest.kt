package me.rerere.rikkahub.data.codex.appserver

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.CodexPersonalityPreference
import me.rerere.rikkahub.data.model.CodexReasoningSummaryPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class CodexHarnessSelectionPolicyTest {
    @Test
    fun `switching to provider clears only model dependent Codex settings`() {
        val modelId = Uuid.random()
        val assistant = Assistant(
            codexModel = "account-model",
            codexReasoningEffort = "max",
            codexReasoningSummary = CodexReasoningSummaryPreference.DETAILED,
            codexPersonality = CodexPersonalityPreference.FRIENDLY,
            codexServiceTier = "fast",
            codexSandboxMode = "workspace-write",
            codexApprovalPolicy = "on-request",
            systemPrompt = "keep me",
        )

        val updated = CodexHarnessSelectionPolicy.selectRikkaHubProviderModel(assistant, modelId)

        assertEquals(CodexHarnessModelTarget.RikkaHubProvider(modelId), updated.codexHarnessModelTarget)
        assertNull(updated.codexModel)
        assertNull(updated.codexReasoningEffort)
        assertNull(updated.codexReasoningSummary)
        assertNull(updated.codexPersonality)
        assertNull(updated.codexServiceTier)
        assertEquals("workspace-write", updated.codexSandboxMode)
        assertEquals("on-request", updated.codexApprovalPolicy)
        assertEquals("keep me", updated.systemPrompt)
    }

    @Test
    fun `server default remains an explicit ChatGPT source`() {
        val updated = CodexHarnessSelectionPolicy.selectChatGptServerDefault(
            Assistant(codexModel = "old", codexServiceTier = "fast"),
        )

        assertEquals(CodexHarnessModelTarget.ChatGptAccount(null), updated.codexHarnessModelTarget)
        assertNull(updated.codexModel)
        assertNull(updated.codexServiceTier)
    }
}
