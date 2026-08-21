package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexHarnessPickerCatalogTest {
    @Test
    fun `catalog keeps account models separate from persisted providers`() {
        val account = accountModel("gpt-account", hidden = false)
        val hidden = accountModel("hidden", hidden = true)
        val chat = Model(modelId = "provider-chat", displayName = "Provider Chat")
        val image = Model(modelId = "provider-image", displayName = "Provider Image", type = ModelType.IMAGE)
        val enabled = ProviderSetting.OpenAI(name = "Enabled", models = listOf(chat, image))
        val disabled = ProviderSetting.OpenAI(name = "Disabled", enabled = false, models = listOf(Model(modelId = "other")))

        val result = CodexHarnessPickerCatalogBuilder.build(
            providers = listOf(enabled, disabled),
            chatGptModels = listOf(account, hidden),
        )

        assertEquals(listOf("gpt-account"), result.chatGptModels.map { it.model })
        assertEquals(listOf("Enabled"), result.providerGroups.map { it.provider.name })
        assertEquals(listOf("provider-chat"), result.providerGroups.single().models.map { it.modelId })
    }

    @Test
    fun `search covers display and wire identifiers`() {
        val account = accountModel("gpt-wire", displayName = "GPT Friendly")
        val providerModel = Model(modelId = "vendor/wire", displayName = "Vendor Friendly")

        assertTrue(CodexHarnessPickerCatalogBuilder.matchesSearch(account, "friendly"))
        assertTrue(CodexHarnessPickerCatalogBuilder.matchesSearch(account, "gpt-wire"))
        assertTrue(CodexHarnessPickerCatalogBuilder.matchesSearch(providerModel, "vendor/wire"))
    }

    private fun accountModel(
        model: String,
        displayName: String = model,
        hidden: Boolean,
    ) = CodexAppServerModel(
        id = model,
        model = model,
        displayName = displayName,
        description = "description",
        hidden = hidden,
        supportedReasoningEfforts = listOf(CodexReasoningEffortOption("high", "high")),
        defaultReasoningEffort = "high",
        supportsPersonality = true,
        isDefault = false,
        raw = JsonObject(emptyMap()),
    )

    private fun accountModel(
        model: String,
        displayName: String = model,
    ) = accountModel(model, displayName, false)
}
