package me.rerere.rikkahub.service

import java.io.Closeable
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

/** Thin application adapter; protocol and process ownership remain in the Stage 13 session. */
class CodexChatRuntime(
    val session: CodexAppServerConversationSession,
    scope: CoroutineScope,
    private val onAgentText: suspend (turnId: String, itemId: String, text: String) -> Unit,
) : Closeable {
    private val _state = MutableStateFlow<CodexConversationUiState>(
        CodexConversationUiState.Ready(session.threadId)
    )
    val state: StateFlow<CodexConversationUiState> = _state.asStateFlow()
    private val text = linkedMapOf<Pair<String, String>, String>()
    private var activeTurnId: String? = null
    private val collector: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.turnApi.events.collect { event ->
            if (event.threadIdOrNull() != session.threadId) return@collect
            when (event) {
                is CodexAppServerTurnEvent.TurnStarted -> {
                    activeTurnId = event.turn.id
                    _state.value = CodexConversationUiState.Running(session.threadId, event.turn.id)
                }
                is CodexAppServerTurnEvent.AgentMessageDelta -> {
                    activeTurnId = event.turnId
                    val key = event.turnId to event.itemId
                    val accumulated = text.getOrDefault(key, "") + event.delta
                    text[key] = accumulated
                    onAgentText(event.turnId, event.itemId, accumulated)
                }
                is CodexAppServerTurnEvent.TurnCompleted -> {
                    activeTurnId = null
                    _state.value = CodexConversationUiState.Terminal(session.threadId, event.turn.id, event.turn.status)
                }
                else -> Unit
            }
        }
    }
    private val failureCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.failure.collect { it?.let { failure ->
            activeTurnId = null
            _state.value = CodexConversationUiState.Failed(failure.message ?: failure.toString())
        } }
    }

    fun markStarting() { _state.value = CodexConversationUiState.Opening }
    fun activeTurnId(): String? = activeTurnId
    fun acceptStartResponse(turnId: String, status: CodexAppServerTurnStatus) {
        if (status is CodexAppServerTurnStatus.InProgress) {
            activeTurnId = turnId
            _state.value = CodexConversationUiState.Running(session.threadId, turnId)
        } else {
            activeTurnId = null
            _state.value = CodexConversationUiState.Terminal(session.threadId, turnId, status)
        }
    }
    override fun close() { collector.cancel(); failureCollector.cancel(); session.close() }
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
