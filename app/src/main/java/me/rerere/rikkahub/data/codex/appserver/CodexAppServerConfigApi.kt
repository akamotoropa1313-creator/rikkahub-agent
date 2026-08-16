package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A deliberately small, non-secret projection of config/read. Never retain the source JSON. */
data class CodexEffectiveConfigSnapshot(
    val model: String? = null,
    val modelContextWindow: Long? = null,
    val modelAutoCompactTokenLimit: Long? = null,
    val modelProvider: String? = null,
    val sandboxMode: CodexDiagnosticSandboxMode? = null,
    val sandboxWorkspaceWrite: CodexSandboxWorkspaceWriteSnapshot? = null,
    val forcedLoginMethod: String? = null,
    val webSearch: String? = null,
    val modelReasoningEffort: String? = null,
    val modelReasoningSummary: String? = null,
    val modelVerbosity: String? = null,
    val serviceTier: String? = null,
    val analyticsEnabled: Boolean? = null,
    val origins: Map<String, CodexConfigOrigin> = emptyMap(),
    val threadAgnostic: Boolean = false,
)

data class CodexDiagnosticSandboxMode(val wireValue: String) {
    val known: Boolean get() = wireValue in setOf("read-only", "workspace-write", "danger-full-access")
}

data class CodexSandboxWorkspaceWriteSnapshot(
    val networkAccess: Boolean? = null,
    val excludeTmpdirEnvVar: Boolean? = null,
    val excludeSlashTmp: Boolean? = null,
    val writableRootsCount: Int? = null,
)

enum class CodexConfigLayerSource {
    MDM, SYSTEM, ENTERPRISE_MANAGED, USER, PROJECT, SESSION_FLAGS,
    LEGACY_MANAGED_FILE, LEGACY_MANAGED_MDM, PACKAGED_DEFAULTS, UNKNOWN;

    val label: String get() = when (this) {
        MDM -> "Managed preferences"
        SYSTEM -> "System config"
        ENTERPRISE_MANAGED -> "Enterprise managed"
        USER -> "User config"
        PROJECT -> "Project .codex"
        SESSION_FLAGS -> "Session override"
        LEGACY_MANAGED_FILE, LEGACY_MANAGED_MDM -> "Legacy managed config"
        PACKAGED_DEFAULTS -> "Packaged defaults"
        UNKNOWN -> "Unknown"
    }
}

data class CodexConfigOrigin(val source: CodexConfigLayerSource)

data class CodexConfigRequirementsSnapshot(
    val allowedSandboxModes: List<CodexDiagnosticSandboxMode>? = null,
    val allowedWebSearchModes: List<String>? = null,
    val featureRequirements: Map<String, Boolean>? = null,
    val newThread: CodexManagedNewThreadDefaults? = null,
)

data class CodexManagedNewThreadDefaults(
    val model: String? = null,
    val modelReasoningEffort: String? = null,
    val serviceTier: String? = null,
)

data class CodexConfigDiagnosticsResult(
    val config: Result<CodexEffectiveConfigSnapshot>,
    /** A successful null means the server explicitly reported no managed requirements. */
    val requirements: Result<CodexConfigRequirementsSnapshot?>,
)

class CodexAppServerConfigProtocolException(message: String) : SerializationException(message)

/** Stable read-only configuration diagnostics over the conversation-owned connection. */
class CodexAppServerConfigApi(private val connection: CodexAppServerConnection) {
    suspend fun readConfig(absoluteCwd: String?, timeout: Duration = 30.seconds): CodexEffectiveConfigSnapshot {
        require(absoluteCwd == null || absoluteCwd.startsWith('/')) { "config/read cwd must be absolute" }
        val params = buildJsonObject { put("cwd", absoluteCwd?.let(::JsonPrimitive) ?: JsonNull) }
        return decodeConfig(connection.sendRequestAfterReady("config/read", params, timeout), absoluteCwd == null)
    }

    suspend fun readRequirements(timeout: Duration = 30.seconds): CodexConfigRequirementsSnapshot? =
        decodeRequirements(connection.sendRequestAfterReady("configRequirements/read", null, timeout))

    suspend fun readDiagnostics(absoluteCwd: String?): CodexConfigDiagnosticsResult = supervisorScope {
        val config = async { runCatching { readConfig(absoluteCwd) } }
        val requirements = async { runCatching { readRequirements() } }
        CodexConfigDiagnosticsResult(config.await(), requirements.await())
    }

    internal fun decodeConfig(value: JsonElement, threadAgnostic: Boolean = false): CodexEffectiveConfigSnapshot {
        val outer = value.objectAt("config/read result")
        val config = outer.requiredObject("config")
        fun string(name: String) = config.optionalString(name)
        fun long(name: String) = config.optionalLong(name)
        val workspace = config.optionalObject("sandbox_workspace_write")?.let { item ->
            CodexSandboxWorkspaceWriteSnapshot(
                item.optionalBoolean("network_access"), item.optionalBoolean("exclude_tmpdir_env_var"),
                item.optionalBoolean("exclude_slash_tmp"), item.optionalArray("writable_roots")?.size,
            )
        }
        val origins = outer.optionalObject("origins")?.mapNotNull { (key, metadata) ->
            if (key !in SAFE_ORIGINS) null else key to CodexConfigOrigin(decodeSource(metadata))
        }?.toMap().orEmpty()
        // layers is intentionally never inspected: a misbehaving server cannot leak raw layers.
        return CodexEffectiveConfigSnapshot(
            string("model"), long("model_context_window"), long("model_auto_compact_token_limit"),
            string("model_provider"), string("sandbox_mode")?.let(::CodexDiagnosticSandboxMode), workspace,
            string("forced_login_method"), string("web_search"), string("model_reasoning_effort"),
            string("model_reasoning_summary"), string("model_verbosity"), string("service_tier"),
            config.optionalObject("analytics")?.optionalBoolean("enabled"), origins, threadAgnostic,
        )
    }

