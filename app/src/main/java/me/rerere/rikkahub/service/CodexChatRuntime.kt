package me.rerere.rikkahub.service

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
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
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnEvent
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnStatus

/** Conversation-owned application adapter around the single Stage 13 protocol session. */
class CodexChatRuntime(
    val session: CodexAppServerConversationSession,
    scope: CoroutineScope,
    private val onAgentText: suspend (turnId: String, itemId: String, text: String) -> Unit,
    private val onTurnTerminal: suspend (turnId: String) -> Unit = {},
    private val onFailure: (CodexChatRuntime, Throwable) -> Unit = { _, _ -> },
) : Closeable {
    private data class TurnRecord(
        val terminal: CompletableDeferred<CodexAppServerTurnStatus> = CompletableDeferred(),
        @Volatile var status: CodexAppServerTurnStatus = CodexAppServerTurnStatus.InProgress,
    )

    private val _state = MutableStateFlow<CodexConversationUiState>(CodexConversationUiState.Ready(session.threadId))
    val state: StateFlow<CodexConversationUiState> = _state.asStateFlow()
    private val turns = ConcurrentHashMap<String, TurnRecord>()
    private val text = ConcurrentHashMap<Pair<String, String>, String>()
    private val terminalClaimed = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicBoolean(false)
    @Volatile private var activeTurnId: String? = null

    private fun record(turnId: String) = turns.computeIfAbsent(turnId) { TurnRecord() }

    private suspend fun terminal(turnId: String, status: CodexAppServerTurnStatus) {
        val turn = record(turnId)
        if (!terminalClaimed.add(turnId)) return
        turn.status = status
        val isCurrent = activeTurnId == null || activeTurnId == turnId
        if (activeTurnId == turnId) activeTurnId = null
        if (isCurrent) _state.value = CodexConversationUiState.Terminal(session.threadId, turnId, status)
        onTurnTerminal(turnId)
        turn.terminal.complete(status)
    }

    private val collector: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.turnApi.events.collect { event ->
            if (event.threadIdOrNull() != session.threadId) return@collect
            when (event) {
                is CodexAppServerTurnEvent.TurnStarted -> {
                    val turn = record(event.turn.id)
                    if (!terminalClaimed.contains(event.turn.id) && (activeTurnId == null || activeTurnId == event.turn.id)) {
                        activeTurnId = event.turn.id
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
                else -> Unit
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

    /** Monotonic: an early terminal notification always wins over a later inProgress response. */
    suspend fun acceptStartResponse(turnId: String, status: CodexAppServerTurnStatus) {
        val turn = record(turnId)
        if (terminalClaimed.contains(turnId)) return
        if (status is CodexAppServerTurnStatus.InProgress) {
            activeTurnId = turnId
            _state.value = CodexConversationUiState.Running(session.threadId, turnId)
        } else terminal(turnId, status)
    }

    suspend fun awaitTurnTerminal(turnId: String): CodexAppServerTurnStatus = record(turnId).terminal.await()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        collector.cancel()
        failureCollector.cancel()
        session.close()
    }
}

sealed interface CodexConversationUiState {
    data object Disabled : CodexConversationUiState
    data object Disconnected : CodexConversationUiState
    data object Opening : CodexConversationUiState
    data class Ready(val threadId: String) : CodexConversationUiState
    data class Running(val threadId: String, val turnId: String) : CodexConversationUiState
    data class Terminal(val threadId: String, val turnId: String, val status: CodexAppServerTurnStatus) : CodexConversationUiState
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
