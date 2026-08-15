package me.rerere.rikkahub.data.codex.appserver

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface CodexMcpAuthStatus {
    val wireValue: String
    data object Unsupported : CodexMcpAuthStatus { override val wireValue = "unsupported" }
    data object UnknownStatus : CodexMcpAuthStatus { override val wireValue = "unknown" }
    data object NotLoggedIn : CodexMcpAuthStatus { override val wireValue = "notLoggedIn" }
    data object BearerToken : CodexMcpAuthStatus { override val wireValue = "bearerToken" }
    data object OAuth : CodexMcpAuthStatus { override val wireValue = "oAuth" }
    data class Unknown(override val wireValue: String) : CodexMcpAuthStatus
}

data class CodexMcpServerStatus(
    val name: String,
    val pluginId: String?,
    val authStatus: CodexMcpAuthStatus,
    val tools: JsonObject,
    val resources: JsonArray,
    val resourceTemplates: JsonArray,
    val serverInfo: JsonObject?,
    val raw: JsonObject,
)
sealed interface CodexMcpServerStatusDetail {
    val wireValue: String
    data object Full : CodexMcpServerStatusDetail { override val wireValue = "full" }
    data object ToolsAndAuthOnly : CodexMcpServerStatusDetail { override val wireValue = "toolsAndAuthOnly" }
}
enum class CodexMcpClientRegistration(val wireValue: String) { AUTO("auto"), CIMD("cimd"), DCR("dcr") }
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

sealed interface CodexAppServerMcpEvent {
    data class OAuthLoginCompleted(
        val name: String, val threadId: String?, val success: Boolean, val error: String?, val raw: JsonObject,
    ) : CodexAppServerMcpEvent
    data class ToolCallProgress(
        val threadId: String, val turnId: String, val itemId: String, val message: String, val raw: JsonObject,
    ) : CodexAppServerMcpEvent
    data class MalformedNotification(
        val method: String, val rawParams: JsonElement?, val cause: CodexAppServerMcpProtocolException,
    ) : CodexAppServerMcpEvent
}

/** Cold, stateless projection of the existing connection event stream. */
fun Flow<CodexAppServerEvent>.toCodexAppServerMcpEvents(): Flow<CodexAppServerMcpEvent> = mapNotNull { event ->
    if (event !is CodexAppServerEvent.UnknownNotification || event.method !in MCP_EVENT_METHODS) return@mapNotNull null
    try {
        val raw = event.params.mcpObject("${event.method} params")
        when (event.method) {
            MCP_OAUTH_COMPLETED -> CodexAppServerMcpEvent.OAuthLoginCompleted(
                raw.mcpString("name"), raw.mcpOptionalString("threadId"), raw.mcpBoolean("success"),
                raw.mcpOptionalString("error"), raw,
            )
            else -> CodexAppServerMcpEvent.ToolCallProgress(
                raw.mcpString("threadId"), raw.mcpString("turnId"), raw.mcpString("itemId"),
                raw.mcpString("message"), raw,
            )
        }
    } catch (cause: CodexAppServerMcpProtocolException) {
        CodexAppServerMcpEvent.MalformedNotification(event.method, event.params, cause)
    }
}

class CodexAppServerMcpApi(private val connection: CodexAppServerConnection) {
    val events = connection.events.toCodexAppServerMcpEvents()
    suspend fun listStatus(
        cursor: String? = null, limit: Int? = null, detail: CodexMcpServerStatusDetail? = null, threadId: String? = null,
        timeout: Duration = 30.seconds,
    ): CodexMcpServerStatusListResult {
        require(limit == null || limit > 0) { "limit must be positive" }
        val params = optionalParams("cursor" to cursor, "limit" to limit, "detail" to detail?.wireValue, "threadId" to threadId)
        val raw = connection.sendRequestAfterReady("mcpServerStatus/list", params, timeout).mcpObject("mcpServerStatus/list result")
        val entries = raw.mcpArray("data").mapIndexed { index, element ->
            val item = element.mcpObject("data[$index]")
            CodexMcpServerStatus(
                name = item.mcpString("name"), pluginId = item.mcpOptionalString("pluginId"),
                authStatus = item.mcpString("authStatus").toAuthStatus(),
                tools = item.mcpObjectField("tools"),
                resources = item.mcpArray("resources"),
                resourceTemplates = item.mcpArray("resourceTemplates"),
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
        name: String, threadId: String? = null, clientRegistration: CodexMcpClientRegistration? = null,
        scopes: List<String>? = null, timeoutSecs: Long? = null,
        timeout: Duration = 30.seconds,
    ): CodexMcpOAuthLoginResult {
        require(name.isNotBlank()) { "name must not be blank" }
        require(scopes == null || scopes.all { it.isNotBlank() }) { "scopes must not contain blanks" }
        require(timeoutSecs == null || timeoutSecs > 0) { "timeoutSecs must be positive" }
        val params = optionalParams(
            "name" to name, "threadId" to threadId, "clientRegistration" to clientRegistration?.wireValue,
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
private fun JsonElement?.mcpObject(label: String) = this as? JsonObject ?: throw CodexAppServerMcpProtocolException("$label must be an object")
private fun JsonObject.mcpArray(name: String) = this[name] as? JsonArray ?: throw CodexAppServerMcpProtocolException("$name must be an array")
private fun JsonObject.mcpObjectField(name: String) = this[name] as? JsonObject ?: throw CodexAppServerMcpProtocolException("$name must be an object")
private fun JsonObject.mcpString(name: String) = runCatching { string(name) }.getOrElse { throw CodexAppServerMcpProtocolException("$name must be a string") }
private fun JsonObject.mcpOptionalString(name: String): String? = if (this[name] == null || this[name].toString() == "null") null else mcpString(name)
private fun JsonObject.mcpBoolean(name: String) = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    ?: throw CodexAppServerMcpProtocolException("$name must be a boolean")
private fun String.toAuthStatus() = when (this) {
    "unknown" -> CodexMcpAuthStatus.UnknownStatus
    "unsupported" -> CodexMcpAuthStatus.Unsupported; "notLoggedIn" -> CodexMcpAuthStatus.NotLoggedIn
    "bearerToken" -> CodexMcpAuthStatus.BearerToken; "oAuth" -> CodexMcpAuthStatus.OAuth
    else -> CodexMcpAuthStatus.Unknown(this)
}
class CodexAppServerMcpProtocolException(message: String) : SerializationException(message)
private const val MCP_OAUTH_COMPLETED = "mcpServer/oauthLogin/completed"
private const val MCP_TOOL_PROGRESS = "item/mcpToolCall/progress"
private val MCP_EVENT_METHODS = setOf(MCP_OAUTH_COMPLETED, MCP_TOOL_PROGRESS)
