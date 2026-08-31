package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface CodexAppServerTurnInput {
    data class Text(val text: String) : CodexAppServerTurnInput
    data class Image(val url: String) : CodexAppServerTurnInput { init { require(url.isNotBlank()) } }
    data class LocalImage(val path: String) : CodexAppServerTurnInput { init { require(path.startsWith('/')) } }
    data class Audio(val url: String) : CodexAppServerTurnInput { init { require(url.isNotBlank()) } }
    data class LocalAudio(val path: String) : CodexAppServerTurnInput { init { require(path.startsWith('/')) } }
    data class Skill(val name: String, val path: String) : CodexAppServerTurnInput {
        init {
            require(name.isNotBlank()) { "skill name must not be blank" }
            require(path.isNotBlank()) { "skill path must not be blank" }
        }
    }
}

data class CodexAppServerExplicitSkillInvocation(
    val input: List<CodexAppServerTurnInput>,
)

/** Builds the official text marker plus one typed input item for every selected skill. */
fun explicitSkillsInvocation(
    skills: List<Pair<String, String>>,
    prompt: String = "",
): CodexAppServerExplicitSkillInvocation {
    require(skills.isNotEmpty()) { "at least one skill is required" }
    skills.forEach { (name, path) ->
        require(name.isNotBlank()) { "skill name must not be blank" }
        require(path.isNotBlank()) { "skill path must not be blank" }
    }
    val distinctSkills = skills.distinctBy { it.second }
    val text = buildString {
        distinctSkills.forEachIndexed { index, (name, _) ->
            if (index > 0) append(' ')
            append('$').append(name)
        }
        if (prompt.isNotBlank()) append(' ').append(prompt)
    }
    return CodexAppServerExplicitSkillInvocation(
        buildList {
            add(CodexAppServerTurnInput.Text(text))
            distinctSkills.forEach { (name, path) ->
                add(CodexAppServerTurnInput.Skill(name, path))
            }
        },
    )
}

/** Backward-compatible one-skill convenience wrapper. */
fun explicitSkillInvocation(name: String, path: String, prompt: String = ""): CodexAppServerExplicitSkillInvocation =
    explicitSkillsInvocation(listOf(name to path), prompt)

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
    val serviceTier: String? = null,
    val approvalPolicy: CodexAppServerApprovalPolicy? = null,
    val sandboxPolicy: CodexAppServerSandboxPolicy? = null,
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
    val error: CodexAppServerTurnError?,
    /** Unix timestamp seconds, exactly as reported by App Server. */
    val startedAt: Long?,
    /** Unix timestamp seconds, exactly as reported by App Server. */
    val completedAt: Long?,
    val durationMs: Long?,
    val itemsView: CodexAppServerTurnItemsView,
    val raw: JsonObject,
)

sealed interface CodexAppServerTurnItemsView {
    data object NotLoaded : CodexAppServerTurnItemsView
    data object Summary : CodexAppServerTurnItemsView
    data object Full : CodexAppServerTurnItemsView
    data class Unknown(val rawValue: String) : CodexAppServerTurnItemsView
}

sealed interface CodexAppServerErrorInfo {
    val raw: JsonElement
    data class Known(val category: String, val httpStatusCode: Int?, override val raw: JsonElement) : CodexAppServerErrorInfo
    data class Unknown(override val raw: JsonElement) : CodexAppServerErrorInfo
}

data class CodexAppServerTurnError(val message: String, val codexErrorInfo: CodexAppServerErrorInfo?, val additionalDetails: String?, val raw: JsonObject)

data class CodexAppServerTurnStartResult(
    val turn: CodexAppServerTurnSnapshot,
    val rawResult: JsonObject,
)

data class CodexAppServerTurnInterruptResult(
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

    suspend fun interruptTurn(
        threadId: String,
        turnId: String,
        timeout: Duration = 30.seconds,
    ): CodexAppServerTurnInterruptResult {
        require(threadId.isNotBlank()) { "threadId must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        val result = connection.sendRequestAfterReady(
            method = "turn/interrupt",
            params = JsonObject(
                mapOf(
                    "threadId" to JsonPrimitive(threadId),
                    "turnId" to JsonPrimitive(turnId),
                ),
            ),
            timeout = timeout,
        )
        return decodeTurnInterruptResult(result)
    }
}

internal fun decodeTurnInterruptResult(value: JsonElement): CodexAppServerTurnInterruptResult {
    val result = value as? JsonObject
        ?: throw CodexAppServerTurnProtocolException("turn/interrupt result must be an object")
    return CodexAppServerTurnInterruptResult(result)
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
        error = raw.optionalTurnError(),
        startedAt = raw.optionalTurnLong("startedAt"),
        completedAt = raw.optionalTurnLong("completedAt"),
        durationMs = raw.optionalTurnLong("durationMs"),
        // App Server's serde default is Full; this also matches payloads from servers that
        // predate the explicit itemsView field and returned the complete items array.
        itemsView = decodeItemsView(raw.optionalTurnString("itemsView") ?: "full"),
        raw = raw,
    )
}

