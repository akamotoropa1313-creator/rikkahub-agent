package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Stable App Server approval values. UI persistence stays compatible with the CLI spelling. */
enum class CodexAppServerApprovalPolicy(val preferenceValue: String, val wireValue: String) {
    UNTRUSTED("untrusted", "unlessTrusted"),
    ON_REQUEST("on-request", "onRequest"),
    NEVER("never", "never");

    companion object { fun fromPreference(value: String?) = entries.firstOrNull { it.preferenceValue == value } }
}

/** Stable public sandbox presets with separate persisted and App Server protocol spellings. */
enum class CodexAppServerSandboxMode(val preferenceValue: String, val wireValue: String) {
    READ_ONLY("read-only", "readOnly"),
    WORKSPACE_WRITE("workspace-write", "workspaceWrite"),
    DANGER_FULL_ACCESS("danger-full-access", "dangerFullAccess");

    fun matchesServerValue(value: String) = value == wireValue || value == preferenceValue

    companion object { fun fromPreference(value: String?) = entries.firstOrNull { it.preferenceValue == value } }
}

/** Local preference sentinel that deliberately leaves the App Server sandbox override unset. */
const val CODEX_SANDBOX_SERVER_DEFAULT = "server-default"

/**
 * Resolve the saved UI preference into the policy sent to App Server.
 *
 * A missing preference comes from assistants created before the Codex controls existed. RikkaHub
 * binds Codex to an isolated Workspace whose purpose is file work, so that compatibility default
 * must be Workspace write rather than silently inheriting a read-only App Server default. Users
 * who deliberately want no override can still select [CODEX_SANDBOX_SERVER_DEFAULT]. Unknown
 * future values remain omitted rather than being guessed.
 */
fun effectiveCodexSandboxMode(value: String?): CodexAppServerSandboxMode? = when (value) {
    null -> CodexAppServerSandboxMode.WORKSPACE_WRITE
    CODEX_SANDBOX_SERVER_DEFAULT -> null
    else -> CodexAppServerSandboxMode.fromPreference(value)
}

/** Exact stable SandboxPolicy object used by turn/start. No custom fields are exposed. */
sealed interface CodexAppServerSandboxPolicy {
    fun toJson(): JsonObject

    data object ReadOnly : CodexAppServerSandboxPolicy {
        override fun toJson() = JsonObject(
            mapOf(
                "type" to JsonPrimitive("readOnly"),
                "networkAccess" to JsonPrimitive(false),
            ),
        )
    }

    data object WorkspaceWrite : CodexAppServerSandboxPolicy {
        override fun toJson() = JsonObject(
            mapOf(
                "type" to JsonPrimitive("workspaceWrite"),
                "writableRoots" to JsonArray(emptyList()),
                "networkAccess" to JsonPrimitive(false),
                "excludeTmpdirEnvVar" to JsonPrimitive(false),
                "excludeSlashTmp" to JsonPrimitive(false),
            ),
        )
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
