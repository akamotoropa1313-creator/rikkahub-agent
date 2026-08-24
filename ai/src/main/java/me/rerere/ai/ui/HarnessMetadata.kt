package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/**
 * Identifies the agent harness that produced a message and carries optional usage dimensions
 * that are not represented by the provider-neutral [me.rerere.ai.core.TokenUsage] type.
 *
 * [id] is deliberately an open string rather than an enum. New harness integrations can start
 * writing their own stable id without changing the message schema or the statistics UI.
 */
@Serializable
data class HarnessMetadata(
    val id: String,
    val runId: String? = null,
    val reasoningOutputTokens: Long = 0L,
    val cacheWriteInputTokens: Long? = null,
)
