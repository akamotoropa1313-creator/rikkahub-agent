package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerTurnEventTest {
    @Test
    fun `all lifecycle and streaming notifications map in arrival order`() {
        runBlocking {
            val source = MutableSharedFlow<CodexAppServerEvent>(); val events = mutableListOf<CodexAppServerTurnEvent>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { source.toCodexAppServerTurnEvents().collect { events += it } }
            emit(source, "turn/started", turnParams("inProgress")); emit(source, "item/started", itemParams(agent(""), "startedAtMs", 10))
            emit(source, "item/agentMessage/delta", delta("Hel")); emit(source, "item/agentMessage/delta", delta("lo!"))
            emit(source, "item/completed", itemParams(agent("Hello, final!"), "completedAtMs", 11))
            emit(source, "item/started", itemParams(reasoning(emptyList(), emptyList()), "startedAtMs", 12))
            emit(source, "item/reasoning/summaryPartAdded", streamIndex("summaryIndex", 0))
            emit(source, "item/reasoning/summaryTextDelta", streamIndex("summaryIndex", 0, "draft"))
            emit(source, "item/reasoning/textDelta", streamIndex("contentIndex", 0, "raw draft"))
            val finalReasoning = reasoning(listOf("Final summary"), listOf("Final raw")); emit(source, "item/completed", itemParams(finalReasoning, "completedAtMs", 13))
            emit(source, "turn/completed", turnParams("completed"))
            assertEquals(11, events.size)
            assertEquals("Hello, final!", ((events[4] as CodexAppServerTurnEvent.ItemCompleted).item as CodexAppServerItemSnapshot.AgentMessage).text)
            val reasoning = (events[9] as CodexAppServerTurnEvent.ItemCompleted).item as CodexAppServerItemSnapshot.Reasoning
            assertEquals(listOf("Final summary"), reasoning.summary); assertEquals(listOf("Final raw"), reasoning.content); assertSame(finalReasoning, reasoning.raw)
            assertEquals(CodexAppServerTurnStatus.Completed, (events.last() as CodexAppServerTurnEvent.TurnCompleted).turn.status)
            job.cancelAndJoin()
        }
    }

    @Test
    fun `completed interrupted failed and unknown items preserve raw data`() {
        runBlocking {
            val source = MutableSharedFlow<CodexAppServerEvent>(); val events = mutableListOf<CodexAppServerTurnEvent>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { source.toCodexAppServerTurnEvents().collect { events += it } }
            listOf("interrupted", "failed").forEach { emit(source, "turn/completed", turnParams(it)) }
            val future = buildJsonObject { put("type", "futureItem"); put("id", "future-1"); put("futureField", buildJsonObject { put("x", 1) }) }
            emit(source, "item/started", itemParams(future, "startedAtMs", Long.MAX_VALUE)); emit(source, "item/completed", itemParams(future, "completedAtMs", 4))
            assertEquals(CodexAppServerTurnStatus.Interrupted, (events[0] as CodexAppServerTurnEvent.TurnCompleted).turn.status)
            assertEquals(CodexAppServerTurnStatus.Failed, (events[1] as CodexAppServerTurnEvent.TurnCompleted).turn.status)
            val other = (events[2] as CodexAppServerTurnEvent.ItemStarted).item as CodexAppServerItemSnapshot.Other
            assertEquals("future-1", other.id); assertEquals("futureItem", other.type); assertEquals(1, other.raw["futureField"]!!.jsonObject["x"]!!.jsonPrimitive.content.toInt())
            assertEquals(Long.MAX_VALUE, (events[2] as CodexAppServerTurnEvent.ItemStarted).startedAtMs)
            assertSame(future, (events[3] as CodexAppServerTurnEvent.ItemCompleted).item.raw)
            job.cancelAndJoin()
        }
    }

    @Test
    fun `malformed known notification becomes diagnostic and stream survives`() {
        runBlocking {
            val source = MutableSharedFlow<CodexAppServerEvent>(); val events = mutableListOf<CodexAppServerTurnEvent>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { source.toCodexAppServerTurnEvents().collect { events += it } }
            emit(source, "item/agentMessage/delta", delta("first"))
            val malformed = buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("itemId", "item"); put("delta", "bad"); put("summaryIndex", "zero") }
            emit(source, "item/reasoning/summaryTextDelta", malformed)
            emit(source, "item/agentMessage/delta", delta("second")); emit(source, "turn/completed", turnParams("failed"))
            assertTrue(events[0] is CodexAppServerTurnEvent.AgentMessageDelta); val diagnostic = events[1] as CodexAppServerTurnEvent.MalformedNotification
            assertEquals("item/reasoning/summaryTextDelta", diagnostic.method); assertSame(malformed, diagnostic.rawParams)
            assertTrue(events[2] is CodexAppServerTurnEvent.AgentMessageDelta); assertTrue(events[3] is CodexAppServerTurnEvent.TurnCompleted)
            job.cancelAndJoin()
        }
    }

    @Test
    fun `representative required field failures are diagnostic rather than thrown`() {
        runBlocking {
            val cases = listOf(
                "turn/started" to buildJsonObject { put("turn", turn("inProgress")) },
                "turn/completed" to buildJsonObject { put("threadId", "t"); put("turn", buildJsonObject { put("id", "id") }) },
                "item/started" to buildJsonObject { put("threadId", "t"); put("turnId", "x"); put("startedAtMs", 1) },
                "item/started" to itemParams(buildJsonObject { put("type", "agentMessage"); put("text", "x") }, "startedAtMs", 1),
                "item/completed" to itemParams(buildJsonObject { put("id", "i") }, "completedAtMs", 1),
                "item/completed" to itemParams(agent("x"), "completedAtMs", "bad"),
                "item/agentMessage/delta" to buildJsonObject { put("threadId", "t"); put("turnId", "x"); put("itemId", 4); put("delta", "x") },
                "item/reasoning/summaryPartAdded" to streamIndex("summaryIndex", "bad"),
                "item/reasoning/textDelta" to streamIndex("contentIndex", 0, null),
            )
            val source = MutableSharedFlow<CodexAppServerEvent>(); val events = mutableListOf<CodexAppServerTurnEvent>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { source.toCodexAppServerTurnEvents().collect { events += it } }
            cases.forEach { emit(source, it.first, it.second) }
            assertEquals(cases.size, events.size); assertTrue(events.all { it is CodexAppServerTurnEvent.MalformedNotification }); job.cancelAndJoin()
        }
    }

    private suspend fun emit(source: MutableSharedFlow<CodexAppServerEvent>, method: String, params: JsonElement?) = source.emit(CodexAppServerEvent.UnknownNotification(method, params))
    private fun turn(status: String) = buildJsonObject { put("id", "turn"); put("status", status) }
    private fun turnParams(status: String) = buildJsonObject { put("threadId", "thread"); put("turn", turn(status)) }
    private fun agent(text: String) = buildJsonObject { put("type", "agentMessage"); put("id", "item"); put("text", text) }
    private fun reasoning(summary: List<String>, content: List<String>) = buildJsonObject { put("type", "reasoning"); put("id", "item"); put("summary", JsonArray(summary.map(::JsonPrimitive))); put("content", JsonArray(content.map(::JsonPrimitive))) }
    private fun itemParams(item: JsonObject, timestamp: String, value: Any) = buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("item", item); when (value) { is Long -> put(timestamp, value); is Int -> put(timestamp, value); is String -> put(timestamp, value) } }
    private fun delta(value: String) = buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("itemId", "item"); put("delta", value) }
    private fun streamIndex(key: String, index: Any, delta: String? = null) = buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("itemId", "item"); when (index) { is Long -> put(key, index); is Int -> put(key, index); is String -> put(key, index) }; delta?.let { put("delta", it) } }
}
