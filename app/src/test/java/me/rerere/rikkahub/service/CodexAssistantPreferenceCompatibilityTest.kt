package me.rerere.rikkahub.service

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.CodexPersonalityPreference
import me.rerere.rikkahub.data.model.CodexReasoningSummaryPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexAssistantPreferenceCompatibilityTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `legacy Assistant JSON decodes with null Codex Stage16 preferences`() {
        val legacy = json.decodeFromString<Assistant>("{}")
        assertNull(legacy.codexModel)
        assertNull(legacy.codexReasoningEffort)
        assertNull(legacy.codexReasoningSummary)
        assertNull(legacy.codexPersonality)
    }

    @Test
    fun `Stage16 preferences survive Settings serialization round trip`() {
        val original = Assistant(
            codexAppServerEnabled = true,
            codexModel = "future-model",
            codexReasoningEffort = "focused-future",
            codexReasoningSummary = CodexReasoningSummaryPreference.DETAILED,
            codexPersonality = CodexPersonalityPreference.PRAGMATIC,
        )
        val decoded = json.decodeFromString<Assistant>(json.encodeToString(original))
        assertEquals("future-model", decoded.codexModel)
        assertEquals("focused-future", decoded.codexReasoningEffort)
        assertEquals(CodexReasoningSummaryPreference.DETAILED, decoded.codexReasoningSummary)
        assertEquals(CodexPersonalityPreference.PRAGMATIC, decoded.codexPersonality)
    }
}
