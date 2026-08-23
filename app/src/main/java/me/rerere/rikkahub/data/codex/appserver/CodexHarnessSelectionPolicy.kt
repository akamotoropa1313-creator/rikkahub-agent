package me.rerere.rikkahub.data.codex.appserver

import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * Model-source transitions deliberately reset only settings whose semantics depend on the selected
 * model/catalog. Harness/security settings (workspace, sandbox, approval policy, Skills/MCP, etc.)
 * survive the switch.
 */
object CodexHarnessSelectionPolicy {
    fun selectRikkaHubProviderModel(assistant: Assistant, modelId: Uuid): Assistant = assistant.copy(
        codexHarnessModelTarget = CodexHarnessModelTarget.RikkaHubProvider(modelId),
        // Old builds interpret codexModel as an App Server / ChatGPT model. Null prevents an
        // external provider id from being accidentally sent as an OpenAI account model.
        codexModel = null,
        // These values are validated against the ChatGPT App Server model catalog today. Until the
        // bridge advertises an equivalent capability contract, carrying them across providers would
        // be an unsafe guess.
        codexReasoningEffort = null,
        codexReasoningSummary = null,
        codexPersonality = null,
        codexServiceTier = null,
    )

    /** Selecting server default is a real ChatGPT-account target, not an absent model target. */
    fun selectChatGptServerDefault(assistant: Assistant): Assistant = assistant.copy(
        codexHarnessModelTarget = CodexHarnessModelTarget.ChatGptAccount(model = null),
        codexModel = null,
        codexReasoningEffort = null,
        codexReasoningSummary = null,
        codexPersonality = null,
        codexServiceTier = null,
    )
}
