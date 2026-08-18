package me.rerere.rikkahub.data.codex.appserver

import me.rerere.workspace.WorkspaceManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun interface CodexAppServerConnectionCreator {
    suspend fun create(root: String, cwd: String): CodexAppServerConnection
}

class WorkspaceCodexAppServerConnectionFactory(
    workspaceManager: WorkspaceManager,
    private val runtimeManager: CodexRuntimeManager,
    private val appVersion: String,
    private val initializeTimeout: Duration = 30.seconds,
) : CodexAppServerConnectionCreator {
    private val launcher = WorkspaceCodexAppServerLauncher(workspaceManager)

    suspend fun create(root: String): CodexAppServerConnection = create(root, "")

    override suspend fun create(root: String, cwd: String): CodexAppServerConnection {
        val runtime = runtimeManager.ensureReady(root)
        val transport = launcher.launch(root, cwd, runtime)
        return try {
            CodexAppServerConnection(
                dispatcher = CodexAppServerRequestDispatcher(transport),
                clientInfo = CodexAppServerClientInfo(version = appVersion),
                initializeTimeout = initializeTimeout,
            )
        } catch (error: Throwable) {
            transport.close()
            throw error
        }
    }
}
