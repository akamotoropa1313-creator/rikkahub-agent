package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException

/**
 * Runs the two-step mutation required when a visible RikkaHub conversation is still bound to
 * a persistent Codex thread. The caller confirms the continuity loss before setting
 * [resetCodexSession] to true; this function then preserves the required reset-before-delete
 * ordering and converts ordinary failures into a value the UI can render instead of letting an
 * exception escape a Main dispatcher coroutine and terminate the app.
 */
internal suspend fun deleteChatMessageSafely(
    resetCodexSession: Boolean,
    resetSession: suspend () -> Unit,
    deleteMessage: suspend () -> Unit,
    onSessionReset: () -> Unit = {},
): Result<Unit> = try {
    if (resetCodexSession) {
        resetSession()
        onSessionReset()
    }
    deleteMessage()
    Result.success(Unit)
} catch (failure: Exception) {
    if (failure is CancellationException) throw failure
    Result.failure(failure)
}
