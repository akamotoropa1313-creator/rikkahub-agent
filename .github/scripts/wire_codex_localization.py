from pathlib import Path


def apply(path: str, replacements: list[tuple[str, str]]) -> None:
    p = Path(path)
    text = p.read_text()
    for old, new in replacements:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{path}: expected one match, found {count}: {old}")
        text = text.replace(old, new, 1)
    p.write_text(text)


apply("app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt", [
    ("import me.rerere.rikkahub.ui.components.codex.codexSafetyIndicator\n", "import me.rerere.rikkahub.ui.components.codex.codexSafetyIndicator\nimport me.rerere.rikkahub.ui.components.codex.codexUiText\n"),
    ('title = { Text("Reset Codex session?") },', 'title = { Text(codexUiText("Reset Codex session?")) },'),
    ('text = { Text("Continuity with the current Codex thread will be lost. The next Send will create a new thread.") },', 'text = { Text(codexUiText("Continuity with the current Codex thread will be lost. The next Send will create a new thread.")) },'),
    ('confirmButton = { TextButton(onClick = { confirmCodexReset = false; onResetCodexSession() }) { Text("Reset") } },', 'confirmButton = { TextButton(onClick = { confirmCodexReset = false; onResetCodexSession() }) { Text(codexUiText("Reset")) } },'),
    ('dismissButton = { TextButton(onClick = { confirmCodexReset = false }) { Text("Cancel") } },', 'dismissButton = { TextButton(onClick = { confirmCodexReset = false }) { Text(codexUiText("Cancel")) } },'),
    ('supportingContent = { Text(codexPrerequisite) },', 'supportingContent = { Text(codexUiText(codexPrerequisite)) },'),
    ('headlineContent = { Text("Codex controls") },', 'headlineContent = { Text(codexUiText("Codex controls")) },'),
    ('Text("Connection, Account, Codex Skills, Codex MCP, and safety")', 'Text(codexUiText("Connection, Account, Codex Skills, Codex MCP, and safety"))'),
    ('codexSafetyIndicator(assistant)?.let { Text(it, color = MaterialTheme.colorScheme.error) }', 'codexSafetyIndicator(assistant)?.let { Text(codexUiText(it), color = MaterialTheme.colorScheme.error) }'),
    ('TextButton(onClick = { confirmCodexReset = true }, enabled = !codexOperationBusy) { Text("Reset Codex session") }', 'TextButton(onClick = { confirmCodexReset = true }, enabled = !codexOperationBusy) { Text(codexUiText("Reset Codex session")) }'),
])

apply("app/src/main/java/me/rerere/rikkahub/ui/components/codex/CodexAccountCard.kt", [
    ('presentation.lines.forEach { Text(it) }', 'presentation.lines.forEach { Text(codexUiText(it)) }'),
    ('Text("Codex account")', 'Text(codexUiText("Codex account"))'),
    ('{ Text("Cancel") }', '{ Text(codexUiText("Cancel")) }'),
    ('{ Text("Sign in with ChatGPT") }', '{ Text(codexUiText("Sign in with ChatGPT")) }'),
    ('{ Text("Refresh") }', '{ Text(codexUiText("Refresh")) }'),
    ('{ Text("Log out") }', '{ Text(codexUiText("Log out")) }'),
])

apply("app/src/main/java/me/rerere/rikkahub/ui/components/codex/CodexApprovalCards.kt", [
    ('ApprovalSurface("Command approval", modifier)', 'ApprovalSurface(codexUiText("Command approval"), modifier)'),
    ('LabeledPlainText("Reason", it)', 'LabeledPlainText(codexUiText("Reason"), it)'),
    ('Text("Network access requested", style = MaterialTheme.typography.titleMedium)', 'Text(codexUiText("Network access requested"), style = MaterialTheme.typography.titleMedium)'),
    ('LabeledPlainText("Host", it.host)', 'LabeledPlainText(codexUiText("Host"), it.host)'),
    ('LabeledPlainText("Protocol", it.protocol.toString())', 'LabeledPlainText(codexUiText("Protocol"), it.protocol.toString())'),
    ('LabeledPlainText("Command", it, true)', 'LabeledPlainText(codexUiText("Command"), it, true)'),
    ('LabeledPlainText("Working directory", it)', 'LabeledPlainText(codexUiText("Working directory"), it)'),
    ('LabeledPlainText("Action", commandActionPresentation(it))', 'LabeledPlainText(codexUiText("Action"), commandActionPresentation(it))'),
    ('LabeledPlainText("Environment", it)', 'LabeledPlainText(codexUiText("Environment"), it)'),
    ('ApprovalSurface("File-change approval", modifier)', 'ApprovalSurface(codexUiText("File-change approval"), modifier)'),
    ('LabeledPlainText("Requested grant root", it)', 'LabeledPlainText(codexUiText("Requested grant root"), it)'),
    ('Text("Change preview unavailable. Approval is disabled for your safety.", color = MaterialTheme.colorScheme.error)', 'Text(codexUiText("Change preview unavailable. Approval is disabled for your safety."), color = MaterialTheme.colorScheme.error)'),
    ('Text("Approve once")', 'Text(codexUiText("Approve once"))'),
    ('Text("Approve for session")', 'Text(codexUiText("Approve for session"))'),
    ('Text("Decline")', 'Text(codexUiText("Decline"))'),
    ('Text("Cancel turn")', 'Text(codexUiText("Cancel turn"))'),
])

