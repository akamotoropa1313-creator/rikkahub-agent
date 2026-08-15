package me.rerere.rikkahub.data.codex.appserver

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity

sealed interface CodexAppServerStaleBindingReason {
    data object MissingConversation : CodexAppServerStaleBindingReason
    data object MissingWorkspace : CodexAppServerStaleBindingReason
}

sealed interface CodexAppServerSessionRecoveryResult {
    data object NotBound : CodexAppServerSessionRecoveryResult
    data class StaleBinding(
        val binding: CodexAppServerSessionBindingEntity,
        val reason: CodexAppServerStaleBindingReason,
    ) : CodexAppServerSessionRecoveryResult
    data class Recovered(val session: CodexAppServerRecoveredSession) : CodexAppServerSessionRecoveryResult
}

class CodexAppServerRecoveredSession internal constructor(
    val binding: CodexAppServerSessionBindingEntity,
    val connection: CodexAppServerConnection,
    val resumeResult: CodexAppServerThreadOpenResult,
) : Closeable {
    val threadApi = CodexAppServerThreadApi(connection)
    val turnApi = CodexAppServerTurnApi(connection)
    val approvalApi = CodexAppServerApprovalApi(connection)
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) connection.close()
    }
}

class CodexAppServerSessionRecovery(
    private val repository: CodexAppServerSessionBindingRepository,
    private val conversationDao: ConversationDAO,
    private val workspaceDao: WorkspaceDAO,
    private val connectionFactory: WorkspaceCodexAppServerConnectionFactory,
) {
    suspend fun recover(conversationId: String): CodexAppServerSessionRecoveryResult {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        val binding = repository.getBinding(conversationId)
            ?: return CodexAppServerSessionRecoveryResult.NotBound
        if (conversationDao.getConversationById(binding.conversationId) == null) {
            return CodexAppServerSessionRecoveryResult.StaleBinding(
                binding, CodexAppServerStaleBindingReason.MissingConversation,
            )
        }
        val workspace = workspaceDao.getById(binding.workspaceId)
            ?: return CodexAppServerSessionRecoveryResult.StaleBinding(
                binding, CodexAppServerStaleBindingReason.MissingWorkspace,
            )

        val connection = connectionFactory.create(workspace.root, binding.workspaceCwd)
        var ownershipTransferred = false
        try {
            connection.initialize()
            val resumed = CodexAppServerThreadApi(connection).resumeThread(binding.threadId)
            repository.markResumed(conversationId)
            val refreshed = checkNotNull(repository.getBinding(conversationId))
            val session = CodexAppServerRecoveredSession(refreshed, connection, resumed)
            ownershipTransferred = true
            return CodexAppServerSessionRecoveryResult.Recovered(session)
        } finally {
            if (!ownershipTransferred) connection.close()
        }
    }
}
