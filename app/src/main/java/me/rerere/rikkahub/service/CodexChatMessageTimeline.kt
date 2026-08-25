package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandAction
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandApprovalRequest
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandExecutionStatus
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerFileUpdateChange
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerItemSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerPatchApplyStatus
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerPatchChangeKind
import kotlin.time.Clock
import kotlin.time.Instant

/** Small presentation value kept separate from the protocol's full skill catalog model. */
internal data class CodexSkillInvocationPresentation(
    val name: String,
    val path: String,
)

/**
 * Builds one persisted RikkaHub assistant message from an App Server turn.
 *
 * App Server lifecycle items are the source of truth. Entries are inserted at first sight and
 * subsequently updated in place, so streaming deltas never reorder prose, reasoning, commands,
 * and file changes. The resulting parts use RikkaHub's existing message renderers.
 */
internal class CodexChatMessageTimeline(
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private data class Entry(
        val turnId: String,
        val id: String,
        var inferredType: String,
        var item: CodexAppServerItemSnapshot? = null,
        var startedAtMs: Long? = null,
        var completedAtMs: Long? = null,
        val agentText: StringBuilder = StringBuilder(),
        val reasoningSummary: LinkedHashMap<Long, StringBuilder> = linkedMapOf(),
        val reasoningContent: LinkedHashMap<Long, StringBuilder> = linkedMapOf(),
        val commandOutput: StringBuilder = StringBuilder(),
        val terminalInteractions: MutableList<String> = mutableListOf(),
        var approvalState: ToolApprovalState = ToolApprovalState.Auto,
        var commandApprovalRequest: CodexAppServerCommandApprovalRequest? = null,
    )

    private val entries = linkedMapOf<String, LinkedHashMap<String, Entry>>()
    private val skills = linkedMapOf<String, MutableList<CodexSkillInvocationPresentation>>()
    private val turnDiffs = linkedMapOf<String, String>()

    @Synchronized
    fun addSkill(turnId: String, skill: CodexSkillInvocationPresentation) {
        val values = skills.getOrPut(turnId) { mutableListOf() }
        if (values.none { it.name == skill.name && it.path == skill.path }) values += skill
    }

    @Synchronized
    fun itemStarted(turnId: String, item: CodexAppServerItemSnapshot, startedAtMs: Long) {
        entry(turnId, item.id, item.type).apply {
            this.startedAtMs = startedAtMs
            applySnapshot(item, completed = false)
        }
    }

    @Synchronized
    fun itemCompleted(turnId: String, item: CodexAppServerItemSnapshot, completedAtMs: Long) {
        entry(turnId, item.id, item.type).apply {
            if (startedAtMs == null) startedAtMs = completedAtMs
            this.completedAtMs = completedAtMs
            applySnapshot(item, completed = true)
        }
    }

    @Synchronized
    fun appendAgentText(turnId: String, itemId: String, delta: String) {
        entry(turnId, itemId, "agentMessage").agentText.append(delta)
    }

    @Synchronized
    fun addReasoningSummaryPart(turnId: String, itemId: String, index: Long) {
        entry(turnId, itemId, "reasoning").reasoningSummary.putIfAbsent(index, StringBuilder())
    }

    @Synchronized
    fun appendReasoningSummary(turnId: String, itemId: String, index: Long, delta: String) {
        val chunks = entry(turnId, itemId, "reasoning").reasoningSummary
        chunks.getOrPut(index) { StringBuilder() }.append(delta)
    }

    @Synchronized
    fun appendReasoningContent(turnId: String, itemId: String, index: Long, delta: String) {
        val chunks = entry(turnId, itemId, "reasoning").reasoningContent
        chunks.getOrPut(index) { StringBuilder() }.append(delta)
    }

    @Synchronized
    fun appendCommandOutput(turnId: String, itemId: String, delta: String) {
        entry(turnId, itemId, "commandExecution").commandOutput.append(delta)
    }

    @Synchronized
    fun addTerminalInteraction(turnId: String, itemId: String, processId: String, stdin: String) {
        entry(turnId, itemId, "commandExecution").terminalInteractions += "$processId: $stdin"
    }

    @Synchronized
    fun updateFileChanges(turnId: String, itemId: String, changes: List<CodexAppServerFileUpdateChange>) {
        val current = entry(turnId, itemId, "fileChange")
        val snapshot = current.item as? CodexAppServerItemSnapshot.FileChange ?: return
        current.item = snapshot.copy(changes = changes)
    }

    @Synchronized
    fun updateTurnDiff(turnId: String, diff: String) {
        turnDiffs[turnId] = diff
    }

    @Synchronized
    fun commandApprovalRequested(turnId: String, request: CodexAppServerCommandApprovalRequest) {
        entry(turnId, request.itemId, "commandExecution").apply {
            commandApprovalRequest = request
            approvalState = ToolApprovalState.Pending
        }
    }

    @Synchronized
    fun fileApprovalRequested(turnId: String, itemId: String) {
        entry(turnId, itemId, "fileChange").approvalState = ToolApprovalState.Pending
    }

    @Synchronized
    fun approvalResolved(
        turnId: String,
        itemId: String,
        state: ToolApprovalState = ToolApprovalState.Auto,
    ) {
        entries[turnId]?.get(itemId)?.let { entry ->
            if (entry.approvalState is ToolApprovalState.Pending) entry.approvalState = state
        }
    }

    @Synchronized
    fun completeTurn(turnId: String, completedAtMs: Long = nowMs()) {
        entries[turnId]?.values?.forEach { if (it.completedAtMs == null) it.completedAtMs = completedAtMs }
    }

    @Synchronized
    fun parts(turnId: String, suppressAgentMessages: Boolean = false): List<UIMessagePart> = buildList {
        skills[turnId].orEmpty().forEachIndexed { index, skill ->
            add(skill.toPart(turnId, index))
        }
        val turnEntries = entries[turnId].orEmpty()
        turnEntries.values.forEach { entry ->
            addAll(entry.toParts(suppressAgentMessages))
        }
        if (turnEntries.values.none { it.item is CodexAppServerItemSnapshot.FileChange }) {
            turnDiffs[turnId]?.takeIf { it.isNotBlank() }?.let { add(turnDiffPart(turnId, it)) }
        }
    }

    @Synchronized
    fun clear(turnId: String) {
        entries.remove(turnId)
        skills.remove(turnId)
        turnDiffs.remove(turnId)
    }

    private fun entry(turnId: String, itemId: String, type: String): Entry {
        val turn = entries.getOrPut(turnId) { linkedMapOf() }
        return turn.getOrPut(itemId) {
            Entry(turnId = turnId, id = itemId, inferredType = type, startedAtMs = nowMs())
        }.also {
            if (it.item == null) it.inferredType = type
        }
    }

    private fun Entry.applySnapshot(snapshot: CodexAppServerItemSnapshot, completed: Boolean) {
        item = snapshot
        inferredType = snapshot.type
        when (snapshot) {
            is CodexAppServerItemSnapshot.AgentMessage -> {
                if (completed || agentText.isBlank()) agentText.replaceWith(snapshot.text)
            }
            is CodexAppServerItemSnapshot.Reasoning -> {
                if (completed || reasoningSummary.isEmpty()) {
                    reasoningSummary.clear()
                    snapshot.summary.forEachIndexed { index, text ->
                        reasoningSummary[index.toLong()] = StringBuilder(text)
                    }
                }
                if (completed || reasoningContent.isEmpty()) {
                    reasoningContent.clear()
                    snapshot.content.forEachIndexed { index, text ->
                        reasoningContent[index.toLong()] = StringBuilder(text)
                    }
                }
            }
            is CodexAppServerItemSnapshot.CommandExecution -> {
                if (completed || commandOutput.isBlank()) {
                    snapshot.aggregatedOutput?.let { commandOutput.replaceWith(it) }
                }
                if (completed && approvalState is ToolApprovalState.Pending) {
                    approvalState = if (snapshot.status is CodexAppServerCommandExecutionStatus.Declined) {
                        ToolApprovalState.Denied()
                    } else {
                        ToolApprovalState.Approved
                    }
                }
            }
            is CodexAppServerItemSnapshot.FileChange -> if (completed && approvalState is ToolApprovalState.Pending) {
                approvalState = if (snapshot.status is CodexAppServerPatchApplyStatus.Declined) {
                    ToolApprovalState.Denied()
                } else {
                    ToolApprovalState.Approved
                }
            }
            else -> Unit
        }
    }

    private fun Entry.toParts(suppressAgentMessages: Boolean): List<UIMessagePart> = when (val snapshot = item) {
        is CodexAppServerItemSnapshot.UserMessage,
        is CodexAppServerItemSnapshot.EnteredReviewMode,
            -> emptyList()
        is CodexAppServerItemSnapshot.AgentMessage ->
            if (suppressAgentMessages || agentText.isBlank()) emptyList()
            else listOf(UIMessagePart.Text(agentText.toString()))
        is CodexAppServerItemSnapshot.ExitedReviewMode ->
            snapshot.review.takeIf { it.isNotBlank() }?.let { listOf(UIMessagePart.Text(it)) }.orEmpty()
        is CodexAppServerItemSnapshot.Reasoning -> reasoningPart()?.let(::listOf).orEmpty()
        is CodexAppServerItemSnapshot.CommandExecution -> listOf(commandPart(snapshot))
        is CodexAppServerItemSnapshot.FileChange -> snapshot.changes.mapIndexed { index, change ->
            fileChangePart(snapshot, change, index)
        }
        is CodexAppServerItemSnapshot.Other -> listOf(otherPart(snapshot))
        null -> when (inferredType) {
            "agentMessage" -> if (suppressAgentMessages || agentText.isBlank()) emptyList()
                else listOf(UIMessagePart.Text(agentText.toString()))
            "reasoning" -> reasoningPart()?.let(::listOf).orEmpty()
            "commandExecution" -> commandApprovalRequest?.let { listOf(commandApprovalPart(it)) }.orEmpty()
            else -> emptyList()
        }
    }

    private fun Entry.reasoningPart(): UIMessagePart.Reasoning? {
        val summary = reasoningSummary.toSortedMap().values
            .filter { it.isNotBlank() }
            .joinToString("\n") { it.toString() }
        val content = reasoningContent.toSortedMap().values
            .filter { it.isNotBlank() }
            .joinToString("\n") { it.toString() }
        val reasoning = summary.ifBlank { content }
        if (reasoning.isBlank()) return null
        val started = safeInstant(startedAtMs ?: nowMs())
        val finished = completedAtMs?.let(::safeInstant)?.coerceAtLeast(started)
        return UIMessagePart.Reasoning(
            reasoning = reasoning,
            createdAt = started,
            finishedAt = finished,
            reasoningType = if (summary.isNotBlank()) ReasoningType.SUMMARY_TEXT else ReasoningType.REASONING_TEXT,
        )
    }

    private fun Entry.commandPart(item: CodexAppServerItemSnapshot.CommandExecution): UIMessagePart.Tool {
        val read = item.commandActions.singleOrNull() as? CodexAppServerCommandAction.Read
        val terminal = item.status !is CodexAppServerCommandExecutionStatus.InProgress
        val output = commandOutput.toString().ifBlank { item.aggregatedOutput.orEmpty() }
        val input = if (read != null) {
            buildJsonObject { put("path", read.path) }
        } else {
            buildJsonObject {
                put("command", item.command)
                item.cwd.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
            }
        }
        val result = if (!terminal && completedAtMs == null) emptyList() else listOf(
            UIMessagePart.Text(
                if (read != null) {
                    buildJsonObject { put("text", output) }.toString()
                } else {
                    buildJsonObject {
                        put("stdout", output)
                        put("stderr", "")
                        item.exitCode?.let { put("exitCode", it) }
                        put("timedOut", false)
                        item.durationMs?.let { put("durationMs", it) }
                        if (terminalInteractions.isNotEmpty()) {
                            put("terminalInteractions", JsonArray(terminalInteractions.map(::JsonPrimitive)))
                        }
                    }.toString()
                },
            ),
        )
        return UIMessagePart.Tool(
            toolCallId = codexToolCallIdFromEntry(item.id),
            toolName = codexCommandToolName(item.commandActions),
            input = input.toString(),
            output = result,
            approvalState = approvalState,
        )
    }

    private fun Entry.commandApprovalPart(request: CodexAppServerCommandApprovalRequest): UIMessagePart.Tool {
        val read = request.commandActions?.singleOrNull() as? CodexAppServerCommandAction.Read
        val input = if (read != null) {
            buildJsonObject { put("path", read.path) }
        } else {
            buildJsonObject {
                request.command?.let { put("command", it) }
                request.cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
                request.reason?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
                request.environmentId?.takeIf { it.isNotBlank() }?.let { put("environment", it) }
                request.networkApprovalContext?.let { put("networkHost", it.host) }
            }
        }
        return UIMessagePart.Tool(
            toolCallId = codexToolCallIdFromEntry(request.itemId),
            toolName = codexCommandToolName(request.commandActions),
            input = input.toString(),
            approvalState = approvalState,
        )
    }

    private fun Entry.fileChangePart(
        item: CodexAppServerItemSnapshot.FileChange,
        change: CodexAppServerFileUpdateChange,
        index: Int,
    ): UIMessagePart.Tool {
        val terminal = item.status !is CodexAppServerPatchApplyStatus.InProgress
        // While approval is pending, expose the patch as preview data to RikkaHub's existing
        // edit-file renderer even though App Server has not applied the change yet.
        val output = if (!terminal && completedAtMs == null && approvalState !is ToolApprovalState.Pending) emptyList() else listOf(
            UIMessagePart.Text("{}", metadata = DiffMetadata(change.diff).toMetadata()),
        )
        val isAdd = change.kind is CodexAppServerPatchChangeKind.Add
        val input = buildJsonObject {
            put("path", change.path)
            if (isAdd) {
                put("text", addedFileText(change.diff))
            } else if (change.kind is CodexAppServerPatchChangeKind.Update) {
                change.kind.movePath?.let { put("move_path", it) }
            }
        }
        return UIMessagePart.Tool(
            toolCallId = codexToolCallIdFromEntry(item.id, index.takeIf { item.changes.size > 1 }),
            toolName = if (isAdd) "workspace_write_file" else "workspace_edit_file",
            input = input.toString(),
            output = output,
            approvalState = if (index == 0) approvalState else ToolApprovalState.Auto,
        )
    }

    private fun Entry.otherPart(item: CodexAppServerItemSnapshot.Other): UIMessagePart.Tool {
        val status = item.raw.stringOrNull("status")
        val terminal = completedAtMs != null ||
            (status != null && status !in setOf("inProgress", "in_progress", "running"))
        val toolName = when (item.type) {
            "contextCompaction" -> "context_compaction"
            "webSearch" -> "search_web"
            else -> item.raw.stringOrNull("tool")
                ?: item.raw.stringOrNull("name")
                ?: "codex_${item.type}"
        }
        val input = item.raw["arguments"] ?: item.raw["input"] ?: item.raw
        return UIMessagePart.Tool(
            toolCallId = codexToolCallIdFromEntry(item.id),
            toolName = toolName,
            input = input.toString(),
            output = if (terminal) listOf(UIMessagePart.Text(item.raw.toString())) else emptyList(),
        )
    }

    private fun CodexSkillInvocationPresentation.toPart(turnId: String, index: Int) = UIMessagePart.Tool(
        toolCallId = "codex:$turnId:skill:$index",
        toolName = "use_skill",
        input = buildJsonObject {
            put("name", name)
            put("path", path)
        }.toString(),
        output = listOf(UIMessagePart.Text("{\"loaded\":true}")),
    )

    private fun turnDiffPart(turnId: String, diff: String) = UIMessagePart.Tool(
        toolCallId = "codex:$turnId:diff",
        toolName = "workspace_edit_file",
        input = buildJsonObject { put("path", "Codex changes") }.toString(),
        output = listOf(UIMessagePart.Text("{}", metadata = DiffMetadata(diff).toMetadata())),
    )

    private fun Entry.codexToolCallIdFromEntry(itemId: String, index: Int? = null): String {
        return codexToolCallId(turnId, itemId, index)
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Extracts the useful body of an added-file unified diff for the existing write-file preview. */
private fun addedFileText(diff: String): String = diff.lineSequence()
    .filterNot { it.startsWith("+++") || it.startsWith("---") || it.startsWith("@@") }
    .filter { it.startsWith('+') }
    .joinToString("\n") { it.drop(1) }

private fun safeInstant(epochMilliseconds: Long): Instant =
    runCatching { Instant.fromEpochMilliseconds(epochMilliseconds) }.getOrElse { Clock.System.now() }

private fun StringBuilder.replaceWith(value: String) {
    setLength(0)
    append(value)
}
