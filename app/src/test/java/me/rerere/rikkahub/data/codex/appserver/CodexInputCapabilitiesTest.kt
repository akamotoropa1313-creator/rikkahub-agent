package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexInputCapabilitiesTest {
    private fun model(id: String, wire: String, default: Boolean, modalities: List<String>?) = CodexAppServerModel(
        id, wire, wire, "", false, emptyList(), "medium", false, default, JsonObject(emptyMap()), modalities,
    )

    @Test fun `capabilities preserve future modality order and resolve by wire model name`() {
        val models = listOf(model("selected", "wire-a", false, listOf("text", "future", "image", "audio")))
        assertEquals(CodexInputCapability.Supported, codexInputCapability("wire-a", models, "image"))
        assertEquals(CodexInputCapability.Supported, codexInputCapability("wire-a", models, "audio"))
        assertEquals(CodexInputCapability.Unsupported, codexInputCapability("wire-a", models, "video"))
        assertEquals(CodexInputCapability.Unknown, codexInputCapability("selected", models, "image"))
        assertEquals(CodexInputCapability.Unsupported, codexInputCapability("selected", models, "audio"))
        assertEquals(listOf("text", "future", "image", "audio"), models.single().inputModalities)
    }

    @Test fun `unreported image stays unknown while unreported audio fails closed`() {
        val legacy = listOf(model("id", "default-wire", true, null))
        assertEquals(CodexInputCapability.Unknown, codexInputCapability(null, null, "image"))
        assertEquals(CodexInputCapability.Unsupported, codexInputCapability(null, null, "audio"))
        assertEquals(CodexInputCapability.Unknown, codexInputCapability(null, legacy, "image"))
        assertEquals(CodexInputCapability.Unsupported, codexInputCapability(null, legacy, "audio"))
    }

    @Test fun `missing saved model does not fall back but explicit default is used when selection is null`() {
        val models = listOf(model("id", "default-wire", true, listOf("text", "audio")))
        assertEquals(CodexInputCapability.Unknown, codexInputCapability("gone", models, "image"))
        assertEquals(CodexInputCapability.Unsupported, codexInputCapability("gone", models, "audio"))
        assertEquals(CodexInputCapability.Supported, codexInputCapability(null, models, "audio"))
    }

    @Test fun `translated external route uses rikkahub model instead of chatgpt default`() {
        val external = Model(
            modelId = "external-text-only",
            displayName = "External Text Only",
            inputModalities = listOf(Modality.TEXT),
        )
        val provider = ProviderSetting.Google(models = listOf(external))
        val chatGptCatalog = listOf(
            model("chatgpt", "chatgpt-default", true, listOf("text", "image", "audio")),
        )
        val route = CodexHarnessModelRoute.BridgeRequired(external, provider)

        assertEquals(
            CodexInputCapability.Unsupported,
            codexHarnessInputCapability(route, chatGptCatalog, "image"),
        )
        assertEquals(
            CodexInputCapability.Unsupported,
            codexHarnessInputCapability(route, chatGptCatalog, "audio"),
        )
    }

    @Test fun `raw responses external route uses image metadata and leaves audio unknown`() {
        val external = Model(
            modelId = "external-vision",
            displayName = "External Vision",
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        )
        val provider = ProviderSetting.OpenAI(
            models = listOf(external),
            useResponseApi = true,
        )
        val route = CodexHarnessModelRoute.DirectResponses(external, provider)

        assertEquals(
            CodexInputCapability.Supported,
            codexHarnessInputCapability(route, null, "image"),
        )
        assertEquals(
            CodexInputCapability.Unknown,
            codexHarnessInputCapability(route, null, "audio"),
        )
    }

    @Test fun `missing external model fails closed`() {
        val route = CodexHarnessModelRoute.MissingProviderModel("gone")
        assertEquals(
            CodexInputCapability.Unsupported,
            codexHarnessInputCapability(route, null, "image"),
        )
        assertEquals(
            CodexInputCapability.Unsupported,
            codexHarnessInputCapability(route, null, "audio"),
        )
    }
}
