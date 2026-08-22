package me.rerere.rikkahub.ui.components.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerModel
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessModelPresentationResolver
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessModelTarget
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessPickerCatalogBuilder
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessSelectionPolicy
import me.rerere.rikkahub.data.codex.appserver.CodexModelCatalogKnowledge
import me.rerere.rikkahub.data.codex.appserver.effectiveCodexHarnessModelTarget
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelAbilityTag
import me.rerere.rikkahub.ui.components.ai.ModelModalityTag
import me.rerere.rikkahub.ui.components.ai.ModelTypeTag
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon

/**
 * Unified Codex-harness model picker that intentionally mirrors RikkaHub's existing ModelListSheet:
 * a compact selector opens a Material bottom sheet with search, sticky provider headers and model
 * cards. ChatGPT account models come from the shared App Server model/list flow; configured
 * RikkaHub providers are shown below without creating synthetic persisted providers.
 */
@Composable
fun CodexHarnessModelSelector(
    assistant: Assistant,
    providers: List<ProviderSetting>,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accountModels by CodexModelCatalogKnowledge.modelsFlow.collectAsStateWithLifecycle()
    val target = assistant.effectiveCodexHarnessModelTarget()
    var visible by remember { mutableStateOf(false) }

    val selectedLabel = CodexHarnessModelPresentationResolver.resolve(
        target = target,
        providers = providers,
        chatGptModels = accountModels,
    ).compactLabel

    TextButton(
        onClick = { visible = true },
        modifier = modifier,
    ) {
        Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    if (!visible) return

    CodexHarnessModelSheet(
        target = target,
        providers = providers,
        accountModels = accountModels,
        onDismiss = { visible = false },
        onSelectAccountDefault = {
            onUpdateAssistant { latest -> CodexHarnessSelectionPolicy.selectChatGptServerDefault(latest) }
            visible = false
        },
        onSelectAccountModel = { model ->
            onUpdateAssistant { latest -> applyCodexModelSelection(latest, model) }
            visible = false
        },
        onSelectProviderModel = { model ->
            onUpdateAssistant { latest -> CodexHarnessSelectionPolicy.selectRikkaHubProviderModel(latest, model.id) }
            visible = false
        },
    )
}

@Composable
private fun CodexHarnessModelSheet(
    target: CodexHarnessModelTarget,
    providers: List<ProviderSetting>,
    accountModels: List<CodexAppServerModel>,
    onDismiss: () -> Unit,
    onSelectAccountDefault: () -> Unit,
    onSelectAccountModel: (CodexAppServerModel) -> Unit,
    onSelectProviderModel: (Model) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val catalog = remember(providers, accountModels) {
        CodexHarnessPickerCatalogBuilder.build(providers, accountModels)
    }
    val accountMatches = remember(accountModels, query) {
        accountModels.filter { CodexHarnessPickerCatalogBuilder.matchesSearch(it, query) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("モデルを検索") },
                    singleLine = true,
                    leadingIcon = { androidx.compose.material3.Icon(HugeIcons.Search01, contentDescription = null) },
                    shape = RoundedCornerShape(50),
                    colors = TextFieldDefaults.colors(
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                stickyHeader(key = "chatgpt-header") {
                    ProviderHeader("ChatGPTアカウント")
                }
                if (query.isBlank() || "サーバー既定".contains(query, ignoreCase = true) || "default".contains(query, ignoreCase = true)) {
                    item(key = "chatgpt-default") {
                        AccountModelRow(
                            title = "サーバー既定",
                            description = "Codex App Serverが選ぶ既定モデルを使用",
                            selected = target == CodexHarnessModelTarget.ChatGptAccount(null),
                            onClick = onSelectAccountDefault,
                        )
                    }
                }
                if (accountModels.isEmpty()) {
                    item(key = "chatgpt-empty") {
                        Text(
                            "ChatGPTモデルはまだ読み込まれていません。Codexアカウントでサインインし、コントロールセンターのモデル更新を実行するとここへ反映されます。",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    items(accountMatches, key = { "chatgpt:${it.id}" }) { model ->
                        AccountModelRow(
                            title = model.displayName + if (model.isDefault) " · 既定" else "",
                            description = model.description,
                            selected = target is CodexHarnessModelTarget.ChatGptAccount && target.model == model.model,
                            onClick = { onSelectAccountModel(model) },
                        )
                    }
                }

                catalog.providerGroups.forEach { group ->
                    val matches = group.models.filter { CodexHarnessPickerCatalogBuilder.matchesSearch(it, query) }
                    if (matches.isNotEmpty()) {
                        stickyHeader(key = "provider:${group.provider.id}") {
                            ProviderHeader(group.provider.name)
                        }
                        items(matches, key = { "provider-model:${it.id}" }) { model ->
                            ProviderModelRow(
                                model = model,
                                selected = target is CodexHarnessModelTarget.RikkaHubProvider && target.modelId == model.id,
                                onClick = { onSelectProviderModel(model) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderHeader(name: String) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Text(
            name,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AccountModelRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AutoAIIcon(name = title, modifier = Modifier.size(40.dp), color = Color.Transparent)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (description.isNotBlank()) {
                    Text(description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected) Text("選択中", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ProviderModelRow(
    model: Model,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AutoAIIcon(name = model.modelId, modifier = Modifier.size(40.dp), color = Color.Transparent)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ModelTypeTag(model)
                    ModelModalityTag(model)
                    ModelAbilityTag(model)
                }
            }
            if (selected) Text("選択中", style = MaterialTheme.typography.labelMedium)
        }
    }
}
