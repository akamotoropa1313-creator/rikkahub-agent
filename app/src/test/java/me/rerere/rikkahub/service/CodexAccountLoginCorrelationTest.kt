package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerAccountEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAccountLoginCorrelationTest {
    @Test fun `anonymous completion before start prevents pending resurrection`() {
        val correlation = CodexAccountLoginCorrelation()
        var pending: String? = null
        var applied: CodexAppServerAccountEvent.LoginCompleted? = null
        correlation.beginAttempt()
        correlation.onCompletion(event(null, false, "denied")) { applied = it }
        correlation.resolveStart("login-1", { applied = it }, { pending = it })
        assertNull(pending)
        assertEquals("denied", applied?.error)
        assertEquals(0, correlation.bufferedCountForTest())
    }

    @Test fun `exact completion before start resolves only matching login`() {
        val correlation = CodexAccountLoginCorrelation()
        var pending: String? = null
        var applied: CodexAppServerAccountEvent.LoginCompleted? = null
        correlation.beginAttempt()
        correlation.onCompletion(event("other", false, "unrelated")) { applied = it }
        correlation.onCompletion(event("login-1", true, null)) { applied = it }
        assertNull(applied)
        correlation.resolveStart("login-1", { applied = it }, { pending = it })
        assertNull(pending)
        assertEquals("login-1", applied?.loginId)
        assertTrue(applied?.success == true)
        assertEquals(0, correlation.bufferedCountForTest())
    }

    @Test fun `mismatched completion cannot suppress pending start`() {
        val correlation = CodexAccountLoginCorrelation()
        var pending: String? = null
        var applied: CodexAppServerAccountEvent.LoginCompleted? = null
        correlation.beginAttempt()
        correlation.onCompletion(event("other", false, "unrelated")) { applied = it }
        correlation.resolveStart("login-1", { applied = it }, { pending = it })
        assertEquals("login-1", pending)
        assertNull(applied)
        assertEquals(0, correlation.bufferedCountForTest())
    }

    @Test fun `browser failure uses same resolution boundary as successful start`() {
        val correlation = CodexAccountLoginCorrelation()
        var pending: String? = null
        var applied: CodexAppServerAccountEvent.LoginCompleted? = null
        correlation.beginAttempt()
        correlation.onCompletion(event(null, true, null)) { applied = it }
        // Browser failure exposes the exact login ID after account/login/start succeeded.
        correlation.resolveStart("login-browser", { applied = it }, { pending = it })
        assertNull(pending)
        assertTrue(applied?.success == true)

        correlation.beginAttempt()
        applied = null
        correlation.resolveStart("login-browser-2", { applied = it }, { pending = it })
        assertEquals("login-browser-2", pending)
        assertNull(applied)
    }

    @Test fun `early completion buffer stays bounded and is cleared at resolution`() {
        val correlation = CodexAccountLoginCorrelation()
        correlation.beginAttempt()
        repeat(32) { index -> correlation.onCompletion(event("other-$index", false, "x")) {} }
        assertTrue(correlation.bufferedCountForTest() <= 8)
        correlation.resolveStart("login-real", {}, {})
        assertEquals(0, correlation.bufferedCountForTest())
    }

    private fun event(loginId: String?, success: Boolean, error: String?) =
        CodexAppServerAccountEvent.LoginCompleted(loginId, success, error, null, JsonObject(emptyMap()))
}
