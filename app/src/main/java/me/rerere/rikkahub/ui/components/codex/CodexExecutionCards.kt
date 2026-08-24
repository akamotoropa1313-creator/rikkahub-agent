package me.rerere.rikkahub.ui.components.codex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandExecutionStatus
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerItemSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerPatchApplyStatus
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerPatchChangeKind

enum class UnifiedDiffLineKind { Header, Hunk, Added, Removed, Context }
data class UnifiedDiffLine(val text: String, val kind: UnifiedDiffLineKind)

/** Presentation-only classification: [text] is never normalized or trimmed. */
fun classifyUnifiedDiffLine(text: String) = UnifiedDiffLine(text, when {
    text.startsWith("@@") -> UnifiedDiffLineKind.Hunk
    text.startsWith("+++") || text.startsWith("---") || text.startsWith("diff ") -> UnifiedDiffLineKind.Header
    text.startsWith("+") -> UnifiedDiffLineKind.Added
    text.startsWith("-") -> UnifiedDiffLineKind.Removed
    else -> UnifiedDiffLineKind.Context
})

@Composable
fun CodexCommandExecutionCard(item: CodexAppServerItemSnapshot.CommandExecution, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(item.command, fontFamily = FontFamily.Monospace)
            Text(item.cwd, style = MaterialTheme.typography.bodySmall)
            val japanese = isJapaneseCodexDisplay()
            val status = localizedCommandStatus(item.status, japanese)
            val exit = item.exitCode?.let { if (japanese) " · 終了コード $it" else " · exit $it" }.orEmpty()
            Text("$status$exit${item.durationMs?.let { " · ${it}ms" } ?: ""}")
            item.aggregatedOutput?.let { CollapsibleText(it, codexUiText("Output"), isDiff = false) }
        }
    }
}

@Composable
fun CodexFileChangeCard(item: CodexAppServerItemSnapshot.FileChange, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(localizedPatchStatus(item.status, isJapaneseCodexDisplay()))
            item.changes.forEach { change ->
                val label = when (val kind = change.kind) {
                    is CodexAppServerPatchChangeKind.Add -> codexUiText("Add")
                    is CodexAppServerPatchChangeKind.Delete -> codexUiText("Delete")
                    is CodexAppServerPatchChangeKind.Update -> codexUiText("Update") + (kind.movePath?.let { " → $it" } ?: "")
                    is CodexAppServerPatchChangeKind.Other -> kind.type
                }
                Text("$label · ${change.path}", style = MaterialTheme.typography.titleSmall)
                CollapsibleText(change.diff, codexUiText("Diff"), isDiff = true)
            }
        }
    }
}

@Composable
fun CodexTurnDiffCard(diff: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(codexUiText("Turn diff"), style = MaterialTheme.typography.titleSmall)
            UnifiedDiff(diff)
        }
    }
}

@Composable
private fun CollapsibleText(text: String, label: String, isDiff: Boolean) {
    var expanded by remember(text) { mutableStateOf(text.length <= 2_000) }
    val japanese = isJapaneseCodexDisplay()
    Text(
        if (japanese) {
            if (expanded) "$label を隠す" else "$label を表示（${text.length}文字）"
        } else {
            if (expanded) "Hide $label" else "Show $label (${text.length} characters)"
        },
        modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.primary,
    )
    if (expanded) {
        if (isDiff) UnifiedDiff(text) else MonospaceOutput(text)
    }
}

@Composable
private fun isJapaneseCodexDisplay(): Boolean = codexUiText("Completed") == "完了"

private fun localizedCommandStatus(status: CodexAppServerCommandExecutionStatus, japanese: Boolean): String = when (status) {
    CodexAppServerCommandExecutionStatus.InProgress -> if (japanese) "実行中" else "In progress"
    CodexAppServerCommandExecutionStatus.Completed -> if (japanese) "完了" else "Completed"
    CodexAppServerCommandExecutionStatus.Failed -> if (japanese) "失敗" else "Failed"
    CodexAppServerCommandExecutionStatus.Declined -> if (japanese) "拒否" else "Declined"
    is CodexAppServerCommandExecutionStatus.Unknown -> status.rawValue
}

private fun localizedPatchStatus(status: CodexAppServerPatchApplyStatus, japanese: Boolean): String = when (status) {
    CodexAppServerPatchApplyStatus.InProgress -> if (japanese) "適用中" else "In progress"
    CodexAppServerPatchApplyStatus.Completed -> if (japanese) "適用完了" else "Completed"
    CodexAppServerPatchApplyStatus.Failed -> if (japanese) "適用失敗" else "Failed"
    CodexAppServerPatchApplyStatus.Declined -> if (japanese) "拒否" else "Declined"
    is CodexAppServerPatchApplyStatus.Unknown -> status.rawValue
}

@Composable
private fun MonospaceOutput(output: String) = LazyTextViewport(output) { MaterialTheme.colorScheme.onSurface }

@Composable
private fun UnifiedDiff(diff: String) = LazyTextViewport(diff) { line ->
    when (classifyUnifiedDiffLine(line).kind) {
        UnifiedDiffLineKind.Added -> MaterialTheme.colorScheme.primary
        UnifiedDiffLineKind.Removed -> MaterialTheme.colorScheme.error
        UnifiedDiffLineKind.Hunk -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
private fun LazyTextViewport(text: String, color: @Composable (String) -> androidx.compose.ui.graphics.Color) {
    val lines = remember(text) { text.split('\n') }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
            Text(
                text = line,
                color = color(line),
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}
