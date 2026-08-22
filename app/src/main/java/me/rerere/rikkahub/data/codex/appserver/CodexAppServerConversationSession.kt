package me.rerere.rikkahub.data.codex.appserver

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The lifecycle boundary for one conversation-bound App Server process. Protocol APIs exposed by
 * this object all use [connection]; this class does not provide a second execution runtime.
 */
open class CodexAppServerConversationSession internal constructor(
    val binding: CodexAppServerSessionBindingEntity,
    val connection: CodexAppServerConnection,
    private val bindingRepository: CodexAppServerSessionBindingRepository,
    val tokenUsageTracker: CodexTokenUsageTracker = CodexTokenUsageTracker(connection, binding.threadId),
    val effectiveCwd: String? = null,
    private val autoRefreshModelCatalog: Boolean = false,
) : Closeable {
    val conversationId: String get() = binding.conversationId
    val workspaceId: String get() = binding.workspaceId
    val workspaceCwd: String get() = binding.workspaceCwd
    val threadId: String get() = binding.threadId

    val threadApi = CodexAppServerThreadApi(connection)
    val turnApi = CodexAppServerTurnApi(connection)
    val reviewApi = CodexAppServerReviewApi(connection)
    val approvalApi = CodexAppServerApprovalApi(connection)
    val skillsApi = CodexAppServerSkillsApi(connection)
    val mcpApi = CodexAppServerMcpApi(connection)
    val accountApi = CodexAppServerAccountApi(connection)
    val modelApi = CodexAppServerModelApi(connection)
    val configApi = CodexAppServerConfigApi(connection)

    private val terminated = AtomicBoolean(false)
    private val closeHook = AtomicReference<(() -> Unit)?>(null)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val mutableFailure = MutableStateFlow<Throwable?>(null)
    /** Terminal connection failure or loss of durable binding ownership. */
    val failure: StateFlow<Throwable?> = mutableFailure.asStateFlow()

    init {
        // UNDISPATCHED establishes the no-replay subscription before construction returns and thus
        // before startTurn can be invoked.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            turnApi.events.collect { event ->
                if (event is CodexAppServerTurnEvent.TurnCompleted && event.threadId == threadId) {
                    try {
                        bindingRepository.recordTurnCompleted(
                            conversationId, threadId, event.turn.id, event.turn.status,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        terminate(failure)
                    }
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.state.collect { state ->
                if (state is CodexAppServerConnectionState.Failed) {
                    terminate(state.cause)
                }
            }
        }
        // Production-created sessions opt in to background catalog discovery. Direct protocol
        // sessions keep this disabled by default so an unrelated model/list request cannot race
        // deterministic low-level request/response tests or other embedders of this session type.
        if (autoRefreshModelCatalog && CodexModelCatalogKnowledge.modelsSnapshot().isEmpty()) {
            scope.launch {
                try {
                    modelApi.listAllVisible()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Catalog discovery must never make an otherwise usable Codex session fail.
                }
            }
        }
    }

    internal fun installCloseHook(hook: () -> Unit) {
        if (terminated.get()) {
            hook()
            return
        }
        val previous = closeHook.getAndSet(hook)
        previous?.invoke()
        if (terminated.get() && closeHook.compareAndSet(hook, null)) hook()
    }

    suspend fun startTurn(
        input: List<CodexAppServerTurnInput>,
        params: CodexAppServerTurnStartParams = CodexAppServerTurnStartParams(),
        timeout: Duration = 30.seconds,
    ): CodexAppServerTurnStartResult {
        checkOpen()
        val result = turnApi.startTurn(threadId, input, params, timeout)
        registerStartedTurn(result.turn)
        return result
    }

    suspend fun startReview(
        target: CodexAppServerReviewTarget,
        timeout: Duration = 30.seconds,
    ): CodexAppServerReviewStartResult {
        checkOpen()
        val result = reviewApi.startReview(threadId, target, timeout)
        if (result.reviewThreadId != threadId) {
            throw CodexAppServerTurnProtocolException("inline review returned a different reviewThreadId")
        }
        registerStartedTurn(result.turn)
        return result
    }

    private suspend fun registerStartedTurn(turn: CodexAppServerTurnSnapshot) {
        try {
            bindingRepository.recordTurnStarted(conversationId, threadId, turn.id)
            if (turn.status !is CodexAppServerTurnStatus.InProgress) {
                bindingRepository.recordTurnCompleted(conversationId, threadId, turn.id, turn.status)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            terminate(failure)
            throw failure
        }
    }

    suspend fun interruptTurn(
        turnId: String,
        timeout: Duration = 30.seconds,
    ): CodexAppServerTurnInterruptResult {
        checkOpen()
        return turnApi.interruptTurn(threadId, turnId, timeout)
    }

    protected fun checkOpen() {
        check(!terminated.get() && job.isActive) { "Codex App Server conversation session is closed" }
    }

    private fun terminate(cause: Throwable?) {
        if (!terminated.compareAndSet(false, true)) return
        // Publication is deliberately last: observing failure is proof that all owned work and
        // the process connection have already crossed their deterministic cleanup boundary.
        job.cancel()
        tokenUsageTracker.close()
        connection.close()
        closeHook.getAndSet(null)?.invoke()
        if (cause != null) mutableFailure.value = cause
    }

    override fun close() {
        terminate(null)
    }
}

sealed interface CodexAppServerConversationSessionOpenResult {
    data class Started(
        val session: CodexAppServerConversationSession,
        val startResult: CodexAppServerThreadOpenResult,
    ) : CodexAppServerConversationSessionOpenResult

    data class Recovered(val session: CodexAppServerRecoveredSession) :
        CodexAppServerConversationSessionOpenResult

    data class StaleBinding(
        val binding: CodexAppServerSessionBindingEntity,
        val reason: CodexAppServerStaleBindingReason,
    ) : CodexAppServerConversationSessionOpenResult
}

/** Stateless application-facing opener. Live connection ownership is transferred to its result. */
class CodexAppServerConversationSessionOpener(
    private val repository: CodexAppServerSessionBindingRepository,
    private val localState: CodexAppServerLocalState,
    private val connectionFactory: CodexAppServerConnectionCreator,
    private val recovery: CodexAppServerSessionRecovery,
    private val harnessProjectionResolver: CodexHarnessConversationProjectionResolver? = null,
    private val autoRefreshModelCatalog: Boolean = false,
) {
    /** Recovery-only entry point: never creates a thread or binding. */
    suspend fun recoverBound(
        conversationId: String,
        overrides: CodexAppServerThreadResumeParams = CodexAppServerThreadResumeParams(),
        routeGuard: CodexHarnessExistingThreadRouteGuard? = null,
    ): CodexAppServerConversationSessionOpenResult {
        require(conversationId.isNotBlank())
        repository.getBinding(conversationId) ?: error("Codex conversation is not bound")
        val projection = harnessProjectionResolver?.resolve(conversationId)
        val effectiveOverrides = overrides.withHarnessProjection(projection)
        val effectiveGuard = routeGuard ?: projection?.let { CodexHarnessExistingThreadRouteGuard.from(it) }
        val result = when (val recovered = recovery.recover(conversationId, effectiveOverrides, effectiveGuard)) {
            CodexAppServerSessionRecoveryResult.NotBound -> error("Binding disappeared while reconnecting")
            is CodexAppServerSessionRecoveryResult.Recovered ->
                CodexAppServerConversationSessionOpenResult.Recovered(recovered.session)
            is CodexAppServerSessionRecoveryResult.StaleBinding ->
                CodexAppServerConversationSessionOpenResult.StaleBinding(recovered.binding, recovered.reason)
        }
        if (result is CodexAppServerConversationSessionOpenResult.Recovered) {
            installHarnessCleanup(result.session, conversationId, projection)
        } else if (projection?.isGatewayBacked == true) {
            harnessProjectionResolver?.release(conversationId)
        }
        return result
    }

    suspend fun open(
        conversationId: String,
        workspaceId: String,
        workspaceCwd: String,
        overrides: CodexAppServerThreadStartParams = CodexAppServerThreadStartParams(),
        routeGuard: CodexHarnessExistingThreadRouteGuard? = null,
    ): CodexAppServerConversationSessionOpenResult {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }

        val projection = harnessProjectionResolver?.resolve(conversationId)
        val effectiveOverrides = overrides.withHarnessProjection(projection)
        val effectiveGuard = routeGuard ?: projection?.let { CodexHarnessExistingThreadRouteGuard.from(it) }

        if (repository.getBinding(conversationId) != null) {
            val resumeOverrides = CodexAppServerThreadResumeParams(
                model = effectiveOverrides.model,
                modelProvider = effectiveOverrides.modelProvider,
                cwd = effectiveOverrides.cwd,
                config = effectiveOverrides.config,
                baseInstructions = effectiveOverrides.baseInstructions,
                developerInstructions = effectiveOverrides.developerInstructions,
                personality = effectiveOverrides.personality,
                sandbox = effectiveOverrides.sandbox,
                approvalPolicy = effectiveOverrides.approvalPolicy,
            )
            val result = when (val recovered = recovery.recover(conversationId, resumeOverrides, effectiveGuard)) {
                CodexAppServerSessionRecoveryResult.NotBound -> error("Binding disappeared while opening session")
                is CodexAppServerSessionRecoveryResult.Recovered ->
                    CodexAppServerConversationSessionOpenResult.Recovered(recovered.session)
                is CodexAppServerSessionRecoveryResult.StaleBinding ->
                    CodexAppServerConversationSessionOpenResult.StaleBinding(recovered.binding, recovered.reason)
            }
            if (result is CodexAppServerConversationSessionOpenResult.Recovered) {
                installHarnessCleanup(result.session, conversationId, projection)
            } else if (projection?.isGatewayBacked == true) {
                harnessProjectionResolver?.release(conversationId)
            }
            return result
        }

        check(localState.conversationExists(conversationId)) { "Conversation $conversationId does not exist" }
        val workspace = checkNotNull(localState.getWorkspace(workspaceId)) { "Workspace $workspaceId does not exist" }
        // Validate before launching a process. The repository remains the canonical validator.
        require(!workspaceCwd.startsWith('/') && !workspaceCwd.startsWith('\\')) { "workspaceCwd must be relative" }
        require(workspaceCwd.split('/', '\\').none { it == ".." }) { "workspaceCwd must stay inside the workspace" }

        val effectiveCwd = resolveCodexEffectiveCwd(workspace.root, workspaceCwd)
        val connection = connectionFactory.create(workspace.root, workspaceCwd)
        var transferred = false
        try {
            connection.initialize()
            val started = CodexAppServerThreadApi(connection).startThread(
                effectiveOverrides.copy(ephemeral = false),
            )
            val binding = repository.createPersistentThreadBinding(
                conversationId, workspaceId, workspaceCwd, started.thread,
            )
            val session = CodexAppServerConversationSession(
                binding,
                connection,
                repository,
                effectiveCwd = effectiveCwd,
                autoRefreshModelCatalog = autoRefreshModelCatalog,
            )
            installHarnessCleanup(session, conversationId, projection)
            transferred = true
            return CodexAppServerConversationSessionOpenResult.Started(session, started)
        } finally {
            if (!transferred) {
                connection.close()
                if (projection?.isGatewayBacked == true) harnessProjectionResolver?.release(conversationId)
            }
        }
    }

    private fun installHarnessCleanup(
        session: CodexAppServerConversationSession,
        conversationId: String,
        projection: CodexHarnessThreadProjection?,
    ) {
        if (projection?.isGatewayBacked != true) return
        session.installCloseHook { harnessProjectionResolver?.release(conversationId) }
    }
}

private fun CodexAppServerThreadStartParams.withHarnessProjection(
    projection: CodexHarnessThreadProjection?,
): CodexAppServerThreadStartParams {
    if (projection == null) return this
    return copy(
        model = projection.model,
        modelProvider = projection.modelProvider,
        config = mergeHarnessConfig(config, projection.config),
    )
}

private fun CodexAppServerThreadResumeParams.withHarnessProjection(
    projection: CodexHarnessThreadProjection?,
): CodexAppServerThreadResumeParams {
    if (projection == null) return this
    return copy(
        model = projection.model,
        modelProvider = projection.modelProvider,
        config = mergeHarnessConfig(config, projection.config),
    )
}

private fun mergeHarnessConfig(
    existing: Map<String, JsonElement>?,
    harness: Map<String, JsonElement>?,
): Map<String, JsonElement>? {
    if (existing == null && harness == null) return null
    return buildMap {
        existing?.let(::putAll)
        harness?.let(::putAll)
    }
}
