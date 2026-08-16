package me.rerere.rikkahub.data.codex.appserver

import me.rerere.workspace.WorkspaceInteractiveProcess
import me.rerere.workspace.WorkspaceManager

class WorkspaceCodexAppServerLauncher(private val workspaceManager: WorkspaceManager) {
    fun launch(root: String, cwd: String = ""): CodexAppServerStdioTransport {
        // Validate through the same resolver used for config/read; WorkspaceManager still receives
        // its established root + relative-cwd pair and performs no new host-path translation.
        resolveCodexEffectiveCwd(root, cwd)
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
