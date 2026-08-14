package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed interface CodexAppServerItemSnapshot {
    val id: String
    val type: String
    val raw: JsonObject

    data class AgentMessage(
        override val id: String,
        val text: String,
        override val raw: JsonObject,
    ) : CodexAppServerItemSnapshot { override val type = "agentMessage" }

    data class Reasoning(
        override val id: String,
        val summary: List<String>,
        val content: List<String>,
        override val raw: JsonObject,
    ) : CodexAppServerItemSnapshot { override val type = "reasoning" }

    data class Other(
        override val id: String,
        override val type: String,
        override val raw: JsonObject,
    ) : CodexAppServerItemSnapshot
}

sealed interface CodexAppServerTurnEvent {
    val rawParams: JsonElement?

    data class TurnStarted(val threadId: String, val turn: CodexAppServerTurnSnapshot, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class TurnCompleted(val threadId: String, val turn: CodexAppServerTurnSnapshot, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class ItemStarted(val item: CodexAppServerItemSnapshot, val threadId: String, val turnId: String, val startedAtMs: Long, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class ItemCompleted(val item: CodexAppServerItemSnapshot, val threadId: String, val turnId: String, val completedAtMs: Long, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class AgentMessageDelta(val threadId: String, val turnId: String, val itemId: String, val delta: String, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class ReasoningSummaryTextDelta(val threadId: String, val turnId: String, val itemId: String, val delta: String, val summaryIndex: Long, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class ReasoningSummaryPartAdded(val threadId: String, val turnId: String, val itemId: String, val summaryIndex: Long, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class ReasoningTextDelta(val threadId: String, val turnId: String, val itemId: String, val delta: String, val contentIndex: Long, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class MalformedNotification(val method: String, override val rawParams: JsonElement?, val cause: Throwable) : CodexAppServerTurnEvent
}

private val turnEventMethods = setOf(
    "turn/started", "turn/completed", "item/started", "item/completed",
    "item/agentMessage/delta", "item/reasoning/summaryTextDelta",
    "item/reasoning/summaryPartAdded", "item/reasoning/textDelta",
)

/** Preserves source arrival order and converts each malformed known notification into a value. */
fun Flow<CodexAppServerEvent>.toCodexAppServerTurnEvents(): Flow<CodexAppServerTurnEvent> =
    mapNotNull { event ->
        val notification = event as? CodexAppServerEvent.UnknownNotification ?: return@mapNotNull null
        if (notification.method !in turnEventMethods) return@mapNotNull null
        try {
            decodeTurnEvent(notification.method, notification.params)
        } catch (cause: Throwable) {
            CodexAppServerTurnEvent.MalformedNotification(notification.method, notification.params, cause)
        }
    }

private fun decodeTurnEvent(method: String, value: JsonElement?): CodexAppServerTurnEvent {
    val p = value as? JsonObject ?: malformed("$method params must be an object")
    return when (method) {
        "turn/started" -> CodexAppServerTurnEvent.TurnStarted(p.string("threadId"), p.turn(), p)
        "turn/completed" -> CodexAppServerTurnEvent.TurnCompleted(p.string("threadId"), p.turn(), p)
        "item/started" -> CodexAppServerTurnEvent.ItemStarted(p.item(), p.string("threadId"), p.string("turnId"), p.long("startedAtMs"), p)
        "item/completed" -> CodexAppServerTurnEvent.ItemCompleted(p.item(), p.string("threadId"), p.string("turnId"), p.long("completedAtMs"), p)
        "item/agentMessage/delta" -> CodexAppServerTurnEvent.AgentMessageDelta(p.string("threadId"), p.string("turnId"), p.string("itemId"), p.string("delta"), p)
        "item/reasoning/summaryTextDelta" -> CodexAppServerTurnEvent.ReasoningSummaryTextDelta(p.string("threadId"), p.string("turnId"), p.string("itemId"), p.string("delta"), p.long("summaryIndex"), p)
        "item/reasoning/summaryPartAdded" -> CodexAppServerTurnEvent.ReasoningSummaryPartAdded(p.string("threadId"), p.string("turnId"), p.string("itemId"), p.long("summaryIndex"), p)
        "item/reasoning/textDelta" -> CodexAppServerTurnEvent.ReasoningTextDelta(p.string("threadId"), p.string("turnId"), p.string("itemId"), p.string("delta"), p.long("contentIndex"), p)
        else -> error("unreachable")
    }
}

private fun JsonObject.turn() = decodeTurnSnapshot(this["turn"] as? JsonObject ?: malformed("turn must be an object"))
private fun JsonObject.item() = decodeItemSnapshot(this["item"] as? JsonObject ?: malformed("item must be an object"))
private fun JsonObject.string(key: String) = requiredString(key)
private fun JsonObject.long(key: String): Long {
    val primitive = this[key] as? JsonPrimitive
    return primitive?.takeUnless { it.isString }?.longOrNull
        ?: malformed("$key must be an integer")
}

internal fun decodeItemSnapshot(raw: JsonObject): CodexAppServerItemSnapshot {
    val id = raw.requiredString("item.id", "id")
    if (id.isBlank()) malformed("item.id must not be blank")
    return when (val type = raw.requiredString("item.type", "type")) {
        "agentMessage" -> CodexAppServerItemSnapshot.AgentMessage(id, raw.requiredString("item.text", "text"), raw)
        "reasoning" -> CodexAppServerItemSnapshot.Reasoning(id, raw.stringListOrEmpty("summary"), raw.stringListOrEmpty("content"), raw)
        else -> CodexAppServerItemSnapshot.Other(id, type, raw)
    }
}

private fun JsonObject.stringListOrEmpty(key: String): List<String> {
    val value = this[key] ?: return emptyList()
    val array = value as? JsonArray ?: malformed("item.$key must be an array")
    return array.mapIndexed { index, element ->
        val primitive = element as? JsonPrimitive
        primitive?.takeIf { it.isString }?.content ?: malformed("item.$key[$index] must be a string")
    }
}

private fun malformed(message: String): Nothing = throw CodexAppServerTurnProtocolException(message)
