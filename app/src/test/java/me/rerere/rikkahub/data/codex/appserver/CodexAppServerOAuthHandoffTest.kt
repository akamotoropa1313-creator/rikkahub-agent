package me.rerere.rikkahub.data.codex.appserver

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CodexAppServerOAuthHandoffTest {
    @Test fun `url policy permits https and loopback only without rewriting`() {
        listOf("https://example.test/auth?state=secret", "http://localhost:1455/callback", "http://127.0.0.1/x", "http://[::1]/x").forEach(::validateAuthUrl)
        listOf("not a url", "/relative", "rikkahub://oauth", "http://example.test/auth").forEach { value ->
            try { validateAuthUrl(value); fail("Expected rejection: $value") } catch (_: CodexAppServerInvalidAuthUrlException) {}
        }
        assertEquals("login-1", CodexAppServerPendingChatGptLogin("login-1").loginId)
    }

    @Test fun `browser failure carries only safe login id`() {
        val error = CodexAppServerBrowserLaunchException("login-1", IllegalStateException("no handler"))
        assertEquals("login-1", error.loginId)
        if (error.message.orEmpty().contains("auth?")) fail("URL leaked")
    }
}
