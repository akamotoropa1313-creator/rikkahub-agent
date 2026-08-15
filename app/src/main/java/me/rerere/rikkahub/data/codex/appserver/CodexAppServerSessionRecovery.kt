package me.rerere.rikkahub.data.codex.appserver

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
    binding: CodexAppServerSessionBindingEntity,
    connection: CodexAppServerConnection,
    val resumeResult: CodexAppServerThreadOpenResult,
    repository: CodexAppServerSessionBindingRepository,
) : CodexAppServerConversationSession(binding, connection, repository)

class CodexAppServerSessionRecovery(
    private val repository: CodexAppServerSessionBindingRepository,
    private val localState: CodexAppServerLocalState,
    private val connectionFactory: CodexAppServerConnectionCreator,
) {
    suspend fun recover(conversationId: String): CodexAppServerSessionRecoveryResult {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        val binding = repository.getBinding(conversationId)
            ?: return CodexAppServerSessionRecoveryResult.NotBound
        if (!localState.conversationExists(binding.conversationId)) {
            return CodexAppServerSessionRecoveryResult.StaleBinding(
                binding, CodexAppServerStaleBindingReason.MissingConversation,
            )
        }
        val workspace = localState.getWorkspace(binding.workspaceId)
            ?: return CodexAppServerSessionRecoveryResult.StaleBinding(
                binding, CodexAppServerStaleBindingReason.MissingWorkspace,
            )

        val connection = connectionFactory.create(workspace.root, binding.workspaceCwd)
        var ownershipTransferred = false
        try {
            connection.initialize()
            val resumed = CodexAppServerThreadApi(connection).resumeThread(binding.threadId)
            val resumedAtMs = repository.markResumed(binding.conversationId, binding.threadId)
            val session = CodexAppServerRecoveredSession(
                binding.copy(lastResumedAtMs = resumedAtMs, updatedAtMs = resumedAtMs),
                connection,
                resumed,
                repository,
            )
            ownershipTransferred = true
            return CodexAppServerSessionRecoveryResult.Recovered(session)
        } finally {
            if (!ownershipTransferred) connection.close()
        }
    }
}
