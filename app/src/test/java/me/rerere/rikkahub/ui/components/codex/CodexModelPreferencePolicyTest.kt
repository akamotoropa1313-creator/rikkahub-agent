package me.rerere.rikkahub.ui.components.codex

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerModel
import me.rerere.rikkahub.data.codex.appserver.CodexReasoningEffortOption
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CodexModelPreferencePolicyTest {
    @Test
    fun `model lookup uses wire model not catalog id`() {
        val models = listOf(model(id = "catalog-id", model = "wire-model"))
        assertEquals("wire-model", selectedCodexModel("wire-model", models)?.model)
        assertEquals(null, selectedCodexModel("catalog-id", models))
    }

    @Test
    fun `server effort order is preserved and compatible saved effort survives model switch`() {
        val target = model(efforts = listOf("focused", "future", "low"), default = "future")
        val updated = applyCodexModelSelection(
            Assistant(codexReasoningEffort = "low"),
            target,
        )
        assertEquals("low", updated.codexReasoningEffort)
        assertEquals(listOf("focused", "future", "low"), target.supportedReasoningEfforts.map { it.reasoningEffort })
    }

    @Test
    fun `unsupported saved effort falls back to concrete catalog default`() {
        val target = model(efforts = listOf("max", "focused"), default = "focused")
        assertEquals("focused", effortForCodexModelSelection("xhigh", target))
    }

    @Test
    fun `empty or inconsistent effort catalogs fail instead of guessing`() {
        expectIllegal { effortForCodexModelSelection(null, model(efforts = emptyList(), default = "high")) }
        expectIllegal { effortForCodexModelSelection(null, model(efforts = listOf("low"), default = "high")) }
    }

    @Test
    fun `saved model missing is only reported after a nonempty catalog is loaded`() {
        assertFalse(savedCodexModelMissing("old", emptyList()))
        assertTrue(savedCodexModelMissing("old", listOf(model(model = "new"))))
        assertFalse(savedCodexModelMissing("new", listOf(model(model = "new"))))
    }

    @Test
    fun `compact label keeps server default and future effort strings`() {
        assertEquals("Codex · server default", codexComposerLabel(Assistant(codexAppServerEnabled = true), emptyList()))
        assertEquals(
            "Codex · Future · focused-v2",
            codexComposerLabel(
                Assistant(codexAppServerEnabled = true, codexModel = "wire", codexReasoningEffort = "focused-v2"),
                listOf(model(model = "wire", displayName = "Future")),
            ),
        )
        assertEquals("", codexComposerLabel(Assistant(codexAppServerEnabled = false), emptyList()))
    }

    private fun model(
        id: String = "id",
        model: String = "wire",
        displayName: String = "Model",
        efforts: List<String> = listOf("low", "high"),
        default: String = efforts.lastOrNull() ?: "high",
    ) = CodexAppServerModel(
        id = id,
        model = model,
        displayName = displayName,
        description = "description",
        hidden = false,
        supportedReasoningEfforts = efforts.map { CodexReasoningEffortOption(it, it) },
        defaultReasoningEffort = default,
        supportsPersonality = true,
        isDefault = false,
        raw = JsonObject(emptyMap()),
    )

    private fun expectIllegal(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
