package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface CodexMcpAuthStatus {
    val wireValue: String
    data object Unsupported : CodexMcpAuthStatus { override val wireValue = "unsupported" }
    data object NotLoggedIn : CodexMcpAuthStatus { override val wireValue = "notLoggedIn" }
    data object BearerToken : CodexMcpAuthStatus { override val wireValue = "bearerToken" }
    data object OAuth : CodexMcpAuthStatus { override val wireValue = "oAuth" }
    data class Unknown(override val wireValue: String) : CodexMcpAuthStatus
}

data class CodexMcpServerStatus(
    val name: String,
    val authStatus: CodexMcpAuthStatus,
    val tools: JsonObject,
    val resources: JsonArray,
    val resourceTemplates: JsonArray,
    val serverInfo: JsonObject?,
    val raw: JsonObject,
)
data class CodexMcpServerStatusListResult(
    val data: List<CodexMcpServerStatus>, val nextCursor: String?, val raw: JsonObject,
)
data class CodexMcpResourceContent(val raw: JsonObject)
data class CodexMcpResourceReadResult(val contents: List<CodexMcpResourceContent>, val raw: JsonObject)
data class CodexMcpToolCallResult(
    val content: List<JsonElement>, val structuredContent: JsonElement?, val isError: Boolean,
    val metadata: JsonElement?, val raw: JsonObject,
)

/** Authorization URL is transient and redacted from diagnostics. App Server owns the exchange. */
class CodexMcpOAuthLoginResult internal constructor(private val authorizationUrl: String) {
    fun authorizationUrlForLaunch(): String = authorizationUrl
    override fun toString() = "CodexMcpOAuthLoginResult(authorizationUrl=<redacted>)"
}

class CodexAppServerMcpApi(private val connection: CodexAppServerConnection) {
    suspend fun listStatus(
        cursor: String? = null, limit: Int? = null, detail: String? = null, threadId: String? = null,
        timeout: Duration = 30.seconds,
    ): CodexMcpServerStatusListResult {
        require(limit == null || limit > 0) { "limit must be positive" }
        val params = optionalParams("cursor" to cursor, "limit" to limit, "detail" to detail, "threadId" to threadId)
        val raw = connection.sendRequestAfterReady("mcpServerStatus/list", params, timeout).mcpObject("mcpServerStatus/list result")
        val entries = raw.mcpArray("data").mapIndexed { index, element ->
            val item = element.mcpObject("data[$index]")
            CodexMcpServerStatus(
                name = item.mcpString("name"), authStatus = item.mcpString("authStatus").toAuthStatus(),
                tools = item["tools"] as? JsonObject ?: JsonObject(emptyMap()),
                resources = item["resources"] as? JsonArray ?: JsonArray(emptyList()),
                resourceTemplates = item["resourceTemplates"] as? JsonArray ?: JsonArray(emptyList()),
                serverInfo = item["serverInfo"] as? JsonObject, raw = item,
            )
        }
        return CodexMcpServerStatusListResult(entries, raw.mcpOptionalString("nextCursor"), raw)
    }

    suspend fun reload(timeout: Duration = 30.seconds): JsonObject =
        connection.sendRequestAfterReady("config/mcpServer/reload", JsonObject(emptyMap()), timeout)
            .mcpObject("config/mcpServer/reload result")

    suspend fun readResource(server: String, uri: String, threadId: String? = null, timeout: Duration = 30.seconds): CodexMcpResourceReadResult {
        require(server.isNotBlank()) { "server must not be blank" }
        require(uri.isNotBlank()) { "uri must not be blank" }
        val raw = connection.sendRequestAfterReady(
            "mcpServer/resource/read", optionalParams("server" to server, "uri" to uri, "threadId" to threadId), timeout,
        ).mcpObject("mcpServer/resource/read result")
        return CodexMcpResourceReadResult(raw.mcpArray("contents").map { CodexMcpResourceContent(it.mcpObject("resource content")) }, raw)
    }

    suspend fun callTool(
        threadId: String, server: String, tool: String, arguments: JsonElement? = null,
        metadata: JsonElement? = null, timeout: Duration = 30.seconds,
    ): CodexMcpToolCallResult {
        require(threadId.isNotBlank()) { "threadId must not be blank" }
        require(server.isNotBlank()) { "server must not be blank" }
        require(tool.isNotBlank()) { "tool must not be blank" }
        val params = optionalParams("threadId" to threadId, "server" to server, "tool" to tool, "arguments" to arguments, "_meta" to metadata)
        val raw = connection.sendRequestAfterReady("mcpServer/tool/call", params, timeout).mcpObject("mcpServer/tool/call result")
        val error = (raw["isError"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false
        return CodexMcpToolCallResult(raw.mcpArray("content"), raw["structuredContent"], error, raw["_meta"], raw)
    }

    suspend fun beginOAuthLogin(
        name: String, threadId: String? = null, scopes: List<String>? = null, timeoutSecs: Long? = null,
        timeout: Duration = 30.seconds,
    ): CodexMcpOAuthLoginResult {
        require(name.isNotBlank()) { "name must not be blank" }
        require(scopes == null || scopes.all { it.isNotBlank() }) { "scopes must not contain blanks" }
        require(timeoutSecs == null || timeoutSecs > 0) { "timeoutSecs must be positive" }
        val params = optionalParams(
            "name" to name, "threadId" to threadId,
            "scopes" to scopes?.let { JsonArray(it.map(::JsonPrimitive)) }, "timeoutSecs" to timeoutSecs,
        )
        val raw = connection.sendRequestAfterReady("mcpServer/oauth/login", params, timeout).mcpObject("mcpServer/oauth/login result")
        return CodexMcpOAuthLoginResult(raw.mcpString("authorizationUrl").also { require(it.isNotBlank()) })
    }
}

private fun optionalParams(vararg values: Pair<String, Any?>): JsonObject = buildMap {
    values.forEach { (key, value) -> when (value) {
        null -> Unit
        is JsonElement -> put(key, value)
        is String -> put(key, JsonPrimitive(value))
        is Int -> put(key, JsonPrimitive(value))
        is Long -> put(key, JsonPrimitive(value))
        else -> error("unsupported JSON value")
    } }
}.let(::JsonObject)
private fun JsonElement.mcpObject(label: String) = this as? JsonObject ?: throw CodexAppServerMcpProtocolException("$label must be an object")
private fun JsonObject.mcpArray(name: String) = this[name] as? JsonArray ?: throw CodexAppServerMcpProtocolException("$name must be an array")
private fun JsonObject.mcpString(name: String) = runCatching { string(name) }.getOrElse { throw CodexAppServerMcpProtocolException("$name must be a string") }
private fun JsonObject.mcpOptionalString(name: String): String? = if (this[name] == null || this[name].toString() == "null") null else mcpString(name)
private fun String.toAuthStatus() = when (this) {
    "unsupported" -> CodexMcpAuthStatus.Unsupported; "notLoggedIn" -> CodexMcpAuthStatus.NotLoggedIn
    "bearerToken" -> CodexMcpAuthStatus.BearerToken; "oAuth" -> CodexMcpAuthStatus.OAuth
    else -> CodexMcpAuthStatus.Unknown(this)
}
class CodexAppServerMcpProtocolException(message: String) : SerializationException(message)
