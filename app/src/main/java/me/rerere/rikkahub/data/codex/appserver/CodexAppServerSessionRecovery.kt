package me.rerere.rikkahub.data.codex.appserver

import me.rerere.rikkahub.data.db.entity.CodexAppServerSessionBindingEntity

sealed interface CodexAppServerStaleBindingReason {
    data object MissingConversation : CodexAppServerStaleBindingReason
    data object MissingWorkspace : CodexAppServerStaleBindingReason
    data class HarnessRouteChanged(
        val existingModelProvider: String?,
        val expectedModelProvider: String?,
    ) : CodexAppServerStaleBindingReason
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
    usageTracker: CodexTokenUsageTracker,
    effectiveCwd: String?,
    autoRefreshModelCatalog: Boolean = false,
) : CodexAppServerConversationSession(
    binding,
    connection,
    repository,
    usageTracker,
    effectiveCwd,
    autoRefreshModelCatalog,
)

class CodexAppServerSessionRecovery(
    private val repository: CodexAppServerSessionBindingRepository,
    private val localState: CodexAppServerLocalState,
    private val connectionFactory: CodexAppServerConnectionCreator,
    private val autoRefreshModelCatalog: Boolean = false,
) {
    suspend fun recover(
        conversationId: String,
        overrides: CodexAppServerThreadResumeParams = CodexAppServerThreadResumeParams(),
        routeGuard: CodexHarnessExistingThreadRouteGuard? = null,
    ): CodexAppServerSessionRecoveryResult {
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

        val effectiveCwd = resolveCodexEffectiveCwd(workspace.root, binding.workspaceCwd)
        val connection = connectionFactory.create(workspace.root, binding.workspaceCwd)
        var ownershipTransferred = false
        var usageTracker: CodexTokenUsageTracker? = null
        try {
            connection.initialize()
            if (routeGuard != null) {
                val existing = CodexAppServerThreadApi(connection).readThread(binding.threadId)
                if (!routeGuard.accepts(existing.modelProvider)) {
                    return CodexAppServerSessionRecoveryResult.StaleBinding(
                        binding,
                        CodexAppServerStaleBindingReason.HarnessRouteChanged(
                            existingModelProvider = existing.modelProvider,
                            expectedModelProvider = when (routeGuard) {
                                CodexHarnessExistingThreadRouteGuard.NativeAccount -> null
                                is CodexHarnessExistingThreadRouteGuard.Gateway -> routeGuard.expectedModelProvider
                            },
                        ),
                    )
                }
            }
            // Establish the no-replay subscription synchronously before resume can emit replay.
            usageTracker = CodexTokenUsageTracker(connection, binding.threadId)
            val resumed = CodexAppServerThreadApi(connection).resumeThread(binding.threadId, overrides)
            val resumedAtMs = repository.markResumed(binding.conversationId, binding.threadId)
            val session = CodexAppServerRecoveredSession(
                binding.copy(lastResumedAtMs = resumedAtMs, updatedAtMs = resumedAtMs),
                connection,
                resumed,
                repository,
                checkNotNull(usageTracker),
                effectiveCwd,
                autoRefreshModelCatalog,
            )
            ownershipTransferred = true
            return CodexAppServerSessionRecoveryResult.Recovered(session)
        } finally {
            if (!ownershipTransferred) { usageTracker?.close(); connection.close() }
        }
    }
}
