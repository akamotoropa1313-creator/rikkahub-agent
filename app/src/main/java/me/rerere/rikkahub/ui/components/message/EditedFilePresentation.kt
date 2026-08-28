package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.codex.appserver.CODEX_FILE_CHANGE_STATUS_METADATA_KEY

private val WORKSPACE_FILE_TOOL_NAMES = setOf("workspace_write_file", "workspace_edit_file")

internal fun editedFileCandidates(parts: List<UIMessagePart>): List<String> =
    parts.filterIsInstance<UIMessagePart.Tool>()
        .filter { it.toolName in WORKSPACE_FILE_TOOL_NAMES && it.isExecuted }
        .filter { tool ->
            val status = (tool.metadata?.get(CODEX_FILE_CHANGE_STATUS_METADATA_KEY) as? JsonPrimitive)
                ?.contentOrNull
            status == null || status == "completed"
        }
        .filterNot(UIMessagePart.Tool::hasFailureEnvelope)
        .mapNotNull { tool ->
            val path = ((tool.inputAsJson() as? JsonObject)?.get("path") as? JsonPrimitive)
                ?.contentOrNull
            normalizeEditedFilePath(path)
        }
        .distinct()

internal fun editedFileDisplayLabel(path: String, allPaths: List<String>): String {
    val segments = path.trim('/').split('/').filter(String::isNotBlank)
    val fileName = segments.lastOrNull().orEmpty()
    val hasDuplicateName = allPaths.count { it.substringAfterLast('/') == fileName } > 1
    if (!hasDuplicateName) return fileName

    for (depth in 2..segments.size) {
        val suffix = segments.takeLast(depth).joinToString("/")
        val unique = allPaths.count { candidate ->
            candidate.trim('/').split('/').filter(String::isNotBlank)
                .takeLast(depth).joinToString("/") == suffix
        } == 1
        if (unique) return suffix
    }
    return path.removePrefix("/")
}

private fun normalizeEditedFilePath(path: String?): String? {
    val trimmed = path?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
    return if (trimmed.startsWith('/')) trimmed else "/workspace/$trimmed"
}

private fun UIMessagePart.Tool.hasFailureEnvelope(): Boolean = output
    .filterIsInstance<UIMessagePart.Text>()
    .any { part ->
        val value = runCatching { Json.parseToJsonElement(part.text) }.getOrNull() as? JsonObject
        value?.containsKey("error") == true
    }
