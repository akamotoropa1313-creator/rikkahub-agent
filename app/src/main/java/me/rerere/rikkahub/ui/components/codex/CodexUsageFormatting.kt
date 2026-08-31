package me.rerere.rikkahub.ui.components.codex

import java.text.NumberFormat
import java.util.Locale
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerErrorInfo
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnStatus
import me.rerere.rikkahub.data.codex.appserver.CodexThreadTokenUsage

internal fun formatTokenCount(value: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(value)
internal fun formatCompactTokens(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> "%.1fk".format(Locale.US, value / 1_000.0).replace(".0k", "k")
    else -> "%.1fM".format(Locale.US, value / 1_000_000.0).replace(".0M", "M")
}
internal fun formatDuration(durationMs: Long): String = when {
    durationMs < 1_000 -> "$durationMs ミリ秒"
    durationMs < 60_000 -> "%.1f 秒".format(Locale.US, durationMs / 1_000.0).replace(".0 秒", " 秒")
    else -> "${durationMs / 60_000}分 ${(durationMs % 60_000) / 1_000}秒"
}
internal fun formatElapsedDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    return if (totalSeconds < 60L) {
        "${totalSeconds}秒"
    } else {
        "${totalSeconds / 60L}分${totalSeconds % 60L}秒"
    }
}
internal fun currentContextText(usage: CodexThreadTokenUsage): String = usage.modelContextWindow?.let {
    "${formatTokenCount(usage.last.totalTokens)} / ${formatTokenCount(it)} トークン"
} ?: "${formatTokenCount(usage.last.totalTokens)} トークン"
internal fun compactContextText(usage: CodexThreadTokenUsage): String = usage.modelContextWindow?.let {
    "コンテキスト ${formatCompactTokens(usage.last.totalTokens)} / ${formatCompactTokens(it)}"
} ?: "コンテキスト ${formatCompactTokens(usage.last.totalTokens)}"
internal fun turnStatusText(turn: CodexAppServerTurnSnapshot): String {
    val status = when (turn.status) {
        CodexAppServerTurnStatus.Completed -> "完了"
        CodexAppServerTurnStatus.Interrupted -> "中断"
        CodexAppServerTurnStatus.Failed -> "失敗"
        CodexAppServerTurnStatus.InProgress -> "実行中"
        is CodexAppServerTurnStatus.Unknown -> turn.status.wireValue
    }
    return turn.durationMs?.let { "$status · ${formatDuration(it)}" } ?: status
}
internal fun errorCategoryLabel(info: CodexAppServerErrorInfo?): String = when (info) {
    null -> "Codexエラー"
    is CodexAppServerErrorInfo.Known -> info.category.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar(Char::uppercase)
    is CodexAppServerErrorInfo.Unknown -> "その他のCodexエラー"
}
