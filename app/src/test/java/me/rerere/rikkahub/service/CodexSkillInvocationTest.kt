package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnInput
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking

class CodexSkillInvocationTest {
    private val skill = CodexSkillMetadata("review", "Review code", null, "/skills/review", "user", true, null, null, JsonObject(emptyMap()))
    private val imagegen = CodexSkillMetadata("imagegen", "Generate images", null, "/skills/imagegen", "user", true, null, null, JsonObject(emptyMap()))

    @Test fun promptProducesOneTranscriptAndOrderedWireSignals() {
        val input = buildCodexSkillInvocation(skill, "check this code")
        assertEquals(listOf(CodexAppServerTurnInput.Text("\$review check this code"), CodexAppServerTurnInput.Skill("review", "/skills/review")), input)
        assertEquals("\$review check this code", codexSkillTranscript(skill, "check this code"))
    }

    @Test fun emptyPromptHasNoTrailingSpace() {
        assertEquals(listOf(CodexAppServerTurnInput.Text("\$review"), CodexAppServerTurnInput.Skill("review", "/skills/review")), buildCodexSkillInvocation(skill, ""))
        assertEquals("\$review", codexSkillTranscript(skill, ""))
    }

    @Test fun `multiple skills keep selection order and each gets a typed wire item`() {
        val input = buildCodexSkillInvocation(listOf(skill, imagegen), "check and illustrate")
        assertEquals(
            listOf(
                CodexAppServerTurnInput.Text("\$review \$imagegen check and illustrate"),
                CodexAppServerTurnInput.Skill("review", "/skills/review"),
                CodexAppServerTurnInput.Skill("imagegen", "/skills/imagegen"),
            ),
            input,
        )
        val transcript = codexSkillTranscript(listOf(skill, imagegen), "check and illustrate")
        assertEquals("\$review \$imagegen check and illustrate", transcript)
        assertEquals("check and illustrate", codexSkillPromptFromTranscript(listOf(skill, imagegen), transcript))
    }

    @Test fun `duplicate skill paths are injected only once`() {
        val duplicate = skill.copy(name = "review-alias")
        assertEquals(
            listOf(
                CodexAppServerTurnInput.Text("\$review"),
                CodexAppServerTurnInput.Skill("review", "/skills/review"),
            ),
            buildCodexSkillInvocation(listOf(skill, duplicate), ""),
        )
    }

    @Test fun `selection clears only after successful turn start acceptance`() = runBlocking {
        var cleared = false
        runCatching { acceptCodexSkillAfterStart<String>({ error("turn start failed") }) { cleared = true } }
        assertFalse(cleared)
        assertEquals("accepted", acceptCodexSkillAfterStart({ "accepted" }) { cleared = true })
        assertTrue(cleared)
    }

}
