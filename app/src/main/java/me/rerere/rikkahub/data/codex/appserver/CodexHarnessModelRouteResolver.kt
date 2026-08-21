package me.rerere.rikkahub.data.codex.appserver

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant

/**
 * The single source of truth for translating the unified picker selection into a Codex-harness
 * route. Keeping this separate from UI and ChatService prevents normal RikkaHub generation
 * settings (temperature/topP/maxTokens/custom request bodies) from accidentally leaking into
 * App Server configuration where they have different or unsupported semantics.
 */
sealed interface CodexHarnessModelRoute {
    /** Auth, provider discovery and model access are owned by Codex / the signed-in ChatGPT account. */
    data class ChatGptAccount(val model: String?) : CodexHarnessModelRoute

    /**
     * An existing OpenAI-compatible RikkaHub provider that explicitly uses the Responses API.
     * The provider object is deliberately kept internal to the app layer so credentials stay in
     * RikkaHub configuration; callers must not log/toString this route.
     */
    data class DirectResponses(
        val model: Model,
        val provider: ProviderSetting.OpenAI,
    ) : CodexHarnessModelRoute

    /** Existing provider requires the app-local Responses bridge before Codex can execute it. */
    data class BridgeRequired(
        val model: Model,
        val provider: ProviderSetting,
    ) : CodexHarnessModelRoute

    /** A persisted provider-model target whose model was later removed from Settings. */
    data class MissingProviderModel(val modelId: String) : CodexHarnessModelRoute
}

/** Typed target when present; otherwise losslessly project the pre-unification codexModel field. */
fun Assistant.effectiveCodexHarnessModelTarget(): CodexHarnessModelTarget =
    codexHarnessModelTarget ?: legacyCodexModelTarget(codexModel)

object CodexHarnessModelRouteResolver {
    fun resolve(assistant: Assistant, settings: Settings): CodexHarnessModelRoute =
        when (val target = assistant.effectiveCodexHarnessModelTarget()) {
            is CodexHarnessModelTarget.ChatGptAccount ->
                CodexHarnessModelRoute.ChatGptAccount(target.model)

            is CodexHarnessModelTarget.RikkaHubProvider -> {
                val model = settings.providers.findModelById(target.modelId)
                    ?: return CodexHarnessModelRoute.MissingProviderModel(target.modelId.toString())
                val provider = model.findProvider(settings.providers)
                    ?: return CodexHarnessModelRoute.MissingProviderModel(target.modelId.toString())
                if (provider is ProviderSetting.OpenAI && provider.useResponseApi) {
                    CodexHarnessModelRoute.DirectResponses(model, provider)
                } else {
                    CodexHarnessModelRoute.BridgeRequired(model, provider)
                }
            }
        }
}
