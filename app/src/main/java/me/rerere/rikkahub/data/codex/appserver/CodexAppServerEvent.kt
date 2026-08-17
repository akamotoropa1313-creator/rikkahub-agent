package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonElement

/**
 * Protocol-level App Server events. Typed events can be added later without changing the JSON-RPC
 * codec; unknown methods retain their complete parameters for forward compatibility.
 */
sealed interface CodexAppServerEvent {
    data class UnknownNotification(
        val method: String,
        val params: JsonElement?,
    ) : CodexAppServerEvent

    data class ServerRequest(
        val id: JsonRpcId,
        val method: String,
        val params: JsonElement?,
    ) : CodexAppServerEvent

    data class MalformedInbound(val line: String, val cause: Throwable) : CodexAppServerEvent
    data class UnknownResponseId(val id: JsonRpcId) : CodexAppServerEvent
    data class TransportFailure(val cause: Throwable) : CodexAppServerEvent
    data object TransportClosed : CodexAppServerEvent
}

fun JsonRpcMessage.toCodexAppServerEventOrNull(): CodexAppServerEvent? = when (this) {
    is JsonRpcMessage.Notification -> CodexAppServerEvent.UnknownNotification(
        method = value.method,
        params = value.params,
    )
    is JsonRpcMessage.Request -> CodexAppServerEvent.ServerRequest(
        id = value.id,
        method = value.method,
        params = value.params,
    )
    is JsonRpcMessage.Response,
    is JsonRpcMessage.ErrorResponse -> null
}
