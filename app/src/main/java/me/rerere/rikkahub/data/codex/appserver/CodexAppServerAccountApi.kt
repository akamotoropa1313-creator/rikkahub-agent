package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

sealed interface CodexAppServerPlanType {
    val displayName: String
    data object Free : CodexAppServerPlanType { override val displayName = "Free" }
    data object Go : CodexAppServerPlanType { override val displayName = "Go" }
    data object Plus : CodexAppServerPlanType { override val displayName = "Plus" }
    data object Pro : CodexAppServerPlanType { override val displayName = "Pro" }
    data object ProLite : CodexAppServerPlanType { override val displayName = "Pro Lite" }
    data object Team : CodexAppServerPlanType { override val displayName = "Team" }
    data object SelfServeBusinessProLite : CodexAppServerPlanType { override val displayName = "Self Serve Business Pro Lite" }
    data object SelfServeBusinessUsageBased : CodexAppServerPlanType { override val displayName = "Self Serve Business Usage Based" }
    data object Business : CodexAppServerPlanType { override val displayName = "Business" }
    data object Ent26 : CodexAppServerPlanType { override val displayName = "Enterprise" }
    data object EnterpriseCbpAutomation : CodexAppServerPlanType { override val displayName = "Enterprise CBP Automation" }
    data object EnterpriseCbpUsageBased : CodexAppServerPlanType { override val displayName = "Enterprise CBP Usage Based" }
    data object Enterprise : CodexAppServerPlanType { override val displayName = "Enterprise" }
    data object Edu : CodexAppServerPlanType { override val displayName = "Edu" }
    data class Unknown(val raw: String) : CodexAppServerPlanType { override val displayName = raw }
}

sealed interface CodexAppServerAccount {
    val raw: JsonObject
    data class ApiKey(override val raw: JsonObject) : CodexAppServerAccount
    data class ChatGpt(val email: String?, val planType: CodexAppServerPlanType, override val raw: JsonObject) : CodexAppServerAccount
    data class AmazonBedrock(val usesCodexManagedCredentials: Boolean, override val raw: JsonObject) : CodexAppServerAccount
    data class Unknown(val type: String?, override val raw: JsonObject) : CodexAppServerAccount
}

data class CodexAppServerAccountSnapshot(val account: CodexAppServerAccount?, val requiresOpenaiAuth: Boolean, val raw: JsonObject)

data class CodexAppServerExternalChatGptTokens(
    val sourceAccountId: String,
    val accessToken: String,
    val chatgptAccountId: String,
    val chatgptPlanType: String? = null,
) {
    init {
        require(sourceAccountId.isNotBlank()) { "sourceAccountId must not be blank" }
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        require(chatgptAccountId.isNotBlank()) { "chatgptAccountId must not be blank" }
    }

    override fun toString(): String =
        "CodexAppServerExternalChatGptTokens(sourceAccountId=<redacted>, accessToken=<redacted>, chatgptAccountId=<redacted>, chatgptPlanType=$chatgptPlanType)"
}

data class CodexAppServerChatGptAuthTokensRefreshRequest(
    val requestId: JsonRpcId,
    val reason: String,
    val previousAccountId: String?,
)

/** The URL is intentionally omitted from [toString]; this value must remain transient. */
class CodexAppServerChatGptLoginStart internal constructor(
    val loginId: String,
    private val sensitiveAuthUrl: String,
) {
    fun authUrlForLaunch(): String = sensitiveAuthUrl
    override fun toString(): String = "CodexAppServerChatGptLoginStart(loginId=$loginId, authUrl=<redacted>)"
}

sealed interface CodexAppServerCancelLoginResult {
    data object Canceled : CodexAppServerCancelLoginResult
    data object NotFound : CodexAppServerCancelLoginResult
    data class Unknown(val raw: String) : CodexAppServerCancelLoginResult
}

sealed interface CodexAppServerAuthMode {
    data object ApiKey : CodexAppServerAuthMode
    data object ChatGpt : CodexAppServerAuthMode
    data object ChatGptAuthTokens : CodexAppServerAuthMode
    data object Headers : CodexAppServerAuthMode
    data object AgentIdentity : CodexAppServerAuthMode
    data object PersonalAccessToken : CodexAppServerAuthMode
    data object BedrockApiKey : CodexAppServerAuthMode
    data class Unknown(val raw: String) : CodexAppServerAuthMode
}

sealed interface CodexAppServerAccountEvent {
    data class LoginCompleted(
        val loginId: String?, val success: Boolean, val error: String?,
        val onboardingEntrypoint: JsonElement?, val rawParams: JsonObject,
    ) : CodexAppServerAccountEvent
    data class Updated(
        val authMode: CodexAppServerAuthMode?, val planType: CodexAppServerPlanType?, val rawParams: JsonObject,
    ) : CodexAppServerAccountEvent
    data class MalformedNotification(
        val method: String, val rawParams: JsonElement?, val cause: CodexAppServerAccountProtocolException,
    ) : CodexAppServerAccountEvent
}

