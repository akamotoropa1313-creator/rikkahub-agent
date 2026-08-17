package me.rerere.rikkahub.service

/**
 * Stage24 policy for the persisted thread-history browser.
 *
 * The search text field is a draft. Refresh and pagination must keep using the
 * last search term that was actually submitted to the App Server.
 */
data class CodexThreadHistoryRequest(
    val searchTerm: String?,
    val cursor: String?,
)

internal fun threadHistorySubmittedSearchTerm(draft: String): String? =
    draft.trim().takeIf(String::isNotEmpty)

internal fun threadHistoryRefreshSearchTerm(state: CodexThreadHistoryUiState): String? =
    state.searchTerm.takeIf(String::isNotEmpty)

internal fun resolveThreadHistoryRequest(
    state: CodexThreadHistoryUiState,
    requestedSearchTerm: String?,
    loadMore: Boolean,
): CodexThreadHistoryRequest {
    val normalized = requestedSearchTerm?.trim()?.takeIf(String::isNotEmpty)
    if (!loadMore) return CodexThreadHistoryRequest(searchTerm = normalized, cursor = null)

    check(state.loaded) { "Thread history must be loaded before pagination" }
    val cursor = checkNotNull(state.nextCursor) { "Thread history has no next page" }
    val active = state.searchTerm.takeIf(String::isNotEmpty)
    check(normalized == active) { "Thread history pagination must preserve the active search" }
    return CodexThreadHistoryRequest(searchTerm = active, cursor = cursor)
}
