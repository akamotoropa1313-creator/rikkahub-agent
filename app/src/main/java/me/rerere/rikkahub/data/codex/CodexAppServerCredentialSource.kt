package me.rerere.rikkahub.data.codex

import me.rerere.rikkahub.data.codex.appserver.CodexAppServerChatGptCredentialSource
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerExternalChatGptTokens

class CodexAppServerCredentialSource(
    private val repository: CodexAccountRepository,
) : CodexAppServerChatGptCredentialSource {
    override suspend fun acquire(): CodexAppServerExternalChatGptTokens? {
        if (repository.accounts.value.none { it.isAvailable() }) return null
        return repository.acquireAccount().toAppServerTokens()
    }

    override suspend fun refresh(sourceAccountId: String): CodexAppServerExternalChatGptTokens? {
        val account = repository.accounts.value.firstOrNull { it.id == sourceAccountId }
            ?.takeIf { it.enabled && it.tokenStatus != CodexTokenStatus.INVALID }
            ?: return null
        return repository.refreshCredentials(account.id, force = true).toAppServerTokens()
    }
}

private fun CodexAccount.toAppServerTokens() = CodexAppServerExternalChatGptTokens(
    sourceAccountId = id,
    accessToken = accessToken,
    chatgptAccountId = chatgptAccountId,
)
