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
    fun `command and file snapshots preserve typed and authoritative wire data`() {
        val diff = "--- a/ü\n+++ b/ü\n@@ -1 +1 @@\n- old\n+\tnew  \n"
        val action = buildJsonObject { put("type", "futureAction"); put("future", 1) }
        val command = buildJsonObject { put("type", "commandExecution"); put("id", "c"); put("command", "printf x"); put("cwd", "/opaque"); put("processId", "42"); put("source", "futureSource"); put("status", "futureStatus"); put("commandActions", JsonArray(listOf(action))); put("aggregatedOutput", "final authoritative output"); put("exitCode", -1); put("durationMs", Long.MAX_VALUE) }
        val projected = decodeItemSnapshot(command) as CodexAppServerItemSnapshot.CommandExecution
        assertEquals(Long.MAX_VALUE, projected.durationMs); assertEquals("final authoritative output", projected.aggregatedOutput)
        assertTrue(projected.status is CodexAppServerCommandExecutionStatus.Unknown); assertTrue(projected.source is CodexAppServerCommandExecutionSource.Unknown)
        assertSame(action, (projected.commandActions.single() as CodexAppServerCommandAction.Other).raw); assertSame(command, projected.raw)

        val kind = buildJsonObject { put("type", "update"); put("move_path", "new/path") }
        val change = buildJsonObject { put("path", "old/path"); put("kind", kind); put("diff", diff) }
        val file = buildJsonObject { put("type", "fileChange"); put("id", "f"); put("status", "completed"); put("changes", JsonArray(listOf(change))) }
        val fileProjected = decodeItemSnapshot(file) as CodexAppServerItemSnapshot.FileChange
        assertEquals(diff, fileProjected.changes.single().diff)
        assertEquals("new/path", (fileProjected.changes.single().kind as CodexAppServerPatchChangeKind.Update).movePath)
        assertTrue("camelCase wire key must not be used", "movePath" !in kind); assertSame(file, fileProjected.raw)
    }

    @Test
    fun `new streaming notifications remain ordered and malformed update does not stop stream`() = runBlocking {
        val source = MutableSharedFlow<CodexAppServerEvent>(); val events = mutableListOf<CodexAppServerTurnEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { source.toCodexAppServerTurnEvents().collect { events += it } }
        emit(source, "item/commandExecution/outputDelta", delta("draft output"))
        emit(source, "item/fileChange/patchUpdated", buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("itemId", "item"); put("changes", "bad") })
        emit(source, "turn/diff/updated", buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("diff", "+ exact\n") })
        emit(source, "item/commandExecution/terminalInteraction", buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("itemId", "item"); put("processId", "p"); put("stdin", "secret") })
        assertTrue(events[0] is CodexAppServerTurnEvent.CommandExecutionOutputDelta); assertTrue(events[1] is CodexAppServerTurnEvent.MalformedNotification)
        assertEquals("+ exact\n", (events[2] as CodexAppServerTurnEvent.TurnDiffUpdated).diff); assertTrue(events[3] is CodexAppServerTurnEvent.TerminalInteraction)
        job.cancelAndJoin()
    }

    @Test
    fun `progress events do not replace authoritative completed command and file items`() = runBlocking {
        val source = MutableSharedFlow<CodexAppServerEvent>()
        val events = mutableListOf<CodexAppServerTurnEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { source.toCodexAppServerTurnEvents().collect { events += it } }
        val provisional = buildJsonObject { put("path", "draft"); put("kind", buildJsonObject { put("type", "add") }); put("diff", "+draft\n") }
        val final = buildJsonObject { put("path", "final"); put("kind", buildJsonObject { put("type", "delete") }); put("diff", "-final\n") }
        val patchParams = buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("itemId", "file"); put("changes", JsonArray(listOf(provisional, final))) }
        emit(source, "item/commandExecution/outputDelta", delta("draft output"))
        emit(source, "item/completed", itemParams(command("final authoritative output"), "completedAtMs", 1))
        emit(source, "item/fileChange/patchUpdated", patchParams)
        emit(source, "item/completed", itemParams(fileChange(final), "completedAtMs", 2))

        assertEquals("final authoritative output", ((events[1] as CodexAppServerTurnEvent.ItemCompleted).item as CodexAppServerItemSnapshot.CommandExecution).aggregatedOutput)
        val patch = events[2] as CodexAppServerTurnEvent.FileChangePatchUpdated
        assertEquals("thread", patch.threadId); assertEquals("turn", patch.turnId); assertEquals("file", patch.itemId)
        assertEquals(listOf("draft", "final"), patch.changes.map { it.path }); assertEquals("+draft\n", patch.changes[0].diff); assertSame(patchParams, patch.rawParams)
        val completed = (events[3] as CodexAppServerTurnEvent.ItemCompleted).item as CodexAppServerItemSnapshot.FileChange
        assertEquals(listOf("final"), completed.changes.map { it.path }); assertEquals("-final\n", completed.changes.single().diff)
        job.cancelAndJoin()
    }
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
    private fun command(output: String) = buildJsonObject { put("type", "commandExecution"); put("id", "item"); put("command", "echo"); put("cwd", "/tmp"); put("status", "completed"); put("commandActions", JsonArray(emptyList())); put("aggregatedOutput", output) }
    private fun fileChange(change: JsonObject) = buildJsonObject { put("type", "fileChange"); put("id", "item"); put("status", "completed"); put("changes", JsonArray(listOf(change))) }
    private fun itemParams(item: JsonObject, timestamp: String, value: Any) = buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("item", item); when (value) { is Long -> put(timestamp, value); is Int -> put(timestamp, value); is String -> put(timestamp, value) } }
    private fun delta(value: String) = buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("itemId", "item"); put("delta", value) }
    private fun streamIndex(key: String, index: Any, delta: String? = null) = buildJsonObject { put("threadId", "thread"); put("turnId", "turn"); put("itemId", "item"); when (index) { is Long -> put(key, index); is Int -> put(key, index); is String -> put(key, index) }; delta?.let { put("delta", it) } }
}
