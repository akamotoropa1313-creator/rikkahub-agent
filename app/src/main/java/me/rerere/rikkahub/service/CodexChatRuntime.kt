package me.rerere.rikkahub.service

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
import me.rerere.rikkahub.data.codex.appserver.*

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
    private val reasoning = java.util.Collections.synchronizedMap(linkedMapOf<Pair<String, String>, String>())
    private val commands = java.util.Collections.synchronizedMap(linkedMapOf<String, CodexAppServerItemSnapshot.CommandExecution>())
    private val files = java.util.Collections.synchronizedMap(linkedMapOf<String, CodexAppServerItemSnapshot.FileChange>())
    @Volatile private var turnDiff: String? = null
    private val terminalInteractions = mutableListOf<String>()
    private val terminalClaimed = ConcurrentHashMap.newKeySet<String>()
    private val recentTerminalTurns = ConcurrentLinkedQueue<String>()
    private val closed = AtomicBoolean(false)
    private val stopController = CodexTurnStopController()
    private val approvalResponses = ConcurrentHashMap.newKeySet<JsonRpcId>()
    @Volatile private var activeTurnId: String? = null
    private val capabilityBusy = AtomicBoolean(false)
    private val reviewTurns = ConcurrentHashMap.newKeySet<String>()
    private val reviewResultsPersisted = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var reviewStarting = false
    private val _review = MutableStateFlow(CodexReviewUiState())
    val review: StateFlow<CodexReviewUiState> = _review.asStateFlow()
    private val _capabilities = MutableStateFlow(CodexCapabilitiesUiState(connected = true))
    val capabilities: StateFlow<CodexCapabilitiesUiState> = _capabilities.asStateFlow()
    val tokenUsage: StateFlow<CodexTokenUsageTelemetry> = session.tokenUsageTracker.state

    private val usageCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        tokenUsage.collect { telemetry -> _state.value = _state.value.withTelemetry(telemetry) }
    }

    private val accountLoginCorrelation = CodexAccountLoginCorrelation()
    private val accountCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.accountApi.events.collect { event ->
            when (event) {
                is CodexAppServerAccountEvent.LoginCompleted ->
                    accountLoginCorrelation.onCompletion(event) { completion ->
                        _capabilities.value = applyAccountLoginCompletion(_capabilities.value, completion)
                    }
                is CodexAppServerAccountEvent.MalformedNotification ->
                    _capabilities.value = _capabilities.value.copy(accountError = event.cause.message ?: "Malformed account event")
                is CodexAppServerAccountEvent.Updated -> Unit
            }
        }
    }
    private val mcpCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.mcpApi.events.collect { event ->
            when (event) {
                is CodexAppServerMcpEvent.OAuthLoginCompleted ->
                    _capabilities.value = applyMcpOAuthCompletion(_capabilities.value, event)
                is CodexAppServerMcpEvent.MalformedNotification ->
                    _capabilities.value = _capabilities.value.copy(mcpError = event.cause.message ?: "Malformed MCP event")
                is CodexAppServerMcpEvent.ToolCallProgress -> Unit
            }
        }
    }

    private suspend fun capabilityOperation(block: suspend () -> Unit) {
        check(activeTurnId == null) { "A Codex turn is already running" }
        check(capabilityBusy.compareAndSet(false, true)) { "Another Codex operation is already running" }
        try { block() } finally { capabilityBusy.set(false) }
    }

    /** Starts a stable native inline review under the same operation/turn admission gate. */
    suspend fun startReview(target: CodexAppServerReviewTarget): CodexAppServerReviewStartResult {
        check(activeTurnId == null) { "A Codex turn is already running" }
        check(capabilityBusy.compareAndSet(false, true)) { "Another Codex operation is already running" }
        reviewStarting = true
        _review.value = CodexReviewUiState(starting = true, targetSummary = target.summary())
        try {
            val result = session.startReview(target)
            reviewTurns.add(result.turn.id)
            _review.value = CodexReviewUiState(activeTurnId = result.turn.id, targetSummary = target.summary())
            acceptStartResponse(result.turn.id, result.turn.status)
            return result
        } catch (cancelled: CancellationException) {
            _review.value = CodexReviewUiState(error = "Review canceled")
            throw cancelled
        } catch (failure: Throwable) {
            val message = if (failure is CodexAppServerResponseException && failure.error.code == -32601L)
                "Native code review is not supported by this App Server" else failure.safeMessage()
            _review.value = CodexReviewUiState(error = message)
            throw failure
        } finally {
            reviewStarting = false
            capabilityBusy.set(false)
        }
    }

    suspend fun refreshSkills(forceReload: Boolean = true) = capabilityOperation {
        _capabilities.value = _capabilities.value.copy(skillsLoading = true, skillsError = null)
        runCatching { session.skillsApi.list(cwds = emptyList(), forceReload = forceReload) }
            .onSuccess { _capabilities.value = _capabilities.value.copy(skillsLoading = false, skillGroups = it.data) }
            .onFailure { _capabilities.value = _capabilities.value.copy(skillsLoading = false, skillsError = it.safeMessage()); throw it }
    }

    suspend fun refreshModels() = capabilityOperation {
        _capabilities.value = _capabilities.value.copy(modelsLoading = true, modelsError = null)
        try {
            val models = session.modelApi.listAllVisible()
            _capabilities.value = _capabilities.value.copy(modelsLoading = false, models = models)
        } catch (failure: Throwable) {
            _capabilities.value = _capabilities.value.copy(modelsLoading = false, modelsError = failure.safeMessage())
            throw failure
        }
    }

    suspend fun loadThreadHistory(searchTerm: String? = null, loadMore: Boolean = false) = capabilityOperation {
        val old = _capabilities.value.threadHistory
        val request = resolveThreadHistoryRequest(old, searchTerm, loadMore)
        _capabilities.value = _capabilities.value.copy(
            threadHistory = old.copy(loading = true, error = null, searchTerm = request.searchTerm.orEmpty()),
        )
        try {
            val page = session.threadApi.listThreads(
                cursor = request.cursor,
                cwd = session.effectiveCwd,
                searchTerm = request.searchTerm,
            )
            val merged = if (loadMore) (old.threads + page.data).distinctBy { it.id } else page.data
            _capabilities.value = _capabilities.value.copy(threadHistory = old.copy(
                loaded = true, loading = false, threads = merged, nextCursor = page.nextCursor,
                backwardsCursor = page.backwardsCursor, error = null, searchTerm = request.searchTerm.orEmpty(),
            ))
        } catch (cancelled: CancellationException) {
            _capabilities.value = _capabilities.value.copy(threadHistory = old.copy(loading = false))
            throw cancelled
        }
        catch (failure: Throwable) {
            val message = if (failure is CodexAppServerResponseException && failure.error.code == -32601L)
                "Thread history is not supported by this App Server" else failure.safeMessage()
            _capabilities.value = _capabilities.value.copy(threadHistory = old.copy(loading = false, error = message))
            throw failure
        }
    }

    suspend fun readHistoryThread(threadId: String) = capabilityOperation {
        val old = _capabilities.value.threadHistory
        _capabilities.value = _capabilities.value.copy(
            threadHistory = old.copy(
                detailLoading = true,
                detailError = null,
                selectedThreadId = threadId,
                selectedThread = null,
            ),
        )
        try {
            val thread = session.threadApi.readThread(threadId)
            val current = _capabilities.value.threadHistory
            if (current.selectedThreadId == threadId) {
                _capabilities.value = _capabilities.value.copy(
                    threadHistory = current.copy(
                        selectedThread = thread,
                        detailLoading = false,
                        detailError = null,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            val current = _capabilities.value.threadHistory
            if (current.selectedThreadId == threadId) {
                _capabilities.value = _capabilities.value.copy(
                    threadHistory = current.copy(detailLoading = false),
                )
            }
            throw cancelled
        } catch (failure: Throwable) {
            val message = if (failure is CodexAppServerResponseException && failure.error.code == -32601L)
                "Thread history is not supported by this App Server" else failure.safeMessage()
            val current = _capabilities.value.threadHistory
            if (current.selectedThreadId == threadId) {
                _capabilities.value = _capabilities.value.copy(
                    threadHistory = current.copy(detailLoading = false, detailError = message),
                )
            }
            throw failure
        }
    }

    fun closeHistoryThread() {
        val current = _capabilities.value.threadHistory
        _capabilities.value = _capabilities.value.copy(
            threadHistory = current.copy(
                selectedThreadId = null,
                selectedThread = null,
                detailLoading = false,
                detailError = null,
            ),
        )
    }

    /** Explicit-only, partial-success configuration diagnostics. Existing snapshots survive errors. */
    suspend fun refreshConfigDiagnostics() = capabilityOperation {
        _capabilities.value = _capabilities.value.copy(
            configLoading = true, configError = null,
            requirementsLoading = true, requirementsError = null,
        )
        val result = session.configApi.readDiagnostics(session.effectiveCwd)
        result.config.fold(
            onSuccess = { _capabilities.value = _capabilities.value.copy(configLoading = false, effectiveConfig = it) },
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                _capabilities.value = _capabilities.value.copy(configLoading = false, configError = capabilityError(failure))
            },
        )
        result.requirements.fold(
            onSuccess = { value -> _capabilities.value = _capabilities.value.copy(requirementsLoading = false, requirements = value, requirementsLoaded = true) },
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                _capabilities.value = _capabilities.value.copy(requirementsLoading = false, requirementsError = capabilityError(failure))
            },
        )
    }

    suspend fun setSkillEnabled(skill: CodexSkillMetadata, enabled: Boolean) = capabilityOperation {
        _capabilities.value = _capabilities.value.copy(skillUpdatingPath = skill.path, skillsError = null)
        try {
            val effective = session.skillsApi.writeConfig(enabled = enabled, path = skill.path).effectiveEnabled
            _capabilities.value = _capabilities.value.copy(skillGroups = _capabilities.value.skillGroups.map { group ->
                group.copy(skills = group.skills.map { if (it.path == skill.path) it.copy(enabled = effective) else it })
            })
        } catch (failure: Throwable) {
            _capabilities.value = _capabilities.value.copy(skillsError = failure.safeMessage()); throw failure
        } finally { _capabilities.value = _capabilities.value.copy(skillUpdatingPath = null) }
    }

    suspend fun refreshAccount(refreshToken: Boolean = false) = capabilityOperation {
        _capabilities.value = _capabilities.value.copy(accountLoading = true, accountError = null)
        try { _capabilities.value = _capabilities.value.copy(account = session.accountApi.readAccount(refreshToken)) }
        catch (failure: Throwable) { _capabilities.value = _capabilities.value.copy(accountError = failure.safeMessage()); throw failure }
        finally { _capabilities.value = _capabilities.value.copy(accountLoading = false) }
    }

    suspend fun beginAccountLogin(launcher: CodexAppServerAuthUrlLauncher) = capabilityOperation {
        accountLoginCorrelation.beginAttempt {
            _capabilities.value = _capabilities.value.copy(accountSubmitting = true, accountError = null)
        }
        try {
            val pending = CodexAppServerOAuthHandoff(session.accountApi, launcher).beginChatGptLogin()
            accountLoginCorrelation.resolveStart(
                pending.loginId,
                onCompletion = { completion ->
                    _capabilities.value = applyAccountLoginCompletion(_capabilities.value, completion)
                },
                onPending = { loginId ->
                    _capabilities.value = _capabilities.value.copy(pendingLoginId = loginId)
                },
            )
        } catch (failure: CodexAppServerLoginHandoffException) {
            accountLoginCorrelation.resolveStart(
                failure.loginId,
                onCompletion = { completion ->
                    _capabilities.value = applyAccountLoginCompletion(_capabilities.value, completion)
                },
                onPending = { loginId ->
                    _capabilities.value = _capabilities.value.copy(pendingLoginId = loginId, accountError = failure.safeMessage())
                },
            )
            throw failure
        } catch (failure: Throwable) {
            accountLoginCorrelation.abortAttempt {
                _capabilities.value = _capabilities.value.copy(accountError = failure.safeMessage())
            }
            throw failure
        } finally { _capabilities.value = _capabilities.value.copy(accountSubmitting = false) }
    }

    suspend fun cancelAccountLogin() = capabilityOperation {
        val id = checkNotNull(_capabilities.value.pendingLoginId) { "No pending Codex sign-in" }
        session.accountApi.cancelLogin(id)
        _capabilities.value = _capabilities.value.copy(pendingLoginId = null, accountStatus = "Sign-in canceled")
    }
    suspend fun logoutAccount() = capabilityOperation {
        session.accountApi.logout()
        _capabilities.value = _capabilities.value.copy(account = session.accountApi.readAccount(), pendingLoginId = null, accountStatus = "Signed out")
    }

    suspend fun refreshMcp() = capabilityOperation { refreshMcpPages() }
    suspend fun reloadMcp() = capabilityOperation { session.mcpApi.reload(); refreshMcpPages() }
    private suspend fun refreshMcpPages() {
        _capabilities.value = _capabilities.value.copy(mcpLoading = true, mcpError = null)
        try {
            val statuses = mutableListOf<CodexMcpServerStatus>(); val seen = mutableSetOf<String>(); var cursor: String? = null
            do {
                val page = session.mcpApi.listStatus(cursor = cursor, detail = CodexMcpServerStatusDetail.ToolsAndAuthOnly, threadId = session.threadId)
                statuses += page.data
                cursor = page.nextCursor
                check(cursor == null || seen.add(cursor)) { "MCP status pagination repeated a cursor" }
                check(seen.size <= 100) { "MCP status pagination exceeded 100 pages" }
            } while (cursor != null)
            _capabilities.value = _capabilities.value.copy(mcpServers = statuses)
        } catch (failure: Throwable) { _capabilities.value = _capabilities.value.copy(mcpError = failure.safeMessage()); throw failure }
        finally { _capabilities.value = _capabilities.value.copy(mcpLoading = false) }
    }
    suspend fun beginMcpOAuth(name: String, launcher: CodexAppServerAuthUrlLauncher) = capabilityOperation {
        _capabilities.value = _capabilities.value.copy(mcpError = null)
        try {
            runCodexMcpOAuthBegin(name, { pending -> _capabilities.value = _capabilities.value.copy(pendingMcpServer = pending) }) {
                val result = session.mcpApi.beginOAuthLogin(name, threadId = session.threadId)
                result.authorizationUrlForLaunch().also(::validateCodexAppServerAuthUrl).let(launcher::launch)
            }
        } catch (failure: Throwable) {
            _capabilities.value = _capabilities.value.copy(mcpError = failure.safeMessage())
            throw failure
        }
    }

    private fun record(turnId: String) = turns.computeIfAbsent(turnId) { TurnRecord() }

    private suspend fun terminal(turnId: String, status: CodexAppServerTurnStatus, snapshot: CodexAppServerTurnSnapshot? = null) {
        val turn = record(turnId)
        if (!terminalClaimed.add(turnId)) {
            val current = _state.value
            if (current is CodexConversationUiState.Terminal && current.turnId == turnId && snapshot != null) {
                _state.value = current.copy(diagnostics = snapshot)
            }
            return
        }
        recentTerminalTurns.add(turnId)
        while (recentTerminalTurns.size > 32) terminalClaimed.remove(recentTerminalTurns.poll())
        turn.status = status
        val isCurrent = activeTurnId == null || activeTurnId == turnId
        if (activeTurnId == turnId) activeTurnId = null
        if (isCurrent) _state.value = CodexConversationUiState.Terminal(session.threadId, turnId, status, activity(turnId), snapshot, tokenUsage.value)
        onTurnTerminal(turnId)
        turn.terminal.complete(status)
    }

    private fun interruptWhenKnown(turnId: String) {
        if (!stopController.onTurnKnown(turnId)) return
        scope.launch {
            runCatching { session.interruptTurn(turnId) }.onFailure(onInterruptFailure)
        }
    }

    private fun activity(turnId: String) = CodexConversationActivity(
        reasoning = synchronized(reasoning) { reasoning.filterKeys { it.first == turnId }.values.joinToString("\n") },
        commands = synchronized(commands) { commands.values.toList() },
        files = synchronized(files) { files.values.toList() },
        diff = turnDiff,
        terminalInteractions = synchronized(terminalInteractions) { terminalInteractions.toList() },
    )

    private fun publishActivity(turnId: String) {
        val snapshot = activity(turnId)
        val current = _state.value
        _state.value = if (current is CodexConversationUiState.WaitingForApproval) current.copy(activity = snapshot)
        else CodexConversationUiState.Running(session.threadId, turnId, snapshot, tokenUsage.value)
    }

    private val collector: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.turnApi.events.collect { event ->
            if (event.threadIdOrNull() != session.threadId) return@collect
            when (event) {
                is CodexAppServerTurnEvent.TurnStarted -> {
                    val turn = record(event.turn.id)
                    if (reviewStarting) reviewTurns.add(event.turn.id)
                    if (!terminalClaimed.contains(event.turn.id) && (activeTurnId == null || activeTurnId == event.turn.id)) {
                        activeTurnId = event.turn.id
                        interruptWhenKnown(event.turn.id)
                        _state.value = CodexConversationUiState.Running(session.threadId, event.turn.id, telemetry = tokenUsage.value)
                    }
                }
                is CodexAppServerTurnEvent.AgentMessageDelta -> {
                    val turn = record(event.turnId)
                    if (!terminalClaimed.contains(event.turnId) && (activeTurnId == null || activeTurnId == event.turnId)) activeTurnId = event.turnId
                    val key = event.turnId to event.itemId
                    val accumulated = text.compute(key) { _, old -> old.orEmpty() + event.delta }!!
                    if (!reviewStarting && event.turnId !in reviewTurns) onAgentText(event.turnId, event.itemId, accumulated)
                }
                is CodexAppServerTurnEvent.TurnCompleted -> terminal(event.turn.id, event.turn.status, event.turn)
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
                    if (event.item is CodexAppServerItemSnapshot.EnteredReviewMode) reviewTurns.add(event.turnId)
                    when (val item = event.item) {
                        is CodexAppServerItemSnapshot.CommandExecution -> commands[item.id] = item
                        is CodexAppServerItemSnapshot.FileChange -> files[item.id] = item
                        else -> Unit
                    }
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.ItemCompleted -> {
                    val item = event.item
                    when (item) {
                        is CodexAppServerItemSnapshot.CommandExecution -> commands[item.id] = item
                        is CodexAppServerItemSnapshot.FileChange -> files[item.id] = item
                        else -> Unit
                    }
                    if (item is CodexAppServerItemSnapshot.ExitedReviewMode && reviewResultsPersisted.add(event.turnId)) {
                        reviewTurns.add(event.turnId)
                        onAgentText(event.turnId, item.id, item.review)
                    }
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.CommandExecutionOutputDelta -> {
                    commands.computeIfPresent(event.itemId) { _, item -> item.copy(aggregatedOutput = item.aggregatedOutput.orEmpty() + event.delta) }
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.TerminalInteraction -> {
                    synchronized(terminalInteractions) {
                        terminalInteractions += "${event.processId}: ${event.stdin}"
                    }
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
                    _state.value = CodexConversationUiState.WaitingForApproval(
                        event,
                        activity = activity(event.request.turnId),
                        telemetry = tokenUsage.value,
                    )
                is CodexAppServerApprovalEvent.FileChangeRequest ->
                    _state.value = CodexConversationUiState.WaitingForApproval(
                        event,
                        fileChange = files[event.request.itemId]?.takeIf { event.request.turnId == activeTurnId },
                        activity = activity(event.request.turnId),
                        telemetry = tokenUsage.value,
                    )
                is CodexAppServerApprovalEvent.Resolved -> {
                    approvalResponses.add(event.requestId)
                    val waiting = _state.value as? CodexConversationUiState.WaitingForApproval
                    if (waiting?.requestId == event.requestId) {
                        _state.value = activeTurnId?.let { CodexConversationUiState.Running(session.threadId, it, waiting.activity, tokenUsage.value) }
                            ?: CodexConversationUiState.Ready(session.threadId, tokenUsage.value)
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
            _state.value = CodexConversationUiState.Running(session.threadId, turnId, activity(turnId), tokenUsage.value)
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
        if (turnId in reviewTurns) _review.value = _review.value.copy(activeTurnId = null)
        // Keep a small terminal-id window so delayed duplicate notifications remain idempotent.
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        collector.cancel()
        failureCollector.cancel()
        approvalCollector.cancel()
        accountCollector.cancel()
        mcpCollector.cancel()
        usageCollector.cancel()
        _capabilities.value = CodexCapabilitiesUiState(connected = false)
        session.close()
    }
}

data class CodexCapabilitiesUiState(
    val connected: Boolean = false,
    val modelsLoading: Boolean = false,
    val models: List<CodexAppServerModel> = emptyList(),
    val modelsError: String? = null,
    val skillsLoading: Boolean = false,
    val skillGroups: List<CodexSkillsListEntry> = emptyList(),
    val skillUpdatingPath: String? = null,
    val skillsError: String? = null,
    val accountLoading: Boolean = false,
    val accountSubmitting: Boolean = false,
    val account: CodexAppServerAccountSnapshot? = null,
    val pendingLoginId: String? = null,
    val accountStatus: String? = null,
    val accountError: String? = null,
    val mcpLoading: Boolean = false,
    val mcpServers: List<CodexMcpServerStatus> = emptyList(),
    val pendingMcpServer: String? = null,
    val mcpStatus: String? = null,
    val mcpError: String? = null,
    val configLoading: Boolean = false,
    val effectiveConfig: CodexEffectiveConfigSnapshot? = null,
    val configError: String? = null,
    val requirementsLoading: Boolean = false,
    val requirements: CodexConfigRequirementsSnapshot? = null,
    val requirementsLoaded: Boolean = false,
    val requirementsError: String? = null,
    val threadHistory: CodexThreadHistoryUiState = CodexThreadHistoryUiState(),
)

data class CodexThreadHistoryUiState(
    val loaded: Boolean = false, val loading: Boolean = false,
    val threads: List<CodexAppServerThreadSnapshot> = emptyList(),
    val nextCursor: String? = null, val backwardsCursor: String? = null,
    val error: String? = null, val searchTerm: String = "",
    val selectedThreadId: String? = null, val detailLoading: Boolean = false,
    val selectedThread: CodexAppServerThreadSnapshot? = null, val detailError: String? = null,
)

data class CodexReviewUiState(
    val starting: Boolean = false,
    val activeTurnId: String? = null,
    val targetSummary: String? = null,
    val error: String? = null,
) {
    val inProgress: Boolean get() = starting || activeTurnId != null
}

private fun CodexAppServerReviewTarget.summary(): String = when (this) {
    CodexAppServerReviewTarget.UncommittedChanges -> "Working tree"
    is CodexAppServerReviewTarget.BaseBranch -> "Base branch: $branch"
    is CodexAppServerReviewTarget.Commit -> "Commit: $sha"
    is CodexAppServerReviewTarget.Custom -> "Custom review"
}

internal fun applyMcpOAuthCompletion(state: CodexCapabilitiesUiState, event: CodexAppServerMcpEvent.OAuthLoginCompleted): CodexCapabilitiesUiState {
    if (state.pendingMcpServer != event.name) return state
    return state.copy(
        pendingMcpServer = null,
        mcpStatus = if (event.success) "${event.name} sign-in completed" else event.error ?: "${event.name} sign-in failed",
        mcpError = event.error,
    )
}

internal fun applyAccountLoginCompletion(state: CodexCapabilitiesUiState, event: CodexAppServerAccountEvent.LoginCompleted): CodexCapabilitiesUiState {
    val pending = state.pendingLoginId
    if (event.loginId != null && pending != null && event.loginId != pending) return state
    return state.copy(
        pendingLoginId = null,
        accountStatus = if (event.success) "Sign-in completed" else event.error ?: "Sign-in failed",
        accountError = event.error,
    )
}

private fun Throwable.safeMessage(): String = message?.replace(Regex("https?://\\S+"), "<redacted>") ?: this::class.simpleName.orEmpty()

private fun capabilityError(failure: Throwable): String =
    if (failure is CodexAppServerResponseException && failure.error.code == -32601L) "Not supported by this App Server"
    else failure.safeMessage()

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

data class CodexConversationActivity(
    val reasoning: String = "",
    val commands: List<CodexAppServerItemSnapshot.CommandExecution> = emptyList(),
    val files: List<CodexAppServerItemSnapshot.FileChange> = emptyList(),
    val diff: String? = null,
    val terminalInteractions: List<String> = emptyList(),
)

sealed interface CodexConversationUiState {
    data object Disabled : CodexConversationUiState
    data object Disconnected : CodexConversationUiState
    data object Opening : CodexConversationUiState
    data class Ready(val threadId: String, val telemetry: CodexTokenUsageTelemetry = CodexTokenUsageTelemetry()) : CodexConversationUiState
    data class Running(val threadId: String, val turnId: String, val activity: CodexConversationActivity = CodexConversationActivity(), val telemetry: CodexTokenUsageTelemetry = CodexTokenUsageTelemetry()) : CodexConversationUiState
    data class Terminal(val threadId: String, val turnId: String, val status: CodexAppServerTurnStatus, val activity: CodexConversationActivity = CodexConversationActivity(), val diagnostics: CodexAppServerTurnSnapshot? = null, val telemetry: CodexTokenUsageTelemetry = CodexTokenUsageTelemetry()) : CodexConversationUiState
    data class WaitingForApproval(
        val event: CodexAppServerApprovalEvent,
        val submitting: Boolean = false,
        val fileChange: CodexAppServerItemSnapshot.FileChange? = null,
        val activity: CodexConversationActivity = CodexConversationActivity(),
        val telemetry: CodexTokenUsageTelemetry = CodexTokenUsageTelemetry(),
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

private fun CodexConversationUiState.withTelemetry(value: CodexTokenUsageTelemetry): CodexConversationUiState = when (this) {
    is CodexConversationUiState.Ready -> copy(telemetry = value)
    is CodexConversationUiState.Running -> copy(telemetry = value)
    is CodexConversationUiState.Terminal -> copy(telemetry = value)
    is CodexConversationUiState.WaitingForApproval -> copy(telemetry = value)
    else -> this
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
