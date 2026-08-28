package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

sealed interface CodexAppServerCommandExecutionStatus { data object InProgress : CodexAppServerCommandExecutionStatus; data object Completed : CodexAppServerCommandExecutionStatus; data object Failed : CodexAppServerCommandExecutionStatus; data object Declined : CodexAppServerCommandExecutionStatus; data class Unknown(val rawValue: String) : CodexAppServerCommandExecutionStatus }
sealed interface CodexAppServerCommandExecutionSource { data object Agent : CodexAppServerCommandExecutionSource; data object UserShell : CodexAppServerCommandExecutionSource; data object UnifiedExecStartup : CodexAppServerCommandExecutionSource; data object UnifiedExecInteraction : CodexAppServerCommandExecutionSource; data class Unknown(val rawValue: String) : CodexAppServerCommandExecutionSource }
sealed interface CodexAppServerCommandAction { val raw: JsonObject
    data class Read(val command: String, val name: String, val path: String, override val raw: JsonObject) : CodexAppServerCommandAction
    data class ListFiles(val command: String, val path: String?, override val raw: JsonObject) : CodexAppServerCommandAction
    data class Search(val command: String, val query: String?, val path: String?, override val raw: JsonObject) : CodexAppServerCommandAction
    data class UnknownCommand(val command: String, override val raw: JsonObject) : CodexAppServerCommandAction
    data class Other(val type: String, override val raw: JsonObject) : CodexAppServerCommandAction
}
sealed interface CodexAppServerPatchApplyStatus { data object InProgress : CodexAppServerPatchApplyStatus; data object Completed : CodexAppServerPatchApplyStatus; data object Failed : CodexAppServerPatchApplyStatus; data object Declined : CodexAppServerPatchApplyStatus; data class Unknown(val rawValue: String) : CodexAppServerPatchApplyStatus }

internal const val CODEX_FILE_CHANGE_STATUS_METADATA_KEY = "codexFileChangeStatus"

internal fun CodexAppServerPatchApplyStatus.metadataValue(): String = when (this) {
    CodexAppServerPatchApplyStatus.InProgress -> "inProgress"
    CodexAppServerPatchApplyStatus.Completed -> "completed"
    CodexAppServerPatchApplyStatus.Failed -> "failed"
    CodexAppServerPatchApplyStatus.Declined -> "declined"
    is CodexAppServerPatchApplyStatus.Unknown -> "unknown:$rawValue"
}
sealed interface CodexAppServerPatchChangeKind { val raw: JsonObject; data class Add(override val raw: JsonObject) : CodexAppServerPatchChangeKind; data class Delete(override val raw: JsonObject) : CodexAppServerPatchChangeKind; data class Update(val movePath: String?, override val raw: JsonObject) : CodexAppServerPatchChangeKind; data class Other(val type: String, override val raw: JsonObject) : CodexAppServerPatchChangeKind }
data class CodexAppServerFileUpdateChange(val path: String, val kind: CodexAppServerPatchChangeKind, val diff: String, val raw: JsonObject)

sealed interface CodexAppServerUserInput {
    val type: String
    val raw: JsonObject

    data class Text(val text: String, val textElements: JsonArray, override val raw: JsonObject) : CodexAppServerUserInput { override val type = "text" }
    data class Image(val url: String, val detail: String?, override val raw: JsonObject) : CodexAppServerUserInput { override val type = "image" }
    data class LocalImage(val path: String, val detail: String?, override val raw: JsonObject) : CodexAppServerUserInput { override val type = "localImage" }
    data class Audio(val url: String, override val raw: JsonObject) : CodexAppServerUserInput { override val type = "audio" }
    data class LocalAudio(val path: String, override val raw: JsonObject) : CodexAppServerUserInput { override val type = "localAudio" }
    data class Skill(val name: String, val path: String, override val raw: JsonObject) : CodexAppServerUserInput { override val type = "skill" }
    data class Mention(val name: String, val path: String, override val raw: JsonObject) : CodexAppServerUserInput { override val type = "mention" }
    data class Other(override val type: String, override val raw: JsonObject) : CodexAppServerUserInput
}

