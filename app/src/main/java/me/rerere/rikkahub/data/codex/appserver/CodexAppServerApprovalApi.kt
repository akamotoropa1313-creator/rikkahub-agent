package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed interface CodexAppServerNetworkProtocol {
    data object Http : CodexAppServerNetworkProtocol
    data object Https : CodexAppServerNetworkProtocol
    data object Socks5Tcp : CodexAppServerNetworkProtocol
    data object Socks5Udp : CodexAppServerNetworkProtocol
    data class Unknown(val rawValue: String) : CodexAppServerNetworkProtocol
}

data class CodexAppServerNetworkApprovalContext(
    val host: String,
    val protocol: CodexAppServerNetworkProtocol,
    val raw: JsonObject,
)

sealed interface CodexAppServerNetworkPolicyRuleAction {
    data object Allow : CodexAppServerNetworkPolicyRuleAction
    data object Deny : CodexAppServerNetworkPolicyRuleAction
    data class Unknown(val rawValue: String) : CodexAppServerNetworkPolicyRuleAction
}

data class CodexAppServerNetworkPolicyAmendment(
    val host: String,
    val action: CodexAppServerNetworkPolicyRuleAction,
    val raw: JsonObject,
)

data class CodexAppServerCommandApprovalRequest(
    val threadId: String, val turnId: String, val itemId: String, val startedAtMs: Long,
    val approvalId: String?, val environmentId: String?, val reason: String?, val command: String?,
    val cwd: String?, val commandActions: List<CodexAppServerCommandAction>?,
    val networkApprovalContext: CodexAppServerNetworkApprovalContext?,
    val proposedExecpolicyAmendment: List<String>?,
    val proposedNetworkPolicyAmendments: List<CodexAppServerNetworkPolicyAmendment>?,
    val rawParams: JsonObject,
)

data class CodexAppServerFileChangeApprovalRequest(
    val threadId: String, val turnId: String, val itemId: String, val startedAtMs: Long,
    val reason: String?, val grantRoot: String?, val rawParams: JsonObject,
)

sealed interface CodexAppServerApprovalEvent {
    data class CommandExecutionRequest(val requestId: JsonRpcId, val request: CodexAppServerCommandApprovalRequest) : CodexAppServerApprovalEvent
    data class FileChangeRequest(val requestId: JsonRpcId, val request: CodexAppServerFileChangeApprovalRequest) : CodexAppServerApprovalEvent
    data class Resolved(val threadId: String, val requestId: JsonRpcId, val rawParams: JsonObject) : CodexAppServerApprovalEvent
    data class MalformedRequest(val requestId: JsonRpcId, val method: String, val rawParams: JsonElement?, val cause: CodexAppServerApprovalProtocolException) : CodexAppServerApprovalEvent
    data class MalformedNotification(val method: String, val rawParams: JsonElement?, val cause: CodexAppServerApprovalProtocolException) : CodexAppServerApprovalEvent
}

sealed interface CodexAppServerCommandApprovalDecision {
    data object Accept : CodexAppServerCommandApprovalDecision
    data object AcceptForSession : CodexAppServerCommandApprovalDecision
    data class AcceptWithExecpolicyAmendment(val amendment: List<String>) : CodexAppServerCommandApprovalDecision
    data class ApplyNetworkPolicyAmendment(val amendment: CodexAppServerNetworkPolicyAmendment) : CodexAppServerCommandApprovalDecision
    data object Decline : CodexAppServerCommandApprovalDecision
    data object Cancel : CodexAppServerCommandApprovalDecision
}
sealed interface CodexAppServerFileChangeApprovalDecision {
    data object Accept : CodexAppServerFileChangeApprovalDecision
    data object AcceptForSession : CodexAppServerFileChangeApprovalDecision
    data object Decline : CodexAppServerFileChangeApprovalDecision
    data object Cancel : CodexAppServerFileChangeApprovalDecision
}

