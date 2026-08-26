package me.rerere.rikkahub.ui.components.codex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import me.rerere.rikkahub.service.CodexCapabilitiesUiState
import me.rerere.rikkahub.service.CodexConversationUiState
import me.rerere.rikkahub.service.threadHistoryRefreshSearchTerm
import me.rerere.rikkahub.service.threadHistorySubmittedSearchTerm
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessModelTarget
import me.rerere.rikkahub.data.codex.appserver.effectiveCodexHarnessModelTarget
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import me.rerere.rikkahub.data.codex.appserver.CodexMcpAuthStatus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.CodexPersonalityPreference
import me.rerere.rikkahub.data.model.CodexReasoningSummaryPreference
import me.rerere.rikkahub.data.codex.appserver.CodexTokenUsageTelemetry
import me.rerere.rikkahub.data.codex.appserver.CodexEffectiveConfigSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexConfigRequirementsSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerReviewTarget
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerItemSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerUserInput
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerStaleBindingReason
import me.rerere.rikkahub.data.codex.appserver.CODEX_SANDBOX_SERVER_DEFAULT
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerSandboxMode
import me.rerere.rikkahub.data.codex.appserver.effectiveCodexSandboxMode
import me.rerere.rikkahub.service.CodexReviewUiState
import me.rerere.rikkahub.service.CodexReviewAction

