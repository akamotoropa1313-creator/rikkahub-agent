package me.rerere.rikkahub.service

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerClientInfo
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandApprovalDecision
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConnection
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConversationSession
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerFileChangeApprovalDecision
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerLocalState
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerRequestDispatcher
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerSessionBindingRepository
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTransport
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTransportEvent
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnStatus
import me.rerere.rikkahub.data.codex.appserver.JsonRpcId
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexChatRuntimeIntegrationTest {
    @Test
    fun `activity survives approval resolution late start response and terminal`() = runBlocking {
        val harness = harness(this)
        try {
            harness.transport.emit(notification("turn/started", turnParams("inProgress")))
            awaitState(harness.runtime) { it is CodexConversationUiState.Running }

            harness.transport.emit(notification("item/reasoning/summaryTextDelta", reasoningParams("reason-1", "first")))
            harness.transport.emit(notification("item/reasoning/summaryTextDelta", reasoningParams("reason-2", "second")))
            harness.transport.emit(notification("item/started", itemParams(command("command-1"), "startedAtMs", 10)))
            harness.transport.emit(notification("item/started", itemParams(command("command-2"), "startedAtMs", 11)))
            harness.transport.emit(notification("item/started", itemParams(fileChange("file-1", "+one\n"), "startedAtMs", 12)))
            harness.transport.emit(notification("item/started", itemParams(fileChange("file-2", "+two\n"), "startedAtMs", 13)))

            val active = awaitState(harness.runtime) {
                it is CodexConversationUiState.Running && it.activity.commands.size == 2 && it.activity.files.size == 2
            } as CodexConversationUiState.Running
            assertActivity(active.activity)

            val requestId = JsonRpcId.StringId("approval-command")
            harness.transport.emit(serverRequest(requestId, "item/commandExecution/requestApproval", commandApprovalParams("command-1")))
            val waiting = awaitState(harness.runtime) {
                it is CodexConversationUiState.WaitingForApproval && it.requestId == requestId
            } as CodexConversationUiState.WaitingForApproval
            assertActivity(waiting.activity)

            harness.transport.emit(notification("serverRequest/resolved", resolved("approval-command")))
            val resumed = awaitState(harness.runtime) {
                it is CodexConversationUiState.Running && it.activity.commands.size == 2
            } as CodexConversationUiState.Running
            assertActivity(resumed.activity)

            harness.runtime.acceptStartResponse("turn-1", CodexAppServerTurnStatus.InProgress)
            val afterLateResponse = harness.runtime.state.value as CodexConversationUiState.Running
            assertActivity(afterLateResponse.activity)

            harness.transport.emit(notification("turn/completed", turnParams("completed")))
            val terminal = awaitState(harness.runtime) { it is CodexConversationUiState.Terminal } as CodexConversationUiState.Terminal
            assertEquals(CodexAppServerTurnStatus.Completed, terminal.status)
            assertActivity(terminal.activity)
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `terminal notification remains monotonic against later inProgress start response`() = runBlocking {
        val harness = harness(this)
        try {
            harness.transport.emit(notification("turn/started", turnParams("inProgress")))
            awaitState(harness.runtime) { it is CodexConversationUiState.Running }
            harness.transport.emit(notification("item/reasoning/summaryTextDelta", reasoningParams("reason-1", "kept")))
            awaitState(harness.runtime) {
                it is CodexConversationUiState.Running && it.activity.reasoning == "kept"
            }
            harness.transport.emit(notification("turn/completed", turnParams("completed")))
            val terminal = awaitState(harness.runtime) { it is CodexConversationUiState.Terminal } as CodexConversationUiState.Terminal

            harness.runtime.acceptStartResponse("turn-1", CodexAppServerTurnStatus.InProgress)

            val afterLateResponse = harness.runtime.state.value
            assertTrue(afterLateResponse is CodexConversationUiState.Terminal)
            assertEquals("kept", (afterLateResponse as CodexConversationUiState.Terminal).activity.reasoning)
            assertEquals(terminal, afterLateResponse)
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `file approval preview requires current turn and matching item`() = runBlocking {
        val harness = harness(this)
        try {
            harness.transport.emit(notification("turn/started", turnParams("inProgress")))
            awaitState(harness.runtime) { it is CodexConversationUiState.Running }
            harness.transport.emit(notification("item/started", itemParams(fileChange("file-1", "+safe\n"), "startedAtMs", 12)))
            awaitState(harness.runtime) {
                it is CodexConversationUiState.Running && it.activity.files.any { file -> file.id == "file-1" }
            }

            val exactId = JsonRpcId.NumberId(42L)
            harness.transport.emit(serverRequest(exactId, "item/fileChange/requestApproval", fileApprovalParams("turn-1", "file-1")))
            val exact = awaitState(harness.runtime) {
                it is CodexConversationUiState.WaitingForApproval && it.requestId == exactId
            } as CodexConversationUiState.WaitingForApproval
            assertNotNull(exact.fileChange)
            assertEquals("file-1", exact.fileChange?.id)

            harness.transport.emit(notification("serverRequest/resolved", resolved(42)))
            awaitState(harness.runtime) { it is CodexConversationUiState.Running }

            val missingId = JsonRpcId.StringId("missing-item")
            harness.transport.emit(serverRequest(missingId, "item/fileChange/requestApproval", fileApprovalParams("turn-1", "file-missing")))
            val missing = awaitState(harness.runtime) {
                it is CodexConversationUiState.WaitingForApproval && it.requestId == missingId
            } as CodexConversationUiState.WaitingForApproval
            assertNull(missing.fileChange)

            harness.transport.emit(notification("serverRequest/resolved", resolved("missing-item")))
            awaitState(harness.runtime) { it is CodexConversationUiState.Running }

            val wrongTurnId = JsonRpcId.StringId("wrong-turn")
            harness.transport.emit(serverRequest(wrongTurnId, "item/fileChange/requestApproval", fileApprovalParams("turn-2", "file-1")))
            yield()
            val afterWrongTurn = harness.runtime.state.value
            assertTrue(afterWrongTurn is CodexConversationUiState.Running)
            assertEquals("turn-1", (afterWrongTurn as CodexConversationUiState.Running).turnId)
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `stale approval callback and wrong responder kind are rejected before wire`() = runBlocking {
        val harness = harness(this)
        try {
            harness.transport.emit(notification("turn/started", turnParams("inProgress")))
            awaitState(harness.runtime) { it is CodexConversationUiState.Running }

            val firstId = JsonRpcId.StringId("approval-a")
            harness.transport.emit(serverRequest(firstId, "item/commandExecution/requestApproval", commandApprovalParams("command-a")))
            awaitState(harness.runtime) { it is CodexConversationUiState.WaitingForApproval && it.requestId == firstId }

            val secondId = JsonRpcId.StringId("approval-b")
            harness.transport.emit(serverRequest(secondId, "item/commandExecution/requestApproval", commandApprovalParams("command-b")))
            awaitState(harness.runtime) { it is CodexConversationUiState.WaitingForApproval && it.requestId == secondId }

            val before = harness.transport.sentLines.size
            assertFalse(harness.runtime.respondCommandApproval(firstId, CodexAppServerCommandApprovalDecision.Accept))
            assertFalse(harness.runtime.respondFileApproval(secondId, CodexAppServerFileChangeApprovalDecision.Accept))
            assertEquals(before, harness.transport.sentLines.size)
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `delayed old turn activity cannot replace current turn`() = runBlocking {
        val harness = harness(this)
        try {
            harness.transport.emit(notification("turn/started", turnParams("inProgress", "turn-1")))
            awaitState(harness.runtime) { it is CodexConversationUiState.Running && it.turnId == "turn-1" }
            harness.transport.emit(notification("turn/completed", turnParams("completed", "turn-1")))
            awaitState(harness.runtime) { it is CodexConversationUiState.Terminal && it.turnId == "turn-1" }

            harness.runtime.acceptStartResponse("turn-2", CodexAppServerTurnStatus.InProgress)
            awaitState(harness.runtime) { it is CodexConversationUiState.Running && it.turnId == "turn-2" }

            harness.transport.emit(notification("item/reasoning/summaryTextDelta", reasoningParams("old-reason", "stale", "turn-1")))
            harness.transport.emit(notification("item/started", itemParams(command("old-command"), "startedAtMs", 30, "turn-1")))
            yield()

            val current = harness.runtime.state.value as CodexConversationUiState.Running
            assertEquals("turn-2", current.turnId)
            assertEquals("", current.activity.reasoning)
            assertTrue(current.activity.commands.isEmpty())
        } finally {
            harness.runtime.close()
        }
    }

    private fun assertActivity(activity: CodexConversationActivity) {
        assertEquals("first\nsecond", activity.reasoning)
        assertEquals(listOf("command-1", "command-2"), activity.commands.map { it.id })
        assertEquals(listOf("file-1", "file-2"), activity.files.map { it.id })
    }

    private suspend fun awaitState(
        runtime: CodexChatRuntime,
        predicate: (CodexConversationUiState) -> Boolean,
    ): CodexConversationUiState = withTimeout(2_000) { runtime.state.first(predicate) }

    private fun harness(scope: CoroutineScope): Harness {
        val binding = CodexAppServerSessionBindingEntity(
            conversationId = "conversation-1",
            workspaceId = "workspace-1",
            threadId = "thread-1",
            workspaceCwd = "",
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )
        val dao = FakeBindingDao(binding)
        val localState = object : CodexAppServerLocalState {
            override suspend fun conversationExists(id: String) = true
            override suspend fun getWorkspace(id: String): WorkspaceEntity? = null
        }
        val repository = CodexAppServerSessionBindingRepository(dao, localState) { 2L }
        val transport = FakeTransport()
        val connection = CodexAppServerConnection(
            CodexAppServerRequestDispatcher(transport),
            CodexAppServerClientInfo(version = "test"),
        )
        val session = CodexAppServerConversationSession(binding, connection, repository)
        return Harness(CodexChatRuntime(session, scope, onAgentText = { _, _, _ -> }), transport)
    }

    private data class Harness(
        val runtime: CodexChatRuntime,
        val transport: FakeTransport,
    )

    private class FakeTransport : CodexAppServerTransport {
        private val inbound = Channel<CodexAppServerTransportEvent>(Channel.UNLIMITED)
        val sentLines = CopyOnWriteArrayList<String>()
        override val events: Flow<CodexAppServerTransportEvent> = inbound.receiveAsFlow()

        override suspend fun sendLine(line: String) {
            sentLines += line
        }

        suspend fun emit(line: String) {
            inbound.send(CodexAppServerTransportEvent.Line(line))
        }

        override fun close() {
            inbound.trySend(CodexAppServerTransportEvent.Closed)
            inbound.close()
        }
    }

    private class FakeBindingDao(initial: CodexAppServerSessionBindingEntity) : CodexAppServerSessionBindingDao {
        private var binding: CodexAppServerSessionBindingEntity? = initial

        override suspend fun getByConversationId(conversationId: String) = binding?.takeIf { it.conversationId == conversationId }
        override fun observeByConversationId(conversationId: String) = flowOf(binding?.takeIf { it.conversationId == conversationId })
        override suspend fun getByThreadId(threadId: String) = binding?.takeIf { it.threadId == threadId }
        override suspend fun upsert(binding: CodexAppServerSessionBindingEntity) { this.binding = binding }
        override suspend fun insertIfAbsent(binding: CodexAppServerSessionBindingEntity): Long {
            if (this.binding != null) return -1L
            this.binding = binding
            return 1L
        }
        override suspend fun updateLastObservedTurn(
            conversationId: String,
            expectedThreadId: String,
            turnId: String,
            status: String,
            updatedAtMs: Long,
        ): Int {
            val current = binding ?: return 0
            if (current.conversationId != conversationId || current.threadId != expectedThreadId) return 0
            binding = current.copy(lastObservedTurnId = turnId, lastObservedTurnStatus = status, updatedAtMs = updatedAtMs)
            return 1
        }
        override suspend fun updateLastObservedTurnStarted(
            conversationId: String,
            expectedThreadId: String,
            turnId: String,
            updatedAtMs: Long,
        ): Int = updateLastObservedTurn(conversationId, expectedThreadId, turnId, "inProgress", updatedAtMs)
        override suspend fun updateLastResumed(conversationId: String, expectedThreadId: String, resumedAtMs: Long): Int {
            val current = binding ?: return 0
            if (current.conversationId != conversationId || current.threadId != expectedThreadId) return 0
            binding = current.copy(lastResumedAtMs = resumedAtMs, updatedAtMs = resumedAtMs)
            return 1
        }
        override suspend fun deleteByConversationId(conversationId: String): Int {
            if (binding?.conversationId != conversationId) return 0
            binding = null
            return 1
        }
    }

    private fun notification(method: String, params: JsonObject) =
        "{\"method\":${JsonPrimitive(method)},\"params\":$params}"

    private fun serverRequest(id: JsonRpcId, method: String, params: JsonObject): String {
        val encodedId = when (id) {
            is JsonRpcId.StringId -> JsonPrimitive(id.value)
            is JsonRpcId.NumberId -> JsonPrimitive(id.value)
        }
        return "{\"id\":$encodedId,\"method\":${JsonPrimitive(method)},\"params\":$params}"
    }

    private fun turn(status: String, turnId: String = "turn-1") = buildJsonObject {
        put("id", turnId)
        put("status", status)
    }

    private fun turnParams(status: String, turnId: String = "turn-1") = buildJsonObject {
        put("threadId", "thread-1")
        put("turn", turn(status, turnId))
    }

    private fun reasoningParams(itemId: String, delta: String, turnId: String = "turn-1") = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", turnId)
        put("itemId", itemId)
        put("delta", delta)
        put("summaryIndex", 0)
    }

    private fun command(id: String) = buildJsonObject {
        put("type", "commandExecution")
        put("id", id)
        put("command", "echo $id")
        put("cwd", "/workspace")
        put("status", "inProgress")
        put("commandActions", JsonArray(emptyList()))
    }

    private fun fileChange(id: String, diff: String) = buildJsonObject {
        put("type", "fileChange")
        put("id", id)
        put("status", "inProgress")
        put("changes", JsonArray(listOf(buildJsonObject {
            put("path", "$id.txt")
            put("kind", buildJsonObject { put("type", "update") })
            put("diff", diff)
        })))
    }

    private fun itemParams(item: JsonObject, timeKey: String, time: Long, turnId: String = "turn-1") = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", turnId)
        put("item", item)
        put(timeKey, time)
    }

    private fun commandApprovalParams(itemId: String) = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", "turn-1")
        put("itemId", itemId)
        put("startedAtMs", 20)
        put("approvalId", "callback-1")
        put("command", "echo test")
        put("cwd", "/workspace")
        put("commandActions", JsonArray(emptyList()))
    }

    private fun fileApprovalParams(turnId: String, itemId: String) = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", turnId)
        put("itemId", itemId)
        put("startedAtMs", 21)
    }

    private fun resolved(requestId: Any) = buildJsonObject {
        put("threadId", "thread-1")
        when (requestId) {
            is String -> put("requestId", requestId)
            is Int -> put("requestId", requestId)
            is Long -> put("requestId", requestId)
            else -> error("unsupported request id")
        }
    }
}
