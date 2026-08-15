package me.rerere.rikkahub.ui.components.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    val actionsEnabled = enabled && !submitting
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Codex account")
            when {
                loginPending -> Text("Waiting for ChatGPT sign-in")
                snapshot == null -> Text("Account status unavailable")
                snapshot.account is CodexAppServerAccount.ChatGpt -> {
                    Text("Signed in with ChatGPT")
                    snapshot.account.email?.takeIf(String::isNotBlank)?.let { Text(it) }
                    Text(snapshot.account.planType.displayName)
                }
                snapshot.account != null -> Text("Authenticated account")
                snapshot.requiresOpenaiAuth -> Text("ChatGPT sign-in required")
                else -> Text("No OpenAI sign-in required")
            }
            statusMessage?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loginPending) OutlinedButton(onCancelSignIn, enabled = actionsEnabled) { Text("Cancel") }
                else if (snapshot?.account == null && snapshot?.requiresOpenaiAuth == true) {
                    Button(onSignIn, enabled = actionsEnabled) { Text("Sign in with ChatGPT") }
                }
                OutlinedButton(onRefresh, enabled = actionsEnabled) { Text("Refresh") }
                if (snapshot?.account != null) OutlinedButton(onLogout, enabled = actionsEnabled) { Text("Log out") }
            }
        }
    }
}
