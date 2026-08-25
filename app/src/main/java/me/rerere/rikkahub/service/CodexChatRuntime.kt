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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
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
    private val onAgentText: suspend (turnId: String, itemId: String, text: String) -> Unit = { _, _, _ -> },
    private val onTurnParts: suspend (turnId: String, parts: List<UIMessagePart>) -> Unit = { _, _ -> },
    val harnessTarget: CodexHarnessModelTarget? = null,
    private val onTokenUsage: suspend (CodexTokenUsageSnapshot) -> Unit = {},
    private val onTurnTerminal: suspend (turnId: String) -> Unit = {},
    private val shouldAutoApprove: suspend (CodexAppServerApprovalEvent) -> Boolean = { false },
    private val onFailure: (CodexChatRuntime, Throwable) -> Unit = { _, _ -> },
    private val onInterruptFailure: (Throwable) -> Unit = {},
) : Closeable {
    private data class TurnRecord(
        val terminal: CompletableDeferred<CodexAppServerTurnStatus> = CompletableDeferred(),
        @Volatile var status: CodexAppServerTurnStatus = CodexAppServerTurnStatus.InProgress,
    )

    private enum class ApprovalKind { Command, FileChange }
    private data class PendingApproval(
        val kind: ApprovalKind,
        val threadId: String,
        val turnId: String,
        val itemId: String,
    )

    private val _state = MutableStateFlow<CodexConversationUiState>(CodexConversationUiState.Ready(session.threadId))
    val state: StateFlow<CodexConversationUiState> = _state.asStateFlow()
    private val turns = ConcurrentHashMap<String, TurnRecord>()
    private val text = ConcurrentHashMap<Pair<String, String>, String>()
    private val reasoning = java.util.Collections.synchronizedMap(linkedMapOf<Pair<String, String>, String>())
    private val commands = java.util.Collections.synchronizedMap(linkedMapOf<Pair<String, String>, CodexAppServerItemSnapshot.CommandExecution>())
    private val files = java.util.Collections.synchronizedMap(linkedMapOf<Pair<String, String>, CodexAppServerItemSnapshot.FileChange>())
    private val messageTimeline = CodexChatMessageTimeline()
    private val turnDiffs = ConcurrentHashMap<String, String>()
    private val terminalInteractions = java.util.Collections.synchronizedMap(linkedMapOf<String, MutableList<String>>())
    private val terminalClaimed = ConcurrentHashMap.newKeySet<String>()
    private val recentTerminalTurns = ConcurrentLinkedQueue<String>()
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val stopController = CodexTurnStopController()
    private val approvalLock = Any()
    private val pendingApprovals = linkedMapOf<JsonRpcId, PendingApproval>()
    private val approvalResponses = linkedSetOf<JsonRpcId>()
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
        tokenUsage.collect { telemetry ->
            _state.value = _state.value.withTelemetry(telemetry)
            telemetry.latest?.let { onTokenUsage(it) }
        }
    }

    private val accountLoginCorrelation = CodexAccountLoginCorrelation()
    private var accountSnapshotRefreshJob: Job? = null
    private val accountCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.accountApi.events.collect { event ->
            when (event) {
                is CodexAppServerAccountEvent.LoginCompleted ->
                    accountLoginCorrelation.onCompletion(event) { completion ->
                        completeAccountLogin(completion)
                    }
                is CodexAppServerAccountEvent.MalformedNotification ->
                    _capabilities.value = _capabilities.value.copy(accountError = event.cause.message ?: "アカウントイベントの形式が不正です")
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
                    _capabilities.value = _capabilities.value.copy(mcpError = event.cause.message ?: "MCPイベントの形式が不正です")
                is CodexAppServerMcpEvent.ToolCallProgress -> Unit
            }
        }
    }

    private suspend fun capabilityOperation(block: suspend () -> Unit) {
        check(activeTurnId == null) { "A Codex turn is already running" }
        check(capabilityBusy.compareAndSet(false, true)) { "Another Codex operation is already running" }
        try { block() } finally { capabilityBusy.set(false) }
    }

    /** A successful browser handoff is not visible in account/read until the cached snapshot is refreshed. */
    private fun completeAccountLogin(event: CodexAppServerAccountEvent.LoginCompleted) {
        _capabilities.value = applyAccountLoginCompletion(_capabilities.value, event)
        if (!event.success || closed.get()) return

        accountSnapshotRefreshJob?.cancel()
        accountSnapshotRefreshJob = scope.launch {
            _capabilities.value = _capabilities.value.copy(accountLoading = true, accountError = null)
            try {
                val account = session.accountApi.readAccount()
                if (!closed.get()) {
                    _capabilities.value = _capabilities.value.copy(
                        account = account,
                        accountStatus = null,
                        accountError = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!closed.get()) {
                    _capabilities.value = _capabilities.value.copy(
                        accountStatus = null,
                        accountError = "サインインは完了しましたが、アカウント情報を自動更新できませんでした。「更新」を押してください。 (${failure.safeMessage()})",
                    )
                }
            } finally {
                if (!closed.get()) {
                    _capabilities.value = _capabilities.value.copy(accountLoading = false)
                }
            }
        }
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
            _review.value = _review.value.copy(starting = false, error = null)
            throw cancelled
        } catch (failure: Throwable) {
            val message = if (failure is CodexAppServerResponseException && failure.error.code == -32601L)
                "このApp Serverはネイティブコードレビューに対応していません" else failure.safeMessage()
            _review.value = CodexReviewUiState(error = message)
            throw failure
        } finally {
            reviewStarting = false
            capabilityBusy.set(false)
        }
    }

    suspend fun refreshSkills(forceReload: Boolean = true) = capabilityOperation {
        _capabilities.value = _capabilities.value.copy(skillsLoading = true, skillsError = null)
        try {
            val result = session.skillsApi.list(cwds = emptyList(), forceReload = forceReload)
            _capabilities.value = _capabilities.value.copy(skillGroups = result.data)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _capabilities.value = _capabilities.value.copy(skillsError = failure.safeMessage())
            throw failure
        } finally {
            _capabilities.value = _capabilities.value.copy(skillsLoading = false)
        }
    }

    suspend fun refreshModels() = capabilityOperation {
        _capabilities.value = _capabilities.value.copy(modelsLoading = true, modelsError = null)
        try {
            val models = session.modelApi.listAllVisible()
            _capabilities.value = _capabilities.value.copy(models = models)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _capabilities.value = _capabilities.value.copy(modelsError = failure.safeMessage())
            throw failure
        } finally {
            _capabilities.value = _capabilities.value.copy(modelsLoading = false)
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
                "このApp Serverはスレッド履歴に対応していません" else failure.safeMessage()
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
                "このApp Serverはスレッド履歴に対応していません" else failure.safeMessage()
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _capabilities.value = _capabilities.value.copy(skillsError = failure.safeMessage())
            throw failure
        } finally {
            _capabilities.value = _capabilities.value.copy(skillUpdatingPath = null)
        }
    }

    suspend fun refreshAccount(refreshToken: Boolean = false) = capabilityOperation {
        accountSnapshotRefreshJob?.cancelAndJoin()
        accountSnapshotRefreshJob = null
        _capabilities.value = _capabilities.value.copy(accountLoading = true, accountError = null)
        try {
            val account = session.accountApi.readAccount(refreshToken)
            _capabilities.value = _capabilities.value.copy(
                account = account,
                accountStatus = null,
                accountError = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _capabilities.value = _capabilities.value.copy(accountError = failure.safeMessage())
            throw failure
        } finally {
            _capabilities.value = _capabilities.value.copy(accountLoading = false)
        }
    }

    suspend fun beginAccountLogin(launcher: CodexAppServerAuthUrlLauncher) = capabilityOperation {
        accountSnapshotRefreshJob?.cancelAndJoin()
        accountSnapshotRefreshJob = null
        accountLoginCorrelation.beginAttempt {
            _capabilities.value = _capabilities.value.copy(accountSubmitting = true, accountError = null)
        }
        try {
            val pending = CodexAppServerOAuthHandoff(session.accountApi, launcher).beginChatGptLogin()
            accountLoginCorrelation.resolveStart(
                pending.loginId,
                onCompletion = { completion ->
                    completeAccountLogin(completion)
                },
                onPending = { loginId ->
                    _capabilities.value = _capabilities.value.copy(pendingLoginId = loginId)
                },
            )
        } catch (cancelled: CodexAppServerLoginHandoffCancellationException) {
            // account/login/start succeeded before launcher cancellation. Keep the exact server
            // login correlated/pending so its later completion remains observable and the user can
            // explicitly cancel it; still rethrow CancellationException to preserve coroutine semantics.
            accountLoginCorrelation.resolveStart(
                cancelled.loginId,
                onCompletion = { completion ->
                    completeAccountLogin(completion)
                },
                onPending = { loginId ->
                    _capabilities.value = _capabilities.value.copy(pendingLoginId = loginId, accountError = null)
                },
            )
            throw cancelled
        } catch (failure: CodexAppServerLoginHandoffException) {
            accountLoginCorrelation.resolveStart(
                failure.loginId,
                onCompletion = { completion ->
                    completeAccountLogin(completion)
                },
                onPending = { loginId ->
                    _capabilities.value = _capabilities.value.copy(pendingLoginId = loginId, accountError = failure.safeMessage())
                },
            )
            throw failure
        } catch (cancelled: CancellationException) {
            accountLoginCorrelation.abortAttempt()
            throw cancelled
        } catch (failure: Throwable) {
            accountLoginCorrelation.abortAttempt {
                _capabilities.value = _capabilities.value.copy(accountError = failure.safeMessage())
            }
            throw failure
        } finally {
            _capabilities.value = _capabilities.value.copy(accountSubmitting = false)
        }
    }

    suspend fun cancelAccountLogin() = capabilityOperation {
        val id = checkNotNull(_capabilities.value.pendingLoginId) { "保留中のCodexサインインはありません" }
        when (val result = session.accountApi.cancelLogin(id)) {
            CodexAppServerCancelLoginResult.Canceled -> {
                accountLoginCorrelation.markCanceled(id)
                _capabilities.value = _capabilities.value.copy(
                    pendingLoginId = null,
                    accountStatus = null,
                    accountError = null,
                )
            }
            CodexAppServerCancelLoginResult.NotFound -> {
                // A completion may have won the server race but still be queued locally. Do not
                // retire the correlation or claim cancellation succeeded; keep accepting that
                // delayed completion for this exact login ID.
                _capabilities.value = _capabilities.value.copy(
                    accountStatus = "App Server側ではサインイン待機状態ではなくなっています",
                    accountError = null,
                )
            }
            is CodexAppServerCancelLoginResult.Unknown -> {
                _capabilities.value = _capabilities.value.copy(
                    accountError = "サインインのキャンセルを確認できませんでした (${result.raw})",
                )
            }
        }
    }
    suspend fun logoutAccount() = capabilityOperation {
        accountSnapshotRefreshJob?.cancelAndJoin()
        accountSnapshotRefreshJob = null
        session.accountApi.logout()
        _capabilities.value = _capabilities.value.copy(
            account = session.accountApi.readAccount(),
            pendingLoginId = null,
            accountStatus = null,
            accountError = null,
        )
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _capabilities.value = _capabilities.value.copy(mcpError = failure.safeMessage())
            throw failure
        } finally {
            _capabilities.value = _capabilities.value.copy(mcpLoading = false)
        }
    }
    suspend fun beginMcpOAuth(name: String, launcher: CodexAppServerAuthUrlLauncher) = capabilityOperation {
        _capabilities.value = _capabilities.value.copy(mcpError = null)
        try {
            runCodexMcpOAuthBegin(name, { pending -> _capabilities.value = _capabilities.value.copy(pendingMcpServer = pending) }) {
                val result = session.mcpApi.beginOAuthLogin(name, threadId = session.threadId)
                result.authorizationUrlForLaunch().also(::validateCodexAppServerAuthUrl).let(launcher::launch)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _capabilities.value = _capabilities.value.copy(mcpError = failure.safeMessage())
            throw failure
        }
    }

    private fun record(turnId: String) = turns.computeIfAbsent(turnId) { TurnRecord() }

    /**
     * Admit a non-terminal event only for the current turn, or claim it as the current turn when
     * no turn ID has been observed yet. Terminal IDs are never resurrected by delayed traffic.
     */
    private fun admitNonTerminalTurn(turnId: String): Boolean {
        if (terminalClaimed.contains(turnId)) return false
        val current = activeTurnId
        if (current != null && current != turnId) return false
        if (current == null) {
            activeTurnId = turnId
            interruptWhenKnown(turnId)
        }
        return true
    }

    private fun clearApprovalsForTurn(turnId: String) = synchronized(approvalLock) {
        val ids = pendingApprovals.filterValues { it.turnId == turnId }.keys.toList()
        ids.forEach {
            pendingApprovals.remove(it)
            approvalResponses.add(it)
        }
    }

    private fun failRuntime(failure: Throwable) {
        if (failure is CancellationException) throw failure
        if (!failed.compareAndSet(false, true)) return
        activeTurnId = null
        synchronized(approvalLock) {
            pendingApprovals.clear()
        }
        _state.value = CodexConversationUiState.Failed(failure.message ?: failure.toString())
        turns.values.forEach { it.terminal.completeExceptionally(failure) }
        runCatching { onFailure(this, failure) }
    }

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
        clearApprovalsForTurn(turnId)
        val isCurrent = activeTurnId == null || activeTurnId == turnId
        if (activeTurnId == turnId) activeTurnId = null
        messageTimeline.completeTurn(turnId)
        publishTurnParts(turnId)
        if (isCurrent) _state.value = CodexConversationUiState.Terminal(session.threadId, turnId, status, activity(turnId), snapshot, tokenUsage.value)
        onTurnTerminal(turnId)
        turn.terminal.complete(status)
    }

    private fun interruptWhenKnown(turnId: String) {
        if (!stopController.onTurnKnown(turnId)) return
        scope.launch {
            try {
                session.interruptTurn(turnId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                onInterruptFailure(failure)
            }
        }
    }

    private fun activity(turnId: String) = CodexConversationActivity(
        reasoning = synchronized(reasoning) { reasoning.filterKeys { it.first == turnId }.values.joinToString("\n") },
        commands = synchronized(commands) { commands.filterKeys { it.first == turnId }.values.toList() },
        files = synchronized(files) { files.filterKeys { it.first == turnId }.values.toList() },
        diff = turnDiffs[turnId],
        terminalInteractions = synchronized(terminalInteractions) { terminalInteractions[turnId]?.toList().orEmpty() },
    )

    private suspend fun publishTurnParts(turnId: String) {
        val parts = messageTimeline.parts(
            turnId = turnId,
            suppressAgentMessages = reviewStarting || turnId in reviewTurns,
        )
        if (parts.isNotEmpty()) onTurnParts(turnId, parts)
    }

    private suspend fun publishActivity(turnId: String) {
        if (!admitNonTerminalTurn(turnId)) return
        val snapshot = activity(turnId)
        val current = _state.value
        _state.value = if (current is CodexConversationUiState.WaitingForApproval && current.event.turnIdOrNull() == turnId) {
            current.copy(activity = snapshot)
        } else {
            CodexConversationUiState.Running(session.threadId, turnId, snapshot, tokenUsage.value)
        }
        publishTurnParts(turnId)
    }

    private fun updateWaitingFilePreview(
        turnId: String,
        item: CodexAppServerItemSnapshot.FileChange,
    ) {
        val waiting = _state.value as? CodexConversationUiState.WaitingForApproval ?: return
        val request = (waiting.event as? CodexAppServerApprovalEvent.FileChangeRequest)?.request ?: return
        if (request.turnId == turnId && request.itemId == item.id) {
            _state.value = waiting.copy(fileChange = item)
        }
    }

    private val collector: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.turnApi.events.collect { event ->
            if (failed.get()) return@collect
            if (event is CodexAppServerTurnEvent.MalformedNotification) {
                val rawThreadId = ((event.rawParams as? kotlinx.serialization.json.JsonObject)?.get("threadId")
                    as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                if (rawThreadId == null || rawThreadId == session.threadId) failRuntime(event.cause)
                return@collect
            }
            if (event.threadIdOrNull() != session.threadId) return@collect
            try {
                when (event) {
                is CodexAppServerTurnEvent.TurnStarted -> {
                    record(event.turn.id)
                    if (reviewStarting) reviewTurns.add(event.turn.id)
                    if (admitNonTerminalTurn(event.turn.id)) {
                        _state.value = CodexConversationUiState.Running(session.threadId, event.turn.id, activity(event.turn.id), tokenUsage.value)
                    }
                }
                is CodexAppServerTurnEvent.AgentMessageDelta -> {
                    record(event.turnId)
                    if (!admitNonTerminalTurn(event.turnId)) return@collect
                    val key = event.turnId to event.itemId
                    val accumulated = text.compute(key) { _, old -> old.orEmpty() + event.delta }!!
                    messageTimeline.appendAgentText(event.turnId, event.itemId, event.delta)
                    if (!reviewStarting && event.turnId !in reviewTurns) onAgentText(event.turnId, event.itemId, accumulated)
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.TurnCompleted -> terminal(event.turn.id, event.turn.status, event.turn)
                is CodexAppServerTurnEvent.ReasoningSummaryTextDelta -> {
                    if (!admitNonTerminalTurn(event.turnId)) return@collect
                    reasoning.compute(event.turnId to event.itemId) { _, old -> old.orEmpty() + event.delta }
                    messageTimeline.appendReasoningSummary(event.turnId, event.itemId, event.summaryIndex, event.delta)
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.ReasoningSummaryPartAdded -> {
                    if (!admitNonTerminalTurn(event.turnId)) return@collect
                    messageTimeline.addReasoningSummaryPart(event.turnId, event.itemId, event.summaryIndex)
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.ReasoningTextDelta -> {
                    if (!admitNonTerminalTurn(event.turnId)) return@collect
                    reasoning.compute(event.turnId to event.itemId) { _, old -> old.orEmpty() + event.delta }
                    messageTimeline.appendReasoningContent(event.turnId, event.itemId, event.contentIndex, event.delta)
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.ItemStarted -> {
                    if (!admitNonTerminalTurn(event.turnId)) return@collect
                    if (event.item is CodexAppServerItemSnapshot.EnteredReviewMode) reviewTurns.add(event.turnId)
                    messageTimeline.itemStarted(event.turnId, event.item, event.startedAtMs)
                    (event.item as? CodexAppServerItemSnapshot.UserMessage)?.content
                        ?.filterIsInstance<CodexAppServerUserInput.Skill>()
                        ?.forEach { skill ->
                            messageTimeline.addSkill(
                                event.turnId,
                                CodexSkillInvocationPresentation(skill.name, skill.path),
                            )
                        }
                    when (val item = event.item) {
                        is CodexAppServerItemSnapshot.CommandExecution -> commands[event.turnId to item.id] = item
                        is CodexAppServerItemSnapshot.FileChange -> {
                            files[event.turnId to item.id] = item
                            updateWaitingFilePreview(event.turnId, item)
                        }
                        else -> Unit
                    }
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.ItemCompleted -> {
                    if (!admitNonTerminalTurn(event.turnId)) return@collect
                    val item = event.item
                    messageTimeline.itemCompleted(event.turnId, item, event.completedAtMs)
                    when (item) {
                        is CodexAppServerItemSnapshot.CommandExecution -> commands[event.turnId to item.id] = item
                        is CodexAppServerItemSnapshot.FileChange -> {
                            files[event.turnId to item.id] = item
                            updateWaitingFilePreview(event.turnId, item)
                        }
                        else -> Unit
                    }
                    if (item is CodexAppServerItemSnapshot.ExitedReviewMode && reviewResultsPersisted.add(event.turnId)) {
                        reviewTurns.add(event.turnId)
                        onAgentText(event.turnId, item.id, item.review)
                    }
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.CommandExecutionOutputDelta -> {
                    if (!admitNonTerminalTurn(event.turnId)) return@collect
                    commands.computeIfPresent(event.turnId to event.itemId) { _, item -> item.copy(aggregatedOutput = item.aggregatedOutput.orEmpty() + event.delta) }
                    messageTimeline.appendCommandOutput(event.turnId, event.itemId, event.delta)
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.TerminalInteraction -> {
                    if (!admitNonTerminalTurn(event.turnId)) return@collect
                    synchronized(terminalInteractions) {
                        terminalInteractions.getOrPut(event.turnId) { mutableListOf() } += "${event.processId}: ${event.stdin}"
                    }
                    messageTimeline.addTerminalInteraction(event.turnId, event.itemId, event.processId, event.stdin)
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.FileChangePatchUpdated -> {
                    if (!admitNonTerminalTurn(event.turnId)) return@collect
                    val updated = files.computeIfPresent(event.turnId to event.itemId) { _, item -> item.copy(changes = event.changes) }
                    messageTimeline.updateFileChanges(event.turnId, event.itemId, event.changes)
                    val waiting = _state.value as? CodexConversationUiState.WaitingForApproval
                    if (waiting?.event is CodexAppServerApprovalEvent.FileChangeRequest &&
                        waiting.event.request.itemId == event.itemId && waiting.event.request.turnId == event.turnId
                    ) {
                        _state.value = waiting.copy(fileChange = updated)
                        publishTurnParts(event.turnId)
                    }
                    else publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.TurnDiffUpdated -> {
                    if (!admitNonTerminalTurn(event.turnId)) return@collect
                    turnDiffs[event.turnId] = event.diff
                    messageTimeline.updateTurnDiff(event.turnId, event.diff)
                    publishActivity(event.turnId)
                }
                is CodexAppServerTurnEvent.MalformedNotification -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failRuntime(failure)
            }
        }
    }

    private fun registerApproval(event: CodexAppServerApprovalEvent, pending: PendingApproval): Boolean {
        if (pending.threadId != session.threadId || !admitNonTerminalTurn(pending.turnId)) return false
        val requestId = event.requestIdOrNull() ?: return false
        synchronized(approvalLock) {
            if (approvalResponses.contains(requestId)) return false
            pendingApprovals[requestId] = pending
        }
        return true
    }

    private val approvalCollector: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.approvalApi.events.collect { event ->
            when (event) {
                is CodexAppServerApprovalEvent.CommandExecutionRequest -> {
                    val request = event.request
                    if (!registerApproval(event, PendingApproval(ApprovalKind.Command, request.threadId, request.turnId, request.itemId))) return@collect
                    if (shouldAutoApproveSafely(event) && autoRespondApproval(event)) return@collect
                    messageTimeline.commandApprovalRequested(request.turnId, request)
                    _state.value = CodexConversationUiState.WaitingForApproval(
                        event,
                        activity = activity(request.turnId),
                        telemetry = tokenUsage.value,
                    )
                    publishTurnParts(request.turnId)
                }
                is CodexAppServerApprovalEvent.FileChangeRequest -> {
                    val request = event.request
                    if (!registerApproval(event, PendingApproval(ApprovalKind.FileChange, request.threadId, request.turnId, request.itemId))) return@collect
                    if (shouldAutoApproveSafely(event) && autoRespondApproval(event)) return@collect
                    messageTimeline.fileApprovalRequested(request.turnId, request.itemId)
                    _state.value = CodexConversationUiState.WaitingForApproval(
                        event,
                        fileChange = files[request.turnId to request.itemId],
                        activity = activity(request.turnId),
                        telemetry = tokenUsage.value,
                    )
                    publishTurnParts(request.turnId)
                }
                is CodexAppServerApprovalEvent.Resolved -> {
                    if (event.threadId != session.threadId) return@collect
                    val resolved = synchronized(approvalLock) {
                        pendingApprovals.remove(event.requestId).also { approvalResponses.add(event.requestId) }
                    }
                    resolved?.let {
                        messageTimeline.approvalResolved(it.turnId, it.itemId)
                        publishTurnParts(it.turnId)
                    }
                    val waiting = _state.value as? CodexConversationUiState.WaitingForApproval
                    if (waiting?.requestId == event.requestId) {
                        _state.value = activeTurnId?.let { CodexConversationUiState.Running(session.threadId, it, activity(it), tokenUsage.value) }
                            ?: CodexConversationUiState.Ready(session.threadId, tokenUsage.value)
                    }
                }
                is CodexAppServerApprovalEvent.MalformedRequest -> _state.value = CodexConversationUiState.Failed(event.cause.message ?: "Malformed approval")
                is CodexAppServerApprovalEvent.MalformedNotification -> _state.value = CodexConversationUiState.Failed(event.cause.message ?: "Malformed approval")
            }
        }
    }

    /** Auto-answers requests suppressed by RikkaHub's existing Workspace/global policy. */
    private suspend fun shouldAutoApproveSafely(event: CodexAppServerApprovalEvent): Boolean = try {
        shouldAutoApprove(event)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

    private suspend fun autoRespondApproval(event: CodexAppServerApprovalEvent): Boolean {
        if (event !is CodexAppServerApprovalEvent.CommandExecutionRequest &&
            event !is CodexAppServerApprovalEvent.FileChangeRequest
        ) return false
        val requestId = event.requestIdOrNull() ?: return false
        synchronized(approvalLock) {
            if (!approvalResponses.add(requestId)) return false
        }
        return try {
            when (event) {
                is CodexAppServerApprovalEvent.CommandExecutionRequest ->
                    session.approvalApi.respondCommandApproval(requestId, CodexAppServerCommandApprovalDecision.Accept)
                is CodexAppServerApprovalEvent.FileChangeRequest ->
                    session.approvalApi.respondFileChangeApproval(requestId, CodexAppServerFileChangeApprovalDecision.Accept)
                else -> error("unreachable approval event")
            }
            synchronized(approvalLock) { pendingApprovals.remove(requestId) }
            true
        } catch (cancelled: CancellationException) {
            synchronized(approvalLock) { approvalResponses.remove(requestId) }
            throw cancelled
        } catch (_: Throwable) {
            // Keep the original request live and fall back to the normal in-chat approval UI.
            synchronized(approvalLock) { approvalResponses.remove(requestId) }
            false
        }
    }

    private val failureCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        session.failure.collect { failure ->
            failure ?: return@collect
            failRuntime(failure)
        }
    }

    fun activeTurnId(): String? = activeTurnId

    /**
     * ChatService calls requestStop only while the conversation-level Codex operation lease is
     * held. Within that outer lease, runtime capabilityBusy distinguishes a capability-only RPC
     * from a normal turn/start whose ID has not arrived yet. Review start is capabilityBusy too,
     * but remains interruptible through reviewStarting.
     */
    fun hasInterruptibleTurn(): Boolean = shouldRecordCodexStop(
        activeTurnId = activeTurnId,
        reviewStarting = reviewStarting,
        runtimeCapabilityBusy = capabilityBusy.get(),
    )

    private fun claimApproval(id: JsonRpcId, kind: ApprovalKind): CodexConversationUiState.WaitingForApproval? = synchronized(approvalLock) {
        val waiting = _state.value as? CodexConversationUiState.WaitingForApproval ?: return@synchronized null
        if (waiting.requestId != id || waiting.submitting) return@synchronized null
        val pending = pendingApprovals[id] ?: return@synchronized null
        if (pending.kind != kind || pending.threadId != session.threadId || pending.turnId != activeTurnId || terminalClaimed.contains(pending.turnId)) {
            return@synchronized null
        }
        if (!approvalResponses.add(id)) return@synchronized null
        _state.value = waiting.copy(submitting = true)
        waiting
    }

    private fun releaseApprovalClaim(id: JsonRpcId, waiting: CodexConversationUiState.WaitingForApproval) = synchronized(approvalLock) {
        approvalResponses.remove(id)
        val current = _state.value as? CodexConversationUiState.WaitingForApproval
        if (current?.requestId == id) _state.value = current.copy(submitting = false)
        else if (_state.value == waiting.copy(submitting = true)) _state.value = waiting
    }

    suspend fun respondCommandApproval(
        id: JsonRpcId,
        decision: CodexAppServerCommandApprovalDecision,
        denialReason: String = "",
    ): Boolean {
        val waiting = claimApproval(id, ApprovalKind.Command) ?: return false
        return try {
            session.approvalApi.respondCommandApproval(id, decision)
            val request = (waiting.event as CodexAppServerApprovalEvent.CommandExecutionRequest).request
            messageTimeline.approvalResolved(request.turnId, request.itemId, decision.toToolApprovalState(denialReason))
            publishTurnParts(request.turnId)
            true
        } catch (cancelled: CancellationException) {
            releaseApprovalClaim(id, waiting)
            throw cancelled
        } catch (failure: Throwable) {
            releaseApprovalClaim(id, waiting)
            false
        }
    }

    suspend fun respondFileApproval(
        id: JsonRpcId,
        decision: CodexAppServerFileChangeApprovalDecision,
        denialReason: String = "",
    ): Boolean {
        val waiting = claimApproval(id, ApprovalKind.FileChange) ?: return false
        return try {
            session.approvalApi.respondFileChangeApproval(id, decision)
            val request = (waiting.event as CodexAppServerApprovalEvent.FileChangeRequest).request
            messageTimeline.approvalResolved(request.turnId, request.itemId, decision.toToolApprovalState(denialReason))
            publishTurnParts(request.turnId)
            true
        } catch (cancelled: CancellationException) {
            releaseApprovalClaim(id, waiting)
            throw cancelled
        } catch (failure: Throwable) {
            releaseApprovalClaim(id, waiting)
            false
        }
    }

    fun requestStop() {
        if (!hasInterruptibleTurn()) return
        stopController.requestStop()
        activeTurnId?.let(::interruptWhenKnown)
    }

    /** Adds the explicit `$skill` invocation to the same persisted turn timeline. */
    suspend fun presentSkillInvocation(turnId: String, name: String, path: String) {
        messageTimeline.addSkill(turnId, CodexSkillInvocationPresentation(name, path))
        publishTurnParts(turnId)
    }

    /** Monotonic: an early terminal notification always wins over a later inProgress response. */
    suspend fun acceptStartResponse(turnId: String, status: CodexAppServerTurnStatus) {
        val turn = record(turnId)
        if (terminalClaimed.contains(turnId)) return
        if (status is CodexAppServerTurnStatus.InProgress) {
            if (!admitNonTerminalTurn(turnId)) return
            _state.value = CodexConversationUiState.Running(session.threadId, turnId, activity(turnId), tokenUsage.value)
        } else terminal(turnId, status)
    }

    suspend fun awaitTurnTerminal(turnId: String): CodexAppServerTurnStatus = record(turnId).terminal.await()

    /** Called only after start response processing and terminal waiting are both complete. */
    fun finishTurn(turnId: String) {
        turns.remove(turnId)
        text.keys.removeAll { it.first == turnId }
        reasoning.keys.removeAll { it.first == turnId }
        commands.keys.removeAll { it.first == turnId }
        files.keys.removeAll { it.first == turnId }
        turnDiffs.remove(turnId)
        synchronized(terminalInteractions) { terminalInteractions.remove(turnId) }
        messageTimeline.clear(turnId)
        clearApprovalsForTurn(turnId)
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
        accountSnapshotRefreshJob?.cancel()
        mcpCollector.cancel()
        usageCollector.cancel()
        synchronized(approvalLock) { pendingApprovals.clear() }
        _capabilities.value = CodexCapabilitiesUiState(connected = false)
        session.close()
    }
}

/** Caller already proved that the conversation-level Codex operation lease is active. */
internal fun shouldRecordCodexStop(
    activeTurnId: String?,
    reviewStarting: Boolean,
    runtimeCapabilityBusy: Boolean,
): Boolean = activeTurnId != null || reviewStarting || !runtimeCapabilityBusy

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
        accountStatus = if (event.success) null else event.error ?: "Sign-in failed",
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
    data class StaleBinding(val reason: CodexAppServerStaleBindingReason) : CodexConversationUiState
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

private fun CodexAppServerApprovalEvent.requestIdOrNull(): JsonRpcId? = when (this) {
    is CodexAppServerApprovalEvent.CommandExecutionRequest -> requestId
    is CodexAppServerApprovalEvent.FileChangeRequest -> requestId
    is CodexAppServerApprovalEvent.Resolved -> requestId
    is CodexAppServerApprovalEvent.MalformedRequest -> requestId
    is CodexAppServerApprovalEvent.MalformedNotification -> null
}

private fun CodexAppServerApprovalEvent.turnIdOrNull(): String? = when (this) {
    is CodexAppServerApprovalEvent.CommandExecutionRequest -> request.turnId
    is CodexAppServerApprovalEvent.FileChangeRequest -> request.turnId
    else -> null
}

private fun CodexAppServerCommandApprovalDecision.toToolApprovalState(reason: String): ToolApprovalState =
    when (this) {
        CodexAppServerCommandApprovalDecision.Decline,
        CodexAppServerCommandApprovalDecision.Cancel,
            -> ToolApprovalState.Denied(reason)
        else -> ToolApprovalState.Approved
    }

private fun CodexAppServerFileChangeApprovalDecision.toToolApprovalState(reason: String): ToolApprovalState =
    when (this) {
        CodexAppServerFileChangeApprovalDecision.Decline,
        CodexAppServerFileChangeApprovalDecision.Cancel,
            -> ToolApprovalState.Denied(reason)
        else -> ToolApprovalState.Approved
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
