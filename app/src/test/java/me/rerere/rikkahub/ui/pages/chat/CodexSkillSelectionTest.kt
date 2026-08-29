package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexSkillSelectionTest {
    @Test fun `selecting another skill appends instead of replacing the first`() {
        val review = skill("review")
        val imagegen = skill("imagegen")

        val selected = toggleCodexSkillSelection(
            toggleCodexSkillSelection(emptyList(), review),
            imagegen,
        )

        assertEquals(listOf(review, imagegen), selected)
    }

    @Test fun `toggling one selected path removes only that skill`() {
        val review = skill("review")
        val imagegen = skill("imagegen")
        assertEquals(
            listOf(imagegen),
            toggleCodexSkillSelection(listOf(review, imagegen), review.copy(description = "updated")),
        )
    }

    private fun skill(name: String) = CodexSkillMetadata(
        name = name,
        description = name,
        shortDescription = null,
        path = "/skills/$name/SKILL.md",
        scope = "user",
        enabled = true,
        interfaceMetadata = null,
        dependencies = null,
        raw = JsonObject(emptyMap()),
    )
}
