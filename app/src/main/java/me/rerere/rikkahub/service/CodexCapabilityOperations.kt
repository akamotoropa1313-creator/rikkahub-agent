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
 * Correlates account/login/completed events that can arrive before account/login/start returns.
 * All state-publishing callbacks run while this object's monitor is held, making the transition
 * from unknown start ID -> pending/completed atomic against completion delivery.
 */
internal class CodexAccountLoginCorrelation {
    private var awaitingStartId = false
    private var anonymousCompletion: CodexAppServerAccountEvent.LoginCompleted? = null
    private val completionsById = linkedMapOf<String, CodexAppServerAccountEvent.LoginCompleted>()

    @Synchronized
    fun beginAttempt(onBegin: () -> Unit = {}) {
        awaitingStartId = true
        clearBuffered()
        onBegin()
    }

    /**
     * Delivers an event immediately when it can be correlated now. Non-null early IDs are buffered
     * until start returns; a nullable ID belongs to the sole in-flight attempt and is terminal now.
     */
    @Synchronized
    fun onCompletion(
        event: CodexAppServerAccountEvent.LoginCompleted,
        apply: (CodexAppServerAccountEvent.LoginCompleted) -> Unit,
    ) {
        if (!awaitingStartId) {
            apply(event)
            return
        }
        val id = event.loginId
        if (id == null) {
            anonymousCompletion = event
            apply(event)
        } else {
            completionsById[id] = event
            while (completionsById.size > MAX_BUFFERED_COMPLETIONS) {
                completionsById.remove(completionsById.keys.first())
            }
        }
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
        val completion = completionsById[loginId] ?: anonymousCompletion
        if (completion != null) onCompletion(completion) else onPending(loginId)
        clearBuffered()
    }

    @Synchronized
    fun abortAttempt(onAbort: () -> Unit = {}) {
        awaitingStartId = false
        clearBuffered()
        onAbort()
    }

    @Synchronized
    internal fun bufferedCountForTest(): Int = completionsById.size + if (anonymousCompletion != null) 1 else 0

    private fun clearBuffered() {
        anonymousCompletion = null
        completionsById.clear()
    }

    private companion object {
        const val MAX_BUFFERED_COMPLETIONS = 8
    }
}
