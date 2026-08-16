package me.rerere.rikkahub.ui.components.codex

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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

/** Explicit control plane for the existing conversation-owned Codex runtime. Opening it sends no RPC. */
@Composable
fun CodexControlSheet(
    connection: CodexConversationUiState,
    capabilities: CodexCapabilitiesUiState,
    assistant: Assistant,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    hasBinding: Boolean,
    onRefreshAccount: () -> Unit,
    onRefreshModels: () -> Unit,
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
    val selectedModel = selectedCodexModel(assistant.codexModel, capabilities.models)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Codex Control Center", style = MaterialTheme.typography.headlineSmall) }
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
                Text("Service tier", style = MaterialTheme.typography.titleMedium)
                Text("Server setting omits the override. On an existing thread, the server-side tier may remain sticky. Default explicitly requests the default tier on the next turn.")
                TextButton(onClick = { onUpdateAssistant { it.copy(codexServiceTier = null) } }) {
                    Text((if (assistant.codexServiceTier == null) "✓ " else "") + "Server setting")
                }
                TextButton(onClick = { onUpdateAssistant { it.copy(codexServiceTier = "default") } }) {
                    Text((if (assistant.codexServiceTier == "default") "✓ " else "") + "Default")
                }
                codexServiceTierOptions(selected).forEach { tier ->
                    TextButton(onClick = { onUpdateAssistant { it.copy(codexServiceTier = tier.id) } }) {
                        Text((if (assistant.codexServiceTier == tier.id) "✓ " else "") + tier.name + " · " + tier.description)
                    }
                }
                selected.defaultServiceTier?.let { default ->
                    val label = codexServiceTierOptions(selected).firstOrNull { it.id == default }?.name ?: default
                    Text("Catalog default: $label")
                }
                val savedTier = assistant.codexServiceTier
                if (savedTier != null && savedTier != "default" && codexServiceTierOptions(selected).none { it.id == savedTier }) {
                    Text("Tier support not confirmed in the current model catalog", color = MaterialTheme.colorScheme.error)
                }
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

internal fun codexReconnectEligible(
    state: CodexConversationUiState,
    hasBinding: Boolean,
    runtimeConnected: Boolean,
    operationBusy: Boolean,
): Boolean = hasBinding && !runtimeConnected && !operationBusy && when (state) {
    CodexConversationUiState.Disconnected, is CodexConversationUiState.Failed -> true
    else -> false
}
