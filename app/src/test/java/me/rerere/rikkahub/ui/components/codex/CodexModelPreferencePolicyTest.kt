package me.rerere.rikkahub.ui.components.codex

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerModel
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessModelTarget
import me.rerere.rikkahub.data.codex.appserver.CodexReasoningEffortOption
import me.rerere.rikkahub.data.codex.appserver.CodexModelServiceTier
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

class CodexModelPreferencePolicyTest {
    @Test
    fun `model lookup uses wire model not catalog id`() {
        val models = listOf(model(id = "catalog-id", model = "wire-model"))
        assertEquals("wire-model", selectedCodexModel("wire-model", models)?.model)
        assertEquals(null, selectedCodexModel("catalog-id", models))
    }

    @Test
    fun `service tier catalog model uses explicit default only when saved model is null`() {
        val default = model(id = "default-id", model = "default-wire", isDefault = true)
        val other = model(id = "other-id", model = "other-wire")
        val models = listOf(other, default)
        assertEquals("default-wire", serviceTierCatalogModel(null, models)?.model)
        assertEquals("other-wire", serviceTierCatalogModel("other-wire", models)?.model)
        assertEquals(null, serviceTierCatalogModel("missing", models))
        assertEquals(null, serviceTierCatalogModel("default-id", models))
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
    fun `effort picker resolves explicit model and server default without leaking into provider route`() {
        val default = model(model = "default-wire", isDefault = true)
        val explicit = model(model = "explicit-wire")
        val models = listOf(explicit, default)

        assertEquals(
            "explicit-wire",
            reasoningEffortCatalogModel(CodexHarnessModelTarget.ChatGptAccount("explicit-wire"), models)?.model,
        )
        assertEquals(
            "default-wire",
            reasoningEffortCatalogModel(CodexHarnessModelTarget.ChatGptAccount(null), models)?.model,
        )
        assertEquals(
            null,
            reasoningEffortCatalogModel(CodexHarnessModelTarget.RikkaHubProvider(Uuid.random()), models),
        )
    }

    @Test
    fun `effort labels localize known values and preserve future strings`() {
        assertEquals("サーバー設定", codexReasoningEffortLabel(null))
        assertEquals("中", codexReasoningEffortLabel("medium"))
        assertEquals("超高", codexReasoningEffortLabel("xhigh"))
        assertEquals("focused-v2", codexReasoningEffortLabel("focused-v2"))
        assertEquals(
            "中 (medium) · モデル既定",
            effortOptionTitle(CodexReasoningEffortOption("medium", "description"), "medium"),
        )
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
        assertEquals("Codex · サーバー既定", codexComposerLabel(Assistant(codexAppServerEnabled = true), emptyList()))
        assertEquals(
            "Codex · Future · focused-v2",
            codexComposerLabel(
                Assistant(codexAppServerEnabled = true, codexModel = "wire", codexReasoningEffort = "focused-v2"),
                listOf(model(model = "wire", displayName = "Future")),
            ),
        )
        assertEquals("", codexComposerLabel(Assistant(codexAppServerEnabled = false), emptyList()))
        assertEquals(
            "Codex · Future · 中",
            codexComposerLabel(
                Assistant(codexAppServerEnabled = true, codexModel = "wire", codexReasoningEffort = "medium"),
                listOf(model(model = "wire", displayName = "Future")),
            ),
        )
    }

    @Test
    fun `modern tiers preserve server order and take precedence over legacy`() {
        val target = model(
            tiers = listOf(CodexModelServiceTier("priority", "Fast", "Faster"), CodexModelServiceTier("future", "Future", "New")),
            legacy = listOf("fast"),
        )
        assertEquals(listOf("priority", "future"), codexServiceTierOptions(target).map { it.id })
        assertEquals("Fast", codexServiceTierOptions(target).first().name)
    }

    @Test
    fun `legacy tiers remain exact and unsupported model switch uses explicit default`() {
        val target = model(legacy = listOf("fast", "future"))
        assertEquals(listOf("fast", "future"), codexServiceTierOptions(target).map { it.id })
        assertEquals("fast", serviceTierForCodexModelSelection("fast", target))
        assertEquals("default", serviceTierForCodexModelSelection("priority", target))
        assertEquals(null, serviceTierForCodexModelSelection(null, target))
        assertEquals("default", serviceTierForCodexModelSelection("default", target))
    }

    @Test
    fun `unreported tier metadata preserves saved exact string but explicit empty rejects it`() {
        assertEquals("priority", serviceTierForCodexModelSelection("priority", model(tiers = null, legacy = null)))
        assertEquals("default", serviceTierForCodexModelSelection("priority", model(tiers = emptyList(), legacy = null)))
        assertEquals("fast", serviceTierForCodexModelSelection("fast", model(tiers = emptyList(), legacy = listOf("fast"))))
    }

    @Test
    fun `composer distinguishes omitted default catalog and unknown tier`() {
        val target = model(model = "wire", displayName = "Future", tiers = listOf(CodexModelServiceTier("priority", "Fast", "Faster")))
        assertEquals("Codex · Future · Fast", codexComposerLabel(Assistant(codexAppServerEnabled = true, codexModel = "wire", codexServiceTier = "priority"), listOf(target)))
        assertEquals("Codex · Future · 既定", codexComposerLabel(Assistant(codexAppServerEnabled = true, codexModel = "wire", codexServiceTier = "default"), listOf(target)))
        assertEquals("Codex · Future · unknown", codexComposerLabel(Assistant(codexAppServerEnabled = true, codexModel = "wire", codexServiceTier = "unknown"), listOf(target)))
    }

    @Test
    fun `composer resolves tier name from explicit default catalog without changing model label`() {
        val default = model(
            model = "default-wire",
            displayName = "Default Model",
            isDefault = true,
            tiers = listOf(CodexModelServiceTier("priority", "Fast", "Faster")),
        )
        assertEquals(
            "Codex · サーバー既定 · Fast",
            codexComposerLabel(Assistant(codexAppServerEnabled = true, codexServiceTier = "priority"), listOf(default)),
        )
    }

    private fun model(
        id: String = "id",
        model: String = "wire",
        displayName: String = "Model",
        efforts: List<String> = listOf("low", "high"),
        default: String = efforts.lastOrNull() ?: "high",
        isDefault: Boolean = false,
        tiers: List<CodexModelServiceTier>? = null,
        legacy: List<String>? = null,
    ) = CodexAppServerModel(
        id = id,
        model = model,
        displayName = displayName,
        description = "description",
        hidden = false,
        supportedReasoningEfforts = efforts.map { CodexReasoningEffortOption(it, it) },
        defaultReasoningEffort = default,
        supportsPersonality = true,
        isDefault = isDefault,
        raw = JsonObject(emptyMap()),
        serviceTiers = tiers,
        additionalSpeedTiers = legacy,
    )

    private fun expectIllegal(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