/** Cold projection: collecting this flow is the only subscription it creates. */
fun Flow<CodexAppServerEvent>.toCodexAppServerAccountEvents(): Flow<CodexAppServerAccountEvent> = mapNotNull { event ->
    if (event !is CodexAppServerEvent.UnknownNotification || event.method !in ACCOUNT_EVENT_METHODS) return@mapNotNull null
    try {
        val raw = event.params.requiredObject("${event.method} params")
        when (event.method) {
            LOGIN_COMPLETED_METHOD -> CodexAppServerAccountEvent.LoginCompleted(
                raw.optionalString("loginId"), raw.requiredBoolean("success"), raw.optionalString("error"),
                raw["onboardingEntrypoint"]?.takeUnless { it === JsonNull }, raw,
            )
            else -> CodexAppServerAccountEvent.Updated(
                raw.optionalString("authMode")?.toAuthMode(), raw.optionalString("planType")?.toPlanType(), raw,
            )
        }
    } catch (cause: CodexAppServerAccountProtocolException) {
        CodexAppServerAccountEvent.MalformedNotification(event.method, event.params, cause)
    }
}

internal fun CodexAppServerAccountEvent.invalidatesModelCatalog(): Boolean = when (this) {
    is CodexAppServerAccountEvent.LoginCompleted -> success
    is CodexAppServerAccountEvent.Updated -> true
    is CodexAppServerAccountEvent.MalformedNotification -> false
}

class CodexAppServerAccountApi(private val connection: CodexAppServerConnection) {
    val events: Flow<CodexAppServerAccountEvent> = connection.events
        .toCodexAppServerAccountEvents()
        .onEach { event ->
            if (event.invalidatesModelCatalog()) {
                CodexModelCatalogKnowledge.invalidateAccountCatalog()
            }
        }

    suspend fun readAccount(refreshToken: Boolean = false): CodexAppServerAccountSnapshot {
        val params = if (refreshToken) buildJsonObject { put("refreshToken", true) } else JsonObject(emptyMap())
        val raw = connection.sendRequestAfterReady("account/read", params).requiredObject("account/read result")
        val accountValue = raw["account"] ?: throw protocol("account/read result is missing account")
        val account = if (accountValue === JsonNull) null else decodeAccount(accountValue.requiredObject("account"))
        return CodexAppServerAccountSnapshot(account, raw.requiredBoolean("requiresOpenaiAuth"), raw)
    }

    suspend fun startChatGptLogin(): CodexAppServerChatGptLoginStart {
        val raw = connection.sendRequestAfterReady(
            "account/login/start", buildJsonObject { put("type", "chatgpt") },
        ).requiredObject("account/login/start result")
        val type = raw.requiredString("type")
        if (type != "chatgpt") throw CodexAppServerUnexpectedLoginVariantException(type, raw)
        val loginId = raw.requiredNonBlankString("loginId")
        val authUrl = raw.requiredNonBlankString("authUrl")
        return CodexAppServerChatGptLoginStart(loginId, authUrl)
    }

    suspend fun startChatGptAuthTokensLogin(tokens: CodexAppServerExternalChatGptTokens) {
        val raw = connection.sendRequestAfterReady(
            "account/login/start",
            buildJsonObject {
                put("type", "chatgptAuthTokens")
                put("accessToken", tokens.accessToken)
                put("chatgptAccountId", tokens.chatgptAccountId)
                tokens.chatgptPlanType?.let { put("chatgptPlanType", it) }
            },
        ).requiredObject("account/login/start result")
        val type = raw.requiredString("type")
        if (type != "chatgptAuthTokens") {
            throw CodexAppServerUnexpectedLoginVariantException(type, raw)
        }
        CodexModelCatalogKnowledge.invalidateAccountCatalog()
    }

    val chatGptAuthTokensRefreshRequests: Flow<CodexAppServerChatGptAuthTokensRefreshRequest> =
        connection.events.mapNotNull { event ->
            if (event !is CodexAppServerEvent.ServerRequest ||
                event.method != CHATGPT_AUTH_TOKENS_REFRESH_METHOD
            ) return@mapNotNull null
            val params = event.params.requiredObject("$CHATGPT_AUTH_TOKENS_REFRESH_METHOD params")
            CodexAppServerChatGptAuthTokensRefreshRequest(
                requestId = event.id,
                reason = params.requiredString("reason"),
                previousAccountId = params.optionalString("previousAccountId"),
            )
        }

    suspend fun respondChatGptAuthTokensRefresh(
        request: CodexAppServerChatGptAuthTokensRefreshRequest,
        tokens: CodexAppServerExternalChatGptTokens,
    ) {
        connection.respondServerRequestAfterReady(
            request.requestId,
            buildJsonObject {
                put("accessToken", tokens.accessToken)
                put("chatgptAccountId", tokens.chatgptAccountId)
                // Unlike account/login/start, the refresh response requires this nullable field.
                put("chatgptPlanType", tokens.chatgptPlanType)
            },
        )
    }

