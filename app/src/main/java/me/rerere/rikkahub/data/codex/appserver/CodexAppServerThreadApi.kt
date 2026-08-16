package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Stable, deliberately small subset of the v2 `thread/start` parameters. */
data class CodexAppServerThreadStartParams(
    val model: String? = null,
    val modelProvider: String? = null,
    val cwd: String? = null,
    val config: Map<String, JsonElement>? = null,
    val serviceName: String? = null,
    val baseInstructions: String? = null,
    val developerInstructions: String? = null,
    val personality: CodexAppServerPersonality? = null,
    val ephemeral: Boolean? = null,
    val serviceTier: String? = null,
    val sandbox: CodexAppServerSandboxMode? = null,
    val approvalPolicy: CodexAppServerApprovalPolicy? = null,
)

/** Stable overrides shared by v2 `thread/resume`; the thread id is supplied separately. */
data class CodexAppServerThreadResumeParams(
    val model: String? = null,
    val modelProvider: String? = null,
    val cwd: String? = null,
    val config: Map<String, JsonElement>? = null,
    val baseInstructions: String? = null,
    val developerInstructions: String? = null,
    val personality: CodexAppServerPersonality? = null,
    val sandbox: CodexAppServerSandboxMode? = null,
    val approvalPolicy: CodexAppServerApprovalPolicy? = null,
)

enum class CodexAppServerPersonality(val wireValue: String) {
    NONE("none"),
    FRIENDLY("friendly"),
    PRAGMATIC("pragmatic"),
}

data class CodexAppServerThreadSnapshot(
    val id: String,
    /** The complete server thread object, including turns not modeled in Stage 5. */
    val raw: JsonObject,
    val turns: List<CodexAppServerHistoryTurn> = emptyList(),
) {
    val sessionId: String? = raw.optionalThreadString("sessionId")
    val forkedFromId: String? = raw.optionalThreadString("forkedFromId")
    val parentThreadId: String? = raw.optionalThreadString("parentThreadId")
    val preview: String? = raw.optionalThreadString("preview")
    val ephemeral: Boolean? = raw.optionalThreadBoolean("ephemeral")
    val modelProvider: String? = raw.optionalThreadString("modelProvider")
    val createdAt: Long? = raw.optionalThreadLong("createdAt")
    val updatedAt: Long? = raw.optionalThreadLong("updatedAt")
    val recencyAt: Long? = raw.optionalThreadLong("recencyAt")
    val cwd: String? = raw.optionalThreadString("cwd")
    val cliVersion: String? = raw.optionalThreadString("cliVersion")
    val name: String? = raw.optionalThreadString("name")
    val status: CodexAppServerThreadStatus? = raw["status"]?.let(::decodeThreadStatus)
}

data class CodexAppServerHistoryTurn(val turn: CodexAppServerTurnSnapshot, val items: List<CodexAppServerItemSnapshot>)

sealed interface CodexAppServerThreadStatus {
    val wireValue: String
    data object NotLoaded : CodexAppServerThreadStatus { override val wireValue = "notLoaded" }
    data object Idle : CodexAppServerThreadStatus { override val wireValue = "idle" }
    data object SystemError : CodexAppServerThreadStatus { override val wireValue = "systemError" }
    data class Active(val activeFlags: List<String>) : CodexAppServerThreadStatus { override val wireValue = "active" }
    data class Unknown(override val wireValue: String, val raw: JsonElement) : CodexAppServerThreadStatus
}

data class CodexAppServerThreadListPage(
    val data: List<CodexAppServerThreadSnapshot>, val nextCursor: String?, val backwardsCursor: String?, val raw: JsonObject,
)

data class CodexAppServerThreadOpenResult(
    val thread: CodexAppServerThreadSnapshot,
    val model: String,
    val modelProvider: String,
    val cwd: String,
    /** The complete result object. [thread.raw] references its thread value without copying it. */
    val rawResult: JsonObject,
)

class CodexAppServerThreadApi(private val connection: CodexAppServerConnection) {
    suspend fun listThreads(cursor: String? = null, limit: Int = 20, cwd: String? = null, searchTerm: String? = null,
        timeout: Duration = 30.seconds): CodexAppServerThreadListPage {
        require(limit in 1..100)
        val params = buildMap<String, JsonElement> {
            cursor?.let { put("cursor", JsonPrimitive(it)) }
            put("limit", JsonPrimitive(limit)); put("sortKey", JsonPrimitive("recency_at"))
            put("sortDirection", JsonPrimitive("desc")); cwd?.let { put("cwd", JsonPrimitive(it)) }
            put("archived", JsonPrimitive(false)); searchTerm?.takeIf(String::isNotBlank)?.let { put("searchTerm", JsonPrimitive(it)) }
        }
        return decodeThreadList(connection.sendRequestAfterReady("thread/list", JsonObject(params), timeout))
    }

