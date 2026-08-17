package me.rerere.rikkahub.ui.components.codex

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** RikkaHub-owned Codex UI text only. App Server payloads and wire values remain untouched. */
@Composable
internal fun codexUiText(text: String): String =
    localizeCodexUiText(text, LocalConfiguration.current.locales[0].language == "ja")

internal fun localizeCodexUiText(text: String, japanese: Boolean): String {
    if (!japanese) return text
    exactJapanese[text]?.let { return it }
    return when {
        text.startsWith("Thread ID: ") -> "スレッド ID: ${text.removePrefix("Thread ID: ")}"
        text.startsWith("Model provider: ") -> "モデルプロバイダー: ${text.removePrefix("Model provider: ")}"
        text.startsWith("Status: ") -> "状態: ${text.removePrefix("Status: ")}"
        text.startsWith("Recency: ") -> "最終利用: ${text.removePrefix("Recency: ")}"
        text.startsWith("Updated: ") -> "更新日時: ${text.removePrefix("Updated: ")}"
        text.startsWith("Turn ") -> "ターン ${text.removePrefix("Turn ")}"
        text.startsWith("Active filter: ") -> "適用中の絞り込み: ${text.removePrefix("Active filter: ")}"
        text.startsWith("Session total: ") -> "セッション合計: ${text.removePrefix("Session total: ").replace(" tokens", " トークン")}"
        text.startsWith("Usage warning: ") -> "使用量の警告: ${text.removePrefix("Usage warning: ")}"
        text.startsWith("Configuration: ") -> "構成: ${text.removePrefix("Configuration: ")}"
        text.startsWith("Managed requirements: ") -> "管理ポリシー: ${text.removePrefix("Managed requirements: ")}"
        text.startsWith("Writable roots: ") -> "書き込み可能ルート: ${text.removePrefix("Writable roots: ")}"
        text.startsWith("Review in progress · ") -> "レビュー実行中 · ${text.removePrefix("Review in progress · ")}"
        text.startsWith("Current preference: server default") -> "現在の設定: サーバー既定。モデルを明示的に選択すると、そのモデルが保存されます。"
        text.startsWith("Catalog default: ") -> "カタログ上の既定値: ${text.removePrefix("Catalog default: ")}"
        text.endsWith(" skills") && text.substringBefore(' ').toIntOrNull() != null -> "${text.substringBefore(' ')} 件のスキル"
        text.startsWith("User: ") -> "ユーザー: ${text.removePrefix("User: ")}"
        text.startsWith("Agent: ") -> "エージェント: ${text.removePrefix("Agent: ")}"
        text.startsWith("Reasoning: ") -> "推論: ${text.removePrefix("Reasoning: ")}"
        text.startsWith("Command: ") -> "コマンド: ${text.removePrefix("Command: ")}"
        text.startsWith("File changes: ") -> "ファイル変更: ${text.removePrefix("File changes: ")}"
        text.startsWith("Entered review mode: ") -> "レビューモード開始: ${text.removePrefix("Entered review mode: ")}"
        text.startsWith("Exited review mode: ") -> "レビューモード終了: ${text.removePrefix("Exited review mode: ")}"
        text.startsWith("Failed: ") -> "失敗: ${text.removePrefix("Failed: ")}"
        text.startsWith("Managed policy currently does not allow ") ->
            "管理ポリシーでは現在この設定は許可されていません。保存済み設定は変更せず、App Server 側のポリシーを優先します。"
        else -> text
    }
}

