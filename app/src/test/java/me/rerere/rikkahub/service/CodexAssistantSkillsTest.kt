package me.rerere.rikkahub.service

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import me.rerere.rikkahub.data.codex.appserver.CodexSkillsListEntry
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAssistantSkillsTest {
    @Test
    fun `assistant selection becomes isolated Codex thread skill config`() {
        val profile = buildCodexAssistantSkillProfile(
            enabledSkillNames = setOf("agent-core"),
            installedSkills = listOf(
                skill("map", "interactive-map"),
                skill("agent-core", "agent-core", autoLoad = true),
            ),
        )

        val config = profile.threadConfig().orEmpty()
        val entries = (config.getValue("skills").jsonObject.getValue("config") as JsonArray)
            .map { it.jsonObject }

        assertEquals(
            listOf("/skills/agent-core/SKILL.md", "/skills/interactive-map/SKILL.md"),
            entries.map { it.getValue("path").jsonPrimitive.content },
        )
        assertEquals(
            listOf(true, false),
            entries.map { it.getValue("enabled").jsonPrimitive.boolean },
        )
    }

    @Test
    fun `enabled auto load skills join assistant and conversation instructions`() {
        val installed = listOf(
            skill("lazy", "lazy"),
            skill("agent-core", "agent-core", autoLoad = true),
            skill("disabled-auto", "disabled-auto", autoLoad = true),
        )
        val profile = buildCodexAssistantSkillProfile(
            enabledSkillNames = setOf("lazy", "agent-core"),
            installedSkills = installed,
            readAutoLoadBody = { metadata -> " body for ${metadata.name} " },
        )

        assertEquals(listOf("agent-core"), profile.autoLoaded.map { it.name })
        assertEquals(
            "base\n\n--- Conversation instructions ---\nchat\n\n" +
                "--- RikkaHub assistant skill: agent-core ---\nbody for agent-core",
            buildCodexDeveloperInstructions("base", "chat", true, profile),
        )
        val changed = buildCodexAssistantSkillProfile(
            enabledSkillNames = setOf("lazy", "agent-core"),
            installedSkills = installed,
            readAutoLoadBody = { "updated" },
        )
        assertNotEquals(profile, changed)
    }

    @Test
    fun `catalog reflects assistant state only for RikkaHub root`() {
        val profile = buildCodexAssistantSkillProfile(
            enabledSkillNames = setOf("agent-core"),
            installedSkills = listOf(
                skill("agent-core", "agent-core"),
                skill("map", "interactive-map"),
            ),
        )
        val agent = codexSkill("agent-core", "/skills/agent-core/SKILL.md", enabled = false)
        val map = codexSkill("map", "/skills/interactive-map/SKILL.md", enabled = true)
        val system = codexSkill("imagegen", "/root/.codex/skills/imagegen/SKILL.md", enabled = false)
        val group = CodexSkillsListEntry("/workspace", listOf(agent, map, system), emptyList(), JsonObject(emptyMap()))

        val projected = profile.applyToCatalog(listOf(group)).single().skills

        assertTrue(projected[0].enabled)
        assertFalse(projected[1].enabled)
        assertFalse("non-RikkaHub Codex config must remain untouched", projected[2].enabled)
        assertTrue(isRikkaHubCodexSkill(agent))
        assertFalse(isRikkaHubCodexSkill(system))
        assertFalse(isRikkaHubCodexSkill(agent.copy(path = "/skills/agent-core/nested/SKILL.md")))
    }

    private fun skill(
        name: String,
        directory: String,
        autoLoad: Boolean = false,
    ) = SkillMetadata(
        name = name,
        description = "$name description",
        autoLoad = autoLoad,
        skillDir = File("/host/skills/$directory"),
    )

    private fun codexSkill(name: String, path: String, enabled: Boolean) = CodexSkillMetadata(
        name = name,
        description = "$name description",
        shortDescription = null,
        path = path,
        scope = "user",
        enabled = enabled,
        interfaceMetadata = null,
        dependencies = null,
        raw = JsonObject(mapOf("path" to JsonPrimitive(path))),
    )
}
