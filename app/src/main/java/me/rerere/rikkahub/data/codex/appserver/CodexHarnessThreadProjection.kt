package me.rerere.rikkahub.data.codex.appserver

import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Thread-level projection consumed by Codex App Server.
 *
 * Model provider selection is a thread property in Codex. ChatGPT account models use Codex's
 * native provider and therefore need no custom config. RikkaHub provider models are routed
 * through the app-private loopback Responses gateway. The only credential copied into Codex's
 * thread config is the short-lived opaque gateway token; the configured provider API key never
 * crosses this boundary.
 */
data class CodexHarnessThreadProjection(
    val model: String?,
    val modelProvider: String?,
    val config: Map<String, JsonElement>?,
) {
    val isGatewayBacked: Boolean get() = modelProvider?.startsWith(CODEX_HARNESS_GATEWAY_PROVIDER_PREFIX) == true
}

internal const val CODEX_HARNESS_GATEWAY_PROVIDER_PREFIX = "rikkahub_gateway_"

internal fun CodexHarnessExecutionPlan.LocalResponsesGateway.gatewayProviderId(): String {
    val route = "${providerId}:${modelId}"
    val digest = MessageDigest.getInstance("SHA-256").digest(route.toByteArray(Charsets.UTF_8))
    val suffix = digest.take(8).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return CODEX_HARNESS_GATEWAY_PROVIDER_PREFIX + suffix
}

object CodexHarnessThreadProjector {
    fun project(
        plan: CodexHarnessExecutionPlan,
        gatewayBaseUrl: String? = null,
        gatewayBearerToken: String? = null,
    ): CodexHarnessThreadProjection = when (plan) {
        is CodexHarnessExecutionPlan.ChatGptAccount -> CodexHarnessThreadProjection(
            model = plan.model,
            modelProvider = null,
            config = null,
        )

        is CodexHarnessExecutionPlan.LocalResponsesGateway -> {
            val baseUrl = requireNotNull(gatewayBaseUrl)
                .trim()
                .trimEnd('/')
                .also { require(it.startsWith("http://127.0.0.1:") || it.startsWith("http://localhost:")) {
                    "Codex harness gateway must be loopback-only"
                } }
            val token = requireNotNull(gatewayBearerToken).trim()
                .also { require(it.isNotEmpty()) { "Codex harness gateway token must not be blank" } }
            val providerId = plan.gatewayProviderId()

            val providerConfig = JsonObject(
                mapOf(
                    "name" to JsonPrimitive("RikkaHub Harness Gateway"),
                    "base_url" to JsonPrimitive(baseUrl),
                    "wire_api" to JsonPrimitive("responses"),
                    "experimental_bearer_token" to JsonPrimitive(token),
                    "requires_openai_auth" to JsonPrimitive(false),
                    // Keep transport deterministic: the app-private gateway currently exposes
                    // Responses over HTTP/SSE only, not Codex's optional websocket transport.
                    "supports_websockets" to JsonPrimitive(false),
                    "supports_standalone_web_search" to JsonPrimitive(false),
                )
            )
            CodexHarnessThreadProjection(
                model = plan.wireModel,
                modelProvider = providerId,
                config = mapOf(
                    "model_providers" to JsonObject(
                        mapOf(providerId to providerConfig)
                    )
                ),
            )
        }

        is CodexHarnessExecutionPlan.MissingProviderModel -> error(
            "Selected RikkaHub model no longer exists: ${plan.modelId}"
        )
    }
}
