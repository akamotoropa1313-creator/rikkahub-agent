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
 *
 * The App Server may omit loginId on the completion notification, so the sole in-flight login
 * attempt has to own that anonymous completion. Non-null completions are retained only for the
 * duration of that start attempt and are bounded to avoid accumulating stale IDs.
 *
 * All calls are made while CodexChatRuntime holds its account-login lock, which lets the runtime
 * update this correlation state and CodexCapabilitiesUiState atomically with respect to the
 * account event collector.
 */
internal class CodexAccountLoginCorrelation {
    private var awaitingStartId = false
    private var anonymousCompletion: CodexAppServerAccountEvent.LoginCompleted? = null
    private val completionsById = linkedMapOf<String, CodexAppServerAccountEvent.LoginCompleted>()

    fun beginAttempt() {
        awaitingStartId = true
        clearBuffered()
    }

    /** Returns true when the event was buffered for the still-unknown start loginId. */
    fun bufferIfAwaiting(event: CodexAppServerAccountEvent.LoginCompleted): Boolean {
        if (!awaitingStartId) return false
        val id = event.loginId
        if (id == null) {
            anonymousCompletion = event
        } else {
            completionsById[id] = event
            while (completionsById.size > MAX_BUFFERED_COMPLETIONS) {
                completionsById.remove(completionsById.keys.first())
            }
        }
        return true
    }

    /**
     * Resolves the start response (or a post-start browser-launch failure) to an early completion.
     * An exact ID wins; otherwise an anonymous completion belongs to the sole in-flight attempt.
     * All unrelated buffered IDs are discarded at this boundary.
     */
    fun resolveStart(loginId: String): CodexAppServerAccountEvent.LoginCompleted? {
        check(awaitingStartId) { "No Codex account login start is awaiting correlation" }
        awaitingStartId = false
        val completion = completionsById[loginId] ?: anonymousCompletion
        clearBuffered()
        return completion
    }

    fun abortAttempt() {
        awaitingStartId = false
        clearBuffered()
    }

    internal fun bufferedCountForTest(): Int = completionsById.size + if (anonymousCompletion != null) 1 else 0

    private fun clearBuffered() {
        anonymousCompletion = null
        completionsById.clear()
    }

    private companion object {
        const val MAX_BUFFERED_COMPLETIONS = 8
    }
}
