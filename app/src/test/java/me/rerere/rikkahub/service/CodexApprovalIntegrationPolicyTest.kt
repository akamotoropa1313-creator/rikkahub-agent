package me.rerere.rikkahub.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerApprovalEvent
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerApprovalPolicy
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandAction
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandApprovalRequest
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerFileChangeApprovalRequest
import me.rerere.rikkahub.data.codex.appserver.JsonRpcId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexApprovalIntegrationPolicyTest {
    @Test
    fun `disabling Workspace command approval also disables App Server approval round trips`() {
        val policy = policy(overrides = mapOf(CODEX_COMMAND_TOOL_NAME to false))

        assertEquals(CodexAppServerApprovalPolicy.NEVER, policy.effectiveAppServerPolicy())
        assertTrue(policy.shouldAutoApprove(commandEvent()))
    }

    @Test
    fun `enabled Workspace command approval keeps native chat approval pending`() {
        val policy = policy(overrides = mapOf(CODEX_COMMAND_TOOL_NAME to true))

        assertEquals(CodexAppServerApprovalPolicy.ON_REQUEST, policy.effectiveAppServerPolicy())
        assertFalse(policy.shouldAutoApprove(commandEvent()))
    }

    @Test
    fun `read command follows read-file approval instead of shell approval`() {
        val read = CodexAppServerCommandAction.Read(
            command = "cat demo.txt",
            name = "cat",
            path = "/workspace/demo.txt",
            raw = buildJsonObject { put("type", "read") },
        )
        val policy = policy(overrides = mapOf(CODEX_COMMAND_TOOL_NAME to true))

        assertTrue(policy.shouldAutoApprove(commandEvent(listOf(read))))
    }

    @Test
    fun `file request is shown when either native file tool requires approval`() {
        val policy = policy(overrides = mapOf(CODEX_EDIT_FILE_TOOL_NAME to true))

        assertFalse(policy.shouldAutoApprove(fileEvent()))
    }

    @Test
    fun `global auto approve and explicit never cover every Codex request`() {
        listOf(
            policy(globalAutoApprove = true),
            policy(appServerPolicy = CodexAppServerApprovalPolicy.NEVER),
        ).forEach { policy ->
            assertEquals(CodexAppServerApprovalPolicy.NEVER, policy.effectiveAppServerPolicy())
            assertTrue(policy.shouldAutoApprove(commandEvent()))
            assertTrue(policy.shouldAutoApprove(fileEvent()))
        }
    }

    private fun policy(
        appServerPolicy: CodexAppServerApprovalPolicy? = CodexAppServerApprovalPolicy.ON_REQUEST,
        overrides: Map<String, Boolean> = emptyMap(),
        globalAutoApprove: Boolean = false,
    ) = CodexApprovalIntegrationPolicy(appServerPolicy, overrides, globalAutoApprove)

    private fun commandEvent(actions: List<CodexAppServerCommandAction>? = emptyList()) =
        CodexAppServerApprovalEvent.CommandExecutionRequest(
            JsonRpcId.StringId("approval-command"),
            CodexAppServerCommandApprovalRequest(
                threadId = "thread",
                turnId = "turn",
                itemId = "command",
                startedAtMs = 1L,
                approvalId = null,
                environmentId = null,
                reason = null,
                command = "echo ok",
                cwd = "/workspace",
                commandActions = actions,
                networkApprovalContext = null,
                proposedExecpolicyAmendment = null,
                proposedNetworkPolicyAmendments = null,
                rawParams = buildJsonObject {},
            ),
        )

    private fun fileEvent() = CodexAppServerApprovalEvent.FileChangeRequest(
        JsonRpcId.StringId("approval-file"),
        CodexAppServerFileChangeApprovalRequest(
            threadId = "thread",
            turnId = "turn",
            itemId = "file",
            startedAtMs = 1L,
            reason = null,
            grantRoot = "/workspace",
            rawParams = buildJsonObject {},
        ),
    )
}
