package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerAccountEvent
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerMcpEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexCapabilityCompletionTest {
    @Test fun `matching MCP OAuth success completes pending correlation`() {
        val result = applyMcpOAuthCompletion(
            CodexCapabilitiesUiState(pendingMcpServer = "server-a"),
            mcpEvent("server-a", true, null),
        )
        assertNull(result.pendingMcpServer)
        assertEquals("server-a sign-in completed", result.mcpStatus)
        assertNull(result.mcpError)
    }

    @Test fun `matching MCP OAuth failure completes correlation and preserves error for retry UI`() {
        val result = applyMcpOAuthCompletion(
            CodexCapabilitiesUiState(pendingMcpServer = "server-a"),
            mcpEvent("server-a", false, "access denied"),
        )
        assertNull(result.pendingMcpServer)
        assertEquals("access denied", result.mcpStatus)
        assertEquals("access denied", result.mcpError)
    }

    @Test fun `wrong MCP server completion cannot clear current pending server`() {
        val initial = CodexCapabilitiesUiState(pendingMcpServer = "server-a")
        assertEquals(initial, applyMcpOAuthCompletion(initial, mcpEvent("server-b", true, null)))
    }

    @Test fun `account failure completion always clears matching pending correlation`() {
        val event = CodexAppServerAccountEvent.LoginCompleted("login-1", false, "denied", null, JsonObject(emptyMap()))
        val result = applyAccountLoginCompletion(CodexCapabilitiesUiState(pendingLoginId = "login-1"), event)
        assertNull(result.pendingLoginId)
        assertEquals("denied", result.accountStatus)
        assertEquals("denied", result.accountError)
    }

    @Test fun `account notification before start state does not create pending correlation`() {
        val event = CodexAppServerAccountEvent.LoginCompleted("login-early", false, "denied", null, JsonObject(emptyMap()))
        val result = applyAccountLoginCompletion(CodexCapabilitiesUiState(accountSubmitting = true), event)
        assertNull(result.pendingLoginId)
        assertEquals("denied", result.accountStatus)
    }

    @Test fun `token exchange transport failure shows network recovery steps`() {
        val event = CodexAppServerAccountEvent.LoginCompleted(
            "login-1",
            false,
            "Token exchange failed: error sending request for url (https://auth.openai.com/oauth/token)",
            null,
            JsonObject(emptyMap()),
        )

        val result = applyAccountLoginCompletion(
            CodexCapabilitiesUiState(pendingLoginId = "login-1"),
            event,
        )

        assertNull(result.pendingLoginId)
        assertEquals(
            "OpenAI認証サーバーに接続できません。VPN・プライベートDNS・広告ブロックを一時停止するか、" +
                "Wi-Fiとモバイル通信を切り替えて、もう一度サインインしてください。",
            result.accountError,
        )
    }

    private fun mcpEvent(name: String, success: Boolean, error: String?) =
        CodexAppServerMcpEvent.OAuthLoginCompleted(name, "thread", success, error, JsonObject(emptyMap()))
}
