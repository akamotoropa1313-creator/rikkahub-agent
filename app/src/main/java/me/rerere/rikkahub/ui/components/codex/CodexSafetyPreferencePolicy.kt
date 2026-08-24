package me.rerere.rikkahub.ui.components.codex

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

/**
 * Null means "omit the override", not "the effective bound-thread policy is known safe". Because
 * App Server turn overrides are sticky, keep a compact warning while either preference is at Server
 * setting; Reset/new-thread is the only Stage19 operation that can reliably clear an older sticky
 * override. This intentionally favors a conservative false-positive on fresh threads over hiding a
 * potentially still-effective Full access / Never policy.
 */
internal fun codexSafetyIndicator(assistant: Assistant): String? {
    val explicit = listOfNotNull(
        "フルアクセス".takeIf { assistant.codexSandboxMode == "danger-full-access" },
        "承認確認なし".takeIf { assistant.codexApprovalPolicy == "never" },
    )
    if (explicit.isNotEmpty()) return explicit.joinToString(" · ")
    if (assistant.codexSandboxMode == null || assistant.codexApprovalPolicy == null) {
        return "サーバーの安全設定 · リセットすると固定された上書き設定を解除します"
    }
    return null
}

internal fun codexSandboxKnown(value: String?) =
    value == null || value in setOf("read-only", "workspace-write", "danger-full-access")

internal fun codexApprovalKnown(value: String?) =
    value == null || value in setOf("untrusted", "on-request", "never")
