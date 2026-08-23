package me.rerere.rikkahub.ui.components.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
    ApprovalSurface("コマンドの承認", modifier) {
        request.reason?.let { LabeledPlainText("理由", it) }
        request.networkApprovalContext?.let {
            Text("ネットワークアクセスが要求されています", style = MaterialTheme.typography.titleMedium)
            LabeledPlainText("ホスト", it.host)
            LabeledPlainText("プロトコル", it.protocol.toString())
        }
        request.command?.let { LabeledPlainText("コマンド", it, true) }
        request.cwd?.let { LabeledPlainText("作業ディレクトリ", it) }
        request.commandActions?.forEach { LabeledPlainText("操作", commandActionPresentation(it)) }
        request.environmentId?.let { LabeledPlainText("環境", it) }
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
    ApprovalSurface("ファイル変更の承認", modifier) {
        request.reason?.let { LabeledPlainText("理由", it) }
        request.grantRoot?.let { LabeledPlainText("要求された許可ルート", it) }
        fileChange?.let { CodexFileChangeCard(it) }
            ?: Text("変更内容をプレビューできないため、安全のため承認を無効にしています。", color = MaterialTheme.colorScheme.error)
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
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) { Button(approve, enabled=approveEnabled) { Text("今回のみ承認") }; OutlinedButton(session, enabled=approveEnabled) { Text("このセッションで承認") } }
    Text("「拒否」はこの操作だけを拒否してターンを続行します。「ターンをキャンセル」は操作を拒否してターンも停止します。", style=MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton(decline, enabled=rejectEnabled) { Text("拒否") }; OutlinedButton(cancel, enabled=rejectEnabled) { Text("ターンをキャンセル") } }
}

internal data class ApprovalActionAvailability(val approveEnabled: Boolean, val rejectEnabled: Boolean)
internal fun approvalActionAvailability(enabled: Boolean, submitting: Boolean, resolved: Boolean, hasPreview: Boolean): ApprovalActionAvailability {
    val interactive = enabled && !submitting && !resolved
    return ApprovalActionAvailability(interactive && hasPreview, interactive)
}

internal fun commandActionPresentation(action: CodexAppServerCommandAction): String = when (action) {
    is CodexAppServerCommandAction.Read -> "読み取り: ${action.path}"
    is CodexAppServerCommandAction.ListFiles -> action.path?.let { "ファイル一覧: $it" } ?: "ファイル一覧"
    is CodexAppServerCommandAction.Search -> when {
        action.query != null && action.path != null -> "検索: ${action.query}（${action.path}）"
        action.query != null -> "検索: ${action.query}"
        action.path != null -> "検索先: ${action.path}"
        else -> "検索"
    }
    is CodexAppServerCommandAction.UnknownCommand -> "コマンド: ${action.command}"
    is CodexAppServerCommandAction.Other -> "不明なコマンド操作"
}
