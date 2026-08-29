package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class CodexSkillMetadata(
    val name: String,
    val description: String,
    val shortDescription: String?,
    val path: String,
    val scope: String,
    val enabled: Boolean,
    val interfaceMetadata: JsonObject?,
    val dependencies: JsonObject?,
    val raw: JsonObject,
)
data class CodexSkillErrorInfo(val path: String, val message: String, val raw: JsonObject)
data class CodexSkillsListEntry(
    val cwd: String,
    val skills: List<CodexSkillMetadata>,
    val errors: List<CodexSkillErrorInfo>,
    val raw: JsonObject,
)
data class CodexSkillsListResult(val data: List<CodexSkillsListEntry>, val raw: JsonObject)
data class CodexSkillsConfigWriteResult(val effectiveEnabled: Boolean, val raw: JsonObject)

class CodexAppServerSkillsApi(private val connection: CodexAppServerConnection) {
    suspend fun replaceExtraRoots(
        extraRoots: List<String>,
        timeout: Duration = 30.seconds,
    ) {
        require(extraRoots.all { it.startsWith('/') && it.isNotBlank() }) {
            "extra skill roots must be absolute paths"
        }
        val params = JsonObject(
            mapOf("extraRoots" to JsonArray(extraRoots.distinct().map(::JsonPrimitive))),
        )
        connection.sendRequestAfterReady("skills/extraRoots/set", params, timeout)
            .skillObject("skills/extraRoots/set result")
    }

    suspend fun list(
        cwds: List<String> = emptyList(),
        forceReload: Boolean = false,
        timeout: Duration = 30.seconds,
    ): CodexSkillsListResult {
        require(cwds.all { it.isNotBlank() }) { "cwds must not contain blank paths" }
        val params = buildMap<String, JsonElement> {
            if (cwds.isNotEmpty()) put("cwds", JsonArray(cwds.map(::JsonPrimitive)))
            if (forceReload) put("forceReload", JsonPrimitive(true))
        }.let(::JsonObject)
        return decodeSkillsList(connection.sendRequestAfterReady("skills/list", params, timeout))
    }

    suspend fun writeConfig(
        enabled: Boolean,
        path: String? = null,
        name: String? = null,
        timeout: Duration = 30.seconds,
    ): CodexSkillsConfigWriteResult {
        require(!path.isNullOrBlank() || !name.isNullOrBlank()) { "path or name must be nonblank" }
        require(path == null || path.isNotBlank()) { "path must not be blank" }
        require(name == null || name.isNotBlank()) { "name must not be blank" }
        val params = buildMap<String, JsonElement> {
            path?.let { put("path", JsonPrimitive(it)) }
            name?.let { put("name", JsonPrimitive(it)) }
            put("enabled", JsonPrimitive(enabled))
        }.let(::JsonObject)
        val raw = connection.sendRequestAfterReady("skills/config/write", params, timeout).skillObject("skills/config/write result")
        return CodexSkillsConfigWriteResult(raw.skillBoolean("effectiveEnabled"), raw)
    }
}

private fun decodeSkillsList(value: JsonElement): CodexSkillsListResult {
    val raw = value.skillObject("skills/list result")
    val data = raw.skillArray("data").mapIndexed { index, value ->
        val entry = value.skillObject("data[$index]")
        CodexSkillsListEntry(
            cwd = entry.skillString("cwd"),
            skills = entry.skillArray("skills").mapIndexed { skillIndex, skillValue ->
                val skill = skillValue.skillObject("data[$index].skills[$skillIndex]")
                CodexSkillMetadata(
                    name = skill.skillString("name"), description = skill.skillString("description"),
                    shortDescription = skill.skillOptionalString("shortDescription"), path = skill.skillString("path"),
                    scope = skill.skillString("scope"), enabled = skill.skillBoolean("enabled"),
                    interfaceMetadata = skill["interface"] as? JsonObject,
                    dependencies = skill["dependencies"] as? JsonObject, raw = skill,
                )
            },
            errors = entry.skillArray("errors").mapIndexed { errorIndex, errorValue ->
                val error = errorValue.skillObject("data[$index].errors[$errorIndex]")
                CodexSkillErrorInfo(error.skillString("path"), error.skillString("message"), error)
            },
            raw = entry,
        )
    }
    return CodexSkillsListResult(data, raw)
}

private fun JsonElement.skillObject(label: String): JsonObject = this as? JsonObject
    ?: throw CodexAppServerSkillsProtocolException("$label must be an object")
private fun JsonObject.skillArray(name: String): JsonArray = this[name] as? JsonArray
    ?: throw CodexAppServerSkillsProtocolException("$name must be an array")
private fun JsonObject.skillString(name: String): String = (this[name] as? JsonPrimitive)
    ?.takeIf { it.isString }?.contentOrNull
    ?: throw CodexAppServerSkillsProtocolException("$name must be a string")
private fun JsonObject.skillOptionalString(name: String): String? = this[name]?.let {
    (it as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: throw CodexAppServerSkillsProtocolException("$name must be a string when present")
}
private fun JsonObject.skillBoolean(name: String): Boolean = (this[name] as? JsonPrimitive)
    ?.takeUnless { it.isString }?.booleanOrNull
    ?: throw CodexAppServerSkillsProtocolException("$name must be a boolean")
class CodexAppServerSkillsProtocolException(message: String) : SerializationException(message)
