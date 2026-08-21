package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * A model selection for the Codex execution harness.
 *
 * Codex is deliberately not represented as a RikkaHub provider. The harness can execute with
 * either a model exposed by the authenticated ChatGPT account or an already-configured RikkaHub
 * provider model. Keeping the source explicit prevents dynamic App Server catalog entries from
 * being persisted as fake ProviderSetting/Model rows.
 */
@Serializable
sealed interface CodexHarnessModelTarget {
    @Serializable
    @SerialName("chatgpt_account")
    data class ChatGptAccount(
        /** Null means: let Codex use the current App Server default model. */
        val model: String? = null,
    ) : CodexHarnessModelTarget

    @Serializable
    @SerialName("rikkahub_provider")
    data class RikkaHubProvider(
        /** Existing RikkaHub Model.id. Provider identity is resolved from Settings at execution. */
        val modelId: Uuid,
    ) : CodexHarnessModelTarget
}

/**
 * Compatibility projection for assistants created before the unified harness picker exists.
 * Existing `codexModel` values always meant a ChatGPT/Codex catalog model, so migration is
 * lossless and a null value keeps the historical "App Server default" behavior.
 */
fun legacyCodexModelTarget(codexModel: String?): CodexHarnessModelTarget =
    CodexHarnessModelTarget.ChatGptAccount(codexModel)
