package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerApprovalEvent
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerApprovalPolicy
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandAction

internal const val CODEX_COMMAND_TOOL_NAME = "workspace_shell"
internal const val CODEX_READ_FILE_TOOL_NAME = "workspace_read_file"
internal const val CODEX_WRITE_FILE_TOOL_NAME = "workspace_write_file"
internal const val CODEX_EDIT_FILE_TOOL_NAME = "workspace_edit_file"

/**
 * Bridges App Server's coarse approval policy to RikkaHub's existing per-Workspace controls.
 *
 * App Server decides when an operation is risky enough to ask. RikkaHub remains the final UI
 * policy owner: a disabled Workspace approval switch (or the global auto-approve switch) answers
 * such a request automatically instead of showing a second, Codex-only approval surface.
 */
internal data class CodexApprovalIntegrationPolicy(
    val appServerPolicy: CodexAppServerApprovalPolicy?,
    val workspaceOverrides: Map<String, Boolean>,
    val globalAutoApprove: Boolean,
) {
    fun effectiveAppServerPolicy(): CodexAppServerApprovalPolicy? = when {
        globalAutoApprove -> CodexAppServerApprovalPolicy.NEVER
        appServerPolicy == CodexAppServerApprovalPolicy.NEVER -> CodexAppServerApprovalPolicy.NEVER
        allIntegratedWorkspaceApprovalsDisabled() -> CodexAppServerApprovalPolicy.NEVER
        else -> appServerPolicy
    }

    fun shouldAutoApprove(event: CodexAppServerApprovalEvent): Boolean {
        if (globalAutoApprove || appServerPolicy == CodexAppServerApprovalPolicy.NEVER) return true
        return when (event) {
            is CodexAppServerApprovalEvent.CommandExecutionRequest ->
                !needsApproval(codexCommandToolName(event.request.commandActions))
            is CodexAppServerApprovalEvent.FileChangeRequest ->
                !needsApproval(CODEX_WRITE_FILE_TOOL_NAME) &&
                    !needsApproval(CODEX_EDIT_FILE_TOOL_NAME)
            else -> false
        }
    }

    private fun allIntegratedWorkspaceApprovalsDisabled(): Boolean =
        !needsApproval(CODEX_COMMAND_TOOL_NAME) &&
            !needsApproval(CODEX_READ_FILE_TOOL_NAME) &&
            !needsApproval(CODEX_WRITE_FILE_TOOL_NAME) &&
            !needsApproval(CODEX_EDIT_FILE_TOOL_NAME)

    private fun needsApproval(toolName: String): Boolean =
        resolveWorkspaceToolApproval(toolName, workspaceOverrides)
}

/** Keep request-policy resolution and the existing chat renderer on exactly the same tool name. */
internal fun codexCommandToolName(actions: List<CodexAppServerCommandAction>?): String =
    if (actions?.singleOrNull() is CodexAppServerCommandAction.Read) {
        CODEX_READ_FILE_TOOL_NAME
    } else {
        CODEX_COMMAND_TOOL_NAME
    }

internal fun codexToolCallId(turnId: String, itemId: String, index: Int? = null): String =
    buildString {
        append("codex:").append(turnId).append(':').append(itemId)
        index?.let { append(':').append(it) }
    }
