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
    durationMs < 1_000 -> "$durationMs ms"
    durationMs < 60_000 -> "%.1f s".format(Locale.US, durationMs / 1_000.0).replace(".0 s", " s")
    else -> "${durationMs / 60_000} min ${(durationMs % 60_000) / 1_000} s"
}
internal fun currentContextText(usage: CodexThreadTokenUsage): String = usage.modelContextWindow?.let {
    "${formatTokenCount(usage.last.totalTokens)} / ${formatTokenCount(it)} tokens"
} ?: "${formatTokenCount(usage.last.totalTokens)} tokens"
internal fun compactContextText(usage: CodexThreadTokenUsage): String = usage.modelContextWindow?.let {
    "Ctx ${formatCompactTokens(usage.last.totalTokens)} / ${formatCompactTokens(it)}"
} ?: "Ctx ${formatCompactTokens(usage.last.totalTokens)}"
internal fun turnStatusText(turn: CodexAppServerTurnSnapshot): String {
    val status = when (turn.status) {
        CodexAppServerTurnStatus.Completed -> "Completed"
        CodexAppServerTurnStatus.Interrupted -> "Interrupted"
        CodexAppServerTurnStatus.Failed -> "Failed"
        CodexAppServerTurnStatus.InProgress -> "In progress"
        is CodexAppServerTurnStatus.Unknown -> turn.status.wireValue
    }
    return turn.durationMs?.let { "$status · ${formatDuration(it)}" } ?: status
}
internal fun errorCategoryLabel(info: CodexAppServerErrorInfo?): String = when (info) {
    null -> "Codex error"
    is CodexAppServerErrorInfo.Known -> info.category.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar(Char::uppercase)
    is CodexAppServerErrorInfo.Unknown -> "Other Codex error"
}