    suspend fun readThread(threadId: String, timeout: Duration = 30.seconds): CodexAppServerThreadSnapshot {
        require(threadId.isNotBlank())
        val value = connection.sendRequestAfterReady("thread/read", JsonObject(mapOf(
            "threadId" to JsonPrimitive(threadId), "includeTurns" to JsonPrimitive(true))), timeout)
        val result = value as? JsonObject ?: throw CodexAppServerThreadProtocolException("thread/read result must be an object")
        val thread = decodeThread(result["thread"] as? JsonObject
            ?: throw CodexAppServerThreadProtocolException("thread/read result must contain a thread object"))
        if (thread.id != threadId) throw CodexAppServerThreadIdMismatchException(threadId, thread.id)
        return thread
    }
    suspend fun startThread(
        params: CodexAppServerThreadStartParams = CodexAppServerThreadStartParams(),
        timeout: Duration = 30.seconds,
    ): CodexAppServerThreadOpenResult = decodeResult(
        connection.sendRequestAfterReady("thread/start", params.toJson(), timeout),
    )

    suspend fun resumeThread(
        threadId: String,
        overrides: CodexAppServerThreadResumeParams = CodexAppServerThreadResumeParams(),
        timeout: Duration = 30.seconds,
    ): CodexAppServerThreadOpenResult {
        require(threadId.isNotBlank()) { "threadId must not be blank" }
        val result = decodeResult(
            connection.sendRequestAfterReady("thread/resume", overrides.toJson(threadId), timeout),
        )
        if (result.thread.id != threadId) {
            throw CodexAppServerThreadIdMismatchException(threadId, result.thread.id)
        }
        return result
    }

    private fun decodeResult(value: JsonElement): CodexAppServerThreadOpenResult {
        val result = value as? JsonObject
            ?: throw CodexAppServerThreadProtocolException("thread result must be an object")
        val thread = result["thread"] as? JsonObject
            ?: throw CodexAppServerThreadProtocolException("thread result must contain a thread object")
        val idElement = thread["id"] as? JsonPrimitive
            ?: throw CodexAppServerThreadProtocolException("thread.id must be a string")
        val id = idElement.takeIf { it.isString }?.contentOrNull
            ?: throw CodexAppServerThreadProtocolException("thread.id must be a string")
        if (id.isBlank()) throw CodexAppServerThreadProtocolException("thread.id must not be blank")
        return CodexAppServerThreadOpenResult(
            thread = CodexAppServerThreadSnapshot(id, thread),
            model = result.requiredString("model"),
            modelProvider = result.requiredString("modelProvider"),
            cwd = result.requiredString("cwd"),
            rawResult = result,
        )
    }
}

internal fun decodeThreadList(value: JsonElement): CodexAppServerThreadListPage {
    val raw = value as? JsonObject ?: throw CodexAppServerThreadProtocolException("thread/list result must be an object")
    val data = raw["data"] as? JsonArray ?: throw CodexAppServerThreadProtocolException("thread/list data must be an array")
    return CodexAppServerThreadListPage(data.mapIndexed { i, e -> decodeThread(e as? JsonObject
        ?: throw CodexAppServerThreadProtocolException("thread/list data[$i] must be an object")) },
        raw.optionalThreadString("nextCursor"), raw.optionalThreadString("backwardsCursor"), raw)
}

internal fun decodeThread(raw: JsonObject): CodexAppServerThreadSnapshot {
    val id = raw.requiredThreadString("id")
    if (id.isBlank()) throw CodexAppServerThreadProtocolException("thread.id must not be blank")
    return CodexAppServerThreadSnapshot(id, raw, decodeHistoryTurns(raw)) // construction strictly validates every known field
}

private fun decodeHistoryTurns(raw: JsonObject): List<CodexAppServerHistoryTurn> = raw["turns"]?.let { value ->
    (value as? JsonArray ?: throw CodexAppServerThreadProtocolException("thread.turns must be an array"))
        .mapIndexed { index, element ->
            val turnRaw = element as? JsonObject ?: throw CodexAppServerThreadProtocolException("thread.turns[$index] must be an object")
            val snapshot = decodeTurnSnapshot(turnRaw)
            val items = turnRaw["items"]?.let { itemsValue ->
                (itemsValue as? JsonArray ?: throw CodexAppServerThreadProtocolException("thread.turns[$index].items must be an array"))
                    .mapIndexed { itemIndex, item -> decodeItemSnapshot(item as? JsonObject
                        ?: throw CodexAppServerThreadProtocolException("thread.turns[$index].items[$itemIndex] must be an object")) }
            }.orEmpty()
            CodexAppServerHistoryTurn(snapshot, items)
        }
}.orEmpty()

