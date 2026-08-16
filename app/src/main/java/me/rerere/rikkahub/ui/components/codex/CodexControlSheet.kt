package me.rerere.rikkahub.ui.components.codex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.service.CodexCapabilitiesUiState
import me.rerere.rikkahub.service.CodexConversationUiState
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import me.rerere.rikkahub.data.codex.appserver.CodexMcpAuthStatus

/** Explicit control plane for the existing conversation-owned Codex runtime. Opening it sends no RPC. */
@Composable
fun CodexControlSheet(
    connection: CodexConversationUiState,
    capabilities: CodexCapabilitiesUiState,
    hasBinding: Boolean,
    onRefreshAccount: () -> Unit,
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
    LazyColumn(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Codex Control Center", style = MaterialTheme.typography.headlineSmall) }
        item { Section("Connection") {
            Text(connectionLabel(connection, hasBinding))
            if (connection is CodexConversationUiState.Disconnected && hasBinding) Button(onClick = onReconnect, enabled = !operationBusy) { Text("Reconnect Codex") }
        } }
        item {
            Section("Account") {
                CodexAccountCard(
                    snapshot = capabilities.account,
                    loginPending = capabilities.pendingLoginId != null,
                    onSignIn = onSignIn, onCancelSignIn = onCancelSignIn,
                    onRefresh = onRefreshAccount, onLogout = onLogout,
                    enabled = capabilities.connected && !capabilities.accountLoading && !operationBusy,
                    submitting = capabilities.accountSubmitting,
                    statusMessage = capabilities.accountError ?: capabilities.accountStatus,
                )
            }
        }
        item {
            Section("Codex Skills") {
                Text("${capabilities.skillGroups.sumOf { it.skills.size }} skills")
                Button(onClick = onRefreshSkills, enabled = capabilities.connected && !capabilities.skillsLoading && !operationBusy) { Text("Refresh") }
                capabilities.skillsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        capabilities.skillGroups.forEach { group ->
            item { Text(group.cwd, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            items(group.skills, key = { it.path }) { skill ->
                ListItem(
                    headlineContent = { Text(skill.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(skill.shortDescription ?: skill.description, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { onSetSkillEnabled(skill, !skill.enabled) }, enabled = capabilities.skillUpdatingPath == null && !operationBusy) { Text(if (skill.enabled) "Disable" else "Enable") }
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
                    Button(onClick = onRefreshMcp, enabled = capabilities.connected && !capabilities.mcpLoading && !operationBusy) { Text("Refresh") }
                    OutlinedButton(onClick = onReloadMcp, enabled = capabilities.connected && !capabilities.mcpLoading && !operationBusy) { Text("Reload MCP") }
                }
                capabilities.mcpError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        items(capabilities.mcpServers, key = { it.name }) { server ->
            ListItem(
                headlineContent = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${server.authStatus.wireValue} · ${server.tools.size} tools · ${server.resources.size} resources") },
                trailingContent = { if (server.authStatus is CodexMcpAuthStatus.NotLoggedIn) TextButton(onClick = { onMcpSignIn(server.name) }, enabled = capabilities.pendingMcpServer == null && !operationBusy) { Text("Sign in") } },
            )
        }
    }
}

@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() }

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
