from pathlib import Path

ROOT = Path('.')

def replace(path: str, old: str, new: str, count: int = -1):
    p = ROOT / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'missing replacement in {path}: {old[:120]!r}')
    if count == -1:
        text = text.replace(old, new)
    else:
        text = text.replace(old, new, count)
    p.write_text(text)

# --- Start the managed runtime immediately when Codex App Server is enabled. ---
chat_vm = 'app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt'
replace(chat_vm,
'''import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
''',
'''import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
''')
replace(chat_vm,
'''import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandApprovalDecision
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerFileChangeApprovalDecision
import me.rerere.rikkahub.data.codex.appserver.JsonRpcId
''',
'''import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandApprovalDecision
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerFileChangeApprovalDecision
import me.rerere.rikkahub.data.codex.appserver.CodexRuntimeResolver
import me.rerere.rikkahub.data.codex.appserver.JsonRpcId
''')
replace(chat_vm,
'''import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
''',
'''import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
''')
replace(chat_vm,
'''import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
''',
'''import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.workspace.WorkspaceShellStatus
import java.util.Locale
''')
replace(chat_vm,
'''    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
''',
'''    private val conversationRepo: ConversationRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val codexRuntimeResolver: CodexRuntimeResolver,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
''')
replace(chat_vm,
'''    fun reconnectCodexSession() { viewModelScope.launch { runCatching { chatService.reconnectCodexSession(_conversationId) } } }
    private fun launchCodexPreparation() {
        if (_codexPrepareJob.value?.isActive == true) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            runCatching { chatService.prepareCodexSession(_conversationId) }
            _hasCodexBinding.value = chatService.hasCodexBinding(_conversationId)
        }
''',
'''    fun reconnectCodexSession() { viewModelScope.launch { runCatching { chatService.reconnectCodexSession(_conversationId) } } }

    private suspend fun ensureCodexRuntimeReadyBeforeSession() {
        val persistedConversation = conversationRepo.getConversationById(_conversationId) ?: conversation.value
        val currentSettings = settingsStore.settingsFlow.first()
        val assistant = currentSettings.getAssistantById(persistedConversation.assistantId)
            ?: currentSettings.getCurrentAssistant()
        if (!assistant.codexAppServerEnabled) return
        val workspaceId = assistant.workspaceId?.toString() ?: return
        val workspace = workspaceRepository.getById(workspaceId) ?: return
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return
        codexRuntimeResolver.ensureReady(workspace.root)
    }

    private fun launchCodexPreparation() {
        if (_codexPrepareJob.value?.isActive == true) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            runCatching {
                // Provision first. This makes the ON toggle itself start download/install instead
                // of relying on the first message to reach the App Server connection factory.
                ensureCodexRuntimeReadyBeforeSession()
                chatService.prepareCodexSession(_conversationId)
            }
            _hasCodexBinding.value = chatService.hasCodexBinding(_conversationId)
        }
''')

vm_module = 'app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt'
replace(vm_module,
'''            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
''',
'''            settingsStore = get(),
            conversationRepo = get(),
            workspaceRepository = get(),
            codexRuntimeResolver = get(),
            chatService = get(),
''')

# --- Files picker / first-run UI ---
files_picker = 'app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt'
for old, new in {
    '"Reset Codex session?"': '"Codexセッションをリセットしますか？"',
    '"Continuity with the current Codex thread will be lost. The next Send will create a new thread."': '"現在のCodexスレッドとの継続性が失われます。次回の送信時に新しいスレッドを作成します。"',
    'Text("Reset")': 'Text("リセット")',
    'Text("Cancel")': 'Text("キャンセル")',
    '"Codex Coding Agent (App Server)"': '"Codex コーディングエージェント（App Server）"',
    '"Workspaceを選択するとCodex Coding Agentを有効にできます"': '"Workspaceを選択するとCodexコーディングエージェントを有効にできます"',
    '"Workspace内でCodex App Server Harnessを使用します。設定 > Providers > Codexとは別機能です。"': '"Workspace内でCodex App Serverを使用します。「設定 > プロバイダー > Codex」とは別の機能です。"',
    '"検証済みRuntime: Codex $it${runtimeProgress.architecture?.let { arch -> " · $arch" }.orEmpty()}"': '"検証済みランタイム: Codex $it${runtimeProgress.architecture?.let { arch -> " · $arch" }.orEmpty()}"',
    '"Codex App Server設定"': '"Codex App Serverの設定"',
    '"接続、App Serverアカウント、モデル、Skills、MCP、安全性を設定します"': '"接続、App Serverアカウント、モデル、スキル、MCP、安全性を設定します"',
    '"通常のCodex Providerのログイン状態とは独立しています"': '"通常のCodexプロバイダーのログイン状態とは独立しています"',
    'Text("Reset Codex session")': 'Text("Codexセッションをリセット")',
}.items():
    replace(files_picker, old, new)

