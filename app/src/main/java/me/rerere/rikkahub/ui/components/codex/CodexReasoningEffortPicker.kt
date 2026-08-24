package me.rerere.rikkahub.ui.components.codex

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerModel
import me.rerere.rikkahub.data.codex.appserver.CodexReasoningEffortOption
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessModelTarget
import me.rerere.rikkahub.data.codex.appserver.effectiveCodexHarnessModelTarget
import me.rerere.rikkahub.data.model.Assistant

/** Compact, conversation-visible picker for the exact effort strings advertised by App Server. */
@Composable
fun CodexReasoningEffortButton(
    assistant: Assistant,
    models: List<CodexAppServerModel>,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    onRefreshModels: () -> Unit,
    connected: Boolean,
    modelsLoading: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val target = assistant.effectiveCodexHarnessModelTarget()
    if (target is CodexHarnessModelTarget.RikkaHubProvider) return

    val catalogModel = reasoningEffortCatalogModel(target, models)
    val selectedEffort = assistant.codexReasoningEffort
    var visible by remember { mutableStateOf(false) }

    IconButton(
        onClick = { visible = true },
        modifier = modifier.semantics {
            stateDescription = "推論強度: ${codexReasoningEffortLabel(selectedEffort)}"
        },
        enabled = enabled,
    ) {
        Icon(
            imageVector = HugeIcons.Idea01,
            contentDescription = "推論強度",
            modifier = Modifier.size(24.dp),
            tint = if (selectedEffort == null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }

    if (visible) {
        CodexReasoningEffortSheet(
            model = catalogModel,
            selectedEffort = selectedEffort,
            connected = connected,
            modelsLoading = modelsLoading,
            enabled = enabled,
            onRefreshModels = onRefreshModels,
            onSelectEffort = { effort ->
                onUpdateAssistant { latest -> latest.copy(codexReasoningEffort = effort) }
                visible = false
            },
            onDismiss = { visible = false },
        )
    }
}

@Composable
private fun CodexReasoningEffortSheet(
    model: CodexAppServerModel?,
    selectedEffort: String?,
    connected: Boolean,
    modelsLoading: Boolean,
    enabled: Boolean,
    onRefreshModels: () -> Unit,
    onSelectEffort: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("推論強度", style = MaterialTheme.typography.titleLarge)
            Text(
                "次のターンから適用する強度を選びます。強くするほど時間とトークンを多く使います。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (model == null) {
                if (modelsLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("モデル情報を読み込んでいます")
                } else {
                    Text("選択中のChatGPTモデルの推論強度情報がまだありません。")
                    Button(
                        onClick = onRefreshModels,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = connected && enabled,
                    ) {
                        Text("モデル情報を読み込む")
                    }
                }
                return@Column
            }

            Text(
                model.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            EffortOptionRow(
                title = "サーバー設定",
                description = "turnごとの上書きを送信しない",
                selected = selectedEffort == null,
                onClick = { onSelectEffort(null) },
            )

            val options = model.supportedReasoningEfforts.distinctBy { it.reasoningEffort }
            options.forEach { option ->
                EffortOptionRow(
                    title = effortOptionTitle(option, model.defaultReasoningEffort),
                    description = option.description,
                    selected = selectedEffort == option.reasoningEffort,
                    onClick = { onSelectEffort(option.reasoningEffort) },
                )
            }

            if (selectedEffort != null && options.none { it.reasoningEffort == selectedEffort }) {
                Text(
                    "保存済みの「$selectedEffort」は、このモデルが現在報告する候補にありません。別の強度を選んでください。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EffortOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (description.isNotBlank()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) Text("選択中", style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun effortOptionTitle(
    option: CodexReasoningEffortOption,
    defaultEffort: String,
): String {
    val localized = codexReasoningEffortLabel(option.reasoningEffort)
    val wire = option.reasoningEffort.takeUnless { it == localized }?.let { " ($it)" }.orEmpty()
    val default = if (option.reasoningEffort == defaultEffort) " · モデル既定" else ""
    return localized + wire + default
}
