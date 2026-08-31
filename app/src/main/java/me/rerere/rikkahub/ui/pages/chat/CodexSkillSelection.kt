package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata

internal fun toggleCodexSkillSelection(
    selected: List<CodexSkillMetadata>,
    skill: CodexSkillMetadata,
): List<CodexSkillMetadata> = if (selected.any { it.path == skill.path }) {
    selected.filterNot { it.path == skill.path }
} else {
    selected + skill
}
