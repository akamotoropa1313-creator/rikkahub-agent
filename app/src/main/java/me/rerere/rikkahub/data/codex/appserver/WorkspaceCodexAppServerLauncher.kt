package me.rerere.rikkahub.data.codex.appserver

import me.rerere.workspace.WorkspaceManager

class WorkspaceCodexAppServerLauncher(private val workspaceManager: WorkspaceManager) {
    fun launch(root: String, cwd: String = "", runtime: CodexRuntimeReady): CodexAppServerStdioTransport {
        // Validate through the same resolver used for config/read; WorkspaceManager still receives
        // its established root + relative-cwd pair and performs no new host-path translation.
        resolveCodexEffectiveCwd(root, cwd)
        val command = "exec ${shellQuote(runtime.executable)} app-server --listen stdio://"
        val process = workspaceManager.startInteractiveProcess(root, command, cwd)
        return try {
            CodexAppServerStdioTransport(process)
        } catch (error: Throwable) {
            process.close()
            throw CodexRuntimeProvisioningException(
                CodexRuntimeErrorCategory.AppServerLaunchFailed,
                error.message ?: "Codex App Server could not be launched",
                error,
            )
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
