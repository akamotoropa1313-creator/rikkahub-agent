package me.rerere.rikkahub.ui.components.codex

import me.rerere.rikkahub.data.codex.appserver.CodexAppServerModel
import me.rerere.rikkahub.data.model.Assistant

internal fun selectedCodexModel(
    savedModel: String?,
    models: List<CodexAppServerModel>,
): CodexAppServerModel? = savedModel?.let { selected -> models.firstOrNull { it.model == selected } }

internal fun savedCodexModelMissing(
    savedModel: String?,
    models: List<CodexAppServerModel>,
): Boolean = savedModel != null && models.isNotEmpty() && selectedCodexModel(savedModel, models) == null

/**
 * Returns the effort that should be persisted when the user explicitly selects [model].
 * Server order is never re-ranked: an already-compatible saved value is kept; otherwise the
 * catalog's concrete default is selected. Empty/inconsistent catalogs are rejected rather than
 * guessed by the client.
 */
internal fun effortForCodexModelSelection(
    savedEffort: String?,
    model: CodexAppServerModel,
): String {
    val advertised = model.supportedReasoningEfforts.map { it.reasoningEffort }
    require(advertised.isNotEmpty()) { "${model.displayName} did not advertise reasoning efforts" }
    require(model.defaultReasoningEffort in advertised) {
        "${model.displayName} advertised a default reasoning effort that is not supported"
    }
    return savedEffort?.takeIf(advertised::contains) ?: model.defaultReasoningEffort
}

internal fun applyCodexModelSelection(
    assistant: Assistant,
    model: CodexAppServerModel,
): Assistant = assistant.copy(
    codexModel = model.model,
    codexReasoningEffort = effortForCodexModelSelection(assistant.codexReasoningEffort, model),
)

internal fun codexComposerLabel(
    assistant: Assistant,
    models: List<CodexAppServerModel>,
): String {
    if (!assistant.codexAppServerEnabled) return ""
    val selected = selectedCodexModel(assistant.codexModel, models)
    val modelLabel = selected?.displayName ?: assistant.codexModel ?: "server default"
    return assistant.codexReasoningEffort?.let { "Codex · $modelLabel · $it" } ?: "Codex · $modelLabel"
}