# --- Account UI ---
account = 'app/src/main/java/me/rerere/rikkahub/ui/components/codex/CodexAccountCard.kt'
for old, new in {
    '"Waiting for ChatGPT sign-in"': '"ChatGPTへのサインインを待っています"',
    '"Account status unavailable"': '"アカウント状態を取得できません"',
    '"Signed in with ChatGPT"': '"ChatGPTでサインイン済み"',
    '"Authenticated account"': '"認証済みアカウント"',
    '"ChatGPT sign-in required"': '"ChatGPTへのサインインが必要です"',
    '"No OpenAI sign-in required"': '"OpenAIへのサインインは不要です"',
    'Text("Codex account")': 'Text("Codexアカウント")',
    'Text("Cancel")': 'Text("キャンセル")',
    'Text("Sign in with ChatGPT")': 'Text("ChatGPTでサインイン")',
    'Text("Refresh")': 'Text("更新")',
    'Text("Log out")': 'Text("ログアウト")',
}.items():
    replace(account, old, new)

# --- Approval UI ---
approval = 'app/src/main/java/me/rerere/rikkahub/ui/components/codex/CodexApprovalCards.kt'
for old, new in {
    '"Command approval"': '"コマンドの承認"',
    '"Reason"': '"理由"',
    '"Network access requested"': '"ネットワークアクセスが要求されています"',
    '"Host"': '"ホスト"',
    '"Protocol"': '"プロトコル"',
    '"Command"': '"コマンド"',
    '"Working directory"': '"作業ディレクトリ"',
    '"Action"': '"操作"',
    '"Environment"': '"環境"',
    '"File-change approval"': '"ファイル変更の承認"',
    '"Requested grant root"': '"要求された許可ルート"',
    '"Change preview unavailable. Approval is disabled for your safety."': '"変更内容をプレビューできないため、安全のため承認を無効にしています。"',
    'Text("Approve once")': 'Text("今回のみ承認")',
    'Text("Approve for session")': 'Text("このセッションで承認")',
    '"Decline rejects this action and lets the turn continue. Cancel turn rejects it and stops the turn."': '"「拒否」はこの操作だけを拒否してターンを続行します。「ターンをキャンセル」は操作を拒否してターンも停止します。"',
    'Text("Decline")': 'Text("拒否")',
    'Text("Cancel turn")': 'Text("ターンをキャンセル")',
    '"Read: ${action.path}"': '"読み取り: ${action.path}"',
    '"List files: $it"': '"ファイル一覧: $it"',
    '"List files"': '"ファイル一覧"',
    '"Search: ${action.query} in ${action.path}"': '"検索: ${action.query}（${action.path}）"',
    '"Search: ${action.query}"': '"検索: ${action.query}"',
    '"Search in: ${action.path}"': '"検索先: ${action.path}"',
    '"Search"': '"検索"',
    '"Command: ${action.command}"': '"コマンド: ${action.command}"',
    '"Unknown command action"': '"不明なコマンド操作"',
}.items():
    replace(approval, old, new)

# --- Execution / diff UI ---
execution = 'app/src/main/java/me/rerere/rikkahub/ui/components/codex/CodexExecutionCards.kt'
for old, new in {
    '" · exit $it"': '" · 終了コード $it"',
    '"Output"': '"出力"',
    '"Add"': '"追加"',
    '"Delete"': '"削除"',
    '"Update${kind.movePath?.let { " → $it" } ?: ""}"': '"更新${kind.movePath?.let { " → $it" } ?: ""}"',
    '"Diff"': '"差分"',
    'Text("Turn diff"': 'Text("ターンの差分"',
    'if (expanded) "Hide $label" else "Show $label (${text.length} characters)"': 'if (expanded) "$labelを隠す" else "$labelを表示（${text.length}文字）"',
}.items():
    replace(execution, old, new)

