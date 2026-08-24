package me.rerere.rikkahub.data.harness

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.HarnessMetadata
import me.rerere.ai.ui.UIMessage

/** Stable ids written into message JSON. Unknown ids remain valid and appear in Statistics. */
object AgentHarnessIds {
    const val CODEX = "codex"
}

/**
 * Provider-neutral usage emitted by one completed or updating harness run.
 *
 * A run is persisted on exactly one message. This keeps the existing global token aggregation
 * correct while allowing Statistics to group the same rows by [harnessId].
 */
data class AgentHarnessUsage(
    val harnessId: String,
    val runId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long = 0L,
    val totalTokens: Long = inputTokens + outputTokens,
    val reasoningOutputTokens: Long = 0L,
    val cacheWriteInputTokens: Long? = null,
)

fun UIMessage.withHarnessIdentity(harnessId: String, runId: String): UIMessage = copy(
    harness = harness
        ?.takeIf { it.id == harnessId && it.runId == runId }
        ?: HarnessMetadata(id = harnessId, runId = runId),
)

/**
 * Applies one run's latest usage to the last matching message and clears stale duplicates.
 * Returning the original list when no matching message exists makes early telemetry harmless;
 * the next text update can retry with the runtime's latest snapshot.
 */
fun List<UIMessage>.withHarnessUsage(usage: AgentHarnessUsage): List<UIMessage> {
    val targetIndex = indexOfLast {
        val metadata = it.harness
        metadata?.id == usage.harnessId && metadata.runId == usage.runId
    }
    if (targetIndex < 0) return this

    val tokenUsage = TokenUsage(
        promptTokens = usage.inputTokens.toTokenInt(),
        completionTokens = usage.outputTokens.toTokenInt(),
        cachedTokens = usage.cachedInputTokens.toTokenInt(),
        totalTokens = usage.totalTokens.toTokenInt(),
    )
    return mapIndexed { index, message ->
        val metadata = message.harness
        if (metadata?.id != usage.harnessId || metadata.runId != usage.runId) {
            message
        } else {
            val isTarget = index == targetIndex
            message.copy(
                usage = tokenUsage.takeIf { isTarget },
                harness = metadata.copy(
                    reasoningOutputTokens = usage.reasoningOutputTokens.takeIf { isTarget } ?: 0L,
                    cacheWriteInputTokens = usage.cacheWriteInputTokens.takeIf { isTarget },
                ),
            )
        }
    }
}

private fun Long.toTokenInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
