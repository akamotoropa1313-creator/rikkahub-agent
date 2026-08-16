package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerModel
import me.rerere.rikkahub.data.codex.appserver.CodexModelCatalogKnowledge
import me.rerere.rikkahub.data.codex.appserver.CodexReasoningEffortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexStage16ProtocolTypesTest {
    @Test
    fun `turn personality is omitted until selected model support is confirmed`() {
        CodexModelCatalogKnowledge.clearForTest()
        val requested = CodexAppServerPersonality.valueOf("FRIENDLY")
        val unknown = CodexAppServerTurnStartParams(model = "model-a", personality = requested)
        assertNull(unknown.personality)

        CodexModelCatalogKnowledge.replace(listOf(model("model-a", supportsPersonality = true)))
        val confirmed = CodexAppServerTurnStartParams(model = "model-a", personality = requested)
        assertEquals(me.rerere.rikkahub.data.codex.appserver.CodexAppServerPersonality.FRIENDLY, confirmed.personality)
    }

    @Test
    fun `unsupported model never receives personality and new thread omits unconfirmed override`() {
        CodexModelCatalogKnowledge.replace(listOf(model("model-a", supportsPersonality = false)))
        val requested = CodexAppServerPersonality.valueOf("PRAGMATIC")
        assertNull(CodexAppServerTurnStartParams(model = "model-a", personality = requested).personality)
        assertNull(CodexAppServerThreadStartParams(model = "model-a", personality = requested).personality)
    }

    private fun model(model: String, supportsPersonality: Boolean) = CodexAppServerModel(
        id = "id-$model",
        model = model,
        displayName = model,
        description = "description",
        hidden = false,
        supportedReasoningEfforts = listOf(CodexReasoningEffortOption("high", "High")),
        defaultReasoningEffort = "high",
        supportsPersonality = supportsPersonality,
        isDefault = false,
        raw = JsonObject(emptyMap()),
    )
}
