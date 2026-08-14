package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CodexAppServerStage8ProjectionTest {
    @Test
    fun `omitted and explicit command sources follow the protocol`() {
        val cases = listOf(
            null to CodexAppServerCommandExecutionSource.Agent,
            "agent" to CodexAppServerCommandExecutionSource.Agent,
            "userShell" to CodexAppServerCommandExecutionSource.UserShell,
            "unifiedExecStartup" to CodexAppServerCommandExecutionSource.UnifiedExecStartup,
            "unifiedExecInteraction" to CodexAppServerCommandExecutionSource.UnifiedExecInteraction,
            "futureSource" to CodexAppServerCommandExecutionSource.Unknown("futureSource"),
        )
        cases.forEach { (source, expected) ->
            val item = commandItem(source = source)
            assertEquals(expected, (decodeItemSnapshot(item) as CodexAppServerItemSnapshot.CommandExecution).source)
        }
    }

    @Test
    fun `all command actions decode and future variants impose no current fields`() {
        val read = buildJsonObject { put("type", "read"); put("command", "cat a"); put("name", "a"); put("path", "a") }
        val listPath = buildJsonObject { put("type", "listFiles"); put("command", "find ."); put("path", ".") }
        val listNull = buildJsonObject { put("type", "listFiles"); put("command", "find"); put("path", JsonNull) }
        val search = buildJsonObject { put("type", "search"); put("command", "rg q"); put("query", "q"); put("path", JsonNull) }
        val searchNull = buildJsonObject { put("type", "search"); put("command", "rg"); put("query", JsonNull); put("path", "src") }
        val unknown = buildJsonObject { put("type", "unknown"); put("command", "opaque") }
        val future = buildJsonObject { put("type", "futureAction"); put("future", 1) }
        val futureWrongUnrelated = buildJsonObject { put("type", "anotherFuture"); put("command", 42) }
        val actions = (decodeItemSnapshot(commandItem(actions = listOf(read, listPath, listNull, search, searchNull, unknown, future, futureWrongUnrelated))) as CodexAppServerItemSnapshot.CommandExecution).commandActions
        assertEquals(CodexAppServerCommandAction.Read("cat a", "a", "a", read), actions[0])
        assertEquals(".", (actions[1] as CodexAppServerCommandAction.ListFiles).path)
        assertNull((actions[2] as CodexAppServerCommandAction.ListFiles).path)
        assertEquals("q", (actions[3] as CodexAppServerCommandAction.Search).query)
        assertNull((actions[4] as CodexAppServerCommandAction.Search).query)
        assertEquals("opaque", (actions[5] as CodexAppServerCommandAction.UnknownCommand).command)
        assertSame(future, (actions[6] as CodexAppServerCommandAction.Other).raw)
        assertEquals("anotherFuture", (actions[7] as CodexAppServerCommandAction.Other).type)
    }

    @Test
    fun `command status nullable fields numbers and raw are exact`() {
        listOf("inProgress", "completed", "failed", "declined", "future").forEach { status ->
            val raw = commandItem(status = status, nullableFields = false)
            val item = decodeItemSnapshot(raw) as CodexAppServerItemSnapshot.CommandExecution
            assertEquals("pid", item.processId); assertEquals("plugin", item.pluginId); assertEquals("script", item.scriptPath)
            assertEquals("final", item.aggregatedOutput); assertEquals(-2, item.exitCode); assertEquals(Long.MAX_VALUE, item.durationMs); assertSame(raw, item.raw)
            if (status == "future") assertEquals(CodexAppServerCommandExecutionStatus.Unknown("future"), item.status)
        }
        val nullable = decodeItemSnapshot(commandItem(nullableFields = true)) as CodexAppServerItemSnapshot.CommandExecution
        assertNull(nullable.processId); assertNull(nullable.pluginId); assertNull(nullable.scriptPath); assertNull(nullable.aggregatedOutput); assertNull(nullable.exitCode); assertNull(nullable.durationMs)
    }

    @Test
    fun `malformed command fields remain protocol failures`() {
        val valid = commandItem()
        listOf(
            valid - "command",
            valid + ("command" to JsonPrimitive(1)),
            valid - "cwd",
            valid + ("cwd" to JsonPrimitive(false)),
            valid + ("status" to JsonPrimitive(1)),
            valid + ("commandActions" to JsonPrimitive("bad")),
            valid + ("commandActions" to JsonArray(listOf(JsonPrimitive("bad")))),
            valid + ("exitCode" to JsonPrimitive(1.5)),
            valid + ("exitCode" to JsonPrimitive(2147483648L)),
            valid + ("durationMs" to JsonPrimitive(1.5)),
        ).forEach { expectProtocolFailure { decodeItemSnapshot(JsonObject(it)) } }
    }

    @Test
    fun `file statuses kinds multiple changes and move_path remain exact`() {
        val exactDiff = "--- a/ü\n+++ b/ü\n@@ -1 +1 @@\n- old\n+\tnew  \n"
        val kinds = listOf(
            buildJsonObject { put("type", "add") },
            buildJsonObject { put("type", "delete") },
            buildJsonObject { put("type", "update"); put("move_path", JsonNull) },
            buildJsonObject { put("type", "update"); put("move_path", "new/path") },
            buildJsonObject { put("type", "futureKind"); put("future", 1) },
        )
        listOf("inProgress", "completed", "failed", "declined", "future").forEach { status ->
            val raw = fileItem(status, kinds.mapIndexed { index, kind -> change("p$index", kind, exactDiff) })
            val item = decodeItemSnapshot(raw) as CodexAppServerItemSnapshot.FileChange
            assertEquals(5, item.changes.size); assertTrue(item.changes.all { it.diff == exactDiff }); assertSame(raw, item.raw)
            assertNull((item.changes[2].kind as CodexAppServerPatchChangeKind.Update).movePath)
            assertEquals("new/path", (item.changes[3].kind as CodexAppServerPatchChangeKind.Update).movePath)
            assertTrue("movePath" !in kinds[3]); assertSame(kinds[4], (item.changes[4].kind as CodexAppServerPatchChangeKind.Other).raw)
            if (status == "future") assertEquals(CodexAppServerPatchApplyStatus.Unknown("future"), item.status)
        }
    }

    private fun commandItem(source: String? = null, status: String = "inProgress", actions: List<JsonObject> = emptyList(), nullableFields: Boolean? = null) = buildJsonObject {
        put("type", "commandExecution"); put("id", "command"); put("command", "echo hi"); put("cwd", "/opaque"); source?.let { put("source", it) }; put("status", status); put("commandActions", JsonArray(actions))
        if (nullableFields != null) {
            if (nullableFields) listOf("processId", "pluginId", "scriptPath", "aggregatedOutput", "exitCode", "durationMs").forEach { put(it, JsonNull) }
            else { put("processId", "pid"); put("pluginId", "plugin"); put("scriptPath", "script"); put("aggregatedOutput", "final"); put("exitCode", -2); put("durationMs", Long.MAX_VALUE) }
        }
    }
    private fun fileItem(status: String, changes: List<JsonObject>) = buildJsonObject { put("type", "fileChange"); put("id", "file"); put("status", status); put("changes", JsonArray(changes)) }
    private fun change(path: String, kind: JsonObject, diff: String) = buildJsonObject { put("path", path); put("kind", kind); put("diff", diff) }
    private fun expectProtocolFailure(block: () -> Unit) { try { block(); fail("Expected CodexAppServerTurnProtocolException") } catch (_: CodexAppServerTurnProtocolException) {} }
}
