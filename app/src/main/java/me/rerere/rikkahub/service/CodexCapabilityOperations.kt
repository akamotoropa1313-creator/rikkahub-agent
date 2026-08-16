package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.codex.appserver.CodexAppServerAccountEvent

/** Keeps OAuth pending state retryable: every failed begin/validation/launch clears it. */
suspend fun <T> runCodexMcpOAuthBegin(
    name: String,
    publishPending: (String?) -> Unit,
    operation: suspend () -> T,
): T {
    publishPending(name)
    return try { operation() } catch (failure: Throwable) {
        publishPending(null)
        throw failure
    }
}

/**
 * Correlates account/login/completed events with the one live login attempt.
 *
 * The start response can race an early completion, so non-null early IDs are buffered until the
 * exact login ID is known. Once start has resolved, only the exact active login ID (or an anonymous
 * completion while that sole attempt is active) may update UI state. Canceled/completed IDs are
 * remembered as terminal so a delayed completion cannot resurrect a finished attempt.
 *
 * All state-publishing callbacks run while this object's monitor is held, making transitions
 * atomic against completion delivery.
 */
internal class CodexAccountLoginCorrelation {
    private var awaitingStartId = false
    private var anonymousCompletionSeen = false
    private var activeLoginId: String? = null
    private val completionsById = linkedMapOf<String, CodexAppServerAccountEvent.LoginCompleted>()
    private val terminalLoginIds = linkedSetOf<String>()

    @Synchronized
    fun beginAttempt(onBegin: () -> Unit = {}) {
        check(!awaitingStartId && activeLoginId == null) { "A Codex account login is already active" }
        awaitingStartId = true
        clearBuffered()
        onBegin()
    }

    /**
     * Delivers an event only when it can be correlated to the current attempt.
     * Non-null early IDs are buffered until start returns; a nullable early completion belongs to
     * the sole start-in-flight attempt and is terminal immediately.
     */
    @Synchronized
    fun onCompletion(
        event: CodexAppServerAccountEvent.LoginCompleted,
        apply: (CodexAppServerAccountEvent.LoginCompleted) -> Unit,
    ) {
        if (awaitingStartId) {
            val id = event.loginId
            if (id == null) {
                anonymousCompletionSeen = true
                apply(event)
            } else {
                completionsById[id] = event
                while (completionsById.size > MAX_BUFFERED_COMPLETIONS) {
                    completionsById.remove(completionsById.keys.first())
                }
            }
            return
        }

        val active = activeLoginId ?: return
        val id = event.loginId
        if (id != null && id != active) return

        activeLoginId = null
        rememberTerminal(active)
        apply(event)
    }

    /**
     * Atomically resolves start to an early terminal event or publishes the exact pending login ID.
     * An exact completion wins over an anonymous one. Unrelated buffered IDs are discarded here.
     */
    @Synchronized
    fun resolveStart(
        loginId: String,
        onCompletion: (CodexAppServerAccountEvent.LoginCompleted) -> Unit,
        onPending: (String) -> Unit,
    ) {
        check(awaitingStartId) { "No Codex account login start is awaiting correlation" }
        awaitingStartId = false
        terminalLoginIds.remove(loginId) // A reused ID belongs to this newly-authoritative attempt.
        val exact = completionsById[loginId]
        when {
            exact != null -> {
                activeLoginId = null
                rememberTerminal(loginId)
                onCompletion(exact)
            }
            anonymousCompletionSeen -> {
                activeLoginId = null
                rememberTerminal(loginId) // anonymous completion was already applied above
            }
            else -> {
                activeLoginId = loginId
                onPending(loginId)
            }
        }
        clearBuffered()
    }

    /** Marks a successfully canceled login terminal before any delayed completion can arrive. */
    @Synchronized
    fun markCanceled(loginId: String) {
        if (activeLoginId == loginId) activeLoginId = null
        rememberTerminal(loginId)
    }

    @Synchronized
    fun abortAttempt(onAbort: () -> Unit = {}) {
        awaitingStartId = false
        clearBuffered()
        onAbort()
    }

    @Synchronized
    internal fun bufferedCountForTest(): Int = completionsById.size + if (anonymousCompletionSeen) 1 else 0

    @Synchronized
    internal fun activeLoginIdForTest(): String? = activeLoginId

    @Synchronized
    internal fun isTerminalForTest(loginId: String): Boolean = loginId in terminalLoginIds

    private fun rememberTerminal(loginId: String) {
        terminalLoginIds += loginId
        while (terminalLoginIds.size > MAX_TERMINAL_IDS) {
            terminalLoginIds.remove(terminalLoginIds.first())
        }
    }

    private fun clearBuffered() {
        anonymousCompletionSeen = false
        completionsById.clear()
    }

    private companion object {
        const val MAX_BUFFERED_COMPLETIONS = 8
        const val MAX_TERMINAL_IDS = 16
    }
}
