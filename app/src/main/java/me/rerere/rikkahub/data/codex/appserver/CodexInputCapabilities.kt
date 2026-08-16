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

/**
 * Stage17 send policy. Images predate modality reporting and therefore remain allowed when support
 * is unknown. Audio is newer and must be explicitly reported as supported before any staging or
 * turn/start serialization occurs, so older App Servers fail closed instead of receiving an
 * unknown `audio`/`localAudio` wire variant.
 */
fun codexInputAllowedForTurn(capability: CodexInputCapability, modality: String): Boolean = when {
    capability == CodexInputCapability.Supported -> true
    capability == CodexInputCapability.Unsupported -> false
    modality == "image" -> true
    modality == "audio" -> false
    else -> false
}
