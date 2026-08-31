package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import me.rerere.rikkahub.data.codex.appserver.RikkaHubCodexAppServerBridge
import me.rerere.rikkahub.data.files.SkillMetadata

/**
 * The RikkaHub skill selection that is applied to one Codex thread.
 *
 * Codex's user-level skill config is process-global and persisted on disk, whereas
 * [me.rerere.rikkahub.data.model.Assistant.enabledSkills] belongs to one assistant. Keep the
 * assistant selection in thread session overrides so two assistants can expose different
 * RikkaHub skills without rewriting each other's global Codex configuration.
 */
internal data class CodexAssistantSkillProfile(
    val skills: List<CodexAssistantSkillState>,
    val autoLoaded: List<CodexAssistantAutoLoadedSkill>,
) {
    val enabledPaths: Set<String> = skills.filterTo(linkedSetOf()) { it.enabled }.mapTo(linkedSetOf()) { it.path }

    fun threadConfig(): Map<String, JsonElement>? {
        if (skills.isEmpty()) return null
        val entries = skills.map { skill ->
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive(skill.path),
                    "enabled" to JsonPrimitive(skill.enabled),
                )
            )
        }
        return mapOf(
            "skills" to JsonObject(
                mapOf("config" to JsonArray(entries))
            )
        )
    }

    fun applyToCatalog(groups: List<me.rerere.rikkahub.data.codex.appserver.CodexSkillsListEntry>) =
        groups.map { group ->
            group.copy(
                skills = group.skills.map { skill ->
                    val state = skills.firstOrNull { it.path == skill.path }
                    if (state == null) skill else skill.copy(enabled = state.enabled)
                }
            )
        }

    companion object {
        val EMPTY = CodexAssistantSkillProfile(emptyList(), emptyList())
    }
}

internal data class CodexAssistantSkillState(
    val name: String,
    val path: String,
    val enabled: Boolean,
)

internal data class CodexAssistantAutoLoadedSkill(
    val name: String,
    val body: String,
)

internal fun buildCodexAssistantSkillProfile(
    enabledSkillNames: Set<String>,
    installedSkills: List<SkillMetadata>,
    readAutoLoadBody: (SkillMetadata) -> String? = { null },
    skillsRoot: String = RikkaHubCodexAppServerBridge.RIKKAHUB_SKILLS_ROOT,
): CodexAssistantSkillProfile {
    val root = skillsRoot.trimEnd('/')
    val states = installedSkills
        .map { skill ->
            CodexAssistantSkillState(
                name = skill.name,
                path = "$root/${skill.skillDir.name}/SKILL.md",
                enabled = skill.name in enabledSkillNames,
            )
        }
        .distinctBy { it.path }
        .sortedBy { it.path }
    val autoLoaded = installedSkills
        .asSequence()
        .filter { it.autoLoad && it.name in enabledSkillNames }
        .distinctBy { it.skillDir.absolutePath }
        .mapNotNull { skill ->
            readAutoLoadBody(skill)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { CodexAssistantAutoLoadedSkill(skill.name, it) }
        }
        .sortedBy { it.name }
        .toList()
    return CodexAssistantSkillProfile(states, autoLoaded)
}

internal fun buildCodexDeveloperInstructions(
    assistantSystemPrompt: String,
    conversationSystemPrompt: String?,
    allowConversationSystemPrompt: Boolean,
    skillProfile: CodexAssistantSkillProfile,
): String? = buildString {
    append(assistantSystemPrompt)
    if (allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
        if (isNotEmpty()) append("\n\n")
        append("--- Conversation instructions ---\n")
        append(conversationSystemPrompt)
    }
    skillProfile.autoLoaded.forEach { skill ->
        if (isNotEmpty()) append("\n\n")
        append("--- RikkaHub assistant skill: ")
        append(skill.name)
        append(" ---\n")
        append(skill.body)
    }
}.ifBlank { null }

internal fun isRikkaHubCodexSkill(
    skill: CodexSkillMetadata,
    skillsRoot: String = RikkaHubCodexAppServerBridge.RIKKAHUB_SKILLS_ROOT,
): Boolean {
    val relative = skill.path.removePrefix(skillsRoot.trimEnd('/') + "/")
    if (relative == skill.path) return false
    val segments = relative.split('/')
    return segments.size == 2 && segments[0].isNotBlank() && segments[1] == "SKILL.md"
}
