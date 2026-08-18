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
                me.rerere.rikkahub.data.codex.appserver.CodexDiagnosticSandboxMode("read-only"),
                me.rerere.rikkahub.data.codex.appserver.CodexDiagnosticSandboxMode("workspace-write"),
            ),
        )
        assertNull(managedSandboxWarning("danger-full-access", false, restricted))
        assertNull(managedSandboxWarning("danger-full-access", true, null))
        assertNull(managedSandboxWarning("danger-full-access", true, me.rerere.rikkahub.data.codex.appserver.CodexConfigRequirementsSnapshot()))
        assertNull(managedSandboxWarning("workspace-write", true, restricted))
        assertNotNull(managedSandboxWarning("danger-full-access", true, restricted))
        assertNull(managedSandboxWarning(null, true, restricted))
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
            "サーバーの安全設定 · リセットすると固定された上書き設定を解除します",
            codexSafetyIndicator(base),
        )
        assertNull(
            codexSafetyIndicator(
                base.copy(codexSandboxMode = "workspace-write", codexApprovalPolicy = "on-request"),
            ),
        )
        assertFalse(codexSandboxKnown("future"))
        assertFalse(codexApprovalKnown("future"))
    }
}
