package me.rerere.rikkahub.ui.components.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandApprovalDecision
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandApprovalRequest
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandAction
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerFileChangeApprovalDecision
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerFileChangeApprovalRequest
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerItemSnapshot

/** Stateless presentation. The owner supplies submitting/resolved state and performs the response. */
@Composable
fun CodexCommandApprovalCard(
    request: CodexAppServerCommandApprovalRequest,
    onDecision: (CodexAppServerCommandApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    submitting: Boolean = false,
    resolved: Boolean = false,
) {
    ApprovalSurface(codexUiText("Command approval"), modifier) {
        request.reason?.let { LabeledPlainText(codexUiText("Reason"), it) }
        request.networkApprovalContext?.let {
            Text(codexUiText("Network access requested"), style = MaterialTheme.typography.titleMedium)
            LabeledPlainText(codexUiText("Host"), it.host)
            LabeledPlainText(codexUiText("Protocol"), it.protocol.toString())
        }
        request.command?.let { LabeledPlainText(codexUiText("Command"), it, true) }
        request.cwd?.let { LabeledPlainText(codexUiText("Working directory"), it) }
        request.commandActions?.forEach { LabeledPlainText(codexUiText("Action"), codexUiText(commandActionPresentation(it))) }
        request.environmentId?.let { LabeledPlainText(codexUiText("Environment"), it) }
        ApprovalActions(enabled && !submitting && !resolved,
            approve = { onDecision(CodexAppServerCommandApprovalDecision.Accept) },
            session = { onDecision(CodexAppServerCommandApprovalDecision.AcceptForSession) },
            decline = { onDecision(CodexAppServerCommandApprovalDecision.Decline) },
            cancel = { onDecision(CodexAppServerCommandApprovalDecision.Cancel) })
    }
}

@Composable
fun CodexFileChangeApprovalCard(
    request: CodexAppServerFileChangeApprovalRequest,
    onDecision: (CodexAppServerFileChangeApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
    fileChange: CodexAppServerItemSnapshot.FileChange? = null,
    enabled: Boolean = true,
    submitting: Boolean = false,
    resolved: Boolean = false,
) {
    ApprovalSurface(codexUiText("File-change approval"), modifier) {
        request.reason?.let { LabeledPlainText(codexUiText("Reason"), it) }
        request.grantRoot?.let { LabeledPlainText(codexUiText("Requested grant root"), it) }
        fileChange?.let { CodexFileChangeCard(it) }
            ?: Text(codexUiText("Change preview unavailable. Approval is disabled for your safety."), color = MaterialTheme.colorScheme.error)
        val availability = approvalActionAvailability(enabled, submitting, resolved, fileChange != null)
        ApprovalActions(availability.approveEnabled, availability.rejectEnabled,
            approve = { onDecision(CodexAppServerFileChangeApprovalDecision.Accept) },
            session = { onDecision(CodexAppServerFileChangeApprovalDecision.AcceptForSession) },
            decline = { onDecision(CodexAppServerFileChangeApprovalDecision.Decline) },
            cancel = { onDecision(CodexAppServerFileChangeApprovalDecision.Cancel) })
    }
}

@Composable private fun ApprovalSurface(title:String, modifier:Modifier, content:@Composable ()->Unit) = Card(modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(title, style=MaterialTheme.typography.titleLarge); content() } }
@Composable private fun LabeledPlainText(label:String, value:String, monospace:Boolean=false) { Text(label, style=MaterialTheme.typography.labelMedium); Text(value, fontFamily=if(monospace) FontFamily.Monospace else FontFamily.Default) }
@Composable private fun ApprovalActions(approveEnabled:Boolean, rejectEnabled:Boolean = approveEnabled, approve:()->Unit, session:()->Unit, decline:()->Unit, cancel:()->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(approve, enabled=approveEnabled) { Text(codexUiText("Approve once")) }; OutlinedButton(session, enabled=approveEnabled) { Text(codexUiText("Approve for session")) } }
    Text(codexUiText("Decline rejects this action and lets the turn continue. Cancel turn rejects it and stops the turn."), style=MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton(decline, enabled=rejectEnabled) { Text(codexUiText("Decline")) }; OutlinedButton(cancel, enabled=rejectEnabled) { Text(codexUiText("Cancel turn")) } }
}

internal data class ApprovalActionAvailability(val approveEnabled: Boolean, val rejectEnabled: Boolean)
internal fun approvalActionAvailability(enabled: Boolean, submitting: Boolean, resolved: Boolean, hasPreview: Boolean): ApprovalActionAvailability {
    val interactive = enabled && !submitting && !resolved
    return ApprovalActionAvailability(interactive && hasPreview, interactive)
}

internal fun commandActionPresentation(action: CodexAppServerCommandAction): String = when (action) {
    is CodexAppServerCommandAction.Read -> "Read: ${action.path}"
    is CodexAppServerCommandAction.ListFiles -> action.path?.let { "List files: $it" } ?: "List files"
    is CodexAppServerCommandAction.Search -> when {
        action.query != null && action.path != null -> "Search: ${action.query} in ${action.path}"
        action.query != null -> "Search: ${action.query}"
        action.path != null -> "Search in: ${action.path}"
        else -> "Search"
    }
    is CodexAppServerCommandAction.UnknownCommand -> "Command: ${action.command}"
    is CodexAppServerCommandAction.Other -> "Unknown command action"
}
