package me.rerere.rikkahub.data.codex.appserver

import me.rerere.workspace.WorkspaceInteractiveProcess
import me.rerere.workspace.WorkspaceManager

class WorkspaceCodexAppServerLauncher(private val workspaceManager: WorkspaceManager) {
    fun launch(root: String, cwd: String = ""): CodexAppServerStdioTransport {
        val process = workspaceManager.startInteractiveProcess(root, COMMAND, cwd)
        return try {
            CodexAppServerStdioTransport(process)
        } catch (error: Throwable) {
            process.close()
            throw error
        }
    }

    companion object {
        const val COMMAND = "exec codex app-server --listen stdio://"
    }
}
