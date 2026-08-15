package me.rerere.rikkahub.data.codex.appserver

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The lifecycle boundary for one conversation-bound App Server process.  Protocol APIs exposed by
 * this object all use [connection]; this class does not provide a second execution runtime.
 */
open class CodexAppServerConversationSession internal constructor(
    val binding: CodexAppServerSessionBindingEntity,
    val connection: CodexAppServerConnection,
    private val bindingRepository: CodexAppServerSessionBindingRepository,
) : Closeable {
    val conversationId: String get() = binding.conversationId
    val workspaceId: String get() = binding.workspaceId
    val workspaceCwd: String get() = binding.workspaceCwd
    val threadId: String get() = binding.threadId

    val threadApi = CodexAppServerThreadApi(connection)
    val turnApi = CodexAppServerTurnApi(connection)
    val approvalApi = CodexAppServerApprovalApi(connection)
    val skillsApi = CodexAppServerSkillsApi(connection)
    val mcpApi = CodexAppServerMcpApi(connection)
    val accountApi = CodexAppServerAccountApi(connection)

    private val closed = AtomicBoolean(false)
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
                    } catch (changed: CodexAppServerBindingChangedException) {
                        loseOwnership(changed)
                    }
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connection.state.collect { state ->
                if (state is CodexAppServerConnectionState.Failed) {
                    mutableFailure.compareAndSet(null, state.cause)
                    job.cancel()
                }
            }
        }
    }

    suspend fun startTurn(
        input: List<CodexAppServerTurnInput>,
        params: CodexAppServerTurnStartParams = CodexAppServerTurnStartParams(),
        timeout: Duration = 30.seconds,
    ): CodexAppServerTurnStartResult {
        checkOpen()
        val result = turnApi.startTurn(threadId, input, params, timeout)
        try {
            bindingRepository.recordTurnStarted(conversationId, threadId, result.turn.id)
            if (result.turn.status !is CodexAppServerTurnStatus.InProgress) {
                bindingRepository.recordTurnCompleted(
                    conversationId, threadId, result.turn.id, result.turn.status,
                )
            }
        } catch (changed: CodexAppServerBindingChangedException) {
            loseOwnership(changed)
            throw changed
        }
        return result
    }

    suspend fun interruptTurn(
        turnId: String,
        timeout: Duration = 30.seconds,
    ): CodexAppServerTurnInterruptResult {
        checkOpen()
        return turnApi.interruptTurn(threadId, turnId, timeout)
    }

    protected fun checkOpen() {
        check(!closed.get() && job.isActive) { "Codex App Server conversation session is closed" }
    }

    private fun loseOwnership(cause: CodexAppServerBindingChangedException) {
        if (mutableFailure.compareAndSet(null, cause)) close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        job.cancel()
        connection.close()
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
) {
    suspend fun open(
        conversationId: String,
        workspaceId: String,
        workspaceCwd: String,
        overrides: CodexAppServerThreadStartParams = CodexAppServerThreadStartParams(),
    ): CodexAppServerConversationSessionOpenResult {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }

        if (repository.getBinding(conversationId) != null) {
            return when (val recovered = recovery.recover(conversationId)) {
                CodexAppServerSessionRecoveryResult.NotBound -> error("Binding disappeared while opening session")
                is CodexAppServerSessionRecoveryResult.Recovered ->
                    CodexAppServerConversationSessionOpenResult.Recovered(recovered.session)
                is CodexAppServerSessionRecoveryResult.StaleBinding ->
                    CodexAppServerConversationSessionOpenResult.StaleBinding(recovered.binding, recovered.reason)
            }
        }

        check(localState.conversationExists(conversationId)) { "Conversation $conversationId does not exist" }
        val workspace = checkNotNull(localState.getWorkspace(workspaceId)) { "Workspace $workspaceId does not exist" }
        // Validate before launching a process. The repository remains the canonical validator.
        require(!workspaceCwd.startsWith('/') && !workspaceCwd.startsWith('\\')) { "workspaceCwd must be relative" }
        require(workspaceCwd.split('/', '\\').none { it == ".." }) { "workspaceCwd must stay inside the workspace" }

        val connection = connectionFactory.create(workspace.root, workspaceCwd)
        var transferred = false
        try {
            connection.initialize()
            val started = CodexAppServerThreadApi(connection).startThread(
                overrides.copy(ephemeral = false),
            )
            val binding = repository.bindPersistentThread(
                conversationId, workspaceId, workspaceCwd, started.thread,
            )
            val session = CodexAppServerConversationSession(binding, connection, repository)
            transferred = true
            return CodexAppServerConversationSessionOpenResult.Started(session, started)
        } finally {
            if (!transferred) connection.close()
        }
    }
}