/** Cold, ordered projection. Subscribe before starting a turn to observe every approval request. */
fun Flow<CodexAppServerEvent>.toCodexAppServerApprovalEvents(): Flow<CodexAppServerApprovalEvent> = mapNotNull { event ->
    when (event) {
        is CodexAppServerEvent.ServerRequest -> if (event.method in approvalRequestMethods) try {
            val raw = event.params.obj("${event.method} params")
            if (event.method == COMMAND_APPROVAL_METHOD) CodexAppServerApprovalEvent.CommandExecutionRequest(event.id, raw.commandRequest())
            else CodexAppServerApprovalEvent.FileChangeRequest(event.id, raw.fileRequest())
        } catch (cause: CodexAppServerApprovalProtocolException) {
            CodexAppServerApprovalEvent.MalformedRequest(event.id, event.method, event.params, cause)
        } else null
        is CodexAppServerEvent.UnknownNotification -> if (event.method == RESOLVED_METHOD) try {
            val raw = event.params.obj("serverRequest/resolved params")
            CodexAppServerApprovalEvent.Resolved(raw.string("threadId"), raw.rpcId("requestId"), raw)
        } catch (cause: CodexAppServerApprovalProtocolException) {
            CodexAppServerApprovalEvent.MalformedNotification(event.method, event.params, cause)
        } else null
        else -> null
    }
}

class CodexAppServerApprovalApi(private val connection: CodexAppServerConnection) {
    val events = connection.events.toCodexAppServerApprovalEvents()
    suspend fun respondCommandApproval(requestId: JsonRpcId, decision: CodexAppServerCommandApprovalDecision) =
        connection.respondServerRequestAfterReady(requestId, buildJsonObject { put("decision", decision.json()) })
    suspend fun respondFileChangeApproval(requestId: JsonRpcId, decision: CodexAppServerFileChangeApprovalDecision) =
        connection.respondServerRequestAfterReady(requestId, buildJsonObject { put("decision", decision.json()) })
}

private fun CodexAppServerCommandApprovalDecision.json(): JsonElement = when (this) {
    CodexAppServerCommandApprovalDecision.Accept -> JsonPrimitive("accept")
    CodexAppServerCommandApprovalDecision.AcceptForSession -> JsonPrimitive("acceptForSession")
    CodexAppServerCommandApprovalDecision.Decline -> JsonPrimitive("decline")
    CodexAppServerCommandApprovalDecision.Cancel -> JsonPrimitive("cancel")
    is CodexAppServerCommandApprovalDecision.AcceptWithExecpolicyAmendment -> buildJsonObject {
        put("acceptWithExecpolicyAmendment", buildJsonObject { put("execpolicy_amendment", JsonArray(amendment.map(::JsonPrimitive))) })
    }
    is CodexAppServerCommandApprovalDecision.ApplyNetworkPolicyAmendment -> buildJsonObject {
        put("applyNetworkPolicyAmendment", buildJsonObject { put("network_policy_amendment", amendment.outboundJson()) })
    }
}
private fun CodexAppServerFileChangeApprovalDecision.json() = JsonPrimitive(when (this) {
    CodexAppServerFileChangeApprovalDecision.Accept -> "accept"
    CodexAppServerFileChangeApprovalDecision.AcceptForSession -> "acceptForSession"
    CodexAppServerFileChangeApprovalDecision.Decline -> "decline"
    CodexAppServerFileChangeApprovalDecision.Cancel -> "cancel"
})
private fun CodexAppServerNetworkPolicyAmendment.outboundJson() = buildJsonObject {
    put("host", host)
    put("action", when (action) { CodexAppServerNetworkPolicyRuleAction.Allow -> "allow"; CodexAppServerNetworkPolicyRuleAction.Deny -> "deny"; is CodexAppServerNetworkPolicyRuleAction.Unknown -> throw IllegalArgumentException("Unknown network action cannot be sent") })
}