/** Explicit control plane for the existing conversation-owned Codex runtime. Opening it sends no RPC. */
@Composable
fun CodexControlSheet(
    connection: CodexConversationUiState,
    capabilities: CodexCapabilitiesUiState,
    review: CodexReviewUiState,
    onStartReview: (CodexReviewAction) -> Unit,
    assistant: Assistant,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    hasBinding: Boolean,
    onRefreshAccount: () -> Unit,
    onRefreshModels: () -> Unit,
    onLoadThreadHistory: (String?, Boolean) -> Unit,
    onReadHistoryThread: (String) -> Unit,
    onCloseHistoryThread: () -> Unit,
    onRefreshConfigDiagnostics: () -> Unit,
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
    onResetSession: () -> Unit,
) {
    var pendingSafety by remember { mutableStateOf<Pair<String?, String?>?>(null) }
    var confirmSessionReset by remember { mutableStateOf(false) }
    var reviewKind by remember { mutableStateOf("作業ツリー") }
    var branch by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var historySearch by remember { mutableStateOf(capabilities.threadHistory.searchTerm) }
    val chatGptTarget = assistant.effectiveCodexHarnessModelTarget() as? CodexHarnessModelTarget.ChatGptAccount
    val usesChatGptCatalog = chatGptTarget != null
    val selectedModel = chatGptTarget?.let { reasoningEffortCatalogModel(it, capabilities.models) }
    val serviceTierModel = chatGptTarget?.let { serviceTierCatalogModel(it.model, capabilities.models) }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Codex コントロールセンター", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("接続") {
                Text(connectionLabel(connection, hasBinding))
                if (codexReconnectEligible(connection, hasBinding, capabilities.connected, operationBusy)) {
                    Button(
                        onClick = onReconnect,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Codexに再接続") }
                }
                if (codexResetEligible(connection, hasBinding, operationBusy)) {
                    OutlinedButton(
                        onClick = { confirmSessionReset = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Codexセッションをリセット") }
                }
            }
        }
        item {
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
                statusIsError = capabilities.accountError != null,
            )
        }
        item {
            Section("スレッド履歴") {
                val history = capabilities.threadHistory
                if (history.selectedThreadId != null) {
                    TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = onCloseHistoryThread) { Text("履歴に戻る") }
                    if (history.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    history.detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    history.selectedThread?.let { thread ->
                        Text(thread.name ?: thread.preview ?: "無題のスレッド", style = MaterialTheme.typography.titleMedium)
                        Text("スレッドID: ${thread.id}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        thread.modelProvider?.let { Text("モデルプロバイダー: $it") }
                        thread.status?.let { Text("状態: ${it.wireValue}") }
                        thread.recencyAt?.let { Text("最近の利用日時: $it") }
                        thread.updatedAt?.let { Text("更新日時: $it") }
                        thread.cwd?.let { Text("CWD: ${it.substringAfterLast('/').ifBlank { "/" }}", maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        thread.turns.forEach { historyTurn ->
                            HorizontalDivider()
                            Text("ターン ${historyTurn.turn.id}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${historyTurn.turn.status.wireValue}${historyTurn.turn.durationMs?.let { " · ${formatDuration(it)}" }.orEmpty()}")
                            historyTurn.turn.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            historyTurn.items.forEach { item -> Text(historyItemText(item), maxLines = 5, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                } else {
                    Text("この会話を切り替えずに、保存済みのApp Serverスレッドを参照できます。")
                    if (history.loaded) {
                        OutlinedTextField(
                            value = historySearch,
                            onValueChange = { historySearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("検索") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { onLoadThreadHistory(threadHistorySubmittedSearchTerm(historySearch), false) },
                            ),
                        )
                        if (history.searchTerm.isNotEmpty()) {
                            Text(
                                "適用中のフィルター: ${history.searchTerm}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val historyControlsEnabled = capabilities.connected && !history.loading && !operationBusy
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            enabled = historyControlsEnabled,
                            onClick = {
                                onLoadThreadHistory(
                                    if (history.loaded) threadHistoryRefreshSearchTerm(history) else null,
                                    false,
                                )
                            },
                        ) { Text(if (history.loaded) "現在の結果を更新" else "履歴を読み込む") }
                        if (history.loaded) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                enabled = historyControlsEnabled,
                                onClick = { onLoadThreadHistory(threadHistorySubmittedSearchTerm(historySearch), false) },
                            ) { Text(if (historySearch.isBlank()) "検索をクリア" else "検索") }
                        }
                    }
                    if (history.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    history.error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    history.threads.forEach { thread ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            headlineContent = { Text(thread.name ?: thread.preview ?: "無題のスレッド", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(listOfNotNull(thread.preview, thread.recencyAt?.toString(), thread.status?.wireValue, thread.modelProvider).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { if (thread.id == connection.threadId) Text("現在", color = MaterialTheme.colorScheme.primary) },
                        )
                        TextButton(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            enabled = capabilities.connected && !history.detailLoading && !operationBusy,
                            onClick = { onReadHistoryThread(thread.id) },
                        ) { Text("詳細を表示") }
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        enabled = capabilities.connected && history.nextCursor != null && !history.loading && !operationBusy,
                        onClick = { onLoadThreadHistory(threadHistoryRefreshSearchTerm(history), true) },
                    ) { Text("さらに読み込む") }
                }
            }
        }
        item {
            Section("使用量と状態") {
                val telemetry = connection.telemetryOrNull()
                val usage = telemetry?.latest?.tokenUsage
                if (usage == null) Text("トークン使用量はまだ報告されていません") else {
                    Text("現在のコンテキスト", style = MaterialTheme.typography.titleMedium)
                    Text(currentContextText(usage))
                    if (usage.modelContextWindow == null) Text("コンテキストウィンドウ: 未報告")
                    Text("セッション合計: ${formatTokenCount(usage.total.totalTokens)} トークン")
                    Text("直近の使用量内訳", style = MaterialTheme.typography.titleMedium)
                    Text("入力: ${formatTokenCount(usage.last.inputTokens)}")
                    Text("キャッシュ済み入力: ${formatTokenCount(usage.last.cachedInputTokens)}")
                    usage.last.cacheWriteInputTokens?.let { Text("キャッシュ書き込み入力: ${formatTokenCount(it)}") }
                    Text("出力: ${formatTokenCount(usage.last.outputTokens)}")
                    Text("推論出力: ${formatTokenCount(usage.last.reasoningOutputTokens)}")
                }
                telemetry?.warning?.let { Text("使用量の警告: $it", color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                (connection as? CodexConversationUiState.Terminal)?.diagnostics?.let { turn ->
                    Text(turnStatusText(turn), style = MaterialTheme.typography.titleMedium)
                    turn.error?.let { error ->
                        Text("${errorCategoryLabel(error.codexErrorInfo)}: ${error.message}", color = MaterialTheme.colorScheme.error)
                        error.additionalDetails?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        item {
            Section("設定とポリシー") {
                Text("ここにはApp Serverの基本設定と管理ポリシーを表示します。RikkaHub側のスレッド/ターン上書き設定とは異なる場合があります。")
                Button(
                    onClick = onRefreshConfigDiagnostics,
                    enabled = capabilities.connected && !capabilities.configLoading && !capabilities.requirementsLoading && !operationBusy,
                ) { Text(if (capabilities.effectiveConfig == null && !capabilities.requirementsLoaded) "設定を読み込む" else "診断情報を更新") }
                if (capabilities.configLoading || capabilities.requirementsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                capabilities.configError?.let { Text("設定: $it", color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                capabilities.requirementsError?.let { Text("管理要件: $it", color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                capabilities.effectiveConfig?.let { config ->
                    Text(if (config.threadAgnostic) "App Server基本設定 · スレッド非依存設定" else "App Server基本設定", style = MaterialTheme.typography.titleMedium)
                    configRow("モデル", config.model, config, "model")
                    configRow("モデルプロバイダー", config.modelProvider, config, "model_provider")
                    configRow("モデルコンテキストウィンドウ", config.modelContextWindow?.toString(), config, "model_context_window")
                    configRow("自動圧縮上限", config.modelAutoCompactTokenLimit?.toString(), config, "model_auto_compact_token_limit")
                    configRow("サンドボックス既定値", config.sandboxMode?.let { if (it.known) it.wireValue else "${it.wireValue} (unknown)" }, config, "sandbox_mode")
                    configRow("workspace-write時のネットワークアクセス", config.sandboxWorkspaceWrite?.networkAccess?.enabledLabel(), config, "sandbox_workspace_write.network_access")
                    config.sandboxWorkspaceWrite?.writableRootsCount?.let { Text("書き込み可能ルート: $it") }
                    configRow("ウェブ検索", config.webSearch, config, "web_search")
                    configRow("推論強度", config.modelReasoningEffort, config, "model_reasoning_effort")
                    configRow("推論要約", config.modelReasoningSummary, config, "model_reasoning_summary")
                    configRow("詳細度", config.modelVerbosity, config, "model_verbosity")
                    configRow("サービスティア", config.serviceTier, config, "service_tier")
                    configRow("分析", config.analyticsEnabled?.enabledLabel(), config, "analytics.enabled")
                }
                if (capabilities.requirementsLoaded) {
                    Text("管理要件", style = MaterialTheme.typography.titleMedium)
                    capabilities.requirements?.let { requirements ->
                        requirements.allowedSandboxModes?.let { Text("許可されたサンドボックスモード: " + it.joinToString(" · ") { mode -> mode.wireValue }) }
                        requirements.allowedWebSearchModes?.let { Text("許可されたウェブ検索: " + it.joinToString(" · ")) }
                        requirements.newThread?.model?.let { Text("管理された新規スレッドのモデル: $it") }
                        requirements.newThread?.modelReasoningEffort?.let { Text("管理された新規スレッドの推論強度: $it") }
                        requirements.newThread?.serviceTier?.let { Text("管理された新規スレッドのサービスティア: $it") }
                        requirements.featureRequirements?.forEach { (feature, required) -> Text("$feature = 必須 ${if (required) "有効" else "無効"}") }
                    } ?: Text("管理要件は報告されていません")
                }
            }
        }
        item {
            Section("コードレビュー") {
                Text("この会話に紐づくスレッドで、App Serverのネイティブコードレビューを実行します。")
                listOf("作業ツリー", "ベースブランチ", "Commit", "カスタム").forEach { kind ->
                    TextButton(modifier = Modifier.heightIn(min = 44.dp), onClick = { reviewKind = kind }) {
                        Text((if (reviewKind == kind) "✓ " else "") + kind)
                    }
                }
                when (reviewKind) {
                    "ベースブランチ" -> OutlinedTextField(branch, { branch = it }, Modifier.fillMaxWidth(), label = { Text("ブランチ") }, singleLine = true)
                    "Commit" -> { OutlinedTextField(sha, { sha = it }, Modifier.fillMaxWidth(), label = { Text("Commit SHA") }, singleLine = true); OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("タイトル（任意）") }) }
                    "カスタム" -> OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("レビュー指示") }, minLines = 4)
                }
                if (review.inProgress) {
                    Text("レビュー実行中 · ${review.targetSummary.orEmpty()}")
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        enabled = capabilities.connected,
                        onClick = { onStartReview(CodexReviewAction.Stop) },
                    ) { Text("レビューを停止") }
                }
                review.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val valid = when (reviewKind) { "ベースブランチ" -> branch.isNotBlank(); "Commit" -> sha.isNotBlank(); "カスタム" -> instructions.isNotBlank(); else -> true }
                Button(modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), enabled = capabilities.connected && valid && !review.inProgress && !operationBusy, onClick = {
                    onStartReview(CodexReviewAction.Start(when (reviewKind) {
                        "ベースブランチ" -> CodexAppServerReviewTarget.BaseBranch(branch)
                        "Commit" -> CodexAppServerReviewTarget.Commit(sha, title.takeIf(String::isNotBlank))
                        "カスタム" -> CodexAppServerReviewTarget.Custom(instructions)
                        else -> CodexAppServerReviewTarget.UncommittedChanges
                    }))
                }) { Text("レビューを開始") }
            }
        }
        item {
            Section("安全性と権限") {
                Text("サンドボックス", style = MaterialTheme.typography.titleMedium)
                Text("選択したWorkspaceはCodexの作業領域です。既定では、端末全体を開放せずにWorkspace内へ書き込める設定を各ターンへ送信します。")
                Text("コマンドの承認確認と書き込み範囲は別設定です。承認確認をオフにしても、読み取り専用ではファイルを作成できません。")
                listOf(
                    null to "Workspace書き込み（推奨）",
                    "read-only" to "読み取り専用",
                    "workspace-write" to "Workspace書き込み（固定）",
                    CODEX_SANDBOX_SERVER_DEFAULT to "App Server設定",
                    "danger-full-access" to "フルアクセス",
                ).forEach { (value, label) ->
                    TextButton(onClick = {
                        val confirmation = codexSafetyConfirmation(assistant, sandbox = value)
                        if (confirmation == CodexSafetyConfirmation.NONE) onUpdateAssistant { it.copy(codexSandboxMode = value) }
                        else pendingSafety = value to assistant.codexApprovalPolicy
                    }) { Text((if (assistant.codexSandboxMode == value) "✓ " else "") + label) }
                }
                Text(when (assistant.codexSandboxMode) {
                    "read-only" -> "Codexはプロジェクトファイルを読み取れますが、書き込みは制限されます。"
                    "workspace-write" -> "CodexはWorkspaceサンドボックスで許可されたファイルを変更できます。"
                    "danger-full-access" -> "App Serverから利用できる環境に対するCodexのサンドボックス制限を解除します。"
                    CODEX_SANDBOX_SERVER_DEFAULT -> "上書きを送信せずApp Serverの設定を使用します。以前の上書きを完全に解除するにはセッションのリセットが必要です。"
                    null -> "Codexは次の送信からWorkspace内のファイルを作成・変更できます。端末全体へのフルアクセスではありません。"
                    else -> "未対応の保存値はApp Serverへ送信しません。"
                })
                if (!codexSandboxKnown(assistant.codexSandboxMode)) Text("保存済みの未対応サンドボックス設定「${assistant.codexSandboxMode}」は保持しますが、App Serverには送信しません。", color = MaterialTheme.colorScheme.error)
                managedSandboxWarning(assistant.codexSandboxMode, capabilities.requirementsLoaded, capabilities.requirements)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Text("承認", style = MaterialTheme.typography.titleMedium)
                Text("確認画面の表示には、既存のWorkspace「ツール承認」設定と全体の自動承認設定が優先して適用されます。ここではCodex側がどの操作で承認を要求するかを指定します。")
                listOf(null to "サーバー設定", "untrusted" to "未信頼", "on-request" to "要求時", "never" to "確認しない").forEach { (value, label) ->
                    TextButton(onClick = {
                        val confirmation = codexSafetyConfirmation(assistant, approval = value)
                        if (confirmation == CodexSafetyConfirmation.NONE) onUpdateAssistant { it.copy(codexApprovalPolicy = value) }
                        else pendingSafety = assistant.codexSandboxMode to value
                    }) { Text((if (assistant.codexApprovalPolicy == value) "✓ " else "") + label) }
                }
                Text(when (assistant.codexApprovalPolicy) {
                    "untrusted" -> "安全と判断できる既知の読み取り専用コマンドだけを自動承認し、それ以外の操作では承認を求める場合があります。"
                    "on-request" -> "承認が必要かどうかをCodexが判断します。"
                    "never" -> "Codexは承認を求めません。禁止された操作は代わりに失敗します。この設定だけでフルアクセスになるわけではありません。"
                    else -> "明示的な上書きを選ばない場合はサーバーの承認ポリシーを使用します。"
                })
                if (!codexApprovalKnown(assistant.codexApprovalPolicy)) Text("保存済みの未対応承認設定「${assistant.codexApprovalPolicy}」は保持しますが、App Serverには送信しません。", color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Section("モデルと動作") {
                Text("モデルと推論強度はチャット入力欄から素早く変更できます。ここではApp Serverのモデルカタログと、選択したChatGPTモデル固有の詳細設定を管理します。")
                Button(
                    onClick = onRefreshModels,
                    enabled = capabilities.connected && !capabilities.modelsLoading && !operationBusy,
                ) {
                    Text(if (capabilities.models.isEmpty()) "モデル情報を読み込む" else "モデル情報を更新")
                }
                if (capabilities.modelsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                capabilities.modelsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!usesChatGptCatalog) {
                    Text("現在はRikkaHubプロバイダーのモデルを使用しています。Codex固有の推論強度・サービスティア・パーソナリティ設定は適用しません。")
                } else {
                    if (savedCodexModelMissing(chatGptTarget?.model, capabilities.models)) {
                        Text(
                            "保存済みのCodexモデル「${chatGptTarget?.model}」は利用できなくなっています。アシスタント設定のチャットモデルから別のモデルを選択してください。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (chatGptTarget?.model == null) {
                        Text("現在のChatGPTモデル設定: サーバー既定")
                    }
                }
            }
        }
        if (usesChatGptCatalog) {
            item {
                Section("サービスティア") {
                    Text("「サーバー設定」ではティアの上書きを送信しません。既存スレッドではサーバー側のティアが残る場合があります。「既定」は次のターンで既定ティアを明示的に要求します。")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { onUpdateAssistant { it.copy(codexServiceTier = null) } }) {
                            Text((if (assistant.codexServiceTier == null) "✓ " else "") + "サーバー設定")
                        }
                        TextButton(onClick = { onUpdateAssistant { it.copy(codexServiceTier = "default") } }) {
                            Text((if (assistant.codexServiceTier == "default") "✓ " else "") + "既定")
                        }
                        serviceTierModel?.let { tierModel ->
                            codexServiceTierOptions(tierModel).forEach { tier ->
                                TextButton(onClick = { onUpdateAssistant { it.copy(codexServiceTier = tier.id) } }) {
                                    Text((if (assistant.codexServiceTier == tier.id) "✓ " else "") + tier.name + " · " + tier.description)
                                }
                            }
                        }
                    }
                    serviceTierModel?.defaultServiceTier?.let { default ->
                        val label = codexServiceTierOptions(serviceTierModel).firstOrNull { it.id == default }?.name ?: default
                        Text("カタログ既定: $label")
                    }
                    val savedTier = assistant.codexServiceTier
                    if (savedTier != null && savedTier != "default") {
                        when {
                            serviceTierModel == null -> Text("現在のモデル一覧ではティア対応を確認できません")
                            serviceTierModel.serviceTiers == null && serviceTierModel.additionalSpeedTiers == null ->
                                Text("このApp Serverはティア対応を報告していません。保存済みのティア値はそのまま保持します")
                            codexServiceTierOptions(serviceTierModel).none { it.id == savedTier } ->
                                Text("選択したCodexモデルではこのティアを利用できません", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            selectedModel?.let { selected ->
                item {
                    Section("推論とパーソナリティ") {
                        Text("推論強度", style = MaterialTheme.typography.titleMedium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            selected.supportedReasoningEfforts.forEach { effort ->
                                TextButton(onClick = { onUpdateAssistant { it.copy(codexReasoningEffort = effort.reasoningEffort) } }) {
                                    Text(
                                        (if (assistant.codexReasoningEffort == effort.reasoningEffort) "✓ " else "") +
                                            effort.reasoningEffort + " · " + effort.description,
                                    )
                                }
                            }
                        }
                        Text("推論要約", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CodexReasoningSummaryPreference.entries.forEach { value ->
                                TextButton(onClick = { onUpdateAssistant { it.copy(codexReasoningSummary = value) } }) {
                                    Text((if (assistant.codexReasoningSummary == value) "✓ " else "") + value.name.lowercase())
                                }
                            }
                        }
                        Text("パーソナリティ", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            Text("このモデルはパーソナリティ対応を報告していません")
                        }
                    }
                }
            }
        }
        item {
            Section("Codexスキル") {
                Text("${capabilities.skillGroups.sumOf { it.skills.size }}件のスキル")
                Button(
                    onClick = onRefreshSkills,
                    enabled = capabilities.connected && !capabilities.skillsLoading && !operationBusy,
                ) { Text("更新") }
                capabilities.skillsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        capabilities.skillGroups.forEach { group ->
            item {
                Text(group.cwd, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            items(
                items = group.skills.distinctBy { it.path },
            ) { skill ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(skill.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            skill.shortDescription ?: skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(
                                onClick = { onSetSkillEnabled(skill, !skill.enabled) },
                                enabled = capabilities.skillUpdatingPath == null && !operationBusy,
                            ) { Text(if (skill.enabled) "無効化" else "有効化") }
                            TextButton(onClick = { onUseSkill(skill) }, enabled = skill.enabled && !operationBusy) { Text("使用") }
                        }
                    }
                }
            }
            items(items = group.errors) { error ->
                Text(error.message, color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Section("Codex MCP") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRefreshMcp,
                        enabled = capabilities.connected && !capabilities.mcpLoading && !operationBusy,
                    ) { Text("更新") }
                    OutlinedButton(
                        onClick = onReloadMcp,
                        enabled = capabilities.connected && !capabilities.mcpLoading && !operationBusy,
                    ) { Text("MCPを再読み込み") }
                }
                capabilities.mcpError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        items(
            items = capabilities.mcpServers.distinctBy { it.name },
        ) { server ->
            ListItem(
                headlineContent = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${server.authStatus.wireValue} · ツール ${server.tools.size}件 · リソース ${server.resources.size}件") },
                trailingContent = {
                    if (server.authStatus is CodexMcpAuthStatus.NotLoggedIn) {
                        TextButton(
                            onClick = { onMcpSignIn(server.name) },
                            enabled = capabilities.pendingMcpServer == null && !operationBusy,
                        ) { Text("サインイン") }
                    }
                },
            )
        }
    }
    pendingSafety?.let { pending ->
        val kind = codexSafetyConfirmation(assistant, pending.first, pending.second)
        AlertDialog(
            onDismissRequest = { pendingSafety = null },
            title = { Text(if (kind == CodexSafetyConfirmation.CRITICAL) "重大な安全性警告" else "安全設定の確認") },
            text = { Text(when (kind) {
                CodexSafetyConfirmation.CRITICAL -> "フルアクセス + 「確認しない」は通常のサンドボックス制限を解除し、承認確認も無効にします。Codexが確認なしで実行環境内のデータを変更できる状態になります。"
                CodexSafetyConfirmation.FULL_ACCESS -> "サンドボックス制限を解除します。Codexが実行環境内のデータを変更できます。制限なしの実行を意図している場合だけ有効にしてください。"
                else -> "承認確認を無効にします。サンドボックスやポリシーで禁止された操作は確認を求めず失敗する場合があります。この設定だけでフルアクセスになるわけではありません。"
            }) },
            confirmButton = { TextButton(onClick = { onUpdateAssistant { it.copy(codexSandboxMode = pending.first, codexApprovalPolicy = pending.second) }; pendingSafety = null }) { Text("確認") } },
            dismissButton = { TextButton(onClick = { pendingSafety = null }) { Text("キャンセル") } },
        )
    }
    if (confirmSessionReset) {
        AlertDialog(
            onDismissRequest = { confirmSessionReset = false },
            title = { Text("Codexセッションをリセットしますか？") },
            text = { Text("RikkaHubの会話履歴は残りますが、現在のCodexスレッドとの継続性は失われます。次回の送信時に新しいスレッドを作成します。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSessionReset = false
                    onResetSession()
                }) { Text("リセット") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSessionReset = false }) { Text("キャンセル") }
            },
        )
    }
}

private fun CodexConversationUiState.telemetryOrNull(): CodexTokenUsageTelemetry? = when (this) {
    is CodexConversationUiState.Ready -> telemetry
    is CodexConversationUiState.Running -> telemetry
    is CodexConversationUiState.Terminal -> telemetry
    is CodexConversationUiState.WaitingForApproval -> telemetry
    else -> null
}

private val CodexConversationUiState.threadId: String? get() = when (this) {
    is CodexConversationUiState.Ready -> threadId
    is CodexConversationUiState.Running -> threadId
    is CodexConversationUiState.Terminal -> threadId
    else -> null
}

private fun historyItemText(item: CodexAppServerItemSnapshot): String = when (item) {
    is CodexAppServerItemSnapshot.UserMessage -> {
        val content = item.content.joinToString("\n", transform = ::historyUserInputText).ifBlank { "[空のメッセージ]" }
        "ユーザー: $content"
    }
    is CodexAppServerItemSnapshot.AgentMessage -> "エージェント: ${item.text}"
    is CodexAppServerItemSnapshot.Reasoning -> "推論: ${(item.summary + item.content).joinToString("\n")}"
    is CodexAppServerItemSnapshot.CommandExecution -> "コマンド: ${item.command}${item.aggregatedOutput?.let { "\n$it" }.orEmpty()}"
    is CodexAppServerItemSnapshot.FileChange -> "ファイル変更: ${item.changes.size}件"
    is CodexAppServerItemSnapshot.EnteredReviewMode -> "レビューモード開始: ${item.review}"
    is CodexAppServerItemSnapshot.ExitedReviewMode -> "レビューモード終了: ${item.review}"
    is CodexAppServerItemSnapshot.Other -> "${item.type} アイテム"
}

private fun historyUserInputText(input: CodexAppServerUserInput): String = when (input) {
    is CodexAppServerUserInput.Text -> input.text
    is CodexAppServerUserInput.Image -> "[画像]"
    is CodexAppServerUserInput.LocalImage -> "[ローカル画像: ${input.path.substringAfterLast('/').ifBlank { "image" }}]"
    is CodexAppServerUserInput.Audio -> "[音声]"
    is CodexAppServerUserInput.LocalAudio -> "[ローカル音声: ${input.path.substringAfterLast('/').ifBlank { "audio" }}]"
    is CodexAppServerUserInput.Skill -> "[スキル: ${input.name}]"
    is CodexAppServerUserInput.Mention -> "@${input.name}"
    is CodexAppServerUserInput.Other -> "[${input.type}]"
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) =
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }

private fun connectionLabel(state: CodexConversationUiState, bound: Boolean) = when (state) {
    CodexConversationUiState.Disabled -> "無効"
    CodexConversationUiState.Disconnected -> if (bound) "切断されています（スレッド紐付け済み）" else "Codexを有効にすると会話スレッドを準備します。"
    CodexConversationUiState.Opening -> "接続中"
    is CodexConversationUiState.Ready -> "接続済み"
    is CodexConversationUiState.Running, is CodexConversationUiState.WaitingForApproval -> "実行中"
    is CodexConversationUiState.Terminal -> "接続済み"
    is CodexConversationUiState.Failed -> "失敗: ${state.message}"
    is CodexConversationUiState.StaleBinding -> "要リセット: ${codexStaleBindingMessage(state.reason)}"
    is CodexConversationUiState.WorkspaceMismatch -> "失敗: Workspaceが一致しません"
}

internal fun codexStaleBindingMessage(reason: CodexAppServerStaleBindingReason): String = when (reason) {
    CodexAppServerStaleBindingReason.MissingConversation ->
        "元のRikkaHub会話が見つかりません。セッションをリセットしてください。"
    CodexAppServerStaleBindingReason.MissingWorkspace ->
        "紐付け先のWorkspaceが見つかりません。Workspaceを選び直してからリセットしてください。"
    CodexAppServerStaleBindingReason.ThreadNotLoaded ->
        "Codex側に保存済みスレッドがありません。セッションをリセットしてください。"
    is CodexAppServerStaleBindingReason.HarnessRouteChanged ->
        "選択したモデルの実行先が以前のスレッドと異なります。セッションをリセットしてください。"
}

@Composable
private fun configRow(label: String, value: String?, config: CodexEffectiveConfigSnapshot, originKey: String) {
    value ?: return
    val origin = config.origins[originKey]?.source?.label
    Text(if (origin == null) "$label: $value" else "$label: $value · $origin", maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private fun Boolean.enabledLabel() = if (this) "有効" else "無効"

internal fun managedSandboxWarning(
    savedMode: String?,
    requirementsLoaded: Boolean,
    requirements: CodexConfigRequirementsSnapshot?,
): String? {
    if (!requirementsLoaded) return null
    val effectiveMode = effectiveCodexSandboxMode(savedMode) ?: return null
    val allowed = requirements?.allowedSandboxModes ?: return null
    if (allowed.any { effectiveMode.matchesServerValue(it.wireValue) }) return null
    val label = when (effectiveMode) {
        CodexAppServerSandboxMode.READ_ONLY -> "読み取り専用"
        CodexAppServerSandboxMode.WORKSPACE_WRITE -> "Workspace書き込み"
        CodexAppServerSandboxMode.DANGER_FULL_ACCESS -> "フルアクセス"
    }
    return "管理ポリシーでは現在「$label」を許可していません。このままでは開始・実行に失敗する場合があるため、許可済みの設定を選択してください。"
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

internal fun codexResetEligible(
    state: CodexConversationUiState,
    hasBinding: Boolean,
    operationBusy: Boolean,
): Boolean = hasBinding && !operationBusy && when (state) {
    is CodexConversationUiState.StaleBinding, is CodexConversationUiState.WorkspaceMismatch -> true
    else -> false
}
