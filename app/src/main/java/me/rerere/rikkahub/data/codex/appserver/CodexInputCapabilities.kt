package me.rerere.rikkahub.data.codex.appserver

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model

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

/**
 * Harness-aware preflight. ChatGPT-account routes use App Server's live model catalog. External
 * routes must use the selected RikkaHub model instead of accidentally falling back to ChatGPT's
 * server-default model when the legacy `codexModel` field is null.
 *
 * RikkaHub currently has no AUDIO modality in [Modality]. Translation routes therefore fail closed
 * for audio because the Responses translator cannot represent it. Raw Responses passthrough keeps
 * audio Unknown: the request remains provider-native and some OpenAI-compatible providers may
 * support it even though RikkaHub cannot currently describe that capability.
 */
fun codexHarnessInputCapability(
    route: CodexHarnessModelRoute,
    chatGptCatalog: List<CodexAppServerModel>?,
    modality: String,
): CodexInputCapability = when (route) {
    is CodexHarnessModelRoute.ChatGptAccount ->
        codexInputCapability(route.model, chatGptCatalog, modality)

    is CodexHarnessModelRoute.DirectResponses ->
        rikkahubModelInputCapability(route.model, modality, rawResponses = true)

    is CodexHarnessModelRoute.BridgeRequired ->
        rikkahubModelInputCapability(route.model, modality, rawResponses = false)

    is CodexHarnessModelRoute.MissingProviderModel ->
        CodexInputCapability.Unsupported
}

private fun rikkahubModelInputCapability(
    model: Model,
    modality: String,
    rawResponses: Boolean,
): CodexInputCapability = when (modality) {
    "text" -> CodexInputCapability.Supported
    "image" -> if (Modality.IMAGE in model.inputModalities) {
        CodexInputCapability.Supported
    } else {
        CodexInputCapability.Unsupported
    }
    "audio" -> if (rawResponses) CodexInputCapability.Unknown else CodexInputCapability.Unsupported
    else -> CodexInputCapability.Unknown
}
