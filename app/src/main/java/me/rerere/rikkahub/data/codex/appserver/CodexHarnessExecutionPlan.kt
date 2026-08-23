package me.rerere.rikkahub.data.codex.appserver

import kotlin.uuid.Uuid

/**
 * Credential-safe projection consumed by ChatService / the future local Responses gateway.
 * ProviderSetting objects (and therefore API keys) deliberately stop at the route-resolver
 * boundary and are never copied into this plan, logs, thread bindings or App Server config.
 */
sealed interface CodexHarnessExecutionPlan {
    data class ChatGptAccount(
        val model: String?,
    ) : CodexHarnessExecutionPlan

    data class LocalResponsesGateway(
        val providerId: Uuid,
        val modelId: Uuid,
        val wireModel: String,
        val mode: GatewayMode,
    ) : CodexHarnessExecutionPlan

    data class MissingProviderModel(val modelId: String) : CodexHarnessExecutionPlan

    enum class GatewayMode {
        /** Provider already speaks Responses; gateway primarily brokers credentials and isolation. */
        RESPONSES_PASSTHROUGH,

        /** Gateway must translate between Responses semantics and the configured provider API. */
        TRANSLATE,
    }
}

object CodexHarnessExecutionPlanner {
    fun from(route: CodexHarnessModelRoute): CodexHarnessExecutionPlan = when (route) {
        is CodexHarnessModelRoute.ChatGptAccount ->
            CodexHarnessExecutionPlan.ChatGptAccount(route.model)

        is CodexHarnessModelRoute.DirectResponses ->
            CodexHarnessExecutionPlan.LocalResponsesGateway(
                providerId = route.provider.id,
                modelId = route.model.id,
                wireModel = route.model.modelId,
                mode = CodexHarnessExecutionPlan.GatewayMode.RESPONSES_PASSTHROUGH,
            )

        is CodexHarnessModelRoute.BridgeRequired ->
            CodexHarnessExecutionPlan.LocalResponsesGateway(
                providerId = route.provider.id,
                modelId = route.model.id,
                wireModel = route.model.modelId,
                mode = CodexHarnessExecutionPlan.GatewayMode.TRANSLATE,
            )

        is CodexHarnessModelRoute.MissingProviderModel ->
            CodexHarnessExecutionPlan.MissingProviderModel(route.modelId)
    }
}