apply("app/src/main/java/me/rerere/rikkahub/ui/components/codex/CodexExecutionCards.kt", [
    ('CollapsibleText(it, "Output", isDiff = false)', 'CollapsibleText(it, codexUiText("Output"), isDiff = false)'),
    ('is CodexAppServerPatchChangeKind.Add -> "Add"', 'is CodexAppServerPatchChangeKind.Add -> codexUiText("Add")'),
    ('is CodexAppServerPatchChangeKind.Delete -> "Delete"', 'is CodexAppServerPatchChangeKind.Delete -> codexUiText("Delete")'),
    ('is CodexAppServerPatchChangeKind.Update -> "Update${kind.movePath?.let { " → $it" } ?: ""}"', 'is CodexAppServerPatchChangeKind.Update -> codexUiText("Update") + (kind.movePath?.let { " → $it" } ?: "")'),
    ('CollapsibleText(change.diff, "Diff", isDiff = true)', 'CollapsibleText(change.diff, codexUiText("Diff"), isDiff = true)'),
    ('Text("Turn diff", style = MaterialTheme.typography.titleSmall)', 'Text(codexUiText("Turn diff"), style = MaterialTheme.typography.titleSmall)'),
])

p = Path("app/src/main/java/me/rerere/rikkahub/ui/components/codex/CodexControlSheet.kt")
text = p.read_text()
# Exact simple literals: wrap only the displayed string, preserving logic/wire comparisons.
simple = [
    "Codex Control Center", "Thread history", "Back to history", "Search", "Current", "View details", "Load more",
    "Usage & status", "No token usage reported yet", "Current context", "Context window: Not reported", "Latest usage breakdown",
    "Configuration & policy", "These are App Server base and managed settings. RikkaHub thread and turn overrides may differ.",
    "Managed requirements", "No managed requirements reported", "Code review",
    "Run a native inline review on this conversation's bound thread.", "Branch", "Commit SHA", "Title (optional)", "Review instructions",
    "Stop review", "Start review", "Safety & permissions", "Sandbox", "Approval", "Model & behavior", "Codex model",
    "The App Server catalog is authoritative. Changes apply from the next Codex turn.", "Service tier", "Reasoning summary", "Personality",
    "Connection", "Reconnect Codex", "Account", "Refresh", "Disable", "Enable", "Use", "Reload MCP", "Sign in",
    "Confirm", "Cancel",
]
for literal in simple:
    old = f'Text("{literal}")'
    new = f'Text(codexUiText("{literal}"))'
    text = text.replace(old, new)
# Variants with style/other args.
for literal in ["Codex Control Center", "Usage & status", "Current context", "Latest usage breakdown", "Managed requirements", "Code review", "Sandbox", "Approval", "Codex model", "Service tier"]:
    text = text.replace(f'Text("{literal}",', f'Text(codexUiText("{literal}"),')
