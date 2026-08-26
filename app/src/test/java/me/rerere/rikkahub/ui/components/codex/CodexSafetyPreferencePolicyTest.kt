package me.rerere.rikkahub.ui.components.codex

import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CodexSafetyPreferencePolicyTest {
    @Test
    fun `managed sandbox diagnostics warn without mutating preferences`() {
        val restricted = me.rerere.rikkahub.data.codex.appserver.CodexConfigRequirementsSnapshot(
            allowedSandboxModes = listOf(
                me.rerere.rikkahub.data.codex.appserver.CodexDiagnosticSandboxMode("readOnly"),
                me.rerere.rikkahub.data.codex.appserver.CodexDiagnosticSandboxMode("workspaceWrite"),
            ),
        )
        assertNull(managedSandboxWarning("danger-full-access", false, restricted))
        assertNull(managedSandboxWarning("danger-full-access", true, null))
        assertNull(managedSandboxWarning("danger-full-access", true, me.rerere.rikkahub.data.codex.appserver.CodexConfigRequirementsSnapshot()))
        assertNull(managedSandboxWarning("workspace-write", true, restricted))
        assertNotNull(managedSandboxWarning("danger-full-access", true, restricted))
        assertNull(managedSandboxWarning(null, true, restricted))
        assertNotNull(
            managedSandboxWarning(
                null,
                true,
                me.rerere.rikkahub.data.codex.appserver.CodexConfigRequirementsSnapshot(
                    allowedSandboxModes = listOf(
                        me.rerere.rikkahub.data.codex.appserver.CodexDiagnosticSandboxMode("readOnly"),
                    ),
                ),
            ),
        )
        assertNull(managedSandboxWarning("server-default", true, restricted))
        assertNull(managedSandboxWarning("future-mode", true, restricted))
    }
    @Test fun warningAndConfirmationStatesArePure() {
        val base = Assistant()
        assertEquals(
            CodexSafetyConfirmation.FULL_ACCESS,
            codexSafetyConfirmation(base, sandbox = "danger-full-access"),
        )
        assertEquals(
            CodexSafetyConfirmation.NEVER,
            codexSafetyConfirmation(base, approval = "never"),
        )
        assertEquals(
            CodexSafetyConfirmation.CRITICAL,
            codexSafetyConfirmation(base, "danger-full-access", "never"),
        )
        assertEquals(
            "フルアクセス · 承認確認なし",
            codexSafetyIndicator(
                base.copy(
                    codexSandboxMode = "danger-full-access",
                    codexApprovalPolicy = "never",
                ),
            ),
        )
        assertEquals(
            "承認はApp Server設定 · 固定された上書きの解除にはリセットが必要です",
            codexSafetyIndicator(base),
        )
        assertNull(
            codexSafetyIndicator(
                base.copy(codexSandboxMode = "workspace-write", codexApprovalPolicy = "on-request"),
            ),
        )
        assertEquals(
            "サンドボックスはApp Server設定 · 固定された上書きの解除にはリセットが必要です",
            codexSafetyIndicator(
                base.copy(codexSandboxMode = "server-default", codexApprovalPolicy = "on-request"),
            ),
        )
        assertNull(
            codexSafetyIndicator(
                base.copy(codexApprovalPolicy = "on-request"),
            ),
        )
        org.junit.Assert.assertTrue(codexSandboxKnown("server-default"))
        assertFalse(codexSandboxKnown("future"))
        assertFalse(codexApprovalKnown("future"))
    }
}
