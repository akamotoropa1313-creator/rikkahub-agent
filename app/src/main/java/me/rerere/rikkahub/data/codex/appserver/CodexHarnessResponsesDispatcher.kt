package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * Narrow gateway seam used by the HTTP server. Keeping the server dependent on the Responses
 * contract instead of ProviderManager details lets JVM tests exercise the real loopback HTTP
 * boundary without constructing Android DataStore/provider state.
 */
interface CodexHarnessTranslatedResponsesBackend {
    fun stream(
        bearerToken: String?,
        request: JsonObject,
    ): Flow<CodexHarnessResponsesSseEvent>
}

/**
 * Credential-brokered dispatch core used by the loopback Responses HTTP server.
 *
 * Authentication resolves an opaque gateway token to provider/model ids. The provider setting,
 * including its API key, is then looked up from the latest SettingsStore snapshot and is passed
 * only to the in-process RikkaHub Provider implementation. It is never copied into Codex config,
 * the gateway session registry, an exception message, or an SSE payload.
 */
class CodexHarnessResponsesDispatcher(
    private val sessionRegistry: CodexHarnessGatewaySessionRegistry,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) : CodexHarnessTranslatedResponsesBackend {
    override fun stream(
        bearerToken: String?,
        request: JsonObject,
    ): Flow<CodexHarnessResponsesSseEvent> = flow {
        val session = sessionRegistry.resolve(bearerToken)
            ?: throw CodexHarnessGatewayUnauthorizedException()
        if (session.mode != CodexHarnessExecutionPlan.GatewayMode.TRANSLATE) {
            throw CodexHarnessGatewayRouteException("Selected provider requires raw Responses passthrough")
        }
        val settings = settingsStore.settingsFlow.value
        val providerSetting = settings.providers.firstOrNull { it.id == session.providerId }
            ?: throw CodexHarnessGatewayRouteException("Selected provider no longer exists")
        if (!providerSetting.enabled) {
            throw CodexHarnessGatewayRouteException("Selected provider is disabled")
        }
        val configuredModel = providerSetting.models.firstOrNull { it.id == session.modelId }
            ?: throw CodexHarnessGatewayRouteException("Selected model no longer exists")
        if (configuredModel.modelId != session.wireModel) {
            throw CodexHarnessGatewayRouteException(
                "Selected model changed after the Codex gateway session was created; reset the Codex session"
            )
        }

        val translated = CodexHarnessResponsesRequestTranslator.translate(request)
        // The model in the opaque session is authoritative. Never let a client-controlled model
        // string pivot the token to another provider model.
        val model = if (translated.tools.isEmpty() || ModelAbility.TOOL in configuredModel.abilities) {
            configuredModel
        } else {
            configuredModel.copy(abilities = configuredModel.abilities + ModelAbility.TOOL)
        }
        val params = TextGenerationParams(
            model = model,
            tools = translated.tools,
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        )
        val encoder = CodexHarnessResponsesSseEncoder(model = session.wireModel)
        encoder.startEvents().forEach { emit(it) }

        @Suppress("UNCHECKED_CAST")
        val provider = providerManager.getProviderByType(providerSetting) as me.rerere.ai.provider.Provider<ProviderSetting>
        provider.streamText(
            providerSetting = providerSetting,
            messages = translated.messages,
            params = params,
        ).collect { chunk ->
            encoder.accept(chunk).forEach { emit(it) }
        }
        // Providers normally emit Finish. Complete defensively if a compatible implementation
        // closes cleanly without one; the encoder de-duplicates terminal events.
        encoder.complete().forEach { emit(it) }
    }
}

class CodexHarnessGatewayUnauthorizedException : IllegalStateException("Unauthorized Codex harness gateway request")
class CodexHarnessGatewayRouteException(message: String) : IllegalStateException(message)
