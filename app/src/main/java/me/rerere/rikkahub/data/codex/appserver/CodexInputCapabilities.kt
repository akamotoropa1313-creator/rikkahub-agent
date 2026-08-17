package me.rerere.rikkahub.data.codex.appserver

enum class CodexInputCapability { Supported, Unsupported, Unknown }

/**
 * Resolves by the wire `model` value, never the catalog presentation id.
 *
 * Image support predates `inputModalities`, so missing capability data remains Unknown and the
 * existing image path may continue. Audio is newer: until the selected model explicitly reports
 * `audio`, it is treated as Unsupported for turn preflight. This keeps older App Servers from ever
 * receiving an `audio`/`localAudio` variant they cannot decode.
 */
fun codexInputCapability(
    savedModel: String?,
    catalog: List<CodexAppServerModel>?,
    modality: String,
): CodexInputCapability {
    fun unreported(): CodexInputCapability =
        if (modality == "audio") CodexInputCapability.Unsupported else CodexInputCapability.Unknown

    if (catalog == null) return unreported()
    val model = if (savedModel != null) catalog.firstOrNull { it.model == savedModel }
    else catalog.firstOrNull { it.isDefault }
    val modalities = model?.inputModalities ?: return unreported()
    return if (modality in modalities) CodexInputCapability.Supported else CodexInputCapability.Unsupported
}
