package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerSafetyPolicyTest {
    @Test fun pinnedRuntimeDialectAndPreferencesValidateWithoutUnsafeFallbacks() {
        assertEquals("0.146.0", CodexRuntimeManager.VALIDATED_VERSION)
        assertEquals(CodexAppServerSandboxMode.READ_ONLY, CodexAppServerSandboxMode.fromPreference("read-only"))
        assertEquals(CodexAppServerSandboxMode.WORKSPACE_WRITE, CodexAppServerSandboxMode.fromPreference("workspace-write"))
        assertEquals(CodexAppServerSandboxMode.DANGER_FULL_ACCESS, CodexAppServerSandboxMode.fromPreference("danger-full-access"))
        assertEquals("read-only", CodexAppServerSandboxMode.READ_ONLY.threadWireValue)
        assertEquals("workspace-write", CodexAppServerSandboxMode.WORKSPACE_WRITE.threadWireValue)
        assertEquals("danger-full-access", CodexAppServerSandboxMode.DANGER_FULL_ACCESS.threadWireValue)
        assertEquals("readOnly", CodexAppServerSandboxMode.READ_ONLY.turnPolicyType)
        assertEquals("workspaceWrite", CodexAppServerSandboxMode.WORKSPACE_WRITE.turnPolicyType)
        assertEquals("dangerFullAccess", CodexAppServerSandboxMode.DANGER_FULL_ACCESS.turnPolicyType)
        assertNull(CodexAppServerSandboxMode.fromPreference(null))
        assertNull(CodexAppServerSandboxMode.fromPreference("future"))
        assertEquals(CodexAppServerApprovalPolicy.UNTRUSTED, CodexAppServerApprovalPolicy.fromPreference("untrusted"))
        assertEquals(CodexAppServerApprovalPolicy.ON_REQUEST, CodexAppServerApprovalPolicy.fromPreference("on-request"))
        assertEquals(CodexAppServerApprovalPolicy.NEVER, CodexAppServerApprovalPolicy.fromPreference("never"))
        assertEquals("untrusted", CodexAppServerApprovalPolicy.UNTRUSTED.runtimeWireValue)
        assertEquals("on-request", CodexAppServerApprovalPolicy.ON_REQUEST.runtimeWireValue)
        assertTrue(CodexDiagnosticSandboxMode("workspaceWrite").known)
        assertTrue(CodexDiagnosticSandboxMode("workspace-write").known)
        assertFalse(CodexDiagnosticSandboxMode("future").known)
        listOf(null, "future", "on-failure", "unlessTrusted", "granular").forEach {
            assertNull(CodexAppServerApprovalPolicy.fromPreference(it))
        }
    }

    @Test fun missingPreferenceDefaultsToBoundWorkspaceWriteWithoutGrantingFullAccess() {
        assertEquals(CodexAppServerSandboxMode.WORKSPACE_WRITE, effectiveCodexSandboxMode(null))
        assertEquals(CodexAppServerSandboxMode.READ_ONLY, effectiveCodexSandboxMode("read-only"))
        assertEquals(CodexAppServerSandboxMode.WORKSPACE_WRITE, effectiveCodexSandboxMode("workspace-write"))
        assertEquals(CodexAppServerSandboxMode.DANGER_FULL_ACCESS, effectiveCodexSandboxMode("danger-full-access"))
        assertNull(effectiveCodexSandboxMode(CODEX_SANDBOX_SERVER_DEFAULT))
        assertNull(effectiveCodexSandboxMode("future"))
    }

    @Test fun publicPresetPoliciesHaveExactStableShape() {
        val readOnly = CodexAppServerSandboxMode.READ_ONLY.toTurnPolicy().toJson()
        assertEquals(setOf("type", "networkAccess"), readOnly.keys)
        assertEquals("readOnly", readOnly["type"]!!.jsonPrimitive.content)
        assertFalse(readOnly["networkAccess"]!!.jsonPrimitive.boolean)

        assertEquals(
            "{\"type\":\"dangerFullAccess\"}",
            CodexAppServerSandboxMode.DANGER_FULL_ACCESS.toTurnPolicy().toJson().toString(),
        )

        val workspace = CodexAppServerSandboxMode.WORKSPACE_WRITE.toTurnPolicy().toJson()
        assertEquals(
            setOf("type", "writableRoots", "networkAccess", "excludeTmpdirEnvVar", "excludeSlashTmp"),
            workspace.keys,
        )
        assertTrue(workspace["writableRoots"]!!.jsonArray.isEmpty())
        listOf("networkAccess", "excludeTmpdirEnvVar", "excludeSlashTmp").forEach {
            assertFalse(workspace[it]!!.jsonPrimitive.boolean)
        }
    }
}
