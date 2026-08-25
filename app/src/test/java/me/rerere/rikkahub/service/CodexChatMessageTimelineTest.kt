package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandApprovalRequest
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandExecutionSource
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandExecutionStatus
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerFileUpdateChange
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerItemSnapshot
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerPatchApplyStatus
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerPatchChangeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexChatMessageTimelineTest {
    @Test
    fun `prose reasoning and commands keep App Server arrival order`() {
        val timeline = CodexChatMessageTimeline(nowMs = { 99L })
        timeline.itemStarted("turn", agent("answer-1", ""), 10L)
        timeline.appendAgentText("turn", "answer-1", "準備します。")
        timeline.itemCompleted("turn", agent("answer-1", "準備します。"), 11L)

        timeline.itemStarted("turn", reasoning("reason-1", emptyList()), 20L)
        timeline.addReasoningSummaryPart("turn", "reason-1", 0L)
        timeline.appendReasoningSummary("turn", "reason-1", 0L, "状態を確認中")
        timeline.itemCompleted("turn", reasoning("reason-1", listOf("状態を確認中")), 21L)

        timeline.itemStarted("turn", command("shell-1", "inProgress", null, null), 30L)
        timeline.appendCommandOutput("turn", "shell-1", "ok\n")
        timeline.itemCompleted("turn", command("shell-1", "completed", "ok\n", 0), 31L)

        timeline.itemStarted("turn", agent("answer-2", "完了しました。"), 40L)
        timeline.itemCompleted("turn", agent("answer-2", "完了しました。"), 41L)

        val parts = timeline.parts("turn")
        assertEquals(4, parts.size)
        assertEquals("準備します。", (parts[0] as UIMessagePart.Text).text)
        assertEquals("状態を確認中", (parts[1] as UIMessagePart.Reasoning).reasoning)
        val tool = parts[2] as UIMessagePart.Tool
        assertEquals("workspace_shell", tool.toolName)
        assertEquals("echo ok", tool.inputAsJson().jsonObject["command"]?.jsonPrimitive?.content)
        val shellResult = Json.parseToJsonElement((tool.output.single() as UIMessagePart.Text).text)
        assertEquals(0, shellResult.jsonObject["exitCode"]?.jsonPrimitive?.content?.toInt())
        assertEquals("完了しました。", (parts[3] as UIMessagePart.Text).text)
    }

    @Test
    fun `skill and file changes reuse existing chat tool renderers`() {
        val timeline = CodexChatMessageTimeline(nowMs = { 99L })
        timeline.addSkill("turn", CodexSkillInvocationPresentation("frontend-design", "/skills/frontend-design/SKILL.md"))
        val changes = listOf(
            change("demo.kt", CodexAppServerPatchChangeKind.Add(buildJsonObject { put("type", "add") }),
                "--- /dev/null\n+++ b/demo.kt\n@@ -0,0 +1 @@\n+fun main() {}\n"),
            change("old.kt", CodexAppServerPatchChangeKind.Update(null, buildJsonObject { put("type", "update") }),
                "--- a/old.kt\n+++ b/old.kt\n@@ -1 +1 @@\n-old\n+new\n"),
        )
        val file = CodexAppServerItemSnapshot.FileChange(
            id = "file-1",
            changes = changes,
            status = CodexAppServerPatchApplyStatus.Completed,
            raw = buildJsonObject { put("type", "fileChange") },
        )
        timeline.itemStarted("turn", file, 10L)
        timeline.itemCompleted("turn", file, 11L)

        val tools = timeline.parts("turn").map { it as UIMessagePart.Tool }
        assertEquals(listOf("use_skill", "workspace_write_file", "workspace_edit_file"), tools.map { it.toolName })
        assertEquals("fun main() {}", tools[1].inputAsJson().jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("codex:turn:file-1:0", tools[1].toolCallId)
        assertEquals("codex:turn:file-1:1", tools[2].toolCallId)
        assertNotNull(tools[2].output.single().metadataAs<DiffMetadata>()?.diff)
    }

    @Test
    fun `App Server approval is rendered by the existing pending tool row`() {
        val timeline = CodexChatMessageTimeline(nowMs = { 99L })
        val request = CodexAppServerCommandApprovalRequest(
            threadId = "thread",
            turnId = "turn",
            itemId = "shell-1",
            startedAtMs = 10L,
            approvalId = null,
            environmentId = "local",
            reason = "verify output",
            command = "echo ok",
            cwd = "/workspace",
            commandActions = emptyList(),
            networkApprovalContext = null,
            proposedExecpolicyAmendment = null,
            proposedNetworkPolicyAmendments = null,
            rawParams = buildJsonObject {},
        )

        // Approval can race ahead of item/started; it must still be visible in the native row.
        timeline.commandApprovalRequested("turn", request)
        val pending = timeline.parts("turn").single() as UIMessagePart.Tool
        assertEquals("codex:turn:shell-1", pending.toolCallId)
        assertEquals("workspace_shell", pending.toolName)
        assertTrue(pending.approvalState is ToolApprovalState.Pending)

        timeline.itemStarted("turn", command("shell-1", "inProgress", null, null), 10L)
        assertTrue((timeline.parts("turn").single() as UIMessagePart.Tool).approvalState is ToolApprovalState.Pending)

        timeline.approvalResolved("turn", "shell-1", ToolApprovalState.Approved)
        assertTrue((timeline.parts("turn").single() as UIMessagePart.Tool).approvalState is ToolApprovalState.Approved)
    }

    @Test
    fun `unknown lifecycle items remain visible instead of being dropped`() {
        val raw = buildJsonObject {
            put("id", "future-1")
            put("type", "futureTool")
            put("name", "future_tool")
            put("status", "completed")
            put("input", buildJsonObject { put("value", 3) })
        }
        val timeline = CodexChatMessageTimeline(nowMs = { 99L })
        val item = CodexAppServerItemSnapshot.Other("future-1", "futureTool", raw)
        timeline.itemStarted("turn", item, 10L)
        timeline.itemCompleted("turn", item, 11L)

        val tool = timeline.parts("turn").single() as UIMessagePart.Tool
        assertEquals("future_tool", tool.toolName)
        assertTrue(tool.isExecuted)
        assertEquals("3", tool.inputAsJson().jsonObject["value"]?.jsonPrimitive?.content)
    }

    private fun agent(id: String, text: String) = CodexAppServerItemSnapshot.AgentMessage(
        id = id,
        text = text,
        raw = buildJsonObject { put("id", id); put("type", "agentMessage"); put("text", text) },
    )

    private fun reasoning(id: String, summary: List<String>) = CodexAppServerItemSnapshot.Reasoning(
        id = id,
        summary = summary,
        content = emptyList(),
        raw = buildJsonObject {
            put("id", id)
            put("type", "reasoning")
            put("summary", JsonArray(emptyList()))
            put("content", JsonArray(emptyList()))
        },
    )

    private fun command(id: String, status: String, output: String?, exitCode: Int?) =
        CodexAppServerItemSnapshot.CommandExecution(
            id = id,
            command = "echo ok",
            cwd = "/workspace",
            processId = null,
            source = CodexAppServerCommandExecutionSource.Agent,
            status = if (status == "inProgress") CodexAppServerCommandExecutionStatus.InProgress
            else CodexAppServerCommandExecutionStatus.Completed,
            commandActions = emptyList(),
            aggregatedOutput = output,
            exitCode = exitCode,
            durationMs = 5L,
            pluginId = null,
            scriptPath = null,
            raw = buildJsonObject { put("id", id); put("type", "commandExecution"); put("status", status) },
        )

    private fun change(path: String, kind: CodexAppServerPatchChangeKind, diff: String) =
        CodexAppServerFileUpdateChange(
            path = path,
            kind = kind,
            diff = diff,
            raw = buildJsonObject { put("path", path); put("diff", diff) },
        )
}
