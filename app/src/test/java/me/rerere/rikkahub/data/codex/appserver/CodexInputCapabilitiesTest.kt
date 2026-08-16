package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexInputCapabilitiesTest {
    private fun model(id: String, wire: String, default: Boolean, modalities: List<String>?) = CodexAppServerModel(
        id, wire, wire, "", false, emptyList(), "medium", false, default, JsonObject(emptyMap()), modalities,
    )

    @Test fun `capabilities preserve unknown and resolve model wire name`() {
        val models = listOf(model("selected", "wire-a", false, listOf("text", "future", "image")))
        assertEquals(CodexInputCapability.Supported, codexInputCapability("wire-a", models, "image"))
        assertEquals(CodexInputCapability.Unsupported, codexInputCapability("wire-a", models, "audio"))
        assertEquals(CodexInputCapability.Unknown, codexInputCapability("selected", models, "image"))
        assertEquals(listOf("text", "future", "image"), models.single().inputModalities)
    }

    @Test fun `missing saved model does not fall back but null selection uses explicit default`() {
        val models = listOf(model("id", "default-wire", true, listOf("text", "audio")))
        assertEquals(CodexInputCapability.Unknown, codexInputCapability("gone", models, "audio"))
        assertEquals(CodexInputCapability.Supported, codexInputCapability(null, models, "audio"))
        assertEquals(CodexInputCapability.Unknown, codexInputCapability(null, null, "audio"))
    }
}