private fun decodeThreadStatus(value: JsonElement): CodexAppServerThreadStatus {
    if (value is JsonPrimitive && value.isString) return when (value.content) {
        "notLoaded" -> CodexAppServerThreadStatus.NotLoaded; "idle" -> CodexAppServerThreadStatus.Idle
        "systemError" -> CodexAppServerThreadStatus.SystemError
        else -> CodexAppServerThreadStatus.Unknown(value.content, value)
    }
    val objectValue = value as? JsonObject ?: throw CodexAppServerThreadProtocolException("thread.status must be a string or object")
    val type = objectValue.requiredThreadString("type")
    if (type != "active") return when (type) {
        "notLoaded" -> CodexAppServerThreadStatus.NotLoaded
        "idle" -> CodexAppServerThreadStatus.Idle
        "systemError" -> CodexAppServerThreadStatus.SystemError
        else -> CodexAppServerThreadStatus.Unknown(type, objectValue)
    }
    val flags = objectValue["activeFlags"] as? JsonArray
        ?: throw CodexAppServerThreadProtocolException("thread.status.activeFlags must be an array")
    return CodexAppServerThreadStatus.Active(flags.mapIndexed { i, flag ->
        (flag as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: throw CodexAppServerThreadProtocolException("thread.status.activeFlags[$i] must be a string")
    })
}

private fun JsonObject.requiredThreadString(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    ?: throw CodexAppServerThreadProtocolException("thread.$key must be a string")
private fun JsonObject.optionalThreadString(key: String): String? { val v=this[key]?:return null; if(v===JsonNull)return null; return (v as? JsonPrimitive)?.takeIf{it.isString}?.contentOrNull ?: throw CodexAppServerThreadProtocolException("thread.$key must be a string or null") }
private fun JsonObject.optionalThreadLong(key: String): Long? { val v=this[key]?:return null; if(v===JsonNull)return null; return (v as? JsonPrimitive)?.takeUnless{it.isString}?.longOrNull ?: throw CodexAppServerThreadProtocolException("thread.$key must be an integer or null") }
private fun JsonObject.optionalThreadBoolean(key: String): Boolean? { val v=this[key]?:return null; if(v===JsonNull)return null; return (v as? JsonPrimitive)?.takeUnless{it.isString}?.content?.let { if(it=="true") true else if(it=="false") false else null } ?: throw CodexAppServerThreadProtocolException("thread.$key must be a boolean or null") }

private fun JsonObject.requiredString(name: String): String {
    val value = this[name]
        ?: throw CodexAppServerThreadProtocolException("thread result is missing $name")
    val primitive = value as? JsonPrimitive
        ?: throw CodexAppServerThreadProtocolException("$name must be a string")
    return primitive.takeIf { it.isString }?.contentOrNull
        ?: throw CodexAppServerThreadProtocolException("$name must be a string")
}

private fun CodexAppServerThreadStartParams.toJson() = buildMap<String, JsonElement> {
    putOptional("model", model); putOptional("modelProvider", modelProvider); putOptional("cwd", cwd)
    config?.let { put("config", JsonObject(it)) }; putOptional("serviceName", serviceName)
    putOptional("baseInstructions", baseInstructions); putOptional("developerInstructions", developerInstructions)
    personality?.let { put("personality", JsonPrimitive(it.wireValue)) }
    ephemeral?.let { put("ephemeral", JsonPrimitive(it)) }
    putOptional("serviceTier", serviceTier)
    sandbox?.let { put("sandbox", JsonPrimitive(it.wireValue)) }
    approvalPolicy?.let { put("approvalPolicy", JsonPrimitive(it.wireValue)) }
}.let(::JsonObject)

private fun CodexAppServerThreadResumeParams.toJson(threadId: String) = buildMap<String, JsonElement> {
    put("threadId", JsonPrimitive(threadId)); putOptional("model", model)
    putOptional("modelProvider", modelProvider); putOptional("cwd", cwd)
    config?.let { put("config", JsonObject(it)) }; putOptional("baseInstructions", baseInstructions)
    putOptional("developerInstructions", developerInstructions)
    personality?.let { put("personality", JsonPrimitive(it.wireValue)) }
    sandbox?.let { put("sandbox", JsonPrimitive(it.wireValue)) }
    approvalPolicy?.let { put("approvalPolicy", JsonPrimitive(it.wireValue)) }
}.let(::JsonObject)

private fun MutableMap<String, JsonElement>.putOptional(name: String, value: String?) {
    value?.let { put(name, JsonPrimitive(it)) }
}

open class CodexAppServerThreadProtocolException(message: String) : SerializationException(message)

class CodexAppServerThreadIdMismatchException(val requestedId: String, val responseId: String) :
    CodexAppServerThreadProtocolException("resumed thread id mismatch: requested '$requestedId', received '$responseId'")
