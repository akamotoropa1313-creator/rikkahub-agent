package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface CodexAppServerTurnInput {
    data class Text(val text: String) : CodexAppServerTurnInput
}

enum class CodexAppServerReasoningSummary(val wireValue: String) {
    AUTO("auto"),
    CONCISE("concise"),
    DETAILED("detailed"),
    NONE("none"),
}

/** Stable, non-experimental subset of the v2 `turn/start` overrides. */
data class CodexAppServerTurnStartParams(
    val clientUserMessageId: String? = null,
    val cwd: String? = null,
    val model: String? = null,
    /** Kept open as the protocol intentionally accepts string effort values. */
    val effort: String? = null,
    val summary: CodexAppServerReasoningSummary? = null,
    val personality: CodexAppServerPersonality? = null,
    val outputSchema: JsonElement? = null,
)

sealed interface CodexAppServerTurnStatus {
    val wireValue: String

    data object Completed : CodexAppServerTurnStatus { override val wireValue = "completed" }
    data object Interrupted : CodexAppServerTurnStatus { override val wireValue = "interrupted" }
    data object Failed : CodexAppServerTurnStatus { override val wireValue = "failed" }
    data object InProgress : CodexAppServerTurnStatus { override val wireValue = "inProgress" }
    data class Unknown(override val wireValue: String) : CodexAppServerTurnStatus
}

data class CodexAppServerTurnSnapshot(
    val id: String,
    val status: CodexAppServerTurnStatus,
    val raw: JsonObject,
)

data class CodexAppServerTurnStartResult(
    val turn: CodexAppServerTurnSnapshot,
    val rawResult: JsonObject,
)

class CodexAppServerTurnApi(private val connection: CodexAppServerConnection) {
    /**
     * A cold, ordered projection of [CodexAppServerConnection.events]. It neither consumes nor
     * replaces that lossless protocol stream. The source has no replay, so callers that cannot
     * miss early streaming notifications must start collecting [events] before [startTurn].
     */
    val events = connection.events.toCodexAppServerTurnEvents()

    suspend fun startTurn(
        threadId: String,
        input: List<CodexAppServerTurnInput>,
        params: CodexAppServerTurnStartParams = CodexAppServerTurnStartParams(),
        timeout: Duration = 30.seconds,
    ): CodexAppServerTurnStartResult {
        require(threadId.isNotBlank()) { "threadId must not be blank" }
        val result = connection.sendRequestAfterReady(
            method = "turn/start",
            params = params.toJson(threadId, input),
            timeout = timeout,
        )
        return decodeTurnStartResult(result)
    }
}

internal fun decodeTurnStartResult(value: JsonElement): CodexAppServerTurnStartResult {
    val result = value as? JsonObject
        ?: throw CodexAppServerTurnProtocolException("turn/start result must be an object")
    val turn = result["turn"] as? JsonObject
        ?: throw CodexAppServerTurnProtocolException("turn/start result must contain a turn object")
    return CodexAppServerTurnStartResult(decodeTurnSnapshot(turn), result)
}

internal fun decodeTurnSnapshot(raw: JsonObject): CodexAppServerTurnSnapshot {
    val id = raw.requiredString("turn.id", "id")
    if (id.isBlank()) throw CodexAppServerTurnProtocolException("turn.id must not be blank")
    return CodexAppServerTurnSnapshot(
        id = id,
        status = decodeTurnStatus(raw.requiredString("turn.status", "status")),
        raw = raw,
    )
}

internal fun decodeTurnStatus(value: String): CodexAppServerTurnStatus = when (value) {
    "completed" -> CodexAppServerTurnStatus.Completed
    "interrupted" -> CodexAppServerTurnStatus.Interrupted
    "failed" -> CodexAppServerTurnStatus.Failed
    "inProgress" -> CodexAppServerTurnStatus.InProgress
    else -> CodexAppServerTurnStatus.Unknown(value)
}

private fun CodexAppServerTurnStartParams.toJson(
    threadId: String,
    input: List<CodexAppServerTurnInput>,
) = buildMap<String, JsonElement> {
    put("threadId", JsonPrimitive(threadId))
    put("input", JsonArray(input.map { it.toJson() }))
    clientUserMessageId?.let { put("clientUserMessageId", JsonPrimitive(it)) }
    cwd?.let { put("cwd", JsonPrimitive(it)) }
    model?.let { put("model", JsonPrimitive(it)) }
    effort?.let { put("effort", JsonPrimitive(it)) }
    summary?.let { put("summary", JsonPrimitive(it.wireValue)) }
    personality?.let { put("personality", JsonPrimitive(it.wireValue)) }
    outputSchema?.let { put("outputSchema", it) }
}.let(::JsonObject)

private fun CodexAppServerTurnInput.toJson(): JsonObject = when (this) {
    is CodexAppServerTurnInput.Text -> JsonObject(
        mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text)),
    )
}

internal fun JsonObject.requiredString(label: String, key: String = label): String {
    val primitive = this[key] as? JsonPrimitive
        ?: throw CodexAppServerTurnProtocolException("$label must be a string")
    return primitive.takeIf { it.isString }?.contentOrNull
        ?: throw CodexAppServerTurnProtocolException("$label must be a string")
}

open class CodexAppServerTurnProtocolException(message: String) : SerializationException(message)
