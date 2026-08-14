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
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerItemSnapshot
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
            Text("${item.status}${item.exitCode?.let { " · exit $it" } ?: ""}${item.durationMs?.let { " · ${it}ms" } ?: ""}")
            item.aggregatedOutput?.let { CollapsibleText(it, "Output", isDiff = false) }
        }
    }
}

@Composable
fun CodexFileChangeCard(item: CodexAppServerItemSnapshot.FileChange, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(item.status.toString())
            item.changes.forEach { change ->
                val label = when (val kind = change.kind) {
                    is CodexAppServerPatchChangeKind.Add -> "Add"
                    is CodexAppServerPatchChangeKind.Delete -> "Delete"
                    is CodexAppServerPatchChangeKind.Update -> "Update${kind.movePath?.let { " → $it" } ?: ""}"
                    is CodexAppServerPatchChangeKind.Other -> kind.type
                }
                Text("$label · ${change.path}", style = MaterialTheme.typography.titleSmall)
                CollapsibleText(change.diff, "Diff", isDiff = true)
            }
        }
    }
}

@Composable
fun CodexTurnDiffCard(diff: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Turn diff", style = MaterialTheme.typography.titleSmall)
            UnifiedDiff(diff)
        }
    }
}

@Composable
private fun CollapsibleText(text: String, label: String, isDiff: Boolean) {
    var expanded by remember(text) { mutableStateOf(text.length <= 2_000) }
    Text(
        if (expanded) "Hide $label" else "Show $label (${text.length} characters)",
        modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.primary,
    )
    if (expanded) {
        if (isDiff) UnifiedDiff(text) else MonospaceOutput(text)
    }
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