# --- Usage/status formatting ---
usage = 'app/src/main/java/me/rerere/rikkahub/ui/components/codex/CodexUsageFormatting.kt'
for old, new in {
    '"$durationMs ms"': '"$durationMs ミリ秒"',
    '"%.1f s"': '"%.1f 秒"',
    '".0 s"': '".0 秒"',
    '" s"': '" 秒"',
    '"${durationMs / 60_000} min ${(durationMs % 60_000) / 1_000} s"': '"${durationMs / 60_000}分 ${(durationMs % 60_000) / 1_000}秒"',
    '"${formatTokenCount(usage.last.totalTokens)} / ${formatTokenCount(it)} tokens"': '"${formatTokenCount(usage.last.totalTokens)} / ${formatTokenCount(it)} トークン"',
    '"${formatTokenCount(usage.last.totalTokens)} tokens"': '"${formatTokenCount(usage.last.totalTokens)} トークン"',
    '"Ctx ${formatCompactTokens(usage.last.totalTokens)} / ${formatCompactTokens(it)}"': '"コンテキスト ${formatCompactTokens(usage.last.totalTokens)} / ${formatCompactTokens(it)}"',
    '"Ctx ${formatCompactTokens(usage.last.totalTokens)}"': '"コンテキスト ${formatCompactTokens(usage.last.totalTokens)}"',
    '"Completed"': '"完了"',
    '"Interrupted"': '"中断"',
    '"Failed"': '"失敗"',
    '"In progress"': '"実行中"',
    '"Codex error"': '"Codexエラー"',
    '"Other Codex error"': '"その他のCodexエラー"',
}.items():
    replace(usage, old, new)

# --- Safety indicator ---
safety = 'app/src/main/java/me/rerere/rikkahub/ui/components/codex/CodexSafetyPreferencePolicy.kt'
for old, new in {
    '"Full access".takeIf': '"フルアクセス".takeIf',
    '"No approvals".takeIf': '"承認確認なし".takeIf',
    '"Server safety setting · Reset clears sticky overrides"': '"サーバーの安全設定 · リセットすると固定された上書き設定を解除します"',
}.items():
    replace(safety, old, new)

# --- Model label helpers ---
model_policy = 'app/src/main/java/me/rerere/rikkahub/ui/components/codex/CodexModelPreferencePolicy.kt'
for old, new in {
    'CodexServiceTierOption(it, if (it == "fast") "Fast" else it, "Legacy service tier")': 'CodexServiceTierOption(it, if (it == "fast") "高速" else it, "旧形式のサービスティア")',
    '?: "server default"': '?: "サーバー既定"',
    'if (saved == "default") "Default"': 'if (saved == "default") "既定"',
}.items():
    replace(model_policy, old, new)