private val exactJapanese = mapOf(
    "Codex Control Center" to "Codex コントロールセンター",
    "Thread history" to "スレッド履歴",
    "Back to history" to "履歴に戻る",
    "Untitled thread" to "無題のスレッド",
    "Browse persisted App Server threads without switching this conversation." to "この会話を切り替えずに、保存済みの App Server スレッドを参照できます。",
    "Search" to "検索",
    "Refresh current results" to "現在の結果を更新",
    "Load history" to "履歴を読み込む",
    "Clear search" to "検索をクリア",
    "Current" to "現在",
    "View details" to "詳細を見る",
    "Load more" to "さらに読み込む",
    "Usage & status" to "使用量と状態",
    "No token usage reported yet" to "トークン使用量はまだ報告されていません",
    "Current context" to "現在のコンテキスト",
    "Context window: Not reported" to "コンテキストウィンドウ: 未報告",
    "Latest usage breakdown" to "直近の使用量内訳",
    "Configuration & policy" to "構成とポリシー",
    "These are App Server base and managed settings. RikkaHub thread and turn overrides may differ." to "ここには App Server の基本設定と管理ポリシーを表示します。RikkaHub 側のスレッド・ターン上書き設定とは異なる場合があります。",
    "Load configuration" to "構成を読み込む",
    "Refresh diagnostics" to "診断情報を更新",
    "Base App Server configuration" to "App Server 基本構成",
    "Managed requirements" to "管理ポリシー",
    "No managed requirements reported" to "管理ポリシーは報告されていません",
    "Code review" to "コードレビュー",
    "Run a native inline review on this conversation's bound thread." to "この会話に紐づくスレッド上で Codex のネイティブレビューを実行します。",
    "Working tree" to "作業ツリー",
    "Base branch" to "ベースブランチ",
    "Commit" to "コミット",
    "Custom" to "カスタム",
    "Branch" to "ブランチ",
    "Commit SHA" to "コミット SHA",
    "Title (optional)" to "タイトル（任意）",
    "Review instructions" to "レビュー指示",
    "Stop review" to "レビューを停止",
    "Start review" to "レビューを開始",
    "Safety & permissions" to "安全性と権限",
    "Sandbox" to "サンドボックス",
    "Server setting" to "サーバー設定",
    "Read only" to "読み取り専用",
    "Workspace write" to "ワークスペース書き込み",
    "Full access" to "フルアクセス",
    "Codex can read project files but writes are restricted." to "Codex はプロジェクトファイルを読み取れますが、書き込みは制限されます。",
    "Codex can modify files allowed by the workspace sandbox." to "Codex はワークスペースのサンドボックスで許可されたファイルを変更できます。",
    "Removes Codex sandbox restrictions for the environment available to the App Server." to "App Server から利用できる環境に対する Codex のサンドボックス制限を解除します。",
    "The App Server setting is used when no explicit override is selected." to "明示的な上書きを選択しない場合は App Server 側の設定を使用します。",
    "Approval" to "承認ポリシー",
    "Untrusted" to "信頼済み操作のみ自動承認",
    "On request" to "必要時に確認",
    "Never" to "承認を求めない",
    "Only known-safe read-only commands are automatically approved; other operations may request approval." to "安全と判定された読み取り専用コマンドだけを自動承認し、それ以外の操作では承認を求める場合があります。",
    "Codex decides when it needs to ask for approval." to "Codex が必要と判断したときに承認を求めます。",
    "Codex does not ask for approval; blocked operations fail instead. This does not itself mean Full access." to "Codex は承認を求めません。ポリシーやサンドボックスで禁止された操作はそのまま失敗します。これだけでフルアクセスになるわけではありません。",
    "Server approval policy is used when no explicit override is selected." to "明示的な上書きを選択しない場合はサーバー側の承認ポリシーを使用します。",
    "Model & behavior" to "モデルと動作",
    "Codex model" to "Codex モデル",
    "The App Server catalog is authoritative. Changes apply from the next Codex turn." to "App Server のモデル一覧が基準です。変更は次の Codex ターンから適用されます。",
    "Load models" to "モデルを読み込む",
    "Refresh models" to "モデル一覧を更新",
    "Selected" to "選択中",
    "Select" to "選択",
    "Service tier" to "サービスタイア",
    "Default" to "既定",
    "Reasoning summary" to "推論要約",
    "Personality" to "パーソナリティ",
    "Connection" to "接続",
    "Reconnect Codex" to "Codex に再接続",
    "Account" to "アカウント",
    "Codex account" to "Codex アカウント",
    "Account status unavailable" to "アカウント状態を取得できません",
    "Waiting for ChatGPT sign-in" to "ChatGPT へのサインインを待機中",
    "Signed in with ChatGPT" to "ChatGPT でサインイン済み",
    "Authenticated account" to "認証済みアカウント",
    "ChatGPT sign-in required" to "ChatGPT へのサインインが必要です",
    "No OpenAI sign-in required" to "OpenAI へのサインインは不要です",
    "Cancel" to "キャンセル",
    "Sign in with ChatGPT" to "ChatGPT でサインイン",
    "Refresh" to "更新",
    "Log out" to "ログアウト",
    "Disable" to "無効化",
    "Enable" to "有効化",
    "Use" to "使用",
    "Reload MCP" to "MCP を再読み込み",
    "Sign in" to "サインイン",
    "Critical safety warning" to "重大な安全性の警告",
    "Confirm safety setting" to "安全設定の確認",
    "Confirm" to "確認",
    "Command approval" to "コマンドの承認",
    "File-change approval" to "ファイル変更の承認",
    "Reason" to "理由",
    "Network access requested" to "ネットワークアクセスが要求されています",
    "Host" to "ホスト",
    "Protocol" to "プロトコル",
    "Command" to "コマンド",
    "Working directory" to "作業ディレクトリ",
    "Action" to "操作",
    "Environment" to "環境",
    "Requested grant root" to "要求された許可ルート",
    "Change preview unavailable. Approval is disabled for your safety." to "変更内容のプレビューを取得できません。安全のため承認は無効になっています。",
    "Approve once" to "今回のみ承認",
    "Approve for session" to "このセッションで承認",
    "Decline" to "拒否",
    "Cancel turn" to "ターンをキャンセル",
    "Output" to "出力",
    "Add" to "追加",
    "Delete" to "削除",
    "Update" to "更新",
    "Diff" to "差分",
    "Turn diff" to "ターンの差分",
    "Disabled" to "無効",
    "Disconnected but bound" to "切断されています（スレッド紐付けあり）",
    "Send a Codex message first to create the conversation thread." to "最初に Codex へメッセージを送信すると会話スレッドが作成されます。",
    "Connecting" to "接続中",
    "Connected" to "接続済み",
    "Running" to "実行中",
    "Completed" to "完了",
    "Interrupted" to "中断",
    "Failed" to "失敗",
    "In progress" to "実行中",
    "Server safety setting · Reset clears sticky overrides" to "サーバーの安全設定 · リセットで以前の上書きを解除",
    "Full access · No approvals" to "フルアクセス · 承認なし",
    "Codex controls" to "Codex 設定",
    "Connection, Account, Codex Skills, Codex MCP, and safety" to "接続、アカウント、Codex Skills、Codex MCP、安全性などを設定します",
    "Uses an App Server-managed thread; the normal provider model is not used" to "App Server が管理するスレッドを使用します。通常のプロバイダーモデルは使用されません",
    "Select a workspace before enabling Codex App Server" to "Codex App Server を有効にする前にワークスペースを選択してください",
    "The selected workspace is unavailable" to "選択したワークスペースを利用できません",
    "The workspace shell must be READY" to "ワークスペースのシェルが READY である必要があります",
    "Reset Codex session?" to "Codex セッションをリセットしますか？",
    "Continuity with the current Codex thread will be lost. The next Send will create a new thread." to "現在の Codex スレッドとの継続性が失われます。次回の送信時に新しいスレッドが作成されます。",
    "Reset" to "リセット",
    "Reset Codex session" to "Codex セッションをリセット",
    "[empty message]" to "[空のメッセージ]",
    "[Image]" to "[画像]",
    "[Audio]" to "[音声]",
)
