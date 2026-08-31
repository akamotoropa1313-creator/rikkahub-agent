package me.rerere.rikkahub.data.harness

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HarnessUsageTest {
    @Test
    fun `one run is counted on exactly one matching message`() {
        val messages = listOf(
            message("run-1", "first"),
            message("run-1", "second"),
        )

        val updated = messages.withHarnessUsage(
            AgentHarnessUsage(
                harnessId = AgentHarnessIds.CODEX,
                runId = "run-1",
                inputTokens = 120,
                outputTokens = 30,
                cachedInputTokens = 80,
                totalTokens = 150,
                reasoningOutputTokens = 12,
                cacheWriteInputTokens = 4,
            )
        )

        assertNull(updated.first().usage)
        assertEquals(120, updated.last().usage?.promptTokens)
        assertEquals(30, updated.last().usage?.completionTokens)
        assertEquals(80, updated.last().usage?.cachedTokens)
        assertEquals(150, updated.last().usage?.totalTokens)
        assertEquals(12L, updated.last().harness?.reasoningOutputTokens)
        assertEquals(4L, updated.last().harness?.cacheWriteInputTokens)
    }

    @Test
    fun `later telemetry replaces rather than duplicates a run`() {
        val initial = listOf(message("run-1", "answer")).withHarnessUsage(
            AgentHarnessUsage(AgentHarnessIds.CODEX, "run-1", 10, 2)
        )

        val updated = initial.withHarnessUsage(
            AgentHarnessUsage(
                harnessId = AgentHarnessIds.CODEX,
                runId = "run-1",
                inputTokens = Long.MAX_VALUE,
                outputTokens = 7,
                totalTokens = Long.MAX_VALUE,
            )
        )

        assertEquals(1, updated.count { it.usage != null })
        assertEquals(Int.MAX_VALUE, updated.single().usage?.promptTokens)
        assertEquals(7, updated.single().usage?.completionTokens)
        assertEquals(Int.MAX_VALUE, updated.single().usage?.totalTokens)
    }

    @Test
    fun `telemetry before a matching message is a harmless no-op`() {
        val messages = listOf(message("another-run", "answer"))

        val updated = messages.withHarnessUsage(
            AgentHarnessUsage(AgentHarnessIds.CODEX, "run-1", 10, 2)
        )

        assertSame(messages, updated)
    }

    @Test
    fun `Codex presentation steps are not replayed as provider tool calls`() {
        val codex = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("thinking"),
                UIMessagePart.Tool(
                    toolCallId = "shell-1",
                    toolName = "workspace_shell",
                    input = "{\"command\":\"pwd\"}",
                    output = listOf(UIMessagePart.Text("{\"stdout\":\"/workspace\"}")),
                ),
                UIMessagePart.Text("done"),
            ),
        ).withHarnessIdentity(AgentHarnessIds.CODEX, "run-1")
        val toolOnly = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("shell-2", "workspace_shell", "{}")),
        ).withHarnessIdentity(AgentHarnessIds.CODEX, "run-2")
        val regular = UIMessage.assistant("regular")

        val requestMessages = listOf(codex, toolOnly, regular).withoutAgentHarnessPresentationParts()

        assertEquals(2, requestMessages.size)
        assertEquals(listOf(UIMessagePart.Text("done")), requestMessages[0].parts)
        assertSame(regular, requestMessages[1])
    }

    private fun message(runId: String, text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    ).withHarnessIdentity(AgentHarnessIds.CODEX, runId)
}
