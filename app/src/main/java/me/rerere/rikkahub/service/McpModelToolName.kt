package me.rerere.rikkahub.service

import java.security.MessageDigest

private val MCP_MODEL_TOOL_NAME = Regex("^[A-Za-z0-9_-]{1,64}$")

/** Stable App-Server-safe name for an MCP tool whose server/tool labels are user supplied. */
internal fun mcpModelToolName(serverId: String, serverName: String, toolName: String): String {
    val serverSlug = serverId.take(8).replace("-", "")
    val candidate = "mcp__${serverSlug}_${serverName}__${toolName}"
    if (MCP_MODEL_TOOL_NAME.matches(candidate)) return candidate
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(candidate.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .take(10)
    val suffix = "_$digest"
    val stem = candidate.map { character ->
        if (character.isAsciiModelToolCharacter()) character else '_'
    }.joinToString("").take(64 - suffix.length).trimEnd('_').ifBlank { "mcp" }
    return (stem + suffix).take(64)
}

private fun Char.isAsciiModelToolCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-'