sealed interface CodexAppServerItemSnapshot {
    val id: String
    val type: String
    val raw: JsonObject

    data class UserMessage(
        override val id: String,
        val clientId: String?,
        val content: List<CodexAppServerUserInput>,
        override val raw: JsonObject,
    ) : CodexAppServerItemSnapshot { override val type = "userMessage" }

    data class AgentMessage(
        override val id: String,
        val text: String,
        override val raw: JsonObject,
    ) : CodexAppServerItemSnapshot { override val type = "agentMessage" }

    data class Reasoning(
        override val id: String,
        val summary: List<String>,
        val content: List<String>,
        override val raw: JsonObject,
    ) : CodexAppServerItemSnapshot { override val type = "reasoning" }

    data class CommandExecution(override val id: String, val command: String, val cwd: String, val processId: String?, val source: CodexAppServerCommandExecutionSource, val status: CodexAppServerCommandExecutionStatus, val commandActions: List<CodexAppServerCommandAction>, val aggregatedOutput: String?, val exitCode: Int?, val durationMs: Long?, val pluginId: String?, val scriptPath: String?, override val raw: JsonObject) : CodexAppServerItemSnapshot { override val type = "commandExecution" }
    data class FileChange(override val id: String, val changes: List<CodexAppServerFileUpdateChange>, val status: CodexAppServerPatchApplyStatus, override val raw: JsonObject) : CodexAppServerItemSnapshot { override val type = "fileChange" }
    data class EnteredReviewMode(override val id: String, val review: String, override val raw: JsonObject) : CodexAppServerItemSnapshot { override val type = "enteredReviewMode" }
    data class ExitedReviewMode(override val id: String, val review: String, override val raw: JsonObject) : CodexAppServerItemSnapshot { override val type = "exitedReviewMode" }

    data class Other(
        override val id: String,
        override val type: String,
        override val raw: JsonObject,
    ) : CodexAppServerItemSnapshot
}

sealed interface CodexAppServerTurnEvent {
    val rawParams: JsonElement?

