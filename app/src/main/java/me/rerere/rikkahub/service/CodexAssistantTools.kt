package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerDynamicToolSpec
import me.rerere.rikkahub.data.codex.appserver.codexDynamicToolsFingerprint

/** The exact RikkaHub tool surface attached to one durable Codex thread. */
internal class CodexAssistantToolProfile private constructor(
    val specs: List<CodexAppServerDynamicToolSpec>,
    private val toolsByName: Map<String, Tool>,
    val fingerprint: String?,
    private val executionIdentity: String?,
) {
    fun tool(name: String): Tool? = toolsByName[name]

    override fun equals(other: Any?): Boolean =
        other is CodexAssistantToolProfile && fingerprint == other.fingerprint && executionIdentity == other.executionIdentity

    override fun hashCode(): Int = 31 * fingerprint.hashCode() + executionIdentity.hashCode()

    companion object {
        val EMPTY = CodexAssistantToolProfile(emptyList(), emptyMap(), null, null)

        fun from(tools: List<Tool>, executionIdentity: String? = null): CodexAssistantToolProfile {
            if (tools.isEmpty() && executionIdentity == null) return EMPTY
            val duplicates = tools.groupingBy(Tool::name).eachCount().filterValues { it > 1 }.keys
            require(duplicates.isEmpty()) {
                "Duplicate RikkaHub tool names cannot be attached to Codex: ${duplicates.sorted().joinToString()}"
            }
            val ordered = tools.sortedBy(Tool::name)
            val specs = ordered.map { tool ->
                CodexAppServerDynamicToolSpec(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = tool.parameters().toCodexJsonSchema(),
                )
            }
            val hash = codexDynamicToolsFingerprint(specs)
            return CodexAssistantToolProfile(specs, ordered.associateBy(Tool::name), hash, executionIdentity)
        }
    }
}

private fun InputSchema?.toCodexJsonSchema(): JsonElement = when (this) {
    null -> buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap()))
    }
    is InputSchema.Obj -> buildJsonObject {
        put("type", "object")
        put("properties", properties)
        required?.let { values ->
            putJsonArray("required") { values.forEach(::add) }
        }
    }
}