    internal fun decodeRequirements(value: JsonElement): CodexConfigRequirementsSnapshot? {
        val outer = value.objectAt("configRequirements/read result")
        val element = outer["requirements"] ?: fail("requirements must be present")
        if (element is JsonNull) return null
        val req = element.objectAt("requirements")
        val models = req.optionalObject("models")
        val newThread = models?.optionalObject("newThread")
        return CodexConfigRequirementsSnapshot(
            req.optionalStringArray("allowedSandboxModes")?.map(::CodexDiagnosticSandboxMode),
            req.optionalStringArray("allowedWebSearchModes"),
            req.optionalObject("featureRequirements")?.mapValues { (key, item) -> item.strictBoolean("featureRequirements.$key") },
            newThread?.let { CodexManagedNewThreadDefaults(it.optionalString("model"), it.optionalString("modelReasoningEffort"), it.optionalString("serviceTier")) },
        )
    }

    private fun decodeSource(metadata: JsonElement): CodexConfigLayerSource {
        val objectValue = metadata as? JsonObject ?: return CodexConfigLayerSource.UNKNOWN
        val sourceElement = objectValue["source"] ?: metadata
        val source = sourceElement as? JsonObject
        val raw = (sourceElement as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: source?.optionalString("type") ?: source?.optionalString("kind")
            ?: objectValue.optionalString("type") ?: objectValue.optionalString("kind")
            ?: return CodexConfigLayerSource.UNKNOWN
        return when (raw.replace("_", "").replace("-", "").lowercase()) {
            "mdm" -> CodexConfigLayerSource.MDM
            "system" -> CodexConfigLayerSource.SYSTEM
            "enterprisemanaged" -> CodexConfigLayerSource.ENTERPRISE_MANAGED
            "user" -> CodexConfigLayerSource.USER
            "project" -> CodexConfigLayerSource.PROJECT
            "sessionflags" -> CodexConfigLayerSource.SESSION_FLAGS
            "legacymanagedconfigtomlfromfile" -> CodexConfigLayerSource.LEGACY_MANAGED_FILE
            "legacymanagedconfigtomlfrommdm" -> CodexConfigLayerSource.LEGACY_MANAGED_MDM
            "packageddefaults" -> CodexConfigLayerSource.PACKAGED_DEFAULTS
            else -> CodexConfigLayerSource.UNKNOWN
        }
    }

    private fun fail(message: String): Nothing = throw CodexAppServerConfigProtocolException(message)
    private fun JsonElement.objectAt(name: String) = this as? JsonObject ?: fail("$name must be an object")
    private fun JsonObject.requiredObject(name: String) = this[name]?.objectAt(name) ?: fail("$name must be present")
    private fun JsonObject.optionalObject(name: String) = optional(name) { it.objectAt(name) }
    private fun JsonObject.optionalArray(name: String) = optional(name) { it as? JsonArray ?: fail("$name must be an array") }
    private fun JsonObject.optionalString(name: String) = optional(name) { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: fail("$name must be a string") }
    private fun JsonObject.optionalLong(name: String) = optional(name) { primitive ->
        val p = primitive as? JsonPrimitive ?: fail("$name must be an integer")
        if (p.isString || p.booleanOrNull != null) fail("$name must be an integer")
        p.longOrNull ?: fail("$name must be an integer")
    }
    private fun JsonObject.optionalBoolean(name: String) = optional(name) { it.strictBoolean(name) }
    private fun JsonElement.strictBoolean(name: String) = (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull ?: fail("$name must be a boolean")
    private fun JsonObject.optionalStringArray(name: String) = optionalArray(name)?.mapIndexed { i, it -> (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: fail("$name[$i] must be a string") }
    private inline fun <T> JsonObject.optional(name: String, decode: (JsonElement) -> T): T? = this[name]?.takeUnless { it is JsonNull }?.let(decode)

    private companion object {
        val SAFE_ORIGINS = setOf(
            "model", "model_context_window", "model_auto_compact_token_limit", "model_provider", "sandbox_mode",
            "sandbox_workspace_write.network_access", "web_search", "model_reasoning_effort", "model_reasoning_summary",
            "model_verbosity", "service_tier", "analytics.enabled",
        )
    }
}

/** Pure workspace-namespace resolver shared by process setup and config/read. */
fun resolveCodexEffectiveCwd(workspaceRoot: String, workspaceCwd: String): String? {
    require(!workspaceCwd.startsWith('/') && !workspaceCwd.startsWith('\\')) { "workspaceCwd must be relative" }
    require(workspaceCwd.split('/', '\\').none { it == ".." }) { "workspaceCwd must stay inside the workspace" }
    if (!workspaceRoot.startsWith('/')) return null
    return if (workspaceCwd.isBlank() || workspaceCwd == ".") workspaceRoot.trimEnd('/').ifEmpty { "/" }
    else workspaceRoot.trimEnd('/') + "/" + workspaceCwd.trimStart('/')
}
