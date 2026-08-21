package me.rerere.rikkahub.data.codex.appserver

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Credential isolation boundary between the Codex subprocess and configured RikkaHub providers.
 *
 * The subprocess receives only an opaque random token. Provider ids/model ids are kept in-process;
 * API keys never enter this registry and are resolved from SettingsStore only when a request is
 * actually dispatched. Sessions are short-lived and can also be revoked explicitly when a Codex
 * conversation/runtime closes.
 */
class CodexHarnessGatewaySessionRegistry(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttl: Duration = 2.hours,
) {
    data class Session(
        val providerId: Uuid,
        val modelId: Uuid,
        val wireModel: String,
        val mode: CodexHarnessExecutionPlan.GatewayMode,
        val expiresAtMillis: Long,
    )

    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Session>()

    fun issue(plan: CodexHarnessExecutionPlan.LocalResponsesGateway): Pair<String, Session> {
        pruneExpired()
        val token = newToken()
        val session = Session(
            providerId = plan.providerId,
            modelId = plan.modelId,
            wireModel = plan.wireModel,
            mode = plan.mode,
            expiresAtMillis = nowMillis() + ttl.inWholeMilliseconds,
        )
        sessions[token] = session
        return token to session
    }

    fun resolve(token: String?): Session? {
        if (token.isNullOrBlank()) return null
        val session = sessions[token] ?: return null
        if (session.expiresAtMillis <= nowMillis()) {
            sessions.remove(token, session)
            return null
        }
        return session
    }

    fun revoke(token: String) {
        sessions.remove(token)
    }

    fun clear() {
        sessions.clear()
    }

    internal fun sizeForTest(): Int = sessions.size

    private fun pruneExpired() {
        val now = nowMillis()
        sessions.entries.removeIf { it.value.expiresAtMillis <= now }
    }

    private fun newToken(): String {
        while (true) {
            val bytes = ByteArray(32).also(random::nextBytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            if (!sessions.containsKey(token)) return token
        }
    }
}