# Dynamic/display expressions.
repls = [
    ('Text(thread.name ?: thread.preview ?: "Untitled thread",', 'Text(codexUiText(thread.name ?: thread.preview ?: "Untitled thread"),'),
    ('Text("Thread ID: ${thread.id}",', 'Text(codexUiText("Thread ID: ${thread.id}"),'),
    ('thread.modelProvider?.let { Text("Model provider: $it") }', 'thread.modelProvider?.let { Text(codexUiText("Model provider: $it")) }'),
    ('thread.status?.let { Text("Status: ${it.wireValue}") }', 'thread.status?.let { Text(codexUiText("Status: ${it.wireValue}")) }'),
    ('thread.recencyAt?.let { Text("Recency: $it") }', 'thread.recencyAt?.let { Text(codexUiText("Recency: $it")) }'),
    ('thread.updatedAt?.let { Text("Updated: $it") }', 'thread.updatedAt?.let { Text(codexUiText("Updated: $it")) }'),
    ('Text("Turn ${historyTurn.turn.id}",', 'Text(codexUiText("Turn ${historyTurn.turn.id}"),'),
    ('Text(historyItemText(item),', 'Text(codexUiText(historyItemText(item)),') ,
    ('Text("Browse persisted App Server threads without switching this conversation.")', 'Text(codexUiText("Browse persisted App Server threads without switching this conversation."))'),
    ('label = { Text("Search") }', 'label = { Text(codexUiText("Search")) }'),
    ('"Active filter: ${history.searchTerm}"', 'codexUiText("Active filter: ${history.searchTerm}")'),
    (') { Text(if (history.loaded) "Refresh current results" else "Load history") }', ') { Text(codexUiText(if (history.loaded) "Refresh current results" else "Load history")) }'),
    (') { Text(if (historySearch.isBlank()) "Clear search" else "Search") }', ') { Text(codexUiText(if (historySearch.isBlank()) "Clear search" else "Search")) }'),
    ('Text("Current", color =', 'Text(codexUiText("Current"), color ='),
    ('Text("Session total: ${formatTokenCount(usage.total.totalTokens)} tokens")', 'Text(codexUiText("Session total: ${formatTokenCount(usage.total.totalTokens)} tokens"))'),
    ('Text("Usage warning: $it",', 'Text(codexUiText("Usage warning: $it"),'),
    (') { Text(if (capabilities.effectiveConfig == null && !capabilities.requirementsLoaded) "Load configuration" else "Refresh diagnostics") }', ') { Text(codexUiText(if (capabilities.effectiveConfig == null && !capabilities.requirementsLoaded) "Load configuration" else "Refresh diagnostics")) }'),
    ('Text("Configuration: $it",', 'Text(codexUiText("Configuration: $it"),'),
    ('Text("Managed requirements: $it",', 'Text(codexUiText("Managed requirements: $it"),'),
    ('configRow("Model",', 'configRow(codexUiText("Model"),'),
    ('configRow("Model provider",', 'configRow(codexUiText("Model provider"),'),
    ('configRow("Model context window",', 'configRow(codexUiText("Model context window"),'),
    ('configRow("Auto compact limit",', 'configRow(codexUiText("Auto compact limit"),'),
    ('configRow("Sandbox default",', 'configRow(codexUiText("Sandbox default"),'),
    ('configRow("Workspace-write network access",', 'configRow(codexUiText("Workspace-write network access"),'),
    ('Text("Writable roots: $it")', 'Text(codexUiText("Writable roots: $it"))'),
    ('configRow("Web search",', 'configRow(codexUiText("Web search"),'),
    ('configRow("Reasoning effort",', 'configRow(codexUiText("Reasoning effort"),'),
    ('configRow("Reasoning summary",', 'configRow(codexUiText("Reasoning summary"),'),
    ('configRow("Verbosity",', 'configRow(codexUiText("Verbosity"),'),
    ('configRow("Service tier",', 'configRow(codexUiText("Service tier"),'),
    ('configRow("Analytics",', 'configRow(codexUiText("Analytics"),'),
    ('Text((if (reviewKind == kind) "✓ " else "") + kind)', 'Text((if (reviewKind == kind) "✓ " else "") + codexUiText(kind))'),
    ('Text("Review in progress · ${review.targetSummary.orEmpty()}")', 'Text(codexUiText("Review in progress · ${review.targetSummary.orEmpty()}"))'),
    ('Text((if (assistant.codexSandboxMode == value) "✓ " else "") + label)', 'Text((if (assistant.codexSandboxMode == value) "✓ " else "") + codexUiText(label))'),
    ('Text(when (assistant.codexSandboxMode) {', 'Text(codexUiText(when (assistant.codexSandboxMode) {'),
    ('                })\n                if (!codexSandboxKnown', '                }))\n                if (!codexSandboxKnown'),
    ('Text((if (assistant.codexApprovalPolicy == value) "✓ " else "") + label)', 'Text((if (assistant.codexApprovalPolicy == value) "✓ " else "") + codexUiText(label))'),
    ('Text(when (assistant.codexApprovalPolicy) {', 'Text(codexUiText(when (assistant.codexApprovalPolicy) {'),
    ('                })\n                if (!codexApprovalKnown', '                }))\n                if (!codexApprovalKnown'),
    (') { Text(if (capabilities.models.isEmpty()) "Load models" else "Refresh models") }', ') { Text(codexUiText(if (capabilities.models.isEmpty()) "Load models" else "Refresh models")) }'),
    ('Text(if (assistant.codexModel == model.model) "Selected" else "Select")', 'Text(codexUiText(if (assistant.codexModel == model.model) "Selected" else "Select"))'),
    ('Text((if (assistant.codexServiceTier == null) "✓ " else "") + "Server setting")', 'Text((if (assistant.codexServiceTier == null) "✓ " else "") + codexUiText("Server setting"))'),
    ('Text((if (assistant.codexServiceTier == "default") "✓ " else "") + "Default")', 'Text((if (assistant.codexServiceTier == "default") "✓ " else "") + codexUiText("Default"))'),
    ('Text(connectionLabel(connection, hasBinding))', 'Text(codexUiText(connectionLabel(connection, hasBinding)))'),
    ('Text("${capabilities.skillGroups.sumOf { it.skills.size }} skills")', 'Text(codexUiText("${capabilities.skillGroups.sumOf { it.skills.size }} skills"))'),
    ('Text(if (skill.enabled) "Disable" else "Enable")', 'Text(codexUiText(if (skill.enabled) "Disable" else "Enable"))'),
]
for old, new in repls:
    if old not in text:
        raise SystemExit(f"CodexControlSheet missing expected text: {old}")
    text = text.replace(old, new, 1)
p.write_text(text)
