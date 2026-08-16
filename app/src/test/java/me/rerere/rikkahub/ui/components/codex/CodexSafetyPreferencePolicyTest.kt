package me.rerere.rikkahub.ui.components.codex

import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CodexSafetyPreferencePolicyTest {
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
            "Full access · No approvals",
            codexSafetyIndicator(
                base.copy(
                    codexSandboxMode = "danger-full-access",
                    codexApprovalPolicy = "never",
                ),
            ),
        )
        assertEquals(
            "Server safety setting · Reset clears sticky overrides",
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