private fun decodeItemsView(value: String) = when (value) {
    "notLoaded" -> CodexAppServerTurnItemsView.NotLoaded
    "summary" -> CodexAppServerTurnItemsView.Summary
    "full" -> CodexAppServerTurnItemsView.Full
    else -> CodexAppServerTurnItemsView.Unknown(value)
}

private fun JsonObject.optionalTurnLong(key: String): Long? {
    val value = this[key] ?: return null
    if (value === JsonNull) return null
    return (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?: throw CodexAppServerTurnProtocolException("turn.$key must be an integer or null")
}
private fun JsonObject.optionalTurnString(key: String): String? {
    val value = this[key] ?: return null
    if (value === JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: throw CodexAppServerTurnProtocolException("turn.$key must be a string or null")
}
private fun JsonObject.optionalTurnError(): CodexAppServerTurnError? {
    val value = this["error"] ?: return null
    if (value === JsonNull) return null
    val error = value as? JsonObject ?: throw CodexAppServerTurnProtocolException("turn.error must be an object or null")
    val info = error["codexErrorInfo"]?.takeUnless { it === JsonNull }?.let(::decodeErrorInfo)
    return CodexAppServerTurnError(error.requiredString("turn.error.message", "message"), info, error.optionalTurnString("additionalDetails"), error)
}

private val knownSimpleErrorCategories = setOf(
    "contextWindowExceeded",
    "sessionBudgetExceeded",
    "usageLimitExceeded",
    "serverOverloaded",
    "cyberPolicy",
    "internalServerError",
    "unauthorized",
    "badRequest",
    "threadRollbackFailed",
    "sandboxError",
    "other",
)
private val knownHttpErrorCategories = setOf(
    "httpConnectionFailed",
    "responseStreamConnectionFailed",
    "responseStreamDisconnected",
    "responseTooManyFailedAttempts",
)
private const val ACTIVE_TURN_NOT_STEERABLE = "activeTurnNotSteerable"

private fun decodeErrorInfo(raw: JsonElement): CodexAppServerErrorInfo = when (raw) {
    is JsonPrimitive -> {
        val category = raw.takeIf { it.isString }?.contentOrNull
            ?: return CodexAppServerErrorInfo.Unknown(raw)
        if (category in knownSimpleErrorCategories) CodexAppServerErrorInfo.Known(category, null, raw)
        else CodexAppServerErrorInfo.Unknown(raw)
    }
    is JsonObject -> {
        val tagged = raw.entries.singleOrNull() ?: return CodexAppServerErrorInfo.Unknown(raw)
        val category = tagged.key
        when {
            category in knownHttpErrorCategories -> {
                val payload = tagged.value as? JsonObject
                    ?: throw CodexAppServerTurnProtocolException("$category payload must be an object")
                val statusElement = payload["httpStatusCode"]
                    ?: throw CodexAppServerTurnProtocolException("$category.httpStatusCode must be present")
                val status = if (statusElement === JsonNull) null else
                    (statusElement as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                        ?: throw CodexAppServerTurnProtocolException("$category.httpStatusCode must be an integer or null")
                CodexAppServerErrorInfo.Known(category, status, raw)
            }
            category == ACTIVE_TURN_NOT_STEERABLE -> {
                if (tagged.value !is JsonObject) {
                    throw CodexAppServerTurnProtocolException("$category payload must be an object")
                }
                CodexAppServerErrorInfo.Known(category, null, raw)
            }
            else -> CodexAppServerErrorInfo.Unknown(raw)
        }
    }
    else -> CodexAppServerErrorInfo.Unknown(raw)
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
    serviceTier?.let { put("serviceTier", JsonPrimitive(it)) }
    approvalPolicy?.let { put("approvalPolicy", JsonPrimitive(it.runtimeWireValue)) }
    sandboxPolicy?.let { put("sandboxPolicy", it.toJson()) }
}.let(::JsonObject)

private fun CodexAppServerTurnInput.toJson(): JsonObject = when (this) {
    is CodexAppServerTurnInput.Text -> JsonObject(
        mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text)),
    )
    is CodexAppServerTurnInput.Skill -> JsonObject(
        mapOf(
            "type" to JsonPrimitive("skill"),
            "name" to JsonPrimitive(name),
            "path" to JsonPrimitive(path),
        ),
    )
    is CodexAppServerTurnInput.Image -> JsonObject(mapOf("type" to JsonPrimitive("image"), "url" to JsonPrimitive(url)))
    is CodexAppServerTurnInput.LocalImage -> JsonObject(mapOf("type" to JsonPrimitive("localImage"), "path" to JsonPrimitive(path)))
    is CodexAppServerTurnInput.Audio -> JsonObject(mapOf("type" to JsonPrimitive("audio"), "url" to JsonPrimitive(url)))
    is CodexAppServerTurnInput.LocalAudio -> JsonObject(mapOf("type" to JsonPrimitive("localAudio"), "path" to JsonPrimitive(path)))
}

internal fun JsonObject.requiredString(label: String, key: String = label): String {
    val primitive = this[key] as? JsonPrimitive
        ?: throw CodexAppServerTurnProtocolException("$label must be a string")
    return primitive.takeIf { it.isString }?.contentOrNull
        ?: throw CodexAppServerTurnProtocolException("$label must be a string")
}

open class CodexAppServerTurnProtocolException(message: String) : SerializationException(message)
