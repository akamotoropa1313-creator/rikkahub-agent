package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.codex.appserver.CODEX_FILE_CHANGE_STATUS_METADATA_KEY
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageEditedFilesTest {
    @Test
    fun `only completed successful file tools become candidates`() {
        val parts = listOf(
            tool("ok", "/workspace/index.html", "{\"path\":\"/workspace/index.html\"}"),
            tool("relative", "notes.txt", "{}"),
            tool("absolute-duplicate", "/workspace/probe.txt", "{}"),
            tool("relative-duplicate", "probe.txt", "{}"),
            tool("error", "/workspace/error.txt", "{\"error\":\"tool_failed\"}"),
            tool("failed", "/workspace/failed.txt", "{}", codexStatus = "failed"),
            tool("pending", "/workspace/pending.txt", "{}", codexStatus = "inProgress"),
            tool("completed", "/workspace/done.txt", "{}", codexStatus = "completed"),
            UIMessagePart.Tool("shell", "workspace_shell", "{}", listOf(UIMessagePart.Text("{}"))),
        )

        assertEquals(
            listOf(
                "/workspace/index.html",
                "/workspace/notes.txt",
                "/workspace/probe.txt",
                "/workspace/done.txt",
            ),
            editedFileCandidates(parts),
        )
    }

    @Test
    fun `duplicate basenames are disambiguated with their paths`() {
        val paths = listOf("/workspace/a/probe.txt", "/workspace/b/probe.txt", "/workspace/index.html")

        assertEquals("a/probe.txt", editedFileDisplayLabel(paths[0], paths))
        assertEquals("b/probe.txt", editedFileDisplayLabel(paths[1], paths))
        assertEquals("index.html", editedFileDisplayLabel(paths[2], paths))
    }

    private fun tool(
        id: String,
        path: String,
        output: String,
        codexStatus: String? = null,
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "workspace_write_file",
        input = "{\"path\":\"$path\"}",
        output = listOf(UIMessagePart.Text(output)),
        metadata = codexStatus?.let {
            buildJsonObject { put(CODEX_FILE_CHANGE_STATUS_METADATA_KEY, it) }
        },
    )
}
