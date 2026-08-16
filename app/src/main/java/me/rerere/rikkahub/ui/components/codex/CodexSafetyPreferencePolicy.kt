package me.rerere.rikkahub.ui.components.codex

import me.rerere.rikkahub.data.model.Assistant

internal enum class CodexSafetyConfirmation { NONE, FULL_ACCESS, NEVER, CRITICAL }

internal fun codexSafetyConfirmation(current: Assistant, sandbox: String? = current.codexSandboxMode, approval: String? = current.codexApprovalPolicy): CodexSafetyConfirmation = when {
    sandbox == "danger-full-access" && approval == "never" &&
        (current.codexSandboxMode != sandbox || current.codexApprovalPolicy != approval) -> CodexSafetyConfirmation.CRITICAL
    sandbox == "danger-full-access" && current.codexSandboxMode != sandbox -> CodexSafetyConfirmation.FULL_ACCESS
    approval == "never" && current.codexApprovalPolicy != approval -> CodexSafetyConfirmation.NEVER
    else -> CodexSafetyConfirmation.NONE
}

internal fun codexSafetyIndicator(assistant: Assistant): String? = listOfNotNull(
    "Full access".takeIf { assistant.codexSandboxMode == "danger-full-access" },
    "No approvals".takeIf { assistant.codexApprovalPolicy == "never" },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

internal fun codexSandboxKnown(value: String?) = value == null || value in setOf("read-only", "workspace-write", "danger-full-access")
internal fun codexApprovalKnown(value: String?) = value == null || value in setOf("untrusted", "on-request", "never")

