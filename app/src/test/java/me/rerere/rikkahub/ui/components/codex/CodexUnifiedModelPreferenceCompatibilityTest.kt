package me.rerere.rikkahub.ui.components.codex

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerModel
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessModelTarget
import me.rerere.rikkahub.data.codex.appserver.CodexReasoningEffortOption
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexUnifiedModelPreferenceCompatibilityTest {
    @Test
    fun `legacy Codex picker dual writes typed ChatGPT target`() {
        val model = CodexAppServerModel(
            id = "catalog-id",
            model = "wire-model",
            displayName = "Wire Model",
            description = "",
            hidden = false,
            supportedReasoningEfforts = listOf(CodexReasoningEffortOption("high", "high")),
            defaultReasoningEffort = "high",
            supportsPersonality = true,
            isDefault = false,
            raw = JsonObject(emptyMap()),
        )

        val updated = applyCodexModelSelection(Assistant(), model)

        assertEquals("wire-model", updated.codexModel)
        assertEquals(
            CodexHarnessModelTarget.ChatGptAccount("wire-model"),
            updated.codexHarnessModelTarget,
        )
    }
}
