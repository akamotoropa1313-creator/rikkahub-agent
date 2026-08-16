package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
