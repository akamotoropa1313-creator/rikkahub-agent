package me.rerere.rikkahub.data.codex.appserver

import me.rerere.workspace.WorkspaceManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun interface CodexRuntimeResolver {
    suspend fun ensureReady(root: String): CodexRuntimeReady
}

fun interface CodexAppServerConnectionCreator {
    suspend fun create(root: String, cwd: String): CodexAppServerConnection
}

class WorkspaceCodexAppServerConnectionFactory(
    workspaceManager: WorkspaceManager,
    private val runtimeResolver: CodexRuntimeResolver,
    private val appVersion: String,
    private val networkEnvironmentPreparer: CodexNetworkEnvironmentPreparer? = null,
    private val initializeTimeout: Duration = 30.seconds,
    private val enableExperimentalApi: Boolean = false,
) : CodexAppServerConnectionCreator {
    private val launcher = WorkspaceCodexAppServerLauncher(workspaceManager)

    suspend fun create(root: String): CodexAppServerConnection = create(root, "")

    override suspend fun create(root: String, cwd: String): CodexAppServerConnection {
        val runtime = runtimeResolver.ensureReady(root)
        networkEnvironmentPreparer?.prepare(root)
        val transport = launcher.launch(root, cwd, runtime)
        return try {
            CodexAppServerConnection(
                dispatcher = CodexAppServerRequestDispatcher(transport),
                clientInfo = CodexAppServerClientInfo(version = appVersion),
                capabilities = if (enableExperimentalApi) {
                    CodexAppServerInitializeCapabilities(experimentalApi = true)
                } else {
                    null
                },
                initializeTimeout = initializeTimeout,
            )
        } catch (error: Throwable) {
            transport.close()
            throw error
        }
    }
}
