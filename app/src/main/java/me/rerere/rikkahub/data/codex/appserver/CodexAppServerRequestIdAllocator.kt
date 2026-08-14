package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.atomic.AtomicLong

/** Thread-safe, monotonically increasing signed 64-bit request IDs. */
class CodexAppServerRequestIdAllocator(startAt: Long = 1L) {
    private val next = AtomicLong(startAt.also { require(it > 0) { "startAt must be positive" } })

    /**
     * Allocates an ID. After [Long.MAX_VALUE] has been returned, all further calls throw rather
     * than silently wrapping into negative IDs.
     */
    fun allocate(): JsonRpcId.NumberId {
        while (true) {
            val candidate = next.get()
            check(candidate != EXHAUSTED) { "JSON-RPC request ID space exhausted" }
            val following = if (candidate == Long.MAX_VALUE) EXHAUSTED else candidate + 1
            if (next.compareAndSet(candidate, following)) return JsonRpcId.NumberId(candidate)
        }
    }

    private companion object {
        const val EXHAUSTED = 0L
    }
}
