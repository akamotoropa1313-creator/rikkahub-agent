package me.rerere.rikkahub.data.codex.appserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexHarnessExistingThreadRouteGuardTest {
    @Test
    fun `native account refuses a previously gateway backed thread`() {
        val guard = CodexHarnessExistingThreadRouteGuard.NativeAccount

        assertTrue(guard.accepts(null))
        assertTrue(guard.accepts("openai"))
        assertFalse(guard.accepts("${CODEX_HARNESS_GATEWAY_PROVIDER_PREFIX}0123456789abcdef"))
    }

    @Test
    fun `gateway guard accepts only the exact route identity`() {
        val expected = "${CODEX_HARNESS_GATEWAY_PROVIDER_PREFIX}0123456789abcdef"
        val guard = CodexHarnessExistingThreadRouteGuard.Gateway(expected)

        assertTrue(guard.accepts(expected))
        assertFalse(guard.accepts("${CODEX_HARNESS_GATEWAY_PROVIDER_PREFIX}fedcba9876543210"))
        assertFalse(guard.accepts("openai"))
        assertFalse(guard.accepts(null))
    }
}
