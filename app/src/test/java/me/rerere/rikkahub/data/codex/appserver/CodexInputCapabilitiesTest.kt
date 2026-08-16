package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonObject
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
}
