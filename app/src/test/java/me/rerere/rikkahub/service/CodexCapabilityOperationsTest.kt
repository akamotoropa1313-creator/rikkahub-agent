package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerInvalidAuthUrlException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexCapabilityOperationsTest {
    @Test fun `MCP OAuth failures always clear pending and permit retry`() = runBlocking {
        listOf<Throwable>(
            CodexAppServerInvalidAuthUrlException("unsafe"),
            IllegalStateException("browser failed"),
            IllegalArgumentException("RPC failed"),
        ).forEach { failure ->
            var pending: String? = null
            assertTrue(runCatching { runCodexMcpOAuthBegin("server", { pending = it }) { throw failure } }.isFailure)
            assertNull(pending)
        }
        var pending: String? = null
        assertEquals("started", runCodexMcpOAuthBegin("server", { pending = it }) { "started" })
        assertEquals("server", pending)
    }
}