private fun JsonObject.commandRequest() = CodexAppServerCommandApprovalRequest(
    string("threadId"), string("turnId"), string("itemId"), long("startedAtMs"), optionalString("approvalId"),
    optionalString("environmentId"), optionalString("reason"), optionalString("command"), optionalString("cwd"),
    optionalArray("commandActions")?.mapIndexed { i, e ->
        val raw = e as? JsonObject ?: bad("commandActions[$i] must be an object")
        try { decodeCommandAction(raw) } catch (e: CodexAppServerTurnProtocolException) { bad(e.message ?: "invalid command action") }
    }, optionalObject("networkApprovalContext")?.networkContext(), stringList("proposedExecpolicyAmendment"),
    optionalArray("proposedNetworkPolicyAmendments")?.mapIndexed { i, e -> (e as? JsonObject ?: bad("proposedNetworkPolicyAmendments[$i] must be an object")).networkAmendment() }, this)
private fun JsonObject.fileRequest() = CodexAppServerFileChangeApprovalRequest(string("threadId"), string("turnId"), string("itemId"), long("startedAtMs"), optionalString("reason"), optionalString("grantRoot"), this)
private fun JsonObject.networkContext() = CodexAppServerNetworkApprovalContext(string("host"), when(val p=string("protocol")){"http"->CodexAppServerNetworkProtocol.Http;"https"->CodexAppServerNetworkProtocol.Https;"socks5Tcp"->CodexAppServerNetworkProtocol.Socks5Tcp;"socks5Udp"->CodexAppServerNetworkProtocol.Socks5Udp;else->CodexAppServerNetworkProtocol.Unknown(p)}, this)
private fun JsonObject.networkAmendment() = CodexAppServerNetworkPolicyAmendment(string("host"), when(val a=string("action")){"allow"->CodexAppServerNetworkPolicyRuleAction.Allow;"deny"->CodexAppServerNetworkPolicyRuleAction.Deny;else->CodexAppServerNetworkPolicyRuleAction.Unknown(a)}, this)
private fun JsonElement?.obj(label:String)=this as? JsonObject ?: bad("$label must be an object")
private fun JsonObject.string(k:String)=(this[k] as? JsonPrimitive)?.takeIf{it.isString}?.contentOrNull ?: bad("$k must be a string")
private fun JsonObject.long(k:String)=(this[k] as? JsonPrimitive)?.takeUnless{it.isString}?.longOrNull ?: bad("$k must be an integer")
private fun JsonObject.optionalString(k:String):String? { val v=this[k]?:return null;if(v===JsonNull)return null;return (v as? JsonPrimitive)?.takeIf{it.isString}?.contentOrNull?:bad("$k must be a string or null") }
private fun JsonObject.optionalArray(k:String):JsonArray? { val v=this[k]?:return null;if(v===JsonNull)return null;return v as? JsonArray?:bad("$k must be an array or null") }
private fun JsonObject.optionalObject(k:String):JsonObject? { val v=this[k]?:return null;if(v===JsonNull)return null;return v as? JsonObject?:bad("$k must be an object or null") }
private fun JsonObject.stringList(k:String)=optionalArray(k)?.mapIndexed{i,e->(e as? JsonPrimitive)?.takeIf{it.isString}?.contentOrNull?:bad("$k[$i] must be a string")}
private fun JsonObject.rpcId(k:String):JsonRpcId { val v=this[k] as? JsonPrimitive ?: bad("$k must be a string or number"); return if(v.isString) JsonRpcId.StringId(v.content) else v.longOrNull?.let(JsonRpcId::NumberId)?:bad("$k must be a string or integer") }
private fun bad(message:String):Nothing=throw CodexAppServerApprovalProtocolException(message)
class CodexAppServerApprovalProtocolException(message:String):Exception(message)
private const val COMMAND_APPROVAL_METHOD="item/commandExecution/requestApproval"
private const val FILE_APPROVAL_METHOD="item/fileChange/requestApproval"
private const val RESOLVED_METHOD="serverRequest/resolved"
private val approvalRequestMethods=setOf(COMMAND_APPROVAL_METHOD, FILE_APPROVAL_METHOD)
