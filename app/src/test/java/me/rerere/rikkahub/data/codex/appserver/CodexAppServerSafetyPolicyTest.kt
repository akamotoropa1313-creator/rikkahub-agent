package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CodexAppServerSafetyPolicyTest {
    @Test fun stablePreferencesValidateWithoutUnsafeFallbacks() {
        assertEquals(CodexAppServerSandboxMode.READ_ONLY, CodexAppServerSandboxMode.fromPreference("read-only"))
        assertEquals(CodexAppServerSandboxMode.WORKSPACE_WRITE, CodexAppServerSandboxMode.fromPreference("workspace-write"))
        assertEquals(CodexAppServerSandboxMode.DANGER_FULL_ACCESS, CodexAppServerSandboxMode.fromPreference("danger-full-access"))
        assertNull(CodexAppServerSandboxMode.fromPreference(null)); assertNull(CodexAppServerSandboxMode.fromPreference("future"))
        assertEquals(CodexAppServerApprovalPolicy.UNTRUSTED, CodexAppServerApprovalPolicy.fromPreference("untrusted"))
        assertEquals(CodexAppServerApprovalPolicy.ON_REQUEST, CodexAppServerApprovalPolicy.fromPreference("on-request"))
        assertEquals(CodexAppServerApprovalPolicy.NEVER, CodexAppServerApprovalPolicy.fromPreference("never"))
        listOf(null, "future", "on-failure", "unlessTrusted", "granular").forEach { assertNull(CodexAppServerApprovalPolicy.fromPreference(it)) }
    }

    @Test fun publicPresetPoliciesHaveExactStableShape() {
        assertEquals("{\"type\":\"readOnly\"}", CodexAppServerSandboxMode.READ_ONLY.toTurnPolicy().toJson().toString())
        assertEquals("{\"type\":\"dangerFullAccess\"}", CodexAppServerSandboxMode.DANGER_FULL_ACCESS.toTurnPolicy().toJson().toString())
        val workspace = CodexAppServerSandboxMode.WORKSPACE_WRITE.toTurnPolicy().toJson()
        assertEquals(setOf("type", "writableRoots", "networkAccess", "excludeTmpdirEnvVar", "excludeSlashTmp"), workspace.keys)
        assertTrue(workspace["writableRoots"]!!.jsonArray.isEmpty())
        listOf("networkAccess", "excludeTmpdirEnvVar", "excludeSlashTmp").forEach { assertFalse(workspace[it]!!.jsonPrimitive.boolean) }
    }
}
