package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnInput
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import me.rerere.rikkahub.data.codex.appserver.explicitSkillInvocation

/** Single source of truth shared by transcript persistence and the one turn/start wire request. */
fun buildCodexSkillInvocation(skill: CodexSkillMetadata, prompt: String): List<CodexAppServerTurnInput> =
    explicitSkillInvocation(skill.name, skill.path, prompt).input

fun codexSkillTranscript(skill: CodexSkillMetadata, prompt: String): String =
    (buildCodexSkillInvocation(skill, prompt).first() as CodexAppServerTurnInput.Text).text

/** Runs the acceptance callback only after turn/start has returned successfully. */
suspend fun <T> acceptCodexSkillAfterStart(start: suspend () -> T, onAccepted: () -> Unit): T =
    start().also { onAccepted() }
