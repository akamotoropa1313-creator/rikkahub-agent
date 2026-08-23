package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexHarnessModelPresentationTest {
    @Test
    fun `external target resolves provider model and capability metadata`() {
        val model = Model(modelId = "vendor/model", displayName = "Model Display")
        val provider = ProviderSetting.Google(name = "Google Custom", models = listOf(model))

        val presentation = CodexHarnessModelPresentationResolver.resolve(
            target = CodexHarnessModelTarget.RikkaHubProvider(model.id),
            providers = listOf(provider),
            chatGptModels = emptyList(),
        )

        assertEquals("Google Custom", presentation.providerName)
        assertEquals("Model Display", presentation.modelName)
        assertEquals("Google Custom · Model Display", presentation.compactLabel)
        assertSame(model, presentation.model)
        assertTrue(presentation.available)
    }

    @Test
    fun `missing external target is explicit instead of falling back to chatgpt`() {
        val target = CodexHarnessModelTarget.RikkaHubProvider(Model().id)
        val presentation = CodexHarnessModelPresentationResolver.resolve(
            target = target,
            providers = emptyList(),
            chatGptModels = emptyList(),
        )

        assertEquals("RikkaHub · 利用できないモデル", presentation.compactLabel)
        assertFalse(presentation.available)
    }

    @Test
    fun `chatgpt account target keeps server default and catalog display names`() {
        val catalog = listOf(
            CodexAppServerModel(
                id = "catalog-id",
                model = "wire-model",
                displayName = "Catalog Display",
                description = "",
                hidden = false,
                supportedReasoningEfforts = emptyList(),
                defaultReasoningEffort = "medium",
                supportsPersonality = false,
                isDefault = true,
                raw = JsonObject(emptyMap()),
            ),
        )

        val defaultPresentation = CodexHarnessModelPresentationResolver.resolve(
            target = CodexHarnessModelTarget.ChatGptAccount(null),
            providers = emptyList(),
            chatGptModels = catalog,
        )
        assertEquals("ChatGPT · サーバー既定", defaultPresentation.compactLabel)

        val selectedPresentation = CodexHarnessModelPresentationResolver.resolve(
            target = CodexHarnessModelTarget.ChatGptAccount("wire-model"),
            providers = emptyList(),
            chatGptModels = catalog,
        )
        assertEquals("ChatGPT · Catalog Display", selectedPresentation.compactLabel)
        assertTrue(selectedPresentation.available)
    }
}
