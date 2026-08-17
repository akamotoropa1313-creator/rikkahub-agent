package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.flow.Flow

/**
 * Process-independent, JSONL-framed transport. Each [sendLine] argument and [events] line is one
 * complete JSON document without a trailing newline. Implementations must serialize concurrent
 * sends, but must not parse JSON-RPC.
 */
interface CodexAppServerTransport : AutoCloseable {
    val events: Flow<CodexAppServerTransportEvent>

    suspend fun sendLine(line: String)

    override fun close()
}

sealed interface CodexAppServerTransportEvent {
    data class Line(val value: String) : CodexAppServerTransportEvent
    data class Failure(val cause: Throwable) : CodexAppServerTransportEvent
    data object Closed : CodexAppServerTransportEvent
}
