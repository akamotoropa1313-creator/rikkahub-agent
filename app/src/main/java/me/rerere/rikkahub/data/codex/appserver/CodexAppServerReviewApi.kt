package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Stable v2 native review targets. Delivery is deliberately not application-configurable. */
sealed interface CodexAppServerReviewTarget {
    data object UncommittedChanges : CodexAppServerReviewTarget
    data class BaseBranch(val branch: String) : CodexAppServerReviewTarget {
        init { require(branch.isNotBlank()) { "branch must not be blank" } }
    }
    data class Commit(val sha: String, val title: String? = null) : CodexAppServerReviewTarget {
        init { require(sha.isNotBlank()) { "sha must not be blank" } }
    }
    data class Custom(val instructions: String) : CodexAppServerReviewTarget {
        init { require(instructions.isNotBlank()) { "instructions must not be blank" } }
    }
}

data class CodexAppServerReviewStartResult(
    val turn: CodexAppServerTurnSnapshot,
    val reviewThreadId: String,
)

/** Native `review/start` on the existing connection and request-id allocator. */
class CodexAppServerReviewApi(private val connection: CodexAppServerConnection) {
    suspend fun startReview(
        threadId: String,
        target: CodexAppServerReviewTarget,
        timeout: Duration = 30.seconds,
    ): CodexAppServerReviewStartResult {
        require(threadId.isNotBlank()) { "threadId must not be blank" }
        val result = connection.sendRequestAfterReady(
            method = "review/start",
            params = JsonObject(mapOf(
                "threadId" to JsonPrimitive(threadId),
                "target" to target.toJson(),
                // Inline is an ownership invariant, never an optional/defaulted preference.
                "delivery" to JsonPrimitive("inline"),
            )),
            timeout = timeout,
        )
        return decodeReviewStartResult(result)
    }
}

private fun CodexAppServerReviewTarget.toJson(): JsonObject = when (this) {
    CodexAppServerReviewTarget.UncommittedChanges -> JsonObject(mapOf("type" to JsonPrimitive("uncommittedChanges")))
    is CodexAppServerReviewTarget.BaseBranch -> JsonObject(mapOf("type" to JsonPrimitive("baseBranch"), "branch" to JsonPrimitive(branch)))
    is CodexAppServerReviewTarget.Commit -> JsonObject(buildMap {
        put("type", JsonPrimitive("commit")); put("sha", JsonPrimitive(sha))
        // Upstream's Option + skip_serializing_if semantics omit None rather than sending null.
        title?.let { put("title", JsonPrimitive(it)) }
    })
    is CodexAppServerReviewTarget.Custom -> JsonObject(mapOf("type" to JsonPrimitive("custom"), "instructions" to JsonPrimitive(instructions)))
}

internal fun decodeReviewStartResult(value: JsonElement): CodexAppServerReviewStartResult {
    val result = value as? JsonObject
        ?: throw CodexAppServerTurnProtocolException("review/start result must be an object")
    val turn = result["turn"] as? JsonObject
        ?: throw CodexAppServerTurnProtocolException("review/start result must contain a turn object")
    val reviewThreadId = result.requiredString("review/start.reviewThreadId", "reviewThreadId")
    if (reviewThreadId.isBlank()) throw CodexAppServerTurnProtocolException("reviewThreadId must not be blank")
    return CodexAppServerReviewStartResult(decodeTurnSnapshot(turn), reviewThreadId)
}
