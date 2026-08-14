package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonElement

/**
 * Protocol-level App Server events. Typed events can be added later without changing the JSON-RPC
 * codec; unknown methods retain their complete parameters for forward compatibility.
 */
sealed interface CodexAppServerEvent {
    val method: String
    val params: JsonElement?

    data class UnknownNotification(
        override val method: String,
        override val params: JsonElement?,
    ) : CodexAppServerEvent

    data class ServerRequest(
        val id: JsonRpcId,
        override val method: String,
        override val params: JsonElement?,
    ) : CodexAppServerEvent
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
