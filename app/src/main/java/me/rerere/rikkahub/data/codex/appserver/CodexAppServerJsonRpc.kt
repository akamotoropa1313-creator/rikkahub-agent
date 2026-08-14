package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A JSON-RPC id. JSON-RPC allows string and integer ids; null ids are deliberately rejected. */
@Serializable(with = JsonRpcIdSerializer::class)
sealed interface JsonRpcId {
    @Serializable
    data class StringId(val value: String) : JsonRpcId

    @Serializable
    data class NumberId(val value: Long) : JsonRpcId
}

internal object JsonRpcIdSerializer : KSerializer<JsonRpcId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("JsonRpcId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JsonRpcId) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("JsonRpcId requires a JSON encoder")
        jsonEncoder.encodeJsonElement(
            when (value) {
                is JsonRpcId.StringId -> JsonPrimitive(value.value)
                is JsonRpcId.NumberId -> JsonPrimitive(value.value)
            }
        )
    }

    override fun deserialize(decoder: Decoder): JsonRpcId {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: throw SerializationException("JsonRpcId requires a JSON decoder")
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("JSON-RPC id must be a string or integer")
        if (primitive is JsonNull) {
            throw SerializationException("JSON-RPC id must be a string or integer")
        }
        if (primitive.isString) return JsonRpcId.StringId(primitive.content)
        if (primitive.booleanOrNull != null) {
            throw SerializationException("JSON-RPC id must be a string or integer")
        }
        return primitive.longOrNull?.let(JsonRpcId::NumberId)
            ?: throw SerializationException("JSON-RPC numeric id must be an integer")
    }
}

@Serializable
data class JsonRpcRequest(
    val id: JsonRpcId,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcNotification(
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val id: JsonRpcId,
    val result: JsonElement = JsonNull,
)

@Serializable
data class JsonRpcError(
    val code: Long,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class JsonRpcErrorResponse(
    val id: JsonRpcId,
    val error: JsonRpcError,
)

/** All valid messages which can arrive from either side of an App Server connection. */
sealed interface JsonRpcMessage {
    data class Request(val value: JsonRpcRequest) : JsonRpcMessage
    data class Notification(val value: JsonRpcNotification) : JsonRpcMessage
    data class Response(val value: JsonRpcResponse) : JsonRpcMessage
    data class ErrorResponse(val value: JsonRpcErrorResponse) : JsonRpcMessage
}

/**
 * Stateless JSON-RPC codec. Framing and I/O intentionally live outside this type so a future
 * stdio transport can feed it one complete JSON object per JSONL line and independently manage
 * process lifetime. Encoded values do not include the line delimiter; the transport owns it.
 */
class CodexAppServerJsonRpc(
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
) {
    fun encode(request: JsonRpcRequest): String = json.encodeToString(request)
    fun encode(notification: JsonRpcNotification): String = json.encodeToString(notification)
    fun encode(response: JsonRpcResponse): String = json.encodeToString(response)
    fun encode(response: JsonRpcErrorResponse): String = json.encodeToString(response)

    /** Never throws for untrusted protocol input. */
    fun decode(input: String): Result<JsonRpcMessage> = runCatching {
        val objectValue = json.parseToJsonElement(input) as? JsonObject
            ?: throw SerializationException("JSON-RPC message must be an object")
        validateOptionalVersion(objectValue)

        val hasMethod = "method" in objectValue
        val hasId = "id" in objectValue
        val hasResult = "result" in objectValue
        val hasError = "error" in objectValue

        when {
            hasMethod && (hasResult || hasError) ->
                throw SerializationException("JSON-RPC message cannot contain method and response fields")
            hasMethod && hasId -> JsonRpcMessage.Request(
                json.decodeFromJsonElement(JsonRpcRequest.serializer(), objectValue)
                    .also { requireMethod(it.method) }
            )
            hasMethod -> JsonRpcMessage.Notification(
                json.decodeFromJsonElement(JsonRpcNotification.serializer(), objectValue)
                    .also { requireMethod(it.method) }
            )
            !hasId -> throw SerializationException("JSON-RPC response is missing id")
            hasResult == hasError ->
                throw SerializationException("JSON-RPC response must contain exactly one of result or error")
            hasResult -> JsonRpcMessage.Response(
                json.decodeFromJsonElement(JsonRpcResponse.serializer(), objectValue)
            )
            else -> JsonRpcMessage.ErrorResponse(
                json.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), objectValue)
            )
        }
    }

    /** Codex omits jsonrpc on the wire, but accepting an explicit 2.0 aids interoperability. */
    private fun validateOptionalVersion(value: JsonObject) {
        val versionElement = value["jsonrpc"] ?: return
        val version = versionElement.jsonPrimitive.contentOrNull
        if (version != "2.0") {
            throw SerializationException("Unsupported JSON-RPC version: $version")
        }
    }

    private fun requireMethod(method: String) {
        require(method.isNotBlank()) { "JSON-RPC method must not be blank" }
    }
}