    suspend fun cancelLogin(loginId: String): CodexAppServerCancelLoginResult {
        require(loginId.isNotBlank()) { "loginId must not be blank" }
        val raw = connection.sendRequestAfterReady(
            "account/login/cancel", buildJsonObject { put("loginId", loginId) },
        ).requiredObject("account/login/cancel result")
        return when (val status = raw.requiredString("status")) {
            "canceled" -> CodexAppServerCancelLoginResult.Canceled
            "notFound" -> CodexAppServerCancelLoginResult.NotFound
            else -> CodexAppServerCancelLoginResult.Unknown(status)
        }
    }

    suspend fun logout() {
        connection.sendRequestAfterReady("account/logout", JsonObject(emptyMap()))
            .requiredObject("account/logout result")
        CodexModelCatalogKnowledge.invalidateAccountCatalog()
    }
}

private fun decodeAccount(raw: JsonObject): CodexAppServerAccount = when (val type = raw.optionalString("type")) {
    "apiKey" -> CodexAppServerAccount.ApiKey(raw)
    "chatgpt" -> CodexAppServerAccount.ChatGpt(raw.optionalString("email"), raw.requiredString("planType").toPlanType(), raw)
    "amazonBedrock" -> CodexAppServerAccount.AmazonBedrock(raw.booleanOrDefault("usesCodexManagedCredentials"), raw)
    else -> CodexAppServerAccount.Unknown(type, raw)
}

internal fun String.toPlanType() = when (this) {
    "free" -> CodexAppServerPlanType.Free; "go" -> CodexAppServerPlanType.Go
    "plus" -> CodexAppServerPlanType.Plus; "pro" -> CodexAppServerPlanType.Pro
    "prolite" -> CodexAppServerPlanType.ProLite; "team" -> CodexAppServerPlanType.Team
    "self_serve_business_prolite" -> CodexAppServerPlanType.SelfServeBusinessProLite
    "self_serve_business_usage_based" -> CodexAppServerPlanType.SelfServeBusinessUsageBased
    "business" -> CodexAppServerPlanType.Business; "ent26" -> CodexAppServerPlanType.Ent26
    "enterprise_cbp_automation" -> CodexAppServerPlanType.EnterpriseCbpAutomation
    "enterprise_cbp_usage_based" -> CodexAppServerPlanType.EnterpriseCbpUsageBased
    "enterprise" -> CodexAppServerPlanType.Enterprise
    "edu" -> CodexAppServerPlanType.Edu; else -> CodexAppServerPlanType.Unknown(this)
}
private fun String.toAuthMode() = when (this) {
    "apikey" -> CodexAppServerAuthMode.ApiKey; "chatgpt" -> CodexAppServerAuthMode.ChatGpt
    "chatgptAuthTokens" -> CodexAppServerAuthMode.ChatGptAuthTokens; "headers" -> CodexAppServerAuthMode.Headers
    "agentIdentity" -> CodexAppServerAuthMode.AgentIdentity; "personalAccessToken" -> CodexAppServerAuthMode.PersonalAccessToken
    "bedrockApiKey" -> CodexAppServerAuthMode.BedrockApiKey; else -> CodexAppServerAuthMode.Unknown(this)
}

private const val CHATGPT_AUTH_TOKENS_REFRESH_METHOD = "account/chatgptAuthTokens/refresh"
private fun JsonElement?.requiredObject(label: String) = this as? JsonObject ?: throw protocol("$label must be an object")
private fun JsonObject.requiredString(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw protocol("$name must be a string")
private fun JsonObject.requiredNonBlankString(name: String) = requiredString(name).also { if (it.isBlank()) throw protocol("$name must not be blank") }
private fun JsonObject.optionalString(name: String): String? { val value = this[name] ?: return null; if (value === JsonNull) return null; return (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw protocol("$name must be a string or null") }
private fun JsonObject.requiredBoolean(name: String) = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw protocol("$name must be a boolean")
private fun JsonObject.booleanOrDefault(name: String): Boolean {
    val value = this[name] ?: return false
    return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: throw protocol("$name must be a boolean when present")
}
private fun protocol(message: String) = CodexAppServerAccountProtocolException(message)
open class CodexAppServerAccountProtocolException(message: String) : SerializationException(message)
class CodexAppServerUnexpectedLoginVariantException(val responseType: String, val rawResult: JsonObject) :
    CodexAppServerAccountProtocolException("account/login/start returned unexpected type '$responseType' (result redacted)")
private const val LOGIN_COMPLETED_METHOD = "account/login/completed"
private const val ACCOUNT_UPDATED_METHOD = "account/updated"
private val ACCOUNT_EVENT_METHODS = setOf(LOGIN_COMPLETED_METHOD, ACCOUNT_UPDATED_METHOD)
