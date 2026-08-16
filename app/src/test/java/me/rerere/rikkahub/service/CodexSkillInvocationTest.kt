package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnInput
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import org.junit.Test
import org.junit.Assert.assertEquals

class CodexSkillInvocationTest {
    private val skill = CodexSkillMetadata("review", "Review code", null, "/skills/review", "user", true, null, null, JsonObject(emptyMap()))

    @Test fun promptProducesOneTranscriptAndOrderedWireSignals() {
        val input = buildCodexSkillInvocation(skill, "check this code")
        assertEquals(listOf(CodexAppServerTurnInput.Text("\$review check this code"), CodexAppServerTurnInput.Skill("review", "/skills/review")), input)
        assertEquals("\$review check this code", codexSkillTranscript(skill, "check this code"))
    }

    @Test fun emptyPromptHasNoTrailingSpace() {
        assertEquals(listOf(CodexAppServerTurnInput.Text("\$review"), CodexAppServerTurnInput.Skill("review", "/skills/review")), buildCodexSkillInvocation(skill, ""))
        assertEquals("\$review", codexSkillTranscript(skill, ""))
    }
}
