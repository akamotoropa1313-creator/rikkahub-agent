package me.rerere.rikkahub.ui.components.codex

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerModel
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessModelPresentationResolver
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessModelTarget
import me.rerere.rikkahub.data.codex.appserver.effectiveCodexHarnessModelTarget
import me.rerere.rikkahub.data.codex.appserver.serviceTierIds
import me.rerere.rikkahub.data.model.Assistant

internal data class CodexServiceTierOption(val id: String, val name: String, val description: String)

internal fun codexServiceTierOptions(model: CodexAppServerModel): List<CodexServiceTierOption> =
    model.serviceTiers?.takeIf { it.isNotEmpty() }?.map { CodexServiceTierOption(it.id, it.name, it.description) }
        ?: model.additionalSpeedTiers.orEmpty().map {
            CodexServiceTierOption(it, if (it == "fast") "高速" else it, "旧形式のサービスティア")
        }

/**
 * A saved specific tier survives an unreported (legacy-server) catalog. It falls back to explicit
 * `default` only when the selected model reported tier metadata and the requested id is absent.
 */
internal fun serviceTierForCodexModelSelection(saved: String?, model: CodexAppServerModel): String? = when (saved) {
    null, "default" -> saved
    else -> saved.takeIf { it in model.serviceTierIds() } ?: "default"
}

internal fun selectedCodexModel(
    savedModel: String?,
    models: List<CodexAppServerModel>,
): CodexAppServerModel? = savedModel?.let { selected -> models.firstOrNull { it.model == selected } }

/** Service-tier capability may use the catalog's explicit default only when no model is saved. */
internal fun serviceTierCatalogModel(
    savedModel: String?,
    models: List<CodexAppServerModel>,
): CodexAppServerModel? = if (savedModel != null) {
    models.firstOrNull { it.model == savedModel }
} else {
    models.firstOrNull { it.isDefault }
}

/**
 * Resolve the model whose open-string effort catalog is safe to show. The server-default target
 * uses only a model explicitly marked as default; an external provider has no App Server effort
 * contract and therefore returns null instead of borrowing a ChatGPT model's options.
 */
internal fun reasoningEffortCatalogModel(
    target: CodexHarnessModelTarget,
    models: List<CodexAppServerModel>,
): CodexAppServerModel? = when (target) {
    is CodexHarnessModelTarget.ChatGptAccount -> serviceTierCatalogModel(target.model, models)
    is CodexHarnessModelTarget.RikkaHubProvider -> null
}

/** Japanese labels for known values; future server-defined strings remain visible verbatim. */
internal fun codexReasoningEffortLabel(effort: String?): String = when (effort?.lowercase()) {
    null -> "サーバー設定"
    "none", "off" -> "なし"
    "minimal" -> "最小"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "超高"
    "max" -> "最大"
    "default" -> "既定"
    else -> effort
}

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

/**
 * Compatibility writer used by the old Codex-only model list while the shared RikkaHub picker is
 * being adopted. Persist both fields so older builds can still read codexModel and the new harness
 * router gets an unambiguous ChatGPT-account target.
 */
internal fun applyCodexModelSelection(
    assistant: Assistant,
    model: CodexAppServerModel,
): Assistant = assistant.copy(
    codexModel = model.model,
    codexHarnessModelTarget = CodexHarnessModelTarget.ChatGptAccount(model.model),
    codexReasoningEffort = effortForCodexModelSelection(assistant.codexReasoningEffort, model),
    codexServiceTier = serviceTierForCodexModelSelection(assistant.codexServiceTier, model),
)

internal fun codexComposerLabel(
    assistant: Assistant,
    models: List<CodexAppServerModel>,
    providers: List<ProviderSetting> = emptyList(),
): String {
    if (!assistant.codexAppServerEnabled) return ""
    val target = assistant.effectiveCodexHarnessModelTarget()
    if (target is CodexHarnessModelTarget.RikkaHubProvider) {
        val modelLabel = if (providers.isEmpty()) {
            "RikkaHubモデル"
        } else {
            CodexHarnessModelPresentationResolver.resolve(target, providers, models).compactLabel
        }
        return "Codex · $modelLabel"
    }
    target as CodexHarnessModelTarget.ChatGptAccount
    val selected = selectedCodexModel(target.model, models)
    val tierModel = serviceTierCatalogModel(target.model, models)
    val modelLabel = selected?.displayName ?: target.model ?: "サーバー既定"
    val effort = assistant.codexReasoningEffort?.let { " · ${codexReasoningEffortLabel(it)}" }.orEmpty()
    val tier = assistant.codexServiceTier?.let { saved ->
        val label = if (saved == "default") "既定" else tierModel?.let { model ->
            codexServiceTierOptions(model).firstOrNull { it.id == saved }?.name
        } ?: saved
        " · $label"
    }.orEmpty()
    return "Codex · $modelLabel$effort$tier"
}