# --- Main Codex control center ---
control = 'app/src/main/java/me/rerere/rikkahub/ui/components/codex/CodexControlSheet.kt'
repls = {
    '"Working tree"': '"作業ツリー"',
    '"Base branch"': '"ベースブランチ"',
    '"Custom"': '"カスタム"',
    '"Codex Control Center"': '"Codex コントロールセンター"',
    '"Thread history"': '"スレッド履歴"',
    '"Back to history"': '"履歴に戻る"',
    '"Untitled thread"': '"無題のスレッド"',
    '"Thread ID: ${thread.id}"': '"スレッドID: ${thread.id}"',
    '"Model provider: $it"': '"モデルプロバイダー: $it"',
    '"Status: ${it.wireValue}"': '"状態: ${it.wireValue}"',
    '"Recency: $it"': '"最近の利用日時: $it"',
    '"Updated: $it"': '"更新日時: $it"',
    '"Turn ${historyTurn.turn.id}"': '"ターン ${historyTurn.turn.id}"',
    '"Browse persisted App Server threads without switching this conversation."': '"この会話を切り替えずに、保存済みのApp Serverスレッドを参照できます。"',
    'Text("Search")': 'Text("検索")',
    '"Active filter: ${history.searchTerm}"': '"適用中のフィルター: ${history.searchTerm}"',
    '"Refresh current results"': '"現在の結果を更新"',
    '"Load history"': '"履歴を読み込む"',
    '"Clear search"': '"検索をクリア"',
    'Text("Current"': 'Text("現在"',
    'Text("View details")': 'Text("詳細を表示")',
    'Text("Load more")': 'Text("さらに読み込む")',
    '"Usage & status"': '"使用量と状態"',
    '"No token usage reported yet"': '"トークン使用量はまだ報告されていません"',
    '"Current context"': '"現在のコンテキスト"',
    '"Context window: Not reported"': '"コンテキストウィンドウ: 未報告"',
    '"Session total: ${formatTokenCount(usage.total.totalTokens)} tokens"': '"セッション合計: ${formatTokenCount(usage.total.totalTokens)} トークン"',
    '"Latest usage breakdown"': '"直近の使用量内訳"',
    '"Input: ${formatTokenCount(usage.last.inputTokens)}"': '"入力: ${formatTokenCount(usage.last.inputTokens)}"',
    '"Cached input: ${formatTokenCount(usage.last.cachedInputTokens)}"': '"キャッシュ済み入力: ${formatTokenCount(usage.last.cachedInputTokens)}"',
    '"Cache write input: ${formatTokenCount(it)}"': '"キャッシュ書き込み入力: ${formatTokenCount(it)}"',
    '"Output: ${formatTokenCount(usage.last.outputTokens)}"': '"出力: ${formatTokenCount(usage.last.outputTokens)}"',
    '"Reasoning output: ${formatTokenCount(usage.last.reasoningOutputTokens)}"': '"推論出力: ${formatTokenCount(usage.last.reasoningOutputTokens)}"',
    '"Usage warning: $it"': '"使用量の警告: $it"',
    '"Configuration & policy"': '"設定とポリシー"',
    '"These are App Server base and managed settings. RikkaHub thread and turn overrides may differ."': '"ここにはApp Serverの基本設定と管理ポリシーを表示します。RikkaHub側のスレッド/ターン上書き設定とは異なる場合があります。"',
    '"Load configuration"': '"設定を読み込む"',
    '"Refresh diagnostics"': '"診断情報を更新"',
    '"Configuration: $it"': '"設定: $it"',
    '"Managed requirements: $it"': '"管理要件: $it"',
    '"Base App Server configuration · Thread-agnostic configuration"': '"App Server基本設定 · スレッド非依存設定"',
    '"Base App Server configuration"': '"App Server基本設定"',
    'configRow("Model",': 'configRow("モデル",',
    'configRow("Model provider",': 'configRow("モデルプロバイダー",',
    'configRow("Model context window",': 'configRow("モデルコンテキストウィンドウ",',
    'configRow("Auto compact limit",': 'configRow("自動圧縮上限",',
    'configRow("Sandbox default",': 'configRow("サンドボックス既定値",',
    'configRow("Workspace-write network access",': 'configRow("workspace-write時のネットワークアクセス",',
    '"Writable roots: $it"': '"書き込み可能ルート: $it"',
    'configRow("Web search",': 'configRow("ウェブ検索",',
    'configRow("Reasoning effort",': 'configRow("推論強度",',
    'configRow("Reasoning summary",': 'configRow("推論要約",',
    'configRow("Verbosity",': 'configRow("詳細度",',
    'configRow("Service tier",': 'configRow("サービスティア",',
    'configRow("Analytics",': 'configRow("分析",',
    'Text("Managed requirements"': 'Text("管理要件"',
    '"Allowed sandbox modes: "': '"許可されたサンドボックスモード: "',
    '"Allowed web search: "': '"許可されたウェブ検索: "',
    '"Managed new-thread model: $it"': '"管理された新規スレッドのモデル: $it"',
    '"Managed new-thread effort: $it"': '"管理された新規スレッドの推論強度: $it"',
    '"Managed new-thread service tier: $it"': '"管理された新規スレッドのサービスティア: $it"',
    '"$feature = required ${if (required) "enabled" else "disabled"}"': '"$feature = 必須 ${if (required) "有効" else "無効"}"',
    '"No managed requirements reported"': '"管理要件は報告されていません"',
    '"Code review"': '"コードレビュー"',
    '"Run a native inline review on this conversation\'s bound thread."': '"この会話に紐づくスレッドで、App Serverのネイティブコードレビューを実行します。"',
    '"Branch"': '"ブランチ"',
    '"Title (optional)"': '"タイトル（任意）"',
    '"Review instructions"': '"レビュー指示"',
    '"Review in progress · ${review.targetSummary.orEmpty()}"': '"レビュー実行中 · ${review.targetSummary.orEmpty()}"',
    'Text("Stop review")': 'Text("レビューを停止")',
    'Text("Start review")': 'Text("レビューを開始")',
    '"Safety & permissions"': '"安全性と権限"',
    '"Sandbox"': '"サンドボックス"',
    '"Server setting omits the override. On an existing thread a previous override may be sticky; Reset is required to return completely to server configuration."': '"「サーバー設定」では上書きを送信しません。既存スレッドでは以前の上書きが残る場合があるため、完全にサーバー設定へ戻すにはリセットが必要です。"',
    'null to "Server setting"': 'null to "サーバー設定"',
    '"read-only" to "Read only"': '"read-only" to "読み取り専用"',
    '"workspace-write" to "Workspace write"': '"workspace-write" to "Workspace書き込み"',
    '"danger-full-access" to "Full access"': '"danger-full-access" to "フルアクセス"',
    '"Codex can read project files but writes are restricted."': '"Codexはプロジェクトファイルを読み取れますが、書き込みは制限されます。"',
    '"Codex can modify files allowed by the workspace sandbox."': '"CodexはWorkspaceサンドボックスで許可されたファイルを変更できます。"',
    '"Removes Codex sandbox restrictions for the environment available to the App Server."': '"App Serverから利用できる環境に対するCodexのサンドボックス制限を解除します。"',
    '"The App Server setting is used when no explicit override is selected."': '"明示的な上書きを選ばない場合はApp Serverの設定を使用します。"',
    '"Unsupported saved sandbox preference \'${assistant.codexSandboxMode}\' is preserved and will not be sent."': '"保存済みの未対応サンドボックス設定「${assistant.codexSandboxMode}」は保持しますが、App Serverには送信しません。"',
    '"Approval"': '"承認"',
    '"untrusted" to "Untrusted"': '"untrusted" to "未信頼"',
    '"on-request" to "On request"': '"on-request" to "要求時"',
    '"never" to "Never"': '"never" to "確認しない"',
    '"Only known-safe read-only commands are automatically approved; other operations may request approval."': '"安全と判断できる既知の読み取り専用コマンドだけを自動承認し、それ以外の操作では承認を求める場合があります。"',
    '"Codex decides when it needs to ask for approval."': '"承認が必要かどうかをCodexが判断します。"',
    '"Codex does not ask for approval; blocked operations fail instead. This does not itself mean Full access."': '"Codexは承認を求めません。禁止された操作は代わりに失敗します。この設定だけでフルアクセスになるわけではありません。"',
    '"Server approval policy is used when no explicit override is selected."': '"明示的な上書きを選ばない場合はサーバーの承認ポリシーを使用します。"',
    '"Unsupported saved approval preference \'${assistant.codexApprovalPolicy}\' is preserved and will not be sent."': '"保存済みの未対応承認設定「${assistant.codexApprovalPolicy}」は保持しますが、App Serverには送信しません。"',
    '"Model & behavior"': '"モデルと動作"',
    '"Codex model"': '"Codexモデル"',
    '"The App Server catalog is authoritative. Changes apply from the next Codex turn."': '"App Serverのモデル一覧を正として扱います。変更は次のCodexターンから反映されます。"',
    '"Load models"': '"モデルを読み込む"',
    '"Refresh models"': '"モデルを更新"',
    '"Saved Codex model \'${assistant.codexModel}\' is no longer available. Select another model explicitly; RikkaHub will not silently replace it."': '"保存済みのCodexモデル「${assistant.codexModel}」は利用できなくなっています。別のモデルを明示的に選択してください。RikkaHubが自動で置き換えることはありません。"',
    '"Current preference: server default. After an explicit selection, a concrete catalog model is saved."': '"現在の設定: サーバー既定。モデルを明示的に選ぶと、そのモデルを保存します。"',
    '" · Default"': '" · 既定"',
    'prefix = "Inputs: "': 'prefix = "入力: "',
    '?: "Inputs: not reported"': '?: "入力: 未報告"',
    '"Effort: "': '"推論強度: "',
    '"This catalog entry has invalid reasoning-effort metadata."': '"このモデル情報には不正な推論強度メタデータがあります。"',
    '"This model does not advertise personality support"': '"このモデルはパーソナリティ対応を報告していません"',
    '"Selected"': '"選択中"',
    '"Select"': '"選択"',
    'Text("Service tier"': 'Text("サービスティア"',
    '"Server setting omits the override. On an existing thread, the server-side tier may remain sticky. Default explicitly requests the default tier on the next turn."': '"「サーバー設定」ではティアの上書きを送信しません。既存スレッドではサーバー側のティアが残る場合があります。「既定」は次のターンで既定ティアを明示的に要求します。"',
    '+ "Server setting"': '+ "サーバー設定"',
    '+ "Default"': '+ "既定"',
    '"Catalog default: $label"': '"カタログ既定: $label"',
    '"Tier support not confirmed in the current model catalog"': '"現在のモデル一覧ではティア対応を確認できません"',
    '"Tier support not reported by this App Server; the saved exact tier will be preserved"': '"このApp Serverはティア対応を報告していません。保存済みのティア値はそのまま保持します"',
    '"Tier is not supported by the selected Codex model"': '"選択したCodexモデルではこのティアを利用できません"',
    'Text("Reasoning summary")': 'Text("推論要約")',
    'Text("Personality")': 'Text("パーソナリティ")',
    '"Connection"': '"接続"',
    'Text("Reconnect Codex")': 'Text("Codexに再接続")',
    '"Account"': '"アカウント"',
    '"Codex Skills"': '"Codexスキル"',
    '"${capabilities.skillGroups.sumOf { it.skills.size }} skills"': '"${capabilities.skillGroups.sumOf { it.skills.size }}件のスキル"',
    'Text("Refresh")': 'Text("更新")',
    'Text(if (skill.enabled) "Disable" else "Enable")': 'Text(if (skill.enabled) "無効化" else "有効化")',
    'Text("Use")': 'Text("使用")',
    'Text("Reload MCP")': 'Text("MCPを再読み込み")',
    '"${server.authStatus.wireValue} · ${server.tools.size} tools · ${server.resources.size} resources"': '"${server.authStatus.wireValue} · ツール ${server.tools.size}件 · リソース ${server.resources.size}件"',
    'Text("Sign in")': 'Text("サインイン")',
    '"Critical safety warning"': '"重大な安全性警告"',
    '"Confirm safety setting"': '"安全設定の確認"',
    '"Full access + Never removes the normal sandbox restriction while also disabling approval prompts. Codex may modify data available in its execution environment without asking."': '"フルアクセス + 「確認しない」は通常のサンドボックス制限を解除し、承認確認も無効にします。Codexが確認なしで実行環境内のデータを変更できる状態になります。"',
    '"Sandbox restrictions are removed. Codex may modify data available inside its execution environment. Enable only when you intentionally want unrestricted execution."': '"サンドボックス制限を解除します。Codexが実行環境内のデータを変更できます。制限なしの実行を意図している場合だけ有効にしてください。"',
    '"Approval prompts are disabled. Operations blocked by the sandbox or policy may fail rather than ask. This does not itself mean Full access."': '"承認確認を無効にします。サンドボックスやポリシーで禁止された操作は確認を求めず失敗する場合があります。この設定だけでフルアクセスになるわけではありません。"',
    'Text("Confirm")': 'Text("確認")',
    'Text("Cancel")': 'Text("キャンセル")',
    '"[empty message]"': '"[空のメッセージ]"',
    '"User: $content"': '"ユーザー: $content"',
    '"Agent: ${item.text}"': '"エージェント: ${item.text}"',
    '"Reasoning: ${(item.summary + item.content).joinToString("\\n")}"': '"推論: ${(item.summary + item.content).joinToString("\\n")}"',
    '"Command: ${item.command}${item.aggregatedOutput?.let { "\\n$it" }.orEmpty()}"': '"コマンド: ${item.command}${item.aggregatedOutput?.let { "\\n$it" }.orEmpty()}"',
    '"File changes: ${item.changes.size}"': '"ファイル変更: ${item.changes.size}件"',
    '"Entered review mode: ${item.review}"': '"レビューモード開始: ${item.review}"',
    '"Exited review mode: ${item.review}"': '"レビューモード終了: ${item.review}"',
    '"${item.type} item"': '"${item.type} アイテム"',
    '"[Image]"': '"[画像]"',
    '"[Local image: ${input.path.substringAfterLast(\'/\').ifBlank { "image" }}]"': '"[ローカル画像: ${input.path.substringAfterLast(\'/\').ifBlank { "image" }}]"',
    '"[Audio]"': '"[音声]"',
    '"[Local audio: ${input.path.substringAfterLast(\'/\').ifBlank { "audio" }}]"': '"[ローカル音声: ${input.path.substringAfterLast(\'/\').ifBlank { "audio" }}]"',
    '"[Skill: ${input.name}]"': '"[スキル: ${input.name}]"',
    'CodexConversationUiState.Disabled -> "Disabled"': 'CodexConversationUiState.Disabled -> "無効"',
    'CodexConversationUiState.Disconnected -> if (bound) "Disconnected but bound" else "Send a Codex message first to create the conversation thread."': 'CodexConversationUiState.Disconnected -> if (bound) "切断されています（スレッド紐付け済み）" else "Codexを有効にすると会話スレッドを準備します。"',
    'CodexConversationUiState.Opening -> "Connecting"': 'CodexConversationUiState.Opening -> "接続中"',
    'is CodexConversationUiState.Ready -> "Connected"': 'is CodexConversationUiState.Ready -> "接続済み"',
    'is CodexConversationUiState.Running, is CodexConversationUiState.WaitingForApproval -> "Running"': 'is CodexConversationUiState.Running, is CodexConversationUiState.WaitingForApproval -> "実行中"',
    'is CodexConversationUiState.Terminal -> "Connected"': 'is CodexConversationUiState.Terminal -> "接続済み"',
    'is CodexConversationUiState.Failed -> "Failed: ${state.message}"': 'is CodexConversationUiState.Failed -> "失敗: ${state.message}"',
    'is CodexConversationUiState.StaleBinding -> "Failed: ${state.reason}"': 'is CodexConversationUiState.StaleBinding -> "失敗: ${state.reason}"',
    'is CodexConversationUiState.WorkspaceMismatch -> "Failed: workspace mismatch"': 'is CodexConversationUiState.WorkspaceMismatch -> "失敗: Workspaceが一致しません"',
    'private fun Boolean.enabledLabel() = if (this) "Enabled" else "Disabled"': 'private fun Boolean.enabledLabel() = if (this) "有効" else "無効"',
    '"Read only"': '"読み取り専用"',
    '"Workspace write"': '"Workspace書き込み"',
    '"Full access"': '"フルアクセス"',
    'return "Managed policy currently does not allow $label. The saved preference is unchanged; the App Server remains authoritative."': 'return "管理ポリシーでは現在「$label」を許可していません。保存済みの設定は変更せず、App Server側のポリシーを優先します。"',
}
for old, new in repls.items():
    replace(control, old, new)

