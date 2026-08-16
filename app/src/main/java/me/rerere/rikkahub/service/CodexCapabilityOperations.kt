package me.rerere.rikkahub.service

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
