package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

fun interface CodexAppServerConnectionBootstrapper {
    suspend fun bootstrap(connection: CodexAppServerConnection)
}

interface CodexAppServerChatGptCredentialSource {
    suspend fun acquire(): CodexAppServerExternalChatGptTokens?
    suspend fun refresh(sourceAccountId: String): CodexAppServerExternalChatGptTokens?
}

/**
 * Connects process-local App Server state to RikkaHub-owned state.
 *
 * RikkaHub's skill directory is already bind-mounted at `/skills` inside every workspace PRoot.
 * The App Server still needs that path registered as an extra standalone skill root. ChatGPT
 * credentials remain encrypted in RikkaHub's credential store; only the current access token is
 * passed over the private stdio connection, using App Server's external-token mode.
 */
class RikkaHubCodexAppServerBridge(
    private val credentialSource: CodexAppServerChatGptCredentialSource,
    private val skillsRoot: String = RIKKAHUB_SKILLS_ROOT,
) : CodexAppServerConnectionBootstrapper {
    private val active = ConcurrentHashMap<CodexAppServerConnection, ActiveConnection>()

    override suspend fun bootstrap(connection: CodexAppServerConnection) {
        CodexAppServerSkillsApi(connection).replaceExtraRoots(listOf(skillsRoot))
        syncAccount(connection)
    }

    /** Returns false only when RikkaHub has no currently usable Codex-provider account. */
    suspend fun syncAccount(connection: CodexAppServerConnection): Boolean {
        val tokens = credentialSource.acquire() ?: return false
        val session = active.computeIfAbsent(connection) { ActiveConnection(it) }
        session.syncMutex.withLock {
            CodexAppServerAccountApi(connection).startChatGptAuthTokensLogin(tokens)
            session.sourceAccountId.set(tokens.sourceAccountId)
            session.chatgptAccountId.set(tokens.chatgptAccountId)
        }
        return true
    }

    private inner class ActiveConnection(
        private val connection: CodexAppServerConnection,
    ) {
        val syncMutex = Mutex()
        val sourceAccountId = AtomicReference<String?>(null)
        val chatgptAccountId = AtomicReference<String?>(null)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val accountApi = CodexAppServerAccountApi(connection)

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                accountApi.chatGptAuthTokensRefreshRequests.collect { request ->
                    respondToRefresh(request)
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                connection.state.collect { state ->
                    if (state is CodexAppServerConnectionState.Failed ||
                        state == CodexAppServerConnectionState.Closing ||
                        state == CodexAppServerConnectionState.Closed
                    ) {
                        if (active.remove(connection, this@ActiveConnection)) scope.cancel()
                    }
                }
            }
        }

        private suspend fun respondToRefresh(request: CodexAppServerChatGptAuthTokensRefreshRequest) {
            try {
                val sourceId = checkNotNull(sourceAccountId.get()) {
                    "RikkaHub Codex credential source is not selected"
                }
                val refreshed = withTimeout(CLIENT_REFRESH_TIMEOUT_MS) {
                    checkNotNull(credentialSource.refresh(sourceId)) {
                        "RikkaHub Codex credential is no longer available"
                    }
                }
                val expectedAccountId = request.previousAccountId ?: chatgptAccountId.get()
                check(expectedAccountId == null || expectedAccountId == refreshed.chatgptAccountId) {
                    "RikkaHub Codex account changed during token refresh"
                }
                accountApi.respondChatGptAuthTokensRefresh(request, refreshed)
                chatgptAccountId.set(refreshed.chatgptAccountId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                runCatching {
                    connection.respondServerRequestErrorAfterReady(
                        request.requestId,
                        JsonRpcError(
                            code = EXTERNAL_AUTH_REFRESH_ERROR,
                            message = "RikkaHub Codex credential refresh failed",
                        ),
                    )
                }
            }
        }
    }

    companion object {
        const val RIKKAHUB_SKILLS_ROOT = "/skills"
        private const val EXTERNAL_AUTH_REFRESH_ERROR = -32001L
        // App Server abandons this server request after 10 seconds; keep time to return an error.
        private const val CLIENT_REFRESH_TIMEOUT_MS = 9_000L
    }
}
