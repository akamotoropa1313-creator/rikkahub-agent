package me.rerere.rikkahub.data.codex.appserver

import me.rerere.rikkahub.data.harness.AgentHarnessIds
import me.rerere.rikkahub.data.harness.AgentHarnessUsage

fun CodexTokenUsageSnapshot.toAgentHarnessUsage(): AgentHarnessUsage = AgentHarnessUsage(
    harnessId = AgentHarnessIds.CODEX,
    runId = turnId,
    inputTokens = tokenUsage.last.inputTokens,
    outputTokens = tokenUsage.last.outputTokens,
    cachedInputTokens = tokenUsage.last.cachedInputTokens,
    totalTokens = tokenUsage.last.totalTokens,
    reasoningOutputTokens = tokenUsage.last.reasoningOutputTokens,
    cacheWriteInputTokens = tokenUsage.last.cacheWriteInputTokens,
)
