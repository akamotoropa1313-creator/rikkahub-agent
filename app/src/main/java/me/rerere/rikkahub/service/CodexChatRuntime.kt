package me.rerere.rikkahub.service

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConversationSession
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerApprovalEvent
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandApprovalDecision
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerFileChangeApprovalDecision
import me.rerere.rikkahub.data.codex.appserver.JsonRpcId
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerItemSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnEvent
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnStatus

/** Conversation-owned application adapter around the single Stage 13 protocol session. */
class CodexChatRuntime(
    val session: CodexAppServerConversationSession,
    private val scope: CoroutineScope,
    private val onAgentText: suspend (turnId: String, itemId: String, text: String) -> Unit,
    private val onTurnTerminal: suspend (turnId: String) -> Unit = {},
    private val onFailure: (CodexChatRuntime, Throwable) -> Unit = { _, _ -> },
    private val onInterruptFailure: (Throwable) -> Unit = {},
) : Closeable {
    private data class TurnRecord(
        val terminal: CompletableDeferred<CodexAppServerTurnStatus> = CompletableDeferred(),
        @Volatile var status: CodexAppServerTurnStatus = CodexAppServerTurnStatus.InProgress,
    )

    private val _state = MutableStateFlow<CodexConversationUiState>(CodexConversationUiState.Ready(session.threadId))
    val state: StateFlow<CodexConversationUiState> = _state.asStateFlow()
    private val turns = ConcurrentHashMap<String, TurnRecord>()
    private val text = ConcurrentHashMap<Pair<String, String>, String>()
    private val reasoning = ConcurrentHashMap<Pair<String, String>, String>()
    private val commands = ConcurrentHashMap<String, CodexAppServerItemSnapshot.CommandExecution>()
    private val files = ConcurrentHashMap<String, CodexAppServerItemSnapshot.FileChange>()
    @Volatile private var turnDiff: String? = null
    private val terminalInteractions = mutableListOf<String>()
    private val terminalClaimed = ConcurrentHashMap.newKeySet<String>()
    private val recentTerminalTurns = ConcurrentLinkedQueue<String>()
    private val closed = AtomicBoolean(false)
    private val stopController = CodexTurnStopController()
    private val approvalResponses = ConcurrentHashMap.newKeySet<JsonRpcId>()
    @Volatile private var activeTurnId: String? = null

    private fun record(turnId: String) = turns.computeIfAbsent(turnId) { TurnRecord() }

    private suspend fun terminal(turnId: String, status: CodexAppServerTurnStatus) {
        val turn = record(turnId)
        if (!terminalClaimed.add(turnId)) return
        recentTerminalTurns.add(turnId)
        while (recentTerminalTurns.size > 32) terminalClaimed.remove(recentTerminalTurns.poll())
        turn.status = status
        val isCurrent = activeTurnId == null || activeTurnId == turnId
        if (activeTurnId == turnId) activeTurnId = null
        if (isCurrent) _state.value = CodexConversationUiState.Terminal(session.threadId, turnId, status)
        onTurnTerminal(turnId)
        turn.terminal.complete(status)
    }

    private fun interruptWhenKnown(turnId: String) {
        if (!stopController.onTurnKnown(turnId)) return
        scope.launch {
            runCatching { session.interruptTurn(turnId) }.onFailure(onInterruptFailure)
        }
    }

    private fun publishActivity(turnId: String) {
        if (_state.value is CodexConversationUiState.WaitingForApproval) return
        _state.value = CodexConversationUiState.Activity(
            session.threadId, turnId,
            reasoning.filterKeys { it.first == turnId }.values.joinToString("\n"),
            commands.values.toList(), files.values.toList(), turnDiff, terminalInteractions.toList(),
        )
    }

    private val collector: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.turnApi.events.collect { event ->
            if (event.threadIdOrNull() != session.threadId) return@collect
            when (event) {
                is CodexAppServerTurnEvent.TurnStarted -> {
                    val turn = record(event.turn.id)
                    if (!terminalClaimed.contains(event.turn.id) && (activeTurnId == null || activeTurnId == event.turn.id)) {
                        activeTurnId = event.turn.id
                        interruptWhenKnown(event.turn.id)
                        _state.value = CodexConversationUiState.Running(session.threadId, event.turn.id)
                    }
                }
                is CodexAppServerTurnEvent.AgentMessageDelta -> {
                    val turn = record(event.turnId)
                    if (!terminalClaimed.contains(event.turnId) && (activeTurnId == null || activeTurnId == event.turnId)) activeTurnId = event.turnId
                    val key = event.turnId to event.itemId
                    val accumulated = text.compute(key) { _, old -> old.orEmpty() + event.delta }!!
                    onAgentText(event.turnId, event.itemId, accumulated)
                }
                is CodexAppServerTurnEvent.TurnCompleted -> terminal(event.turn.id, event.turn.status)
                is CodexAppServerTurnEvent.ReasoningSummaryTextDelta -> {
                    reasoning.compute(event.turnId to event.itemId) { _, old -> old.orEmpty() + event.delta }
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.ReasoningSummaryPartAdded -> publishActivity(event.turnId)
                is CodexAppServerTurnEvent.ReasoningTextDelta -> {
                    reasoning.compute(event.turnId to event.itemId) { _, old -> old.orEmpty() + event.delta }
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.ItemStarted -> {
                    when (val item = event.item) {
                        is CodexAppServerItemSnapshot.CommandExecution -> commands[item.id] = item
                        is CodexAppServerItemSnapshot.FileChange -> files[item.id] = item
                        else -> Unit
                    }
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.ItemCompleted -> {
                    when (val item = event.item) {
                        is CodexAppServerItemSnapshot.CommandExecution -> commands[item.id] = item
                        is CodexAppServerItemSnapshot.FileChange -> files[item.id] = item
                        else -> Unit
                    }
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.CommandExecutionOutputDelta -> {
                    commands.computeIfPresent(event.itemId) { _, item -> item.copy(aggregatedOutput = item.aggregatedOutput.orEmpty() + event.delta) }
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.TerminalInteraction -> {
                    terminalInteractions += "${event.processId}: ${event.stdin}"
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.FileChangePatchUpdated -> {
                    val updated = files.computeIfPresent(event.itemId) { _, item -> item.copy(changes = event.changes) }
                    val waiting = _state.value as? CodexConversationUiState.WaitingForApproval
                    if (waiting?.event is CodexAppServerApprovalEvent.FileChangeRequest &&
                        waiting.event.request.itemId == event.itemId && waiting.event.request.turnId == event.turnId
                    ) _state.value = waiting.copy(fileChange = updated)
                    else publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.TurnDiffUpdated -> { turnDiff = event.diff; publishActivity(event.turnId) }
                is CodexAppServerTurnEvent.MalformedNotification -> _state.value = CodexConversationUiState.Failed(event.cause.message ?: "Malformed Codex event")
            }
        }
    }

    private val approvalCollector: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.approvalApi.events.collect { event ->
            when (event) {
                is CodexAppServerApprovalEvent.CommandExecutionRequest ->
                    _state.value = CodexConversationUiState.WaitingForApproval(event)
                is CodexAppServerApprovalEvent.FileChangeRequest ->
                    _state.value = CodexConversationUiState.WaitingForApproval(
                        event, fileChange = files[event.request.itemId]?.takeIf { event.request.turnId == activeTurnId }
                    )
                is CodexAppServerApprovalEvent.Resolved -> {
                    approvalResponses.add(event.requestId)
                    val waiting = _state.value as? CodexConversationUiState.WaitingForApproval
                    if (waiting?.requestId == event.requestId) {
                        _state.value = activeTurnId?.let { CodexConversationUiState.Running(session.threadId, it) }
                            ?: CodexConversationUiState.Ready(session.threadId)
                    }
                }
                is CodexAppServerApprovalEvent.MalformedRequest -> _state.value = CodexConversationUiState.Failed(event.cause.message ?: "Malformed approval")
                is CodexAppServerApprovalEvent.MalformedNotification -> _state.value = CodexConversationUiState.Failed(event.cause.message ?: "Malformed approval")
            }
        }
    }

    private val failureCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.failure.collect { failure ->
            failure ?: return@collect
            activeTurnId = null
            _state.value = CodexConversationUiState.Failed(failure.message ?: failure.toString())
            turns.values.forEach { it.terminal.completeExceptionally(failure) }
            onFailure(this@CodexChatRuntime, failure)
        }
    }

    fun activeTurnId(): String? = activeTurnId

    /** Records Stop even while turn/start is outstanding; exact ID may arrive by event or response. */
    suspend fun respondCommandApproval(id: JsonRpcId, decision: CodexAppServerCommandApprovalDecision): Boolean {
        if (!approvalResponses.add(id)) return false
        val waiting = _state.value as? CodexConversationUiState.WaitingForApproval
        if (waiting?.requestId == id) _state.value = waiting.copy(submitting = true)
        return runCatching { session.approvalApi.respondCommandApproval(id, decision) }.onFailure {
            approvalResponses.remove(id)
            if (waiting != null) _state.value = waiting.copy(submitting = false)
        }.isSuccess
    }

    suspend fun respondFileApproval(id: JsonRpcId, decision: CodexAppServerFileChangeApprovalDecision): Boolean {
        if (!approvalResponses.add(id)) return false
        val waiting = _state.value as? CodexConversationUiState.WaitingForApproval
        if (waiting?.requestId == id) _state.value = waiting.copy(submitting = true)
        return runCatching { session.approvalApi.respondFileChangeApproval(id, decision) }.onFailure {
            approvalResponses.remove(id)
            if (waiting != null) _state.value = waiting.copy(submitting = false)
        }.isSuccess
    }

    fun requestStop() {
        stopController.requestStop()
        activeTurnId?.let(::interruptWhenKnown)
    }

    /** Monotonic: an early terminal notification always wins over a later inProgress response. */
    suspend fun acceptStartResponse(turnId: String, status: CodexAppServerTurnStatus) {
        val turn = record(turnId)
        if (terminalClaimed.contains(turnId)) return
        if (status is CodexAppServerTurnStatus.InProgress) {
            activeTurnId = turnId
            interruptWhenKnown(turnId)
            _state.value = CodexConversationUiState.Running(session.threadId, turnId)
        } else terminal(turnId, status)
    }

    suspend fun awaitTurnTerminal(turnId: String): CodexAppServerTurnStatus = record(turnId).terminal.await()

    /** Called only after start response processing and terminal waiting are both complete. */
    fun finishTurn(turnId: String) {
        turns.remove(turnId)
        text.keys.removeAll { it.first == turnId }
        reasoning.keys.removeAll { it.first == turnId }
        commands.clear(); files.clear(); turnDiff = null; terminalInteractions.clear()
        stopController.finishTurn(turnId)
        // Keep a small terminal-id window so delayed duplicate notifications remain idempotent.
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        collector.cancel()
        failureCollector.cancel()
        approvalCollector.cancel()
        session.close()
    }
}

internal class CodexTurnStopController {
    private val stopRequested = AtomicBoolean(false)
    private val interruptSent = AtomicBoolean(false)
    @Volatile private var turnId: String? = null

    fun requestStop() { stopRequested.set(true) }
    fun onTurnKnown(id: String): Boolean {
        turnId = id
        return stopRequested.get() && interruptSent.compareAndSet(false, true)
    }
    fun finishTurn(id: String) {
        if (turnId != null && turnId != id) return
        turnId = null
        stopRequested.set(false)
        interruptSent.set(false)
    }
}

sealed interface CodexConversationUiState {
    data object Disabled : CodexConversationUiState
    data object Disconnected : CodexConversationUiState
    data object Opening : CodexConversationUiState
    data class Ready(val threadId: String) : CodexConversationUiState
    data class Running(val threadId: String, val turnId: String) : CodexConversationUiState
    data class Terminal(val threadId: String, val turnId: String, val status: CodexAppServerTurnStatus) : CodexConversationUiState
    data class Activity(
        val threadId: String, val turnId: String, val reasoning: String,
        val commands: List<CodexAppServerItemSnapshot.CommandExecution>,
        val files: List<CodexAppServerItemSnapshot.FileChange>, val diff: String?,
        val terminalInteractions: List<String>,
    ) : CodexConversationUiState
    data class WaitingForApproval(
        val event: CodexAppServerApprovalEvent,
        val submitting: Boolean = false,
        val fileChange: CodexAppServerItemSnapshot.FileChange? = null,
    ) : CodexConversationUiState {
        val requestId: JsonRpcId? get() = when (event) {
            is CodexAppServerApprovalEvent.CommandExecutionRequest -> event.requestId
            is CodexAppServerApprovalEvent.FileChangeRequest -> event.requestId
            else -> null
        }
    }
    data class StaleBinding(val reason: String) : CodexConversationUiState
    data class WorkspaceMismatch(val boundWorkspaceId: String, val requestedWorkspaceId: String) : CodexConversationUiState
    data class Failed(val message: String) : CodexConversationUiState
}

private fun CodexAppServerTurnEvent.threadIdOrNull(): String? = when (this) {
    is CodexAppServerTurnEvent.TurnStarted -> threadId
    is CodexAppServerTurnEvent.TurnCompleted -> threadId
    is CodexAppServerTurnEvent.ItemStarted -> threadId
    is CodexAppServerTurnEvent.ItemCompleted -> threadId
    is CodexAppServerTurnEvent.AgentMessageDelta -> threadId
    is CodexAppServerTurnEvent.ReasoningSummaryTextDelta -> threadId
    is CodexAppServerTurnEvent.ReasoningSummaryPartAdded -> threadId
    is CodexAppServerTurnEvent.ReasoningTextDelta -> threadId
    is CodexAppServerTurnEvent.CommandExecutionOutputDelta -> threadId
    is CodexAppServerTurnEvent.TerminalInteraction -> threadId
    is CodexAppServerTurnEvent.FileChangePatchUpdated -> threadId
    is CodexAppServerTurnEvent.TurnDiffUpdated -> threadId
    is CodexAppServerTurnEvent.MalformedNotification -> null
}
