package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnInput
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import me.rerere.rikkahub.data.codex.appserver.explicitSkillsInvocation

/** Single source of truth shared by transcript persistence and the one turn/start wire request. */
fun buildCodexSkillInvocation(
    skills: List<CodexSkillMetadata>,
    prompt: String,
): List<CodexAppServerTurnInput> = explicitSkillsInvocation(
    skills.map { it.name to it.path },
    prompt,
).input

fun buildCodexSkillInvocation(skill: CodexSkillMetadata, prompt: String): List<CodexAppServerTurnInput> =
    buildCodexSkillInvocation(listOf(skill), prompt)

fun codexSkillTranscript(skills: List<CodexSkillMetadata>, prompt: String): String =
    (buildCodexSkillInvocation(skills, prompt).first() as CodexAppServerTurnInput.Text).text

fun codexSkillTranscript(skill: CodexSkillMetadata, prompt: String): String =
    codexSkillTranscript(listOf(skill), prompt)

fun codexSkillPromptFromTranscript(skills: List<CodexSkillMetadata>, transcript: String): String {
    val marker = skills.distinctBy { it.path }.joinToString(" ") { "\$${it.name}" }
    return transcript.removePrefix(marker).trimStart()
}

/** Runs the acceptance callback only after turn/start has returned successfully. */
suspend fun <T> acceptCodexSkillAfterStart(start: suspend () -> T, onAccepted: () -> Unit): T =
    start().also { onAccepted() }
