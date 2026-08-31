package me.rerere.rikkahub.ui.components.codex

import me.rerere.rikkahub.data.codex.appserver.CODEX_SANDBOX_SERVER_DEFAULT
import me.rerere.rikkahub.data.model.Assistant

internal enum class CodexSafetyConfirmation { NONE, FULL_ACCESS, NEVER, CRITICAL }

internal fun codexSafetyConfirmation(
    current: Assistant,
    sandbox: String? = current.codexSandboxMode,
    approval: String? = current.codexApprovalPolicy,
): CodexSafetyConfirmation = when {
    sandbox == "danger-full-access" && approval == "never" &&
        (current.codexSandboxMode != sandbox || current.codexApprovalPolicy != approval) -> CodexSafetyConfirmation.CRITICAL
    sandbox == "danger-full-access" && current.codexSandboxMode != sandbox -> CodexSafetyConfirmation.FULL_ACCESS
    approval == "never" && current.codexApprovalPolicy != approval -> CodexSafetyConfirmation.NEVER
    else -> CodexSafetyConfirmation.NONE
}

/** Keep server-managed sticky settings visible without warning for the safe Workspace default. */
internal fun codexSafetyIndicator(assistant: Assistant): String? {
    val explicit = listOfNotNull(
        "フルアクセス".takeIf { assistant.codexSandboxMode == "danger-full-access" },
        "承認確認なし".takeIf { assistant.codexApprovalPolicy == "never" },
    )
    if (explicit.isNotEmpty()) return explicit.joinToString(" · ")
    val serverManaged = listOfNotNull(
        "サンドボックス".takeIf { assistant.codexSandboxMode == CODEX_SANDBOX_SERVER_DEFAULT },
        "承認".takeIf { assistant.codexApprovalPolicy == null },
    )
    if (serverManaged.isNotEmpty()) {
        return serverManaged.joinToString("・") + "はApp Server設定 · 固定された上書きの解除にはリセットが必要です"
    }
    return null
}

internal fun codexSandboxKnown(value: String?) =
    value == null || value in setOf(
        CODEX_SANDBOX_SERVER_DEFAULT,
        "read-only",
        "workspace-write",
        "danger-full-access",
    )

internal fun codexApprovalKnown(value: String?) =
    value == null || value in setOf("untrusted", "on-request", "never")
