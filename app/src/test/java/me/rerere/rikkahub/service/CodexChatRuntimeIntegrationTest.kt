package me.rerere.rikkahub.service

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerAccount
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerAuthUrlLauncher
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
    fun `slow timeline persistence does not block later protocol activity`() = runBlocking {
        val persistenceStarted = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val harness = harness(this, onTurnParts = { _, _ ->
            if (persistenceStarted.complete(Unit)) releasePersistence.await()
        })
        try {
            harness.transport.emit(notification("turn/started", turnParams("inProgress")))
            harness.transport.emit(notification("item/started", itemParams(buildJsonObject {
                put("type", "agentMessage")
                put("id", "agent-1")
                put("text", "")
            }, "startedAtMs", 10)))
            harness.transport.emit(notification("item/agentMessage/delta", buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("itemId", "agent-1")
                put("delta", "先に表示")
            }))
            withTimeout(2_000) { persistenceStarted.await() }

            harness.transport.emit(notification(
                "item/reasoning/summaryTextDelta",
                reasoningParams("reason-1", "受信は継続"),
            ))
            val running = awaitState(harness.runtime) {
                it is CodexConversationUiState.Running && it.activity.reasoning == "受信は継続"
            } as CodexConversationUiState.Running
            assertEquals(CodexTurnStage.Thinking, running.progress.stage)
        } finally {
            releasePersistence.complete(Unit)
            harness.runtime.close()
        }
    }

    @Test
    fun `turn callback exposes interleaved existing chat message parts`() = runBlocking {
        val snapshots = CopyOnWriteArrayList<List<UIMessagePart>>()
        val harness = harness(this, onTurnParts = { _, parts -> snapshots += parts })
        try {
            harness.transport.emit(notification("turn/started", turnParams("inProgress")))
            harness.transport.emit(notification("item/started", itemParams(buildJsonObject {
                put("type", "agentMessage")
                put("id", "agent-1")
                put("text", "")
            }, "startedAtMs", 10)))
            harness.transport.emit(notification("item/agentMessage/delta", buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("itemId", "agent-1")
                put("delta", "確認します。")
            }))
            harness.transport.emit(notification("item/started", itemParams(command("command-1"), "startedAtMs", 11)))

            val parts = withTimeout(2_000) {
                while (snapshots.lastOrNull()?.size != 2) yield()
                snapshots.last()
            }
            assertEquals("確認します。", (parts[0] as UIMessagePart.Text).text)
            assertEquals("workspace_shell", (parts[1] as UIMessagePart.Tool).toolName)
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `approval request updates the existing chat tool row instead of a separate card`() = runBlocking {
        val snapshots = CopyOnWriteArrayList<List<UIMessagePart>>()
        val harness = harness(this, onTurnParts = { _, parts -> snapshots += parts })
        try {
            harness.transport.emit(notification("turn/started", turnParams("inProgress")))
            harness.transport.emit(notification("item/started", itemParams(command("command-1"), "startedAtMs", 11)))
            harness.transport.emit(serverRequest(
                JsonRpcId.StringId("approval-command"),
                "item/commandExecution/requestApproval",
                commandApprovalParams("command-1"),
            ))

            val pending = withTimeout(2_000) {
                while (true) {
                    val tool = snapshots.lastOrNull()?.filterIsInstance<UIMessagePart.Tool>()?.singleOrNull()
                    if (tool?.approvalState is ToolApprovalState.Pending) return@withTimeout tool
                    yield()
                }
                error("unreachable")
            }
            assertEquals("codex:turn-1:command-1", pending.toolCallId)
            assertEquals("workspace_shell", pending.toolName)
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `successful browser login refreshes the account snapshot automatically`() = runBlocking {
        val harness = harness(this)
        try {
            val initializing = async { harness.connection.initialize() }
            val initialize = awaitClientMessage(harness.transport, 0)
            harness.transport.emit(
                """{"id":${initialize["id"]},"result":{"userAgent":"test","codexHome":"/home","platformFamily":"unix","platformOs":"linux"}}""",
            )
            initializing.await()
            assertEquals("initialized", awaitClientMessage(harness.transport, 1)["method"]?.jsonPrimitive?.content)

            val beginning = async {
                harness.runtime.beginAccountLogin(CodexAppServerAuthUrlLauncher { })
            }
            val login = awaitClientMessage(harness.transport, 2)
            assertEquals("account/login/start", login["method"]?.jsonPrimitive?.content)
            harness.transport.emit(
                """{"id":${login["id"]},"result":{"type":"chatgpt","loginId":"login-1","authUrl":"https://auth.openai.com/start"}}""",
            )
            beginning.await()
            assertEquals("login-1", harness.runtime.capabilities.value.pendingLoginId)

            harness.transport.emit(notification("account/login/completed", buildJsonObject {
                put("loginId", "login-1")
                put("success", true)
            }))
            val accountRead = awaitClientMessage(harness.transport, 3)
            assertEquals("account/read", accountRead["method"]?.jsonPrimitive?.content)
            harness.transport.emit(
                """{"id":${accountRead["id"]},"result":{"account":{"type":"chatgpt","email":"user@example.com","planType":"plus"},"requiresOpenaiAuth":false}}""",
            )

            val refreshed = withTimeout(2_000) {
                harness.runtime.capabilities.first {
                    it.account?.account is CodexAppServerAccount.ChatGpt && !it.accountLoading
                }
            }
            assertNull(refreshed.pendingLoginId)
            assertEquals("user@example.com", (refreshed.account?.account as CodexAppServerAccount.ChatGpt).email)
            assertFalse(refreshed.accountLoading)
            assertNull(refreshed.accountError)
        } finally {
            harness.runtime.close()
        }
    }

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

    @Test
    fun `agent text callback failure releases terminal waiter with failure`() = runBlocking {
        val harness = harness(this, onAgentText = { _, _, _ -> error("persist failed") })
        try {
            harness.runtime.acceptStartResponse("turn-1", CodexAppServerTurnStatus.InProgress)
            val waiter = async { runCatching { harness.runtime.awaitTurnTerminal("turn-1") } }

            harness.transport.emit(notification("item/agentMessage/delta", buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("itemId", "agent-1")
                put("delta", "hello")
            }))

            val result = withTimeout(2_000) { waiter.await() }
            assertTrue(result.isFailure)
            assertEquals("persist failed", result.exceptionOrNull()?.message)
            assertTrue(awaitState(harness.runtime) { it is CodexConversationUiState.Failed } is CodexConversationUiState.Failed)
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `turn parts persistence failure releases terminal waiter with failure`() = runBlocking {
        val harness = harness(this, onTurnParts = { _, _ -> error("timeline persist failed") })
        try {
            harness.runtime.acceptStartResponse("turn-1", CodexAppServerTurnStatus.InProgress)
            val waiter = async { runCatching { harness.runtime.awaitTurnTerminal("turn-1") } }

            harness.transport.emit(notification("item/agentMessage/delta", buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("itemId", "agent-1")
                put("delta", "hello")
            }))

            val result = withTimeout(2_000) { waiter.await() }
            assertTrue(result.isFailure)
            assertEquals("timeline persist failed", result.exceptionOrNull()?.message)
            assertTrue(awaitState(harness.runtime) { it is CodexConversationUiState.Failed } is CodexConversationUiState.Failed)
        } finally {
            harness.runtime.close()
        }
    }

    @Test
    fun `malformed turn completed releases active terminal waiter with protocol failure`() = runBlocking {
        val harness = harness(this)
        try {
            harness.runtime.acceptStartResponse("turn-1", CodexAppServerTurnStatus.InProgress)
            val waiter = async { runCatching { harness.runtime.awaitTurnTerminal("turn-1") } }

            harness.transport.emit(notification("turn/completed", buildJsonObject {
                put("threadId", "thread-1")
                put("turn", buildJsonObject {
                    put("id", "turn-1")
                })
            }))

            val result = withTimeout(2_000) { waiter.await() }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("status") == true)
            assertTrue(awaitState(harness.runtime) { it is CodexConversationUiState.Failed } is CodexConversationUiState.Failed)
        } finally {
            harness.runtime.close()
        }
    }

    @Test fun `enabled Termux dynamic tool executes on Android side`() = runBlocking {
        var received: JsonObject? = null
        val tool = Tool("termux_run_command", "Run through Termux", { InputSchema.Obj(JsonObject(emptyMap())) }, execute = {
            received = it.jsonObject; listOf(UIMessagePart.Text("termux-ok"))
        })
        val harness = harness(this, assistantToolProfile = CodexAssistantToolProfile.from(listOf(tool), "assistant:false"))
        try {
            initialize(harness); harness.transport.emit(notification("turn/started", turnParams("inProgress")))
            awaitState(harness.runtime) { it is CodexConversationUiState.Running }
            harness.transport.emit(serverRequest(JsonRpcId.NumberId(77L), "item/tool/call", dynamicToolParams(
                "call-1", "termux_run_command", buildJsonObject { put("command", "pwd") },
            )))
            val response = awaitClientMessage(harness.transport, 2)
            assertEquals("pwd", received?.get("command")?.jsonPrimitive?.content)
            assertEquals(true, response["result"]?.jsonObject?.get("success")?.jsonPrimitive?.content?.toBoolean())
        } finally { harness.runtime.close() }
    }

    @Test fun `approval gated dynamic tool waits then executes`() = runBlocking {
        val executions = AtomicInteger()
        val tool = Tool("send_notification", "Send", { InputSchema.Obj(JsonObject(emptyMap())) }, needsApproval = { true }, execute = {
            executions.incrementAndGet(); listOf(UIMessagePart.Text("sent"))
        })
        val harness = harness(this, assistantToolProfile = CodexAssistantToolProfile.from(listOf(tool), "assistant:false"))
        try {
            initialize(harness); harness.transport.emit(notification("turn/started", turnParams("inProgress")))
            awaitState(harness.runtime) { it is CodexConversationUiState.Running }
            val id = JsonRpcId.StringId("tool-approval")
            harness.transport.emit(serverRequest(id, "item/tool/call", dynamicToolParams("call-2", "send_notification", JsonObject(emptyMap()))))
            awaitState(harness.runtime) { it is CodexConversationUiState.WaitingForApproval && it.requestId == id }
            assertEquals(0, executions.get()); assertTrue(harness.runtime.respondDynamicToolApproval(id, true))
            awaitClientMessage(harness.transport, 2); assertEquals(1, executions.get())
        } finally { harness.runtime.close() }
    }

    @Test fun `ask_user answer returns without headless fallback`() = runBlocking {
        val executions = AtomicInteger()
        val tool = Tool("ask_user", "Ask", { InputSchema.Obj(JsonObject(emptyMap())) }, needsApproval = { true }, execute = {
            executions.incrementAndGet(); listOf(UIMessagePart.Text("ask_user_unavailable"))
        })
        val harness = harness(this, assistantToolProfile = CodexAssistantToolProfile.from(listOf(tool), "assistant:false"))
        try {
            initialize(harness); harness.transport.emit(notification("turn/started", turnParams("inProgress")))
            awaitState(harness.runtime) { it is CodexConversationUiState.Running }
            val id = JsonRpcId.StringId("ask-user")
            harness.transport.emit(serverRequest(id, "item/tool/call", dynamicToolParams("call-ask", "ask_user", JsonObject(emptyMap()))))
            awaitState(harness.runtime) { it is CodexConversationUiState.WaitingForApproval && it.requestId == id }
            val answer = "{\"destination\":\"Tokyo\"}"
            assertTrue(harness.runtime.respondDynamicToolAnswer(id, answer))
            val response = awaitClientMessage(harness.transport, 2)
            assertEquals(0, executions.get())
            assertEquals(answer, (response["result"]?.jsonObject?.get("contentItems") as JsonArray).single().jsonObject["text"]?.jsonPrimitive?.content)
        } finally { harness.runtime.close() }
    }

    @Test
    fun `successful account completion does not leave contradictory temporary status`() {
        val state = CodexCapabilitiesUiState(pendingLoginId = "login-1", accountStatus = "Waiting for ChatGPT sign-in")
        val completed = applyAccountLoginCompletion(
            state,
            me.rerere.rikkahub.data.codex.appserver.CodexAppServerAccountEvent.LoginCompleted(
                loginId = "login-1",
                success = true,
                error = null,
                onboardingEntrypoint = null,
                rawParams = kotlinx.serialization.json.buildJsonObject {},
            ),
        )
        assertEquals(null, completed.pendingLoginId)
        assertEquals(null, completed.accountStatus)
        assertEquals(null, completed.accountError)
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

    private fun harness(
        scope: CoroutineScope,
        onAgentText: suspend (turnId: String, itemId: String, text: String) -> Unit = { _, _, _ -> },
        onTurnParts: suspend (turnId: String, parts: List<UIMessagePart>) -> Unit = { _, _ -> },
        assistantToolProfile: CodexAssistantToolProfile = CodexAssistantToolProfile.EMPTY,
    ): Harness {
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
        return Harness(
            CodexChatRuntime(
                session,
                scope,
                onAgentText = onAgentText,
                onTurnParts = onTurnParts,
                assistantToolProfile = assistantToolProfile,
            ),
            transport,
            connection,
        )
    }

    private data class Harness(
        val runtime: CodexChatRuntime,
        val transport: FakeTransport,
        val connection: CodexAppServerConnection,
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

    private suspend fun awaitClientMessage(transport: FakeTransport, index: Int): JsonObject =
        withTimeout(2_000) {
            while (transport.sentLines.size <= index) yield()
            Json.parseToJsonElement(transport.sentLines[index]).jsonObject
        }

    private suspend fun initialize(harness: Harness) = coroutineScope {
        val initializing = async { harness.connection.initialize() }
        val request = awaitClientMessage(harness.transport, 0)
        harness.transport.emit("""{"id":${request["id"]},"result":{"userAgent":"test","codexHome":"/home","platformFamily":"unix","platformOs":"linux"}}""")
        initializing.await(); assertEquals("initialized", awaitClientMessage(harness.transport, 1)["method"]?.jsonPrimitive?.content)
    }

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

    private fun dynamicToolParams(callId: String, tool: String, arguments: JsonObject) = buildJsonObject {
        put("threadId", "thread-1"); put("turnId", "turn-1"); put("callId", callId)
        put("tool", tool); put("arguments", arguments)
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