# --- Live chat Codex status UI ---
chat_list = 'app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt'
for old, new in {
    '"Unsupported Codex approval event"': '"未対応のCodex承認イベントです"',
    '"Codex activity"': '"Codexの動作"',
    '"Codex disabled"': '"Codexは無効です"',
    '"Codex disconnected"': '"Codexは切断されています"',
    '"Opening Codex App Server…"': '"Codex App Serverに接続しています…"',
    '"Codex ready · ${state.threadId}"': '"Codex準備完了 · ${state.threadId}"',
    '"Codex running · ${state.turnId}"': '"Codex実行中 · ${state.turnId}"',
    '"Codex is waiting for your approval"': '"Codexが承認を待っています"',
    '"Codex binding is stale: ${state.reason}"': '"Codexのスレッド紐付けが古くなっています: ${state.reason}"',
    '"Codex workspace mismatch"': '"CodexのWorkspaceが一致しません"',
    '"Codex failed: ${state.message}"': '"Codexで失敗しました: ${state.message}"',
}.items():
    replace(chat_list, old, new)

# --- Runtime progress/error text that is surfaced directly in the UI. ---
runtime = 'app/src/main/java/me/rerere/rikkahub/data/codex/appserver/CodexRuntimeManager.kt'
for old, new in {
    '"Workspace platform check timed out"': '"Workspaceのプラットフォーム確認がタイムアウトしました"',
    '"Codex runtime requires a Linux Workspace"': '"CodexランタイムにはLinux Workspaceが必要です"',
    '"Unsupported Workspace architecture: ${lines[1]}"': '"未対応のWorkspaceアーキテクチャです: ${lines[1]}"',
    '"Installed Codex runtime failed validation"': '"インストール済みCodexランタイムの検証に失敗しました"',
    'failure.message ?: "Codex runtime setup failed"': 'failure.message ?: "Codexランタイムのセットアップに失敗しました"',
    '"Codex runtime permissions could not be set"': '"Codexランタイムの実行権限を設定できませんでした"',
    '"Existing Codex runtime could not be staged for update"': '"既存のCodexランタイムを更新用に退避できませんでした"',
    '"Codex runtime could not be installed atomically"': '"Codexランタイムを安全に置き換えられませんでした"',
    '"Codex runtime download failed: HTTP ${response.code}"': '"Codexランタイムのダウンロードに失敗しました: HTTP ${response.code}"',
    '"Codex runtime download cancelled"': '"Codexランタイムのダウンロードをキャンセルしました"',
    '"Truncated Codex archive header"': '"Codexアーカイブが途中で切れています"',
    '"Official Codex archive did not contain the expected executable"': '"OpenAI公式Codexアーカイブに想定した実行ファイルが含まれていません"',
}.items():
    replace(runtime, old, new)

