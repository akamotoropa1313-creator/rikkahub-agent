package me.rerere.rikkahub.data.codex.appserver

import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

data class CodexTokenUsageBreakdown(
    val totalTokens: Long,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    /** Null means that this (notably 0.144) server did not report the field. */
    val cacheWriteInputTokens: Long?,
    val outputTokens: Long,
    val reasoningOutputTokens: Long,
    val raw: JsonObject,
)

data class CodexThreadTokenUsage(
    val total: CodexTokenUsageBreakdown,
    val last: CodexTokenUsageBreakdown,
    val modelContextWindow: Long?,
    val raw: JsonObject,
)

sealed interface CodexAppServerTokenUsageEvent {
    data class Updated(val threadId: String, val turnId: String, val tokenUsage: CodexThreadTokenUsage, val rawParams: JsonObject) : CodexAppServerTokenUsageEvent
    data class MalformedNotification(val rawParams: JsonElement?, val cause: Throwable) : CodexAppServerTokenUsageEvent
}

fun Flow<CodexAppServerEvent>.toCodexAppServerTokenUsageEvents(): Flow<CodexAppServerTokenUsageEvent> = mapNotNull { event ->
    val notification = event as? CodexAppServerEvent.UnknownNotification ?: return@mapNotNull null
    if (notification.method != "thread/tokenUsage/updated") return@mapNotNull null
    try { decodeTokenUsageEvent(notification.params) }
    catch (failure: CodexAppServerTurnProtocolException) { CodexAppServerTokenUsageEvent.MalformedNotification(notification.params, failure) }
}

internal fun decodeTokenUsageEvent(value: JsonElement?): CodexAppServerTokenUsageEvent.Updated {
    val raw = value as? JsonObject ?: usageMalformed("params must be an object")
    val threadId = raw.usageString("threadId")
    val turnId = raw.usageString("turnId")
    val tokenUsage = raw["tokenUsage"] as? JsonObject ?: usageMalformed("tokenUsage must be an object")
    return CodexAppServerTokenUsageEvent.Updated(threadId, turnId, decodeThreadTokenUsage(tokenUsage), raw)
}

internal fun decodeThreadTokenUsage(raw: JsonObject) = CodexThreadTokenUsage(
    total = decodeBreakdown(raw["total"] as? JsonObject ?: usageMalformed("total must be an object"), "total"),
    last = decodeBreakdown(raw["last"] as? JsonObject ?: usageMalformed("last must be an object"), "last"),
    modelContextWindow = raw.usageNullableLong("modelContextWindow"),
    raw = raw,
)

private fun decodeBreakdown(raw: JsonObject, label: String) = CodexTokenUsageBreakdown(
    totalTokens = raw.usageLong("$label.totalTokens", "totalTokens"),
    inputTokens = raw.usageLong("$label.inputTokens", "inputTokens"),
    cachedInputTokens = raw.usageLong("$label.cachedInputTokens", "cachedInputTokens"),
    cacheWriteInputTokens = if ("cacheWriteInputTokens" in raw) raw.usageLong("$label.cacheWriteInputTokens", "cacheWriteInputTokens") else null,
    outputTokens = raw.usageLong("$label.outputTokens", "outputTokens"),
    reasoningOutputTokens = raw.usageLong("$label.reasoningOutputTokens", "reasoningOutputTokens"),
    raw = raw,
)

private fun JsonObject.usageString(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?.takeIf { it.isNotBlank() } ?: usageMalformed("$key must be a non-blank string")
private fun JsonObject.usageLong(label: String, key: String): Long = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    ?: usageMalformed("$label must be an integer")
private fun JsonObject.usageNullableLong(key: String): Long? {
    val value = this[key] ?: usageMalformed("$key must be present")
    if (value === JsonNull) return null
    return (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: usageMalformed("$key must be an integer or null")
}
private fun usageMalformed(message: String): Nothing = throw CodexAppServerTurnProtocolException(message)

data class CodexTokenUsageSnapshot(val turnId: String, val tokenUsage: CodexThreadTokenUsage)
data class CodexTokenUsageTelemetry(val latest: CodexTokenUsageSnapshot? = null, val warning: String? = null)

/** Session-owned, replay-safe collector. Construct this before thread/resume or thread/start. */
class CodexTokenUsageTracker(connection: CodexAppServerConnection, private val threadId: String) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(CodexTokenUsageTelemetry())
    val state: StateFlow<CodexTokenUsageTelemetry> = mutableState.asStateFlow()
    private val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        connection.events.toCodexAppServerTokenUsageEvents().collect { event ->
            when (event) {
                is CodexAppServerTokenUsageEvent.Updated -> if (event.threadId == threadId) {
                    mutableState.value = CodexTokenUsageTelemetry(CodexTokenUsageSnapshot(event.turnId, event.tokenUsage))
                }
                is CodexAppServerTokenUsageEvent.MalformedNotification -> mutableState.value = mutableState.value.copy(
                    warning = event.cause.message ?: "Malformed token usage notification",
                )
            }
        }
    }
    override fun close() { collector.cancel(); scope.cancel() }
}