    data class TurnStarted(val threadId: String, val turn: CodexAppServerTurnSnapshot, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class TurnCompleted(val threadId: String, val turn: CodexAppServerTurnSnapshot, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class ItemStarted(val item: CodexAppServerItemSnapshot, val threadId: String, val turnId: String, val startedAtMs: Long, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class ItemCompleted(val item: CodexAppServerItemSnapshot, val threadId: String, val turnId: String, val completedAtMs: Long, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class AgentMessageDelta(val threadId: String, val turnId: String, val itemId: String, val delta: String, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class ReasoningSummaryTextDelta(val threadId: String, val turnId: String, val itemId: String, val delta: String, val summaryIndex: Long, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class ReasoningSummaryPartAdded(val threadId: String, val turnId: String, val itemId: String, val summaryIndex: Long, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class ReasoningTextDelta(val threadId: String, val turnId: String, val itemId: String, val delta: String, val contentIndex: Long, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class CommandExecutionOutputDelta(val threadId: String, val turnId: String, val itemId: String, val delta: String, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class TerminalInteraction(val threadId: String, val turnId: String, val itemId: String, val processId: String, val stdin: String, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class FileChangePatchUpdated(val threadId: String, val turnId: String, val itemId: String, val changes: List<CodexAppServerFileUpdateChange>, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class TurnDiffUpdated(val threadId: String, val turnId: String, val diff: String, override val rawParams: JsonObject) : CodexAppServerTurnEvent
    data class MalformedNotification(val method: String, override val rawParams: JsonElement?, val cause: Throwable) : CodexAppServerTurnEvent
}

private val turnEventMethods = setOf(
    "turn/started", "turn/completed", "item/started", "item/completed",
    "item/agentMessage/delta", "item/reasoning/summaryTextDelta",
    "item/reasoning/summaryPartAdded", "item/reasoning/textDelta",
    "item/commandExecution/outputDelta", "item/commandExecution/terminalInteraction",
    "item/fileChange/patchUpdated", "turn/diff/updated",
)

/** Preserves source arrival order and converts each malformed known notification into a value. */
fun Flow<CodexAppServerEvent>.toCodexAppServerTurnEvents(): Flow<CodexAppServerTurnEvent> =
    mapNotNull { event ->
        val notification = event as? CodexAppServerEvent.UnknownNotification ?: return@mapNotNull null
        if (notification.method !in turnEventMethods) return@mapNotNull null
        try {
            decodeTurnEvent(notification.method, notification.params)
        } catch (cause: CodexAppServerTurnProtocolException) {
            CodexAppServerTurnEvent.MalformedNotification(notification.method, notification.params, cause)
        }
    }

private fun decodeTurnEvent(method: String, value: JsonElement?): CodexAppServerTurnEvent {
    val p = value as? JsonObject ?: malformed("$method params must be an object")
    return when (method) {
        "turn/started" -> CodexAppServerTurnEvent.TurnStarted(p.string("threadId"), p.turn(), p)
        "turn/completed" -> CodexAppServerTurnEvent.TurnCompleted(p.string("threadId"), p.turn(), p)
        "item/started" -> CodexAppServerTurnEvent.ItemStarted(p.item(), p.string("threadId"), p.string("turnId"), p.long("startedAtMs"), p)
        "item/completed" -> CodexAppServerTurnEvent.ItemCompleted(p.item(), p.string("threadId"), p.string("turnId"), p.long("completedAtMs"), p)
        "item/agentMessage/delta" -> CodexAppServerTurnEvent.AgentMessageDelta(p.string("threadId"), p.string("turnId"), p.string("itemId"), p.string("delta"), p)
        "item/reasoning/summaryTextDelta" -> CodexAppServerTurnEvent.ReasoningSummaryTextDelta(p.string("threadId"), p.string("turnId"), p.string("itemId"), p.string("delta"), p.long("summaryIndex"), p)
        "item/reasoning/summaryPartAdded" -> CodexAppServerTurnEvent.ReasoningSummaryPartAdded(p.string("threadId"), p.string("turnId"), p.string("itemId"), p.long("summaryIndex"), p)
        "item/reasoning/textDelta" -> CodexAppServerTurnEvent.ReasoningTextDelta(p.string("threadId"), p.string("turnId"), p.string("itemId"), p.string("delta"), p.long("contentIndex"), p)
        "item/commandExecution/outputDelta" -> CodexAppServerTurnEvent.CommandExecutionOutputDelta(p.string("threadId"), p.string("turnId"), p.string("itemId"), p.string("delta"), p)
        "item/commandExecution/terminalInteraction" -> CodexAppServerTurnEvent.TerminalInteraction(p.string("threadId"), p.string("turnId"), p.string("itemId"), p.string("processId"), p.string("stdin"), p)
        "item/fileChange/patchUpdated" -> CodexAppServerTurnEvent.FileChangePatchUpdated(p.string("threadId"), p.string("turnId"), p.string("itemId"), decodeChanges(p["changes"]), p)
        "turn/diff/updated" -> CodexAppServerTurnEvent.TurnDiffUpdated(p.string("threadId"), p.string("turnId"), p.string("diff"), p)
        else -> error("unreachable")
    }
}

private fun JsonObject.turn() = decodeTurnSnapshot(this["turn"] as? JsonObject ?: malformed("turn must be an object"))
private fun JsonObject.item() = decodeItemSnapshot(this["item"] as? JsonObject ?: malformed("item must be an object"))
private fun JsonObject.string(key: String) = requiredString(key)
private fun JsonObject.long(key: String): Long {
    val primitive = this[key] as? JsonPrimitive
    return primitive?.takeUnless { it.isString }?.longOrNull
        ?: malformed("$key must be an integer")
}

internal fun decodeItemSnapshot(raw: JsonObject): CodexAppServerItemSnapshot {
    val id = raw.requiredString("item.id", "id")
    if (id.isBlank()) malformed("item.id must not be blank")
    return when (val type = raw.requiredString("item.type", "type")) {
        "userMessage" -> decodeUserMessage(id, raw)
        "agentMessage" -> CodexAppServerItemSnapshot.AgentMessage(id, raw.requiredString("item.text", "text"), raw)
        "reasoning" -> CodexAppServerItemSnapshot.Reasoning(id, raw.stringListOrEmpty("summary"), raw.stringListOrEmpty("content"), raw)
        "commandExecution" -> decodeCommandExecution(id, raw)
        "fileChange" -> CodexAppServerItemSnapshot.FileChange(id, decodeChanges(raw["changes"]), decodePatchStatus(raw.requiredString("item.status", "status")), raw)
        "enteredReviewMode" -> CodexAppServerItemSnapshot.EnteredReviewMode(id, raw.requiredString("item.review", "review"), raw)
        "exitedReviewMode" -> CodexAppServerItemSnapshot.ExitedReviewMode(id, raw.requiredString("item.review", "review"), raw)
        else -> CodexAppServerItemSnapshot.Other(id, type, raw)
    }
}

private fun decodeUserMessage(id: String, raw: JsonObject): CodexAppServerItemSnapshot.UserMessage {
    val clientId = raw.optionalString("clientId")
    val content = (raw["content"] as? JsonArray ?: malformed("item.content must be an array")).mapIndexed { index, element ->
        decodeUserInput(element as? JsonObject ?: malformed("item.content[$index] must be an object"), index)
    }
    return CodexAppServerItemSnapshot.UserMessage(id, clientId, content, raw)
}

private fun decodeUserInput(raw: JsonObject, index: Int): CodexAppServerUserInput {
    val prefix = "item.content[$index]"
    return when (val type = raw.requiredString("$prefix.type", "type")) {
        "text" -> CodexAppServerUserInput.Text(
            raw.requiredString("$prefix.text", "text"),
            when (val textElements = raw["text_elements"]) {
                null -> JsonArray(emptyList())
                is JsonArray -> textElements
                else -> malformed("$prefix.text_elements must be an array")
            },
            raw,
        )
        "image" -> CodexAppServerUserInput.Image(raw.requiredString("$prefix.url", "url"), raw.optionalString("detail"), raw)
        "localImage" -> CodexAppServerUserInput.LocalImage(raw.requiredString("$prefix.path", "path"), raw.optionalString("detail"), raw)
        "audio" -> CodexAppServerUserInput.Audio(raw.requiredString("$prefix.url", "url"), raw)
        "localAudio" -> CodexAppServerUserInput.LocalAudio(raw.requiredString("$prefix.path", "path"), raw)
        "skill" -> CodexAppServerUserInput.Skill(raw.requiredString("$prefix.name", "name"), raw.requiredString("$prefix.path", "path"), raw)
        "mention" -> CodexAppServerUserInput.Mention(raw.requiredString("$prefix.name", "name"), raw.requiredString("$prefix.path", "path"), raw)
        else -> CodexAppServerUserInput.Other(type, raw)
    }
}

private fun decodeCommandExecution(id: String, raw: JsonObject) = CodexAppServerItemSnapshot.CommandExecution(
    id, raw.requiredString("item.command", "command"), raw.requiredString("item.cwd", "cwd"), raw.optionalString("processId"),
    raw.defaultedCommandSource(), decodeCommandStatus(raw.requiredString("item.status", "status")),
    (raw["commandActions"] as? JsonArray ?: malformed("item.commandActions must be an array")).mapIndexed { i, it -> decodeCommandAction(it as? JsonObject ?: malformed("item.commandActions[$i] must be an object")) },
    raw.optionalString("aggregatedOutput"), raw.optionalInt("exitCode"), raw.optionalLong("durationMs"), raw.optionalString("pluginId"), raw.optionalString("scriptPath"), raw)

internal fun decodeCommandAction(raw: JsonObject): CodexAppServerCommandAction {
    val type = raw.requiredString("action.type", "type")
    return when (type) {
        "read" -> CodexAppServerCommandAction.Read(raw.requiredString("action.command", "command"), raw.requiredString("action.name", "name"), raw.requiredString("action.path", "path"), raw)
        "listFiles" -> CodexAppServerCommandAction.ListFiles(raw.requiredString("action.command", "command"), raw.optionalString("path"), raw)
        "search" -> CodexAppServerCommandAction.Search(raw.requiredString("action.command", "command"), raw.optionalString("query"), raw.optionalString("path"), raw)
        "unknown" -> CodexAppServerCommandAction.UnknownCommand(raw.requiredString("action.command", "command"), raw)
        else -> CodexAppServerCommandAction.Other(type, raw)
    }
}
private fun decodeChanges(value: JsonElement?): List<CodexAppServerFileUpdateChange> = (value as? JsonArray ?: malformed("changes must be an array")).mapIndexed { i, e -> val raw = e as? JsonObject ?: malformed("changes[$i] must be an object"); val kindRaw = raw["kind"] as? JsonObject ?: malformed("changes[$i].kind must be an object"); val type = kindRaw.requiredString("kind.type", "type"); val kind = when(type) { "add" -> CodexAppServerPatchChangeKind.Add(kindRaw); "delete" -> CodexAppServerPatchChangeKind.Delete(kindRaw); "update" -> CodexAppServerPatchChangeKind.Update(kindRaw.optionalString("move_path"), kindRaw); else -> CodexAppServerPatchChangeKind.Other(type, kindRaw) }; CodexAppServerFileUpdateChange(raw.requiredString("change.path", "path"), kind, raw.requiredString("change.diff", "diff"), raw) }
private fun decodeCommandStatus(v:String)=when(v){"inProgress"->CodexAppServerCommandExecutionStatus.InProgress;"completed"->CodexAppServerCommandExecutionStatus.Completed;"failed"->CodexAppServerCommandExecutionStatus.Failed;"declined"->CodexAppServerCommandExecutionStatus.Declined;else->CodexAppServerCommandExecutionStatus.Unknown(v)}
private fun decodeSource(v:String)=when(v){"agent"->CodexAppServerCommandExecutionSource.Agent;"userShell"->CodexAppServerCommandExecutionSource.UserShell;"unifiedExecStartup"->CodexAppServerCommandExecutionSource.UnifiedExecStartup;"unifiedExecInteraction"->CodexAppServerCommandExecutionSource.UnifiedExecInteraction;else->CodexAppServerCommandExecutionSource.Unknown(v)}
private fun decodePatchStatus(v:String)=when(v){"inProgress"->CodexAppServerPatchApplyStatus.InProgress;"completed"->CodexAppServerPatchApplyStatus.Completed;"failed"->CodexAppServerPatchApplyStatus.Failed;"declined"->CodexAppServerPatchApplyStatus.Declined;else->CodexAppServerPatchApplyStatus.Unknown(v)}
private fun JsonObject.defaultedCommandSource(): CodexAppServerCommandExecutionSource =
    if ("source" !in this) CodexAppServerCommandExecutionSource.Agent
    else decodeSource(requiredString("item.source", "source"))
private fun JsonObject.requiredNullableString(label:String,key:String):String? { val e=this[key] ?: malformed("$label must be a string or null"); if(e === JsonNull) return null; return (e as? JsonPrimitive)?.takeIf{it.isString}?.contentOrNull ?: malformed("$label must be a string or null") }
private fun JsonObject.optionalString(key:String):String? { val e=this[key]?:return null; if(e === JsonNull) return null; return (e as? JsonPrimitive)?.takeIf{it.isString}?.contentOrNull ?: malformed("$key must be a string or null") }
private fun JsonObject.optionalLong(key:String):Long? { val e=this[key]?:return null; if(e === JsonNull) return null; return (e as? JsonPrimitive)?.takeUnless{it.isString}?.longOrNull ?: malformed("$key must be an integer or null") }
private fun JsonObject.optionalInt(key:String):Int? { val e=this[key]?:return null; if(e === JsonNull) return null; return (e as? JsonPrimitive)?.takeUnless{it.isString}?.intOrNull ?: malformed("$key must be an i32 integer or null") }

private fun JsonObject.stringListOrEmpty(key: String): List<String> {
    val value = this[key] ?: return emptyList()
    val array = value as? JsonArray ?: malformed("item.$key must be an array")
    return array.mapIndexed { index, element ->
        val primitive = element as? JsonPrimitive
        primitive?.takeIf { it.isString }?.content ?: malformed("item.$key[$index] must be a string")
    }
}

private fun malformed(message: String): Nothing = throw CodexAppServerTurnProtocolException(message)