# --- Runtime/account capability messages visible in the control center. ---
chat_runtime = 'app/src/main/java/me/rerere/rikkahub/service/CodexChatRuntime.kt'
for old, new in {
    '"Malformed account event"': '"アカウントイベントの形式が不正です"',
    '"Malformed MCP event"': '"MCPイベントの形式が不正です"',
    '"Native code review is not supported by this App Server"': '"このApp Serverはネイティブコードレビューに対応していません"',
    '"Thread history is not supported by this App Server"': '"このApp Serverはスレッド履歴に対応していません"',
    '"No pending Codex sign-in"': '"保留中のCodexサインインはありません"',
    '"Sign-in is no longer pending on the App Server"': '"App Server側ではサインイン待機状態ではなくなっています"',
    '"Unable to confirm sign-in cancellation (${result.raw})"': '"サインインのキャンセルを確認できませんでした (${result.raw})"',
}.items():
    replace(chat_runtime, old, new)

# --- Update unit-test expectations for localized presentation helpers. ---
account_test = 'app/src/test/java/me/rerere/rikkahub/ui/components/codex/CodexAccountCardTest.kt'
replace(account_test, '"No OpenAI sign-in required"', '"OpenAIへのサインインは不要です"')
replace(account_test, '"Authenticated account"', '"認証済みアカウント"')

