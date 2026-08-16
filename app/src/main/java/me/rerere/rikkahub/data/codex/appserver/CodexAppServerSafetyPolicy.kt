package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Stable App Server approval values. Experimental granular and deprecated aliases are excluded. */
enum class CodexAppServerApprovalPolicy(val wireValue: String) {
    UNTRUSTED("untrusted"), ON_REQUEST("on-request"), NEVER("never");

    companion object { fun fromPreference(value: String?) = entries.firstOrNull { it.wireValue == value } }
}

/** Stable public sandbox presets accepted by thread/start and thread/resume. */
enum class CodexAppServerSandboxMode(val wireValue: String) {
    READ_ONLY("read-only"), WORKSPACE_WRITE("workspace-write"), DANGER_FULL_ACCESS("danger-full-access");

    companion object { fun fromPreference(value: String?) = entries.firstOrNull { it.wireValue == value } }
}

/** Exact stable SandboxPolicy object used by turn/start. No custom fields are exposed. */
sealed interface CodexAppServerSandboxPolicy {
    fun toJson(): JsonObject
    data object ReadOnly : CodexAppServerSandboxPolicy {
        override fun toJson() = JsonObject(mapOf("type" to JsonPrimitive("readOnly")))
    }
    data object WorkspaceWrite : CodexAppServerSandboxPolicy {
        override fun toJson() = JsonObject(mapOf(
            "type" to JsonPrimitive("workspaceWrite"),
            "writableRoots" to JsonArray(emptyList()),
            "networkAccess" to JsonPrimitive(false),
            "excludeTmpdirEnvVar" to JsonPrimitive(false),
            "excludeSlashTmp" to JsonPrimitive(false),
        ))
    }
    data object DangerFullAccess : CodexAppServerSandboxPolicy {
        override fun toJson() = JsonObject(mapOf("type" to JsonPrimitive("dangerFullAccess")))
    }
}

fun CodexAppServerSandboxMode.toTurnPolicy(): CodexAppServerSandboxPolicy = when (this) {
    CodexAppServerSandboxMode.READ_ONLY -> CodexAppServerSandboxPolicy.ReadOnly
    CodexAppServerSandboxMode.WORKSPACE_WRITE -> CodexAppServerSandboxPolicy.WorkspaceWrite
    CodexAppServerSandboxMode.DANGER_FULL_ACCESS -> CodexAppServerSandboxPolicy.DangerFullAccess
}
