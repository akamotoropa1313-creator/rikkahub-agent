package me.rerere.rikkahub.ui.components.codex

import me.rerere.rikkahub.data.codex.appserver.CodexAppServerErrorInfo
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnError
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexRuntimeErrorCategory

data class CodexErrorPresentation(
    val category: CodexRuntimeErrorCategory,
    val title: String,
    val detail: String?,
)

/**
 * Uses structured App Server error metadata first. Human text is only a safe fallback and is never
 * parsed to invent reset timestamps; if the server reports a date only in its message, that exact
 * message remains visible to the user.
 */
fun codexTurnErrorPresentation(snapshot: CodexAppServerTurnSnapshot?): CodexErrorPresentation? =
    snapshot?.error?.let(::codexTurnErrorPresentation)

fun codexTurnErrorPresentation(error: CodexAppServerTurnError): CodexErrorPresentation {
    val category = (error.codexErrorInfo as? CodexAppServerErrorInfo.Known)?.category
    return when (category) {
        "usageLimitExceeded", "sessionBudgetExceeded" -> CodexErrorPresentation(
            category = CodexRuntimeErrorCategory.UsageLimitExceeded,
            title = "Codexの利用上限に達しています",
            detail = error.additionalDetails ?: error.message,
        )
        "unauthorized" -> CodexErrorPresentation(
            category = CodexRuntimeErrorCategory.AuthenticationRequired,
            title = "ChatGPTへのサインインが必要です",
            detail = error.additionalDetails ?: error.message,
        )
        "httpConnectionFailed", "responseStreamConnectionFailed", "responseStreamDisconnected" -> CodexErrorPresentation(
            category = CodexRuntimeErrorCategory.NetworkFailure,
            title = "Codexとの通信に失敗しました",
            detail = error.additionalDetails ?: error.message,
        )
        else -> CodexErrorPresentation(
            category = CodexRuntimeErrorCategory.Unknown,
            title = "Codexの処理に失敗しました",
            detail = error.additionalDetails ?: error.message,
        )
    }
}
