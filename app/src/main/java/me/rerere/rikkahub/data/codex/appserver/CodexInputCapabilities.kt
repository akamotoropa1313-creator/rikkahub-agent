package me.rerere.rikkahub.data.codex.appserver

enum class CodexInputCapability { Supported, Unsupported, Unknown }

/** Resolves by the wire `model` value, never the catalog presentation id. */
fun codexInputCapability(
    savedModel: String?,
    catalog: List<CodexAppServerModel>?,
    modality: String,
): CodexInputCapability {
    if (catalog == null) return CodexInputCapability.Unknown
    val model = if (savedModel != null) catalog.firstOrNull { it.model == savedModel }
    else catalog.firstOrNull { it.isDefault }
    val modalities = model?.inputModalities ?: return CodexInputCapability.Unknown
    return if (modality in modalities) CodexInputCapability.Supported else CodexInputCapability.Unsupported
}
