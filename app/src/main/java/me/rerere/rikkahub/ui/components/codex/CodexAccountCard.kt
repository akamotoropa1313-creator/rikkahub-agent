package me.rerere.rikkahub.ui.components.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerAccount
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerAccountSnapshot

internal data class CodexAccountPresentation(
    val lines: List<String>, val showSignIn: Boolean, val showCancel: Boolean,
    val showLogout: Boolean, val actionsEnabled: Boolean,
)

internal fun codexAccountPresentation(
    snapshot: CodexAppServerAccountSnapshot?, loginPending: Boolean,
    enabled: Boolean, submitting: Boolean,
): CodexAccountPresentation {
    val lines = when {
        loginPending -> listOf("ChatGPTへのサインインを待っています")
        snapshot == null -> listOf("アカウント状態を取得できません")
        snapshot.account is CodexAppServerAccount.ChatGpt -> buildList {
            add("ChatGPTでサインイン済み")
            snapshot.account.email?.takeIf(String::isNotBlank)?.let(::add)
            add(snapshot.account.planType.displayName)
        }
        snapshot.account != null -> listOf("認証済みアカウント")
        snapshot.requiresOpenaiAuth -> listOf("ChatGPTへのサインインが必要です")
        else -> listOf("OpenAIへのサインインは不要です")
    }
    return CodexAccountPresentation(
        lines, showSignIn = !loginPending && snapshot?.account == null && snapshot?.requiresOpenaiAuth == true,
        showCancel = loginPending, showLogout = snapshot?.account != null, actionsEnabled = enabled && !submitting,
    )
}

@Composable
fun CodexAccountCard(
    snapshot: CodexAppServerAccountSnapshot?,
    loginPending: Boolean,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    submitting: Boolean = false,
    statusMessage: String? = null,
) {
    val presentation = codexAccountPresentation(snapshot, loginPending, enabled, submitting)
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Codexアカウント")
            presentation.lines.forEach { Text(it) }
            statusMessage?.let { Text(it) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (presentation.showCancel) OutlinedButton(onCancelSignIn, enabled = presentation.actionsEnabled) { Text("キャンセル") }
                else if (presentation.showSignIn) {
                    Button(onSignIn, enabled = presentation.actionsEnabled) { Text("ChatGPTでサインイン") }
                }
                OutlinedButton(onRefresh, enabled = presentation.actionsEnabled) { Text("更新") }
                if (presentation.showLogout) OutlinedButton(onLogout, enabled = presentation.actionsEnabled) { Text("ログアウト") }
            }
        }
    }
}
