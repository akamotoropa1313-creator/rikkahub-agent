package me.rerere.rikkahub.data.codex.appserver

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Experimental v2 function tool supplied by the App Server client at thread/start. */
data class CodexAppServerDynamicToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonElement,
    val deferLoading: Boolean = false,
) {
    init {
        require(DYNAMIC_TOOL_NAME.matches(name)) {
            "Dynamic tool name must match ${DYNAMIC_TOOL_NAME.pattern}"
        }
    }

    internal fun toJson(): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
        if (deferLoading) put("deferLoading", true)
    }
}

data class CodexAppServerDynamicToolCallRequest(
    val threadId: String,
    val turnId: String,
    val callId: String,
    val namespace: String?,
    val tool: String,
    val arguments: JsonElement,
    val rawParams: JsonObject,
)

sealed interface CodexAppServerDynamicToolEvent {
    data class Call(
        val requestId: JsonRpcId,
        val request: CodexAppServerDynamicToolCallRequest,
    ) : CodexAppServerDynamicToolEvent

    data class MalformedCall(
        val requestId: JsonRpcId,
        val rawParams: JsonElement?,
        val cause: CodexAppServerDynamicToolProtocolException,
    ) : CodexAppServerDynamicToolEvent
}

sealed interface CodexAppServerDynamicToolOutputContentItem {
    data class Text(val text: String) : CodexAppServerDynamicToolOutputContentItem
    data class Image(val imageUrl: String) : CodexAppServerDynamicToolOutputContentItem
    data class Audio(val audioUrl: String) : CodexAppServerDynamicToolOutputContentItem
}

internal fun decodeCodexDynamicToolOutputContentItems(
    value: JsonElement?,
): List<CodexAppServerDynamicToolOutputContentItem>? {
    if (value == null || value === JsonNull) return null
    val values = value as? JsonArray
        ?: dynamicToolMalformed("contentItems must be an array or null")
    return values.mapIndexed { index, element ->
        val item = element as? JsonObject
            ?: dynamicToolMalformed("contentItems[$index] must be an object")
        when (item.dynamicToolString("type")) {
            "inputText" -> CodexAppServerDynamicToolOutputContentItem.Text(
                item.dynamicToolStringAllowEmpty("text"),
            )
            "inputImage" -> CodexAppServerDynamicToolOutputContentItem.Image(
                item.dynamicToolString("imageUrl"),
            )
            "inputAudio" -> CodexAppServerDynamicToolOutputContentItem.Audio(
                item.dynamicToolString("audioUrl"),
            )
            else -> dynamicToolMalformed("contentItems[$index].type is unsupported")
        }
    }
}

fun Flow<CodexAppServerEvent>.toCodexAppServerDynamicToolEvents(): Flow<CodexAppServerDynamicToolEvent> =
    mapNotNull { event ->
        val request = event as? CodexAppServerEvent.ServerRequest ?: return@mapNotNull null
        if (request.method != DYNAMIC_TOOL_CALL_METHOD) return@mapNotNull null
        try {
            val raw = request.params as? JsonObject
                ?: dynamicToolMalformed("item/tool/call params must be an object")
            CodexAppServerDynamicToolEvent.Call(request.id, raw.dynamicToolCall())
        } catch (cause: CodexAppServerDynamicToolProtocolException) {
            CodexAppServerDynamicToolEvent.MalformedCall(request.id, request.params, cause)
        }
    }

class CodexAppServerDynamicToolApi(private val connection: CodexAppServerConnection) {
    val events: Flow<CodexAppServerDynamicToolEvent> = connection.events.toCodexAppServerDynamicToolEvents()

    suspend fun respond(
        requestId: JsonRpcId,
        contentItems: List<CodexAppServerDynamicToolOutputContentItem>,
        success: Boolean,
    ) {
        connection.respondServerRequestAfterReady(
            requestId,
            buildJsonObject {
                put("contentItems", JsonArray(contentItems.map { it.toJson() }))
                put("success", success)
            },
        )
    }
}

private fun JsonObject.dynamicToolCall(): CodexAppServerDynamicToolCallRequest =
    CodexAppServerDynamicToolCallRequest(
        threadId = dynamicToolString("threadId"),
        turnId = dynamicToolString("turnId"),
        callId = dynamicToolString("callId"),
        namespace = dynamicToolOptionalString("namespace"),
        tool = dynamicToolString("tool"),
        arguments = this["arguments"] ?: dynamicToolMalformed("arguments is required"),
        rawParams = this,
    )

private fun CodexAppServerDynamicToolOutputContentItem.toJson(): JsonObject = when (this) {
    is CodexAppServerDynamicToolOutputContentItem.Text -> buildJsonObject {
        put("type", "inputText")
        put("text", text)
    }
    is CodexAppServerDynamicToolOutputContentItem.Image -> buildJsonObject {
        put("type", "inputImage")
        put("imageUrl", imageUrl)
    }
    is CodexAppServerDynamicToolOutputContentItem.Audio -> buildJsonObject {
        put("type", "inputAudio")
        put("audioUrl", audioUrl)
    }
}

private fun JsonObject.dynamicToolString(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: dynamicToolMalformed("$key must be a non-blank string")

private fun JsonObject.dynamicToolStringAllowEmpty(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: dynamicToolMalformed("$key must be a string")

private fun JsonObject.dynamicToolOptionalString(key: String): String? {
    val value = this[key] ?: return null
    if (value === JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: dynamicToolMalformed("$key must be a string or null")
}

private fun dynamicToolMalformed(message: String): Nothing =
    throw CodexAppServerDynamicToolProtocolException(message)

class CodexAppServerDynamicToolProtocolException(message: String) : Exception(message)

internal fun codexDynamicToolsFingerprint(
    specs: List<CodexAppServerDynamicToolSpec>?,
): String? {
    val populated = specs?.takeIf { it.isNotEmpty() } ?: return null
    val canonical = JsonArray(populated.sortedBy { it.name }.map(CodexAppServerDynamicToolSpec::toJson)).toString()
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private val DYNAMIC_TOOL_NAME = Regex("^[A-Za-z0-9_-]{1,64}$")
private const val DYNAMIC_TOOL_CALL_METHOD = "item/tool/call"
