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
    suspend fun list(
        cwds: List<String>,
        forceReload: Boolean,
        timeout: Duration = 30.seconds,
    ): CodexSkillsListResult {
        require(cwds.all { it.isNotBlank() }) { "cwds must not contain blank paths" }
        val params = JsonObject(mapOf(
            "cwds" to JsonArray(cwds.map(::JsonPrimitive)),
            "forceReload" to JsonPrimitive(forceReload),
        ))
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
        val raw = connection.sendRequestAfterReady("skills/config/write", params, timeout).objectValue("skills/config/write result")
        return CodexSkillsConfigWriteResult(raw.boolean("effectiveEnabled"), raw)
    }
}

private fun decodeSkillsList(value: JsonElement): CodexSkillsListResult {
    val raw = value.objectValue("skills/list result")
    val data = raw.array("data").mapIndexed { index, value ->
        val entry = value.objectValue("data[$index]")
        CodexSkillsListEntry(
            cwd = entry.string("cwd"),
            skills = entry.array("skills").mapIndexed { skillIndex, skillValue ->
                val skill = skillValue.objectValue("data[$index].skills[$skillIndex]")
                CodexSkillMetadata(
                    name = skill.string("name"), description = skill.string("description"),
                    shortDescription = skill.optionalString("shortDescription"), path = skill.string("path"),
                    scope = skill.string("scope"), enabled = skill.boolean("enabled"),
                    interfaceMetadata = skill["interface"] as? JsonObject,
                    dependencies = skill["dependencies"] as? JsonObject, raw = skill,
                )
            },
            errors = entry.array("errors").mapIndexed { errorIndex, errorValue ->
                val error = errorValue.objectValue("data[$index].errors[$errorIndex]")
                CodexSkillErrorInfo(error.string("path"), error.string("message"), error)
            },
            raw = entry,
        )
    }
    return CodexSkillsListResult(data, raw)
}

internal fun JsonElement.objectValue(label: String): JsonObject = this as? JsonObject
    ?: throw CodexAppServerSkillsProtocolException("$label must be an object")
internal fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray
    ?: throw CodexAppServerSkillsProtocolException("$name must be an array")
internal fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)
    ?.takeIf { it.isString }?.contentOrNull
    ?: throw CodexAppServerSkillsProtocolException("$name must be a string")
internal fun JsonObject.optionalString(name: String): String? = this[name]?.let {
    (it as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: throw CodexAppServerSkillsProtocolException("$name must be a string when present")
}
internal fun JsonObject.boolean(name: String): Boolean = (this[name] as? JsonPrimitive)
    ?.takeUnless { it.isString }?.booleanOrNull
    ?: throw CodexAppServerSkillsProtocolException("$name must be a boolean")
class CodexAppServerSkillsProtocolException(message: String) : SerializationException(message)
