package me.rerere.rikkahub.ui.components.codex

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.service.CodexCapabilitiesUiState
import me.rerere.rikkahub.service.CodexConversationUiState
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import me.rerere.rikkahub.data.codex.appserver.CodexMcpAuthStatus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.CodexPersonalityPreference
import me.rerere.rikkahub.data.model.CodexReasoningSummaryPreference
import me.rerere.rikkahub.data.codex.appserver.CodexTokenUsageTelemetry
import me.rerere.rikkahub.data.codex.appserver.CodexEffectiveConfigSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexConfigRequirementsSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerReviewTarget
import me.rerere.rikkahub.service.CodexReviewUiState

/** Explicit control plane for the existing conversation-owned Codex runtime. Opening it sends no RPC. */
@Composable
fun CodexControlSheet(
    connection: CodexConversationUiState,
    capabilities: CodexCapabilitiesUiState,
    review: CodexReviewUiState,
    onStartReview: (CodexAppServerReviewTarget) -> Unit,
    assistant: Assistant,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    hasBinding: Boolean,
    onRefreshAccount: () -> Unit,
    onRefreshModels: () -> Unit,
    onRefreshConfigDiagnostics: () -> Unit,
    onRefreshSkills: () -> Unit,
    onRefreshMcp: () -> Unit,
    onReloadMcp: () -> Unit,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onLogout: () -> Unit,
    onSetSkillEnabled: (CodexSkillMetadata, Boolean) -> Unit,
    onUseSkill: (CodexSkillMetadata) -> Unit,
    onMcpSignIn: (String) -> Unit,
    operationBusy: Boolean,
    onReconnect: () -> Unit,
) {
    var pendingSafety by remember { mutableStateOf<Pair<String?, String?>?>(null) }
    var reviewKind by remember { mutableStateOf("Working tree") }
    var branch by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    val selectedModel = selectedCodexModel(assistant.codexModel, capabilities.models)
    val serviceTierModel = serviceTierCatalogModel(assistant.codexModel, capabilities.models)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Codex Control Center", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("Usage & status") {
                val telemetry = connection.telemetryOrNull()
                val usage = telemetry?.latest?.tokenUsage
                if (usage == null) Text("No token usage reported yet") else {
                    Text("Current context", style = MaterialTheme.typography.titleMedium)
                    Text(currentContextText(usage))
                    if (usage.modelContextWindow == null) Text("Context window: Not reported")
                    Text("Session total: ${formatTokenCount(usage.total.totalTokens)} tokens")
                    Text("Latest usage breakdown", style = MaterialTheme.typography.titleMedium)
                    Text("Input: ${formatTokenCount(usage.last.inputTokens)}")
                    Text("Cached input: ${formatTokenCount(usage.last.cachedInputTokens)}")
                    usage.last.cacheWriteInputTokens?.let { Text("Cache write input: ${formatTokenCount(it)}") }
                    Text("Output: ${formatTokenCount(usage.last.outputTokens)}")
                    Text("Reasoning output: ${formatTokenCount(usage.last.reasoningOutputTokens)}")
                }
                telemetry?.warning?.let { Text("Usage warning: $it", color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                (connection as? CodexConversationUiState.Terminal)?.diagnostics?.let { turn ->
                    Text(turnStatusText(turn), style = MaterialTheme.typography.titleMedium)
                    turn.error?.let { error ->
                        Text("${errorCategoryLabel(error.codexErrorInfo)}: ${error.message}", color = MaterialTheme.colorScheme.error)
                        error.additionalDetails?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        item {
            Section("Configuration & policy") {
                Text("These are App Server base and managed settings. RikkaHub thread and turn overrides may differ.")
                Button(
                    onClick = onRefreshConfigDiagnostics,
                    enabled = capabilities.connected && !capabilities.configLoading && !capabilities.requirementsLoading && !operationBusy,
                ) { Text(if (capabilities.effectiveConfig == null && !capabilities.requirementsLoaded) "Load configuration" else "Refresh diagnostics") }
                if (capabilities.configLoading || capabilities.requirementsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                capabilities.configError?.let { Text("Configuration: $it", color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                capabilities.requirementsError?.let { Text("Managed requirements: $it", color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                capabilities.effectiveConfig?.let { config ->
                    Text(if (config.threadAgnostic) "Base App Server configuration · Thread-agnostic configuration" else "Base App Server configuration", style = MaterialTheme.typography.titleMedium)
                    configRow("Model", config.model, config, "model")
                    configRow("Model provider", config.modelProvider, config, "model_provider")
                    configRow("Model context window", config.modelContextWindow?.toString(), config, "model_context_window")
                    configRow("Auto compact limit", config.modelAutoCompactTokenLimit?.toString(), config, "model_auto_compact_token_limit")
                    configRow("Sandbox default", config.sandboxMode?.let { if (it.known) it.wireValue else "${it.wireValue} (unknown)" }, config, "sandbox_mode")
                    configRow("Workspace-write network access", config.sandboxWorkspaceWrite?.networkAccess?.enabledLabel(), config, "sandbox_workspace_write.network_access")
                    config.sandboxWorkspaceWrite?.writableRootsCount?.let { Text("Writable roots: $it") }
                    configRow("Web search", config.webSearch, config, "web_search")
                    configRow("Reasoning effort", config.modelReasoningEffort, config, "model_reasoning_effort")
                    configRow("Reasoning summary", config.modelReasoningSummary, config, "model_reasoning_summary")
                    configRow("Verbosity", config.modelVerbosity, config, "model_verbosity")
                    configRow("Service tier", config.serviceTier, config, "service_tier")
                    configRow("Analytics", config.analyticsEnabled?.enabledLabel(), config, "analytics.enabled")
                }
                if (capabilities.requirementsLoaded) {
                    Text("Managed requirements", style = MaterialTheme.typography.titleMedium)
                    capabilities.requirements?.let { requirements ->
                        requirements.allowedSandboxModes?.let { Text("Allowed sandbox modes: " + it.joinToString(" · ") { mode -> mode.wireValue }) }
                        requirements.allowedWebSearchModes?.let { Text("Allowed web search: " + it.joinToString(" · ")) }
                        requirements.newThread?.model?.let { Text("Managed new-thread model: $it") }
                        requirements.newThread?.modelReasoningEffort?.let { Text("Managed new-thread effort: $it") }
                        requirements.newThread?.serviceTier?.let { Text("Managed new-thread service tier: $it") }
                        requirements.featureRequirements?.forEach { (feature, required) -> Text("$feature = required ${if (required) "enabled" else "disabled"}") }
                    } ?: Text("No managed requirements reported")
                }
            }
        }
        item {
            Section("Code review") {
                Text("Run a native inline review on this conversation's bound thread.")
                listOf("Working tree", "Base branch", "Commit", "Custom").forEach { kind ->
                    TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = { reviewKind = kind }) {
                        Text((if (reviewKind == kind) "✓ " else "") + kind)
                    }
                }
                when (reviewKind) {
                    "Base branch" -> OutlinedTextField(branch, { branch = it }, Modifier.fillMaxWidth(), label = { Text("Branch") }, singleLine = true)
                    "Commit" -> { OutlinedTextField(sha, { sha = it }, Modifier.fillMaxWidth(), label = { Text("Commit SHA") }, singleLine = true); OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title (optional)") }) }
                    "Custom" -> OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("Review instructions") }, minLines = 4)
                }
                if (review.inProgress) Text("Review in progress · ${review.targetSummary.orEmpty()}")
                review.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val valid = when (reviewKind) { "Base branch" -> branch.isNotBlank(); "Commit" -> sha.isNotBlank(); "Custom" -> instructions.isNotBlank(); else -> true }
                Button(modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), enabled = valid && !review.inProgress && !operationBusy, onClick = {
                    onStartReview(when (reviewKind) {
                        "Base branch" -> CodexAppServerReviewTarget.BaseBranch(branch)
                        "Commit" -> CodexAppServerReviewTarget.Commit(sha, title.takeIf(String::isNotBlank))
                        "Custom" -> CodexAppServerReviewTarget.Custom(instructions)
                        else -> CodexAppServerReviewTarget.UncommittedChanges
                    })
                }) { Text("Start review") }
            }
        }
        item {
            Section("Safety & permissions") {
                Text("Sandbox", style = MaterialTheme.typography.titleMedium)
                Text("Server setting omits the override. On an existing thread a previous override may be sticky; Reset is required to return completely to server configuration.")
                listOf(null to "Server setting", "read-only" to "Read only", "workspace-write" to "Workspace write", "danger-full-access" to "Full access").forEach { (value, label) ->
                    TextButton(onClick = {
                        val confirmation = codexSafetyConfirmation(assistant, sandbox = value)
                        if (confirmation == CodexSafetyConfirmation.NONE) onUpdateAssistant { it.copy(codexSandboxMode = value) }
                        else pendingSafety = value to assistant.codexApprovalPolicy
                    }) { Text((if (assistant.codexSandboxMode == value) "✓ " else "") + label) }
                }
                Text(when (assistant.codexSandboxMode) {
                    "read-only" -> "Codex can read project files but writes are restricted."
                    "workspace-write" -> "Codex can modify files allowed by the workspace sandbox."
                    "danger-full-access" -> "Removes Codex sandbox restrictions for the environment available to the App Server."
                    else -> "The App Server setting is used when no explicit override is selected."
                })
                if (!codexSandboxKnown(assistant.codexSandboxMode)) Text("Unsupported saved sandbox preference '${assistant.codexSandboxMode}' is preserved and will not be sent.", color = MaterialTheme.colorScheme.error)
                managedSandboxWarning(assistant.codexSandboxMode, capabilities.requirementsLoaded, capabilities.requirements)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Text("Approval", style = MaterialTheme.typography.titleMedium)
                listOf(null to "Server setting", "untrusted" to "Untrusted", "on-request" to "On request", "never" to "Never").forEach { (value, label) ->
                    TextButton(onClick = {
                        val confirmation = codexSafetyConfirmation(assistant, approval = value)
                        if (confirmation == CodexSafetyConfirmation.NONE) onUpdateAssistant { it.copy(codexApprovalPolicy = value) }
                        else pendingSafety = assistant.codexSandboxMode to value
                    }) { Text((if (assistant.codexApprovalPolicy == value) "✓ " else "") + label) }
                }
                Text(when (assistant.codexApprovalPolicy) {
                    "untrusted" -> "Only known-safe read-only commands are automatically approved; other operations may request approval."
                    "on-request" -> "Codex decides when it needs to ask for approval."
                    "never" -> "Codex does not ask for approval; blocked operations fail instead. This does not itself mean Full access."
                    else -> "Server approval policy is used when no explicit override is selected."
                })
                if (!codexApprovalKnown(assistant.codexApprovalPolicy)) Text("Unsupported saved approval preference '${assistant.codexApprovalPolicy}' is preserved and will not be sent.", color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Section("Model & behavior") {
                Text("Codex model", style = MaterialTheme.typography.titleMedium)
                Text("The App Server catalog is authoritative. Changes apply from the next Codex turn.")
                Button(
                    onClick = onRefreshModels,
                    enabled = capabilities.connected && !capabilities.modelsLoading && !operationBusy,
                ) {
                    Text(if (capabilities.models.isEmpty()) "Load models" else "Refresh models")
                }
                if (capabilities.modelsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                capabilities.modelsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (savedCodexModelMissing(assistant.codexModel, capabilities.models)) {
                    Text(
                        "Saved Codex model '${assistant.codexModel}' is no longer available. Select another model explicitly; RikkaHub will not silently replace it.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (assistant.codexModel == null) {
                    Text("Current preference: server default. After an explicit selection, a concrete catalog model is saved.")
                }
            }
        }
        items(capabilities.models, key = { it.id }) { model ->
            val advertisedEfforts = model.supportedReasoningEfforts.map { it.reasoningEffort }
            val validEfforts = advertisedEfforts.isNotEmpty() && model.defaultReasoningEffort in advertisedEfforts
            ListItem(
                headlineContent = {
                    Text(
                        model.displayName + if (model.isDefault) " · Default" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Column {
                        Text(model.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            model.inputModalities?.joinToString(" · ", prefix = "Inputs: ")
                                ?: "Inputs: not reported",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (validEfforts) {
                            Text("Effort: " + advertisedEfforts.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        } else {
                            Text("This catalog entry has invalid reasoning-effort metadata.", color = MaterialTheme.colorScheme.error)
                        }
                        if (!model.supportsPersonality) Text("This model does not advertise personality support")
                    }
                },
                trailingContent = {
                    TextButton(
                        enabled = validEfforts,
                        onClick = { onUpdateAssistant { latest -> applyCodexModelSelection(latest, model) } },
                    ) {
                        Text(if (assistant.codexModel == model.model) "Selected" else "Select")
                    }
                },
            )
        }
        item {
            Text("Service tier", style = MaterialTheme.typography.titleMedium)
            Text("Server setting omits the override. On an existing thread, the server-side tier may remain sticky. Default explicitly requests the default tier on the next turn.")
            TextButton(onClick = { onUpdateAssistant { it.copy(codexServiceTier = null) } }) {
                Text((if (assistant.codexServiceTier == null) "✓ " else "") + "Server setting")
            }
            TextButton(onClick = { onUpdateAssistant { it.copy(codexServiceTier = "default") } }) {
                Text((if (assistant.codexServiceTier == "default") "✓ " else "") + "Default")
            }
            serviceTierModel?.let { tierModel ->
                codexServiceTierOptions(tierModel).forEach { tier ->
                    TextButton(onClick = { onUpdateAssistant { it.copy(codexServiceTier = tier.id) } }) {
                        Text((if (assistant.codexServiceTier == tier.id) "✓ " else "") + tier.name + " · " + tier.description)
                    }
                }
                tierModel.defaultServiceTier?.let { default ->
                    val label = codexServiceTierOptions(tierModel).firstOrNull { it.id == default }?.name ?: default
                    Text("Catalog default: $label")
                }
            }
            val savedTier = assistant.codexServiceTier
            if (savedTier != null && savedTier != "default") {
                when {
                    serviceTierModel == null -> Text("Tier support not confirmed in the current model catalog")
                    serviceTierModel.serviceTiers == null && serviceTierModel.additionalSpeedTiers == null ->
                        Text("Tier support not reported by this App Server; the saved exact tier will be preserved")
                    codexServiceTierOptions(serviceTierModel).none { it.id == savedTier } ->
                        Text("Tier is not supported by the selected Codex model", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        selectedModel?.let { selected ->
            items(selected.supportedReasoningEfforts, key = { it.reasoningEffort }) { effort ->
                TextButton(onClick = { onUpdateAssistant { it.copy(codexReasoningEffort = effort.reasoningEffort) } }) {
                    Text(
                        (if (assistant.codexReasoningEffort == effort.reasoningEffort) "✓ " else "") +
                            effort.reasoningEffort + " · " + effort.description,
                    )
                }
            }
            item {
                Text("Reasoning summary")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    CodexReasoningSummaryPreference.entries.forEach { value ->
                        TextButton(onClick = { onUpdateAssistant { it.copy(codexReasoningSummary = value) } }) {
                            Text((if (assistant.codexReasoningSummary == value) "✓ " else "") + value.name.lowercase())
                        }
                    }
                }
                Text("Personality")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    CodexPersonalityPreference.entries.forEach { value ->
                        TextButton(
                            enabled = selected.supportsPersonality,
                            onClick = { onUpdateAssistant { it.copy(codexPersonality = value) } },
                        ) {
                            Text((if (assistant.codexPersonality == value) "✓ " else "") + value.name.lowercase())
                        }
                    }
                }
                if (!selected.supportsPersonality) {
                    Text("This model does not advertise personality support")
                }
            }
        }
        item {
            Section("Connection") {
                Text(connectionLabel(connection, hasBinding))
                if (codexReconnectEligible(connection, hasBinding, capabilities.connected, operationBusy)) {
                    Button(onClick = onReconnect) { Text("Reconnect Codex") }
                }
            }
        }
        item {
            Section("Account") {
                CodexAccountCard(
                    snapshot = capabilities.account,
                    loginPending = capabilities.pendingLoginId != null,
                    onSignIn = onSignIn,
                    onCancelSignIn = onCancelSignIn,
                    onRefresh = onRefreshAccount,
                    onLogout = onLogout,
                    enabled = capabilities.connected && !capabilities.accountLoading && !operationBusy,
                    submitting = capabilities.accountSubmitting,
                    statusMessage = capabilities.accountError ?: capabilities.accountStatus,
                )
            }
        }
        item {
            Section("Codex Skills") {
                Text("${capabilities.skillGroups.sumOf { it.skills.size }} skills")
                Button(
                    onClick = onRefreshSkills,
                    enabled = capabilities.connected && !capabilities.skillsLoading && !operationBusy,
                ) { Text("Refresh") }
                capabilities.skillsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        capabilities.skillGroups.forEach { group ->
            item {
                Text(group.cwd, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            items(group.skills, key = { it.path }) { skill ->
                ListItem(
                    headlineContent = { Text(skill.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(skill.shortDescription ?: skill.description, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Row {
                            TextButton(
                                onClick = { onSetSkillEnabled(skill, !skill.enabled) },
                                enabled = capabilities.skillUpdatingPath == null && !operationBusy,
                            ) { Text(if (skill.enabled) "Disable" else "Enable") }
                            TextButton(onClick = { onUseSkill(skill) }, enabled = skill.enabled && !operationBusy) { Text("Use") }
                        }
                    },
                )
            }
            items(group.errors, key = { it.path }) { Text(it.message, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Section("Codex MCP") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRefreshMcp,
                        enabled = capabilities.connected && !capabilities.mcpLoading && !operationBusy,
                    ) { Text("Refresh") }
                    OutlinedButton(
                        onClick = onReloadMcp,
                        enabled = capabilities.connected && !capabilities.mcpLoading && !operationBusy,
                    ) { Text("Reload MCP") }
                }
                capabilities.mcpError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        items(capabilities.mcpServers, key = { it.name }) { server ->
            ListItem(
                headlineContent = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${server.authStatus.wireValue} · ${server.tools.size} tools · ${server.resources.size} resources") },
                trailingContent = {
                    if (server.authStatus is CodexMcpAuthStatus.NotLoggedIn) {
                        TextButton(
                            onClick = { onMcpSignIn(server.name) },
                            enabled = capabilities.pendingMcpServer == null && !operationBusy,
                        ) { Text("Sign in") }
                    }
                },
            )
        }
    }
    pendingSafety?.let { pending ->
        val kind = codexSafetyConfirmation(assistant, pending.first, pending.second)
        AlertDialog(
            onDismissRequest = { pendingSafety = null },
            title = { Text(if (kind == CodexSafetyConfirmation.CRITICAL) "Critical safety warning" else "Confirm safety setting") },
            text = { Text(when (kind) {
                CodexSafetyConfirmation.CRITICAL -> "Full access + Never removes the normal sandbox restriction while also disabling approval prompts. Codex may modify data available in its execution environment without asking."
                CodexSafetyConfirmation.FULL_ACCESS -> "Sandbox restrictions are removed. Codex may modify data available inside its execution environment. Enable only when you intentionally want unrestricted execution."
                else -> "Approval prompts are disabled. Operations blocked by the sandbox or policy may fail rather than ask. This does not itself mean Full access."
            }) },
            confirmButton = { TextButton(onClick = { onUpdateAssistant { it.copy(codexSandboxMode = pending.first, codexApprovalPolicy = pending.second) }; pendingSafety = null }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { pendingSafety = null }) { Text("Cancel") } },
        )
    }
}

private fun CodexConversationUiState.telemetryOrNull(): CodexTokenUsageTelemetry? = when (this) {
    is CodexConversationUiState.Ready -> telemetry
    is CodexConversationUiState.Running -> telemetry
    is CodexConversationUiState.Terminal -> telemetry
    is CodexConversationUiState.WaitingForApproval -> telemetry
    else -> null
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }

private fun connectionLabel(state: CodexConversationUiState, bound: Boolean) = when (state) {
    CodexConversationUiState.Disabled -> "Disabled"
    CodexConversationUiState.Disconnected -> if (bound) "Disconnected but bound" else "Send a Codex message first to create the conversation thread."
    CodexConversationUiState.Opening -> "Connecting"
    is CodexConversationUiState.Ready -> "Connected"
    is CodexConversationUiState.Running, is CodexConversationUiState.WaitingForApproval -> "Running"
    is CodexConversationUiState.Terminal -> "Connected"
    is CodexConversationUiState.Failed -> "Failed: ${state.message}"
    is CodexConversationUiState.StaleBinding -> "Failed: ${state.reason}"
    is CodexConversationUiState.WorkspaceMismatch -> "Failed: workspace mismatch"
}

@Composable
private fun configRow(label: String, value: String?, config: CodexEffectiveConfigSnapshot, originKey: String) {
    value ?: return
    val origin = config.origins[originKey]?.source?.label
    Text(if (origin == null) "$label: $value" else "$label: $value · $origin", maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private fun Boolean.enabledLabel() = if (this) "Enabled" else "Disabled"

internal fun managedSandboxWarning(
    savedMode: String?,
    requirementsLoaded: Boolean,
    requirements: CodexConfigRequirementsSnapshot?,
): String? {
    if (!requirementsLoaded || savedMode !in setOf("read-only", "workspace-write", "danger-full-access")) return null
    val allowed = requirements?.allowedSandboxModes ?: return null
    if (allowed.any { it.wireValue == savedMode }) return null
    val label = when (savedMode) {
        "read-only" -> "Read only"
        "workspace-write" -> "Workspace write"
        else -> "Full access"
    }
    return "Managed policy currently does not allow $label. The saved preference is unchanged; the App Server remains authoritative."
}

internal fun codexReconnectEligible(
    state: CodexConversationUiState,
    hasBinding: Boolean,
    runtimeConnected: Boolean,
    operationBusy: Boolean,
): Boolean = hasBinding && !runtimeConnected && !operationBusy && when (state) {
    CodexConversationUiState.Disconnected, is CodexConversationUiState.Failed -> true
    else -> false
}
