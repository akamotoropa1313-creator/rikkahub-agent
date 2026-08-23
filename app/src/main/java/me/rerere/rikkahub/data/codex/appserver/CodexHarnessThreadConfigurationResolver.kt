package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.ConcurrentHashMap
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

/**
 * High-level boundary ChatService uses when opening/resuming a Codex thread.
 *
 * It resolves the typed picker target, creates a fresh short-lived gateway session only when an
 * external RikkaHub provider is selected, and projects the result into App Server thread fields.
 * The active token is tracked per conversation solely so it can be revoked on reset/switch.
 */
class CodexHarnessThreadConfigurationResolver(
    private val gatewayServer: CodexHarnessResponsesGatewayServer,
    private val sessionRegistry: CodexHarnessGatewaySessionRegistry,
) {
    private val tokensByConversation = ConcurrentHashMap<String, String>()

    suspend fun resolve(
        conversationId: String,
        assistant: Assistant,
        settings: Settings,
    ): CodexHarnessThreadProjection {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        val route = CodexHarnessModelRouteResolver.resolve(assistant, settings)
        val plan = CodexHarnessExecutionPlanner.from(route)

        return when (plan) {
            is CodexHarnessExecutionPlan.ChatGptAccount -> {
                revokeConversationToken(conversationId)
                CodexHarnessThreadProjector.project(plan)
            }

            is CodexHarnessExecutionPlan.LocalResponsesGateway -> {
                val endpoint = gatewayServer.ensureStarted()
                val (token) = sessionRegistry.issue(plan)
                tokensByConversation.put(conversationId, token)?.let(sessionRegistry::revoke)
                CodexHarnessThreadProjector.project(
                    plan = plan,
                    gatewayBaseUrl = endpoint.baseUrl,
                    gatewayBearerToken = token,
                )
            }

            is CodexHarnessExecutionPlan.MissingProviderModel -> {
                throw CodexHarnessGatewayRouteException(
                    "Selected RikkaHub model no longer exists; choose another model"
                )
            }
        }
    }

    fun release(conversationId: String) {
        if (conversationId.isBlank()) return
        revokeConversationToken(conversationId)
    }

    fun releaseAll() {
        tokensByConversation.values.forEach(sessionRegistry::revoke)
        tokensByConversation.clear()
    }

    internal fun hasGatewaySessionForTest(conversationId: String): Boolean =
        tokensByConversation[conversationId]?.let { sessionRegistry.resolve(it) } != null

    private fun revokeConversationToken(conversationId: String) {
        tokensByConversation.remove(conversationId)?.let(sessionRegistry::revoke)
    }
}