approval_test = 'app/src/test/java/me/rerere/rikkahub/ui/components/codex/CodexApprovalCardsTest.kt'
for old, new in {
    '"Read: /a"': '"読み取り: /a"',
    '"List files: /work"': '"ファイル一覧: /work"',
    '"Search: q in /work"': '"検索: q（/work）"',
    '"Command: custom"': '"コマンド: custom"',
    '"Unknown command action"': '"不明なコマンド操作"',
}.items():
    replace(approval_test, old, new)

usage_test = 'app/src/test/java/me/rerere/rikkahub/ui/components/codex/CodexUsageFormattingTest.kt'
for old, new in {
    '"42,381 / 200,000 tokens"': '"42,381 / 200,000 トークン"',
    '"Ctx 42.4k / 200k"': '"コンテキスト 42.4k / 200k"',
    '"842 ms"': '"842 ミリ秒"',
    '"1.4 s"': '"1.4 秒"',
    '"1 min 12 s"': '"1分 12秒"',
}.items():
    replace(usage_test, old, new)

model_test = 'app/src/test/java/me/rerere/rikkahub/ui/components/codex/CodexModelPreferencePolicyTest.kt'
replace(model_test, '"Codex · server default"', '"Codex · サーバー既定"')
replace(model_test, '"Codex · Future · Default"', '"Codex · Future · 既定"')
replace(model_test, '"Codex · server default · Fast"', '"Codex · サーバー既定 · Fast"')

safety_test = 'app/src/test/java/me/rerere/rikkahub/ui/components/codex/CodexSafetyPreferencePolicyTest.kt'
replace(safety_test, '"Full access · No approvals"', '"フルアクセス · 承認確認なし"')
replace(safety_test, '"Server safety setting · Reset clears sticky overrides"', '"サーバーの安全設定 · リセットすると固定された上書き設定を解除します"')

print('localized Codex UI and added enable-time runtime provisioning')
