package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CodexStage24HistoryPolicyTest {
    @Test
    fun `submitted search trims whitespace and blank clears search`() {
        assertEquals("needle", threadHistorySubmittedSearchTerm("  needle  "))
        assertNull(threadHistorySubmittedSearchTerm("   "))
    }

    @Test
    fun `refresh preserves active submitted search instead of draft text`() {
        val state = CodexThreadHistoryUiState(loaded = true, searchTerm = "active")
        assertEquals("active", threadHistoryRefreshSearchTerm(state))
        assertNull(threadHistoryRefreshSearchTerm(state.copy(searchTerm = "")))
    }

    @Test
    fun `first page normalizes search and never reuses a cursor`() {
        val state = CodexThreadHistoryUiState(loaded = true, nextCursor = "old-cursor", searchTerm = "old")
        val request = resolveThreadHistoryRequest(state, "  new query  ", loadMore = false)
        assertEquals("new query", request.searchTerm)
        assertNull(request.cursor)
    }

    @Test
    fun `load more preserves active query and uses next cursor`() {
        val state = CodexThreadHistoryUiState(loaded = true, nextCursor = "next", searchTerm = "active")
        val request = resolveThreadHistoryRequest(state, "active", loadMore = true)
        assertEquals("active", request.searchTerm)
        assertEquals("next", request.cursor)
    }

    @Test
    fun `load more rejects a different search query before rpc`() {
        val state = CodexThreadHistoryUiState(loaded = true, nextCursor = "next", searchTerm = "active")
        assertThrows(IllegalStateException::class.java) {
            resolveThreadHistoryRequest(state, "different", loadMore = true)
        }
    }

    @Test
    fun `load more rejects missing next page before rpc`() {
        val state = CodexThreadHistoryUiState(loaded = true, nextCursor = null, searchTerm = "active")
        assertThrows(IllegalStateException::class.java) {
            resolveThreadHistoryRequest(state, "active", loadMore = true)
        }
    }

    @Test
    fun `load more rejects pagination before initial load`() {
        val state = CodexThreadHistoryUiState(loaded = false, nextCursor = "next", searchTerm = "")
        assertThrows(IllegalStateException::class.java) {
            resolveThreadHistoryRequest(state, null, loadMore = true)
        }
    }
}
