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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import me.rerere.rikkahub.service.CodexCapabilitiesUiState
import me.rerere.rikkahub.service.CodexConversationUiState
import me.rerere.rikkahub.service.threadHistoryRefreshSearchTerm
import me.rerere.rikkahub.service.threadHistorySubmittedSearchTerm
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import me.rerere.rikkahub.data.codex.appserver.CodexMcpAuthStatus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.CodexPersonalityPreference
import me.rerere.rikkahub.data.model.CodexReasoningSummaryPreference
import me.rerere.rikkahub.data.codex.appserver.CodexTokenUsageTelemetry
import me.rerere.rikkahub.data.codex.appserver.CodexEffectiveConfigSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexConfigRequirementsSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerReviewTarget
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerItemSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerUserInput
import me.rerere.rikkahub.service.CodexReviewUiState
import me.rerere.rikkahub.service.CodexReviewAction

/** Explicit control plane for the existing conversation-owned Codex runtime. Opening it sends no RPC. */
@Composable
fun CodexControlSheet(
    connection: CodexConversationUiState,
    capabilities: CodexCapabilitiesUiState,
    review: CodexReviewUiState,
    onStartReview: (CodexReviewAction) -> Unit,
    assistant: Assistant,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    hasBinding: Boolean,
    onRefreshAccount: () -> Unit,
    onRefreshModels: () -> Unit,
    onLoadThreadHistory: (String?, Boolean) -> Unit,
    onReadHistoryThread: (String) -> Unit,
    onCloseHistoryThread: () -> Unit,
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
    var historySearch by remember { mutableStateOf(capabilities.threadHistory.searchTerm) }
    val selectedModel = selectedCodexModel(assistant.codexModel, capabilities.models)
    val serviceTierModel = serviceTierCatalogModel(assistant.codexModel, capabilities.models)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(codexUiText("Codex Control Center"), style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("Thread history") {
                val history = capabilities.threadHistory
                if (history.selectedThreadId != null) {
                    TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = onCloseHistoryThread) { Text(codexUiText("Back to history")) }
                    if (history.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    history.detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    history.selectedThread?.let { thread ->
                        Text(codexUiText(thread.name ?: thread.preview ?: "Untitled thread"), style = MaterialTheme.typography.titleMedium)
                        Text(codexUiText("Thread ID: ${thread.id}"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        thread.modelProvider?.let { Text(codexUiText("Model provider: $it")) }
                        thread.status?.let { Text(codexUiText("Status: ${it.wireValue}")) }
                        thread.recencyAt?.let { Text(codexUiText("Recency: $it")) }
                        thread.updatedAt?.let { Text(codexUiText("Updated: $it")) }
                        thread.cwd?.let { Text("CWD: ${it.substringAfterLast('/').ifBlank { "/" }}", maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        thread.turns.forEach { historyTurn ->
                            HorizontalDivider()
                            Text(codexUiText("Turn ${historyTurn.turn.id}"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${historyTurn.turn.status.wireValue}${historyTurn.turn.durationMs?.let { " · ${it}ms" }.orEmpty()}")
                            historyTurn.turn.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            historyTurn.items.forEach { item -> Text(codexUiText(historyItemText(item)), maxLines = 5, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                } else {
                    Text(codexUiText("Browse persisted App Server threads without switching this conversation."))
                    if (history.loaded) {
                        OutlinedTextField(
                            value = historySearch,
                            onValueChange = { historySearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(codexUiText("Search")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { onLoadThreadHistory(threadHistorySubmittedSearchTerm(historySearch), false) },
                            ),
                        )
                        if (history.searchTerm.isNotEmpty()) {
                            Text(
                                codexUiText("Active filter: ${history.searchTerm}"),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val historyControlsEnabled = capabilities.connected && !history.loading && !operationBusy
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            enabled = historyControlsEnabled,
                            onClick = {
                                onLoadThreadHistory(
                                    if (history.loaded) threadHistoryRefreshSearchTerm(history) else null,
                                    false,
                                )
                            },
                        ) { Text(codexUiText(if (history.loaded) "Refresh current results" else "Load history")) }
                        if (history.loaded) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                enabled = historyControlsEnabled,
                                onClick = { onLoadThreadHistory(threadHistorySubmittedSearchTerm(historySearch), false) },
                            ) { Text(codexUiText(if (historySearch.isBlank()) "Clear search" else "Search")) }
                        }
                    }
                    if (history.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    history.error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    history.threads.forEach { thread ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            headlineContent = { Text(codexUiText(thread.name ?: thread.preview ?: "Untitled thread"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(listOfNotNull(thread.preview, thread.recencyAt?.toString(), thread.status?.wireValue, thread.modelProvider).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { if (thread.id == connection.threadId) Text(codexUiText("Current"), color = MaterialTheme.colorScheme.primary) },
                        )
                        TextButton(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            enabled = capabilities.connected && !history.detailLoading && !operationBusy,
                            onClick = { onReadHistoryThread(thread.id) },
                        ) { Text(codexUiText("View details")) }
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        enabled = capabilities.connected && history.nextCursor != null && !history.loading && !operationBusy,
                        onClick = { onLoadThreadHistory(threadHistoryRefreshSearchTerm(history), true) },
                    ) { Text(codexUiText("Load more")) }
                }
            }
        }
        item {
            Section("Usage & status") {
                val telemetry = connection.telemetryOrNull()
                val usage = telemetry?.latest?.tokenUsage
                if (usage == null) Text(codexUiText("No token usage reported yet")) else {
                    Text(codexUiText("Current context"), style = MaterialTheme.typography.titleMedium)
                    Text(currentContextText(usage))
                    if (usage.modelContextWindow == null) Text(codexUiText("Context window: Not reported"))
                    Text(codexUiText("Session total: ${formatTokenCount(usage.total.totalTokens)} tokens"))
                    Text(codexUiText("Latest usage breakdown"), style = MaterialTheme.typography.titleMedium)
                    Text("Input: ${formatTokenCount(usage.last.inputTokens)}")
                    Text("Cached input: ${formatTokenCount(usage.last.cachedInputTokens)}")
                    usage.last.cacheWriteInputTokens?.let { Text("Cache write input: ${formatTokenCount(it)}") }
                    Text("Output: ${formatTokenCount(usage.last.outputTokens)}")
                    Text("Reasoning output: ${formatTokenCount(usage.last.reasoningOutputTokens)}")
                }
                telemetry?.warning?.let { Text(codexUiText("Usage warning: $it"), color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
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
                Text(codexUiText("These are App Server base and managed settings. RikkaHub thread and turn overrides may differ."))
                Button(
                    onClick = onRefreshConfigDiagnostics,
                    enabled = capabilities.connected && !capabilities.configLoading && !capabilities.requirementsLoading && !operationBusy,
                ) { Text(codexUiText(if (capabilities.effectiveConfig == null && !capabilities.requirementsLoaded) "Load configuration" else "Refresh diagnostics")) }
                if (capabilities.configLoading || capabilities.requirementsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                capabilities.configError?.let { Text(codexUiText("Configuration: $it"), color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                capabilities.requirementsError?.let { Text(codexUiText("Managed requirements: $it"), color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                capabilities.effectiveConfig?.let { config ->
                    Text(if (config.threadAgnostic) "Base App Server configuration · Thread-agnostic configuration" else "Base App Server configuration", style = MaterialTheme.typography.titleMedium)
                    configRow(codexUiText("Model"), config.model, config, "model")
                    configRow(codexUiText("Model provider"), config.modelProvider, config, "model_provider")
                    configRow(codexUiText("Model context window"), config.modelContextWindow?.toString(), config, "model_context_window")
                    configRow(codexUiText("Auto compact limit"), config.modelAutoCompactTokenLimit?.toString(), config, "model_auto_compact_token_limit")
                    configRow(codexUiText("Sandbox default"), config.sandboxMode?.let { if (it.known) it.wireValue else "${it.wireValue} (unknown)" }, config, "sandbox_mode")
                    configRow(codexUiText("Workspace-write network access"), config.sandboxWorkspaceWrite?.networkAccess?.enabledLabel(), config, "sandbox_workspace_write.network_access")
                    config.sandboxWorkspaceWrite?.writableRootsCount?.let { Text(codexUiText("Writable roots: $it")) }
                    configRow(codexUiText("Web search"), config.webSearch, config, "web_search")
                    configRow(codexUiText("Reasoning effort"), config.modelReasoningEffort, config, "model_reasoning_effort")
                    configRow(codexUiText("Reasoning summary"), config.modelReasoningSummary, config, "model_reasoning_summary")
                    configRow(codexUiText("Verbosity"), config.modelVerbosity, config, "model_verbosity")
                    configRow(codexUiText("Service tier"), config.serviceTier, config, "service_tier")
                    configRow(codexUiText("Analytics"), config.analyticsEnabled?.enabledLabel(), config, "analytics.enabled")
                }
                if (capabilities.requirementsLoaded) {
                    Text(codexUiText("Managed requirements"), style = MaterialTheme.typography.titleMedium)
                    capabilities.requirements?.let { requirements ->
                        requirements.allowedSandboxModes?.let { Text("Allowed sandbox modes: " + it.joinToString(" · ") { mode -> mode.wireValue }) }
                        requirements.allowedWebSearchModes?.let { Text("Allowed web search: " + it.joinToString(" · ")) }
                        requirements.newThread?.model?.let { Text("Managed new-thread model: $it") }
                        requirements.newThread?.modelReasoningEffort?.let { Text("Managed new-thread effort: $it") }
                        requirements.newThread?.serviceTier?.let { Text("Managed new-thread service tier: $it") }
                        requirements.featureRequirements?.forEach { (feature, required) -> Text("$feature = required ${if (required) "enabled" else "disabled"}") }
                    } ?: Text(codexUiText("No managed requirements reported"))
                }
            }
        }
        item {
            Section("Code review") {
                Text(codexUiText("Run a native inline review on this conversation's bound thread."))
                listOf("Working tree", "Base branch", "Commit", "Custom").forEach { kind ->
                    TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = { reviewKind = kind }) {
                        Text((if (reviewKind == kind) "✓ " else "") + codexUiText(kind))
                    }
                }
                when (reviewKind) {
                    "Base branch" -> OutlinedTextField(branch, { branch = it }, Modifier.fillMaxWidth(), label = { Text(codexUiText("Branch")) }, singleLine = true)
                    "Commit" -> { OutlinedTextField(sha, { sha = it }, Modifier.fillMaxWidth(), label = { Text(codexUiText("Commit SHA")) }, singleLine = true); OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(codexUiText("Title (optional)")) }) }
                    "Custom" -> OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text(codexUiText("Review instructions")) }, minLines = 4)
                }
                if (review.inProgress) {
                    Text(codexUiText("Review in progress · ${review.targetSummary.orEmpty()}"))
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        enabled = capabilities.connected,
                        onClick = { onStartReview(CodexReviewAction.Stop) },
                    ) { Text(codexUiText("Stop review")) }
                }
                review.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val valid = when (reviewKind) { "Base branch" -> branch.isNotBlank(); "Commit" -> sha.isNotBlank(); "Custom" -> instructions.isNotBlank(); else -> true }
                Button(modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), enabled = capabilities.connected && valid && !review.inProgress && !operationBusy, onClick = {
                    onStartReview(CodexReviewAction.Start(when (reviewKind) {
                        "Base branch" -> CodexAppServerReviewTarget.BaseBranch(branch)
                        "Commit" -> CodexAppServerReviewTarget.Commit(sha, title.takeIf(String::isNotBlank))
                        "Custom" -> CodexAppServerReviewTarget.Custom(instructions)
                        else -> CodexAppServerReviewTarget.UncommittedChanges
                    }))
                }) { Text(codexUiText("Start review")) }
            }
        }
        item {
            Section("Safety & permissions") {
                Text(codexUiText("Sandbox"), style = MaterialTheme.typography.titleMedium)
                Text("Server setting omits the override. On an existing thread a previous override may be sticky; Reset is required to return completely to server configuration.")
                listOf(null to "Server setting", "read-only" to "Read only", "workspace-write" to "Workspace write", "danger-full-access" to "Full access").forEach { (value, label) ->
                    TextButton(onClick = {
                        val confirmation = codexSafetyConfirmation(assistant, sandbox = value)
                        if (confirmation == CodexSafetyConfirmation.NONE) onUpdateAssistant { it.copy(codexSandboxMode = value) }
                        else pendingSafety = value to assistant.codexApprovalPolicy
                    }) { Text((if (assistant.codexSandboxMode == value) "✓ " else "") + codexUiText(label)) }
                }
                Text(codexUiText(when (assistant.codexSandboxMode) {
                    "read-only" -> "Codex can read project files but writes are restricted."
                    "workspace-write" -> "Codex can modify files allowed by the workspace sandbox."
                    "danger-full-access" -> "Removes Codex sandbox restrictions for the environment available to the App Server."
                    else -> "The App Server setting is used when no explicit override is selected."
                }))
                if (!codexSandboxKnown(assistant.codexSandboxMode)) Text("Unsupported saved sandbox preference '${assistant.codexSandboxMode}' is preserved and will not be sent.", color = MaterialTheme.colorScheme.error)
                managedSandboxWarning(assistant.codexSandboxMode, capabilities.requirementsLoaded, capabilities.requirements)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Text(codexUiText("Approval"), style = MaterialTheme.typography.titleMedium)
                listOf(null to "Server setting", "untrusted" to "Untrusted", "on-request" to "On request", "never" to "Never").forEach { (value, label) ->
                    TextButton(onClick = {
                        val confirmation = codexSafetyConfirmation(assistant, approval = value)
                        if (confirmation == CodexSafetyConfirmation.NONE) onUpdateAssistant { it.copy(codexApprovalPolicy = value) }
                        else pendingSafety = assistant.codexSandboxMode to value
                    }) { Text((if (assistant.codexApprovalPolicy == value) "✓ " else "") + codexUiText(label)) }
                }
                Text(codexUiText(when (assistant.codexApprovalPolicy) {
                    "untrusted" -> "Only known-safe read-only commands are automatically approved; other operations may request approval."
                    "on-request" -> "Codex decides when it needs to ask for approval."
                    "never" -> "Codex does not ask for approval; blocked operations fail instead. This does not itself mean Full access."
                    else -> "Server approval policy is used when no explicit override is selected."
                }))
                if (!codexApprovalKnown(assistant.codexApprovalPolicy)) Text("Unsupported saved approval preference '${assistant.codexApprovalPolicy}' is preserved and will not be sent.", color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Section("Model & behavior") {
                Text(codexUiText("Codex model"), style = MaterialTheme.typography.titleMedium)
                Text(codexUiText("The App Server catalog is authoritative. Changes apply from the next Codex turn."))
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
                        Text(codexUiText(if (assistant.codexModel == model.model) "Selected" else "Select"))
                    }
                },
            )
        }
        item {
            Text(codexUiText("Service tier"), style = MaterialTheme.typography.titleMedium)
            Text("Server setting omits the override. On an existing thread, the server-side tier may remain sticky. Default explicitly requests the default tier on the next turn.")
            TextButton(onClick = { onUpdateAssistant { it.copy(codexServiceTier = null) } }) {
                Text((if (assistant.codexServiceTier == null) "✓ " else "") + codexUiText("Server setting"))
            }
            TextButton(onClick = { onUpdateAssistant { it.copy(codexServiceTier = "default") } }) {
                Text((if (assistant.codexServiceTier == "default") "✓ " else "") + codexUiText("Default"))
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
                Text(codexUiText("Reasoning summary"))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    CodexReasoningSummaryPreference.entries.forEach { value ->
                        TextButton(onClick = { onUpdateAssistant { it.copy(codexReasoningSummary = value) } }) {
                            Text((if (assistant.codexReasoningSummary == value) "✓ " else "") + value.name.lowercase())
                        }
                    }
                }
                Text(codexUiText("Personality"))
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
                Text(codexUiText(connectionLabel(connection, hasBinding)))
                if (codexReconnectEligible(connection, hasBinding, capabilities.connected, operationBusy)) {
                    Button(onClick = onReconnect) { Text(codexUiText("Reconnect Codex")) }
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
                Text(codexUiText("${capabilities.skillGroups.sumOf { it.skills.size }} skills"))
                Button(
                    onClick = onRefreshSkills,
                    enabled = capabilities.connected && !capabilities.skillsLoading && !operationBusy,
                ) { Text(codexUiText("Refresh")) }
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
                            ) { Text(codexUiText(if (skill.enabled) "Disable" else "Enable")) }
                            TextButton(onClick = { onUseSkill(skill) }, enabled = skill.enabled && !operationBusy) { Text(codexUiText("Use")) }
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
                    ) { Text(codexUiText("Refresh")) }
                    OutlinedButton(
                        onClick = onReloadMcp,
                        enabled = capabilities.connected && !capabilities.mcpLoading && !operationBusy,
                    ) { Text(codexUiText("Reload MCP")) }
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
                        ) { Text(codexUiText("Sign in")) }
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
            confirmButton = { TextButton(onClick = { onUpdateAssistant { it.copy(codexSandboxMode = pending.first, codexApprovalPolicy = pending.second) }; pendingSafety = null }) { Text(codexUiText("Confirm")) } },
            dismissButton = { TextButton(onClick = { pendingSafety = null }) { Text(codexUiText("Cancel")) } },
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

private val CodexConversationUiState.threadId: String? get() = when (this) {
    is CodexConversationUiState.Ready -> threadId
    is CodexConversationUiState.Running -> threadId
    is CodexConversationUiState.Terminal -> threadId
    else -> null
}

private fun historyItemText(item: CodexAppServerItemSnapshot): String = when (item) {
    is CodexAppServerItemSnapshot.UserMessage -> {
        val content = item.content.joinToString("\n", transform = ::historyUserInputText).ifBlank { "[empty message]" }
        "User: $content"
    }
    is CodexAppServerItemSnapshot.AgentMessage -> "Agent: ${item.text}"
    is CodexAppServerItemSnapshot.Reasoning -> "Reasoning: ${(item.summary + item.content).joinToString("\n")}"
    is CodexAppServerItemSnapshot.CommandExecution -> "Command: ${item.command}${item.aggregatedOutput?.let { "\n$it" }.orEmpty()}"
    is CodexAppServerItemSnapshot.FileChange -> "File changes: ${item.changes.size}"
    is CodexAppServerItemSnapshot.EnteredReviewMode -> "Entered review mode: ${item.review}"
    is CodexAppServerItemSnapshot.ExitedReviewMode -> "Exited review mode: ${item.review}"
    is CodexAppServerItemSnapshot.Other -> "${item.type} item"
}

private fun historyUserInputText(input: CodexAppServerUserInput): String = when (input) {
    is CodexAppServerUserInput.Text -> input.text
    is CodexAppServerUserInput.Image -> "[Image]"
    is CodexAppServerUserInput.LocalImage -> "[Local image: ${input.path.substringAfterLast('/').ifBlank { "image" }}]"
    is CodexAppServerUserInput.Audio -> "[Audio]"
    is CodexAppServerUserInput.LocalAudio -> "[Local audio: ${input.path.substringAfterLast('/').ifBlank { "audio" }}]"
    is CodexAppServerUserInput.Skill -> "[Skill: ${input.name}]"
    is CodexAppServerUserInput.Mention -> "@${input.name}"
    is CodexAppServerUserInput.Other -> "[${input.type}]"
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
