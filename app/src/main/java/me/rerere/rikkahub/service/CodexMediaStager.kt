package me.rerere.rikkahub.service

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerTurnInput
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

private const val CODEX_MEDIA_ROOT = "tmp/rikkahub-codex-input"
internal const val CODEX_IMAGE_MAX_BYTES = 64L * 1024 * 1024 // RikkaHub Android safety limit, not an App Server protocol limit.
internal const val CODEX_AUDIO_MAX_BYTES = 50L * 1024 * 1024
private val CODEX_AUDIO_EXTENSIONS = setOf("wav", "mp3", "m4a", "webm", "ogg")

class CodexMediaStager(
    private val context: Context,
    private val workspaces: WorkspaceRepository,
) {
    class StagedTurn internal constructor(
        val inputs: List<CodexAppServerTurnInput>,
        private val workspaceId: String,
        private val relativeDirectory: String,
        private val workspaces: WorkspaceRepository,
    ) {
        suspend fun cleanup() = withContext(NonCancellable) {
            runCatching { workspaces.deleteFile(workspaceId, WorkspaceStorageArea.LINUX, relativeDirectory, true) }
        }
    }

    suspend fun stage(workspaceId: String, conversationId: String, parts: List<UIMessagePart>): StagedTurn {
        require(conversationId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid conversation identifier" }
        val conversationDirectory = "$CODEX_MEDIA_ROOT/$conversationId"
        // Only this conversation's stale artifacts are removed; other active conversations are isolated.
        withContext(NonCancellable) {
            runCatching { workspaces.deleteFile(workspaceId, WorkspaceStorageArea.LINUX, conversationDirectory, true) }
        }
        val turnDirectory = "$conversationDirectory/${UUID.randomUUID()}"
        val staged = ArrayList<CodexAppServerTurnInput>()
        try {
            for (part in parts) when (part) {
                is UIMessagePart.Image -> staged += stageOne(workspaceId, turnDirectory, part.url, false)
                is UIMessagePart.Audio -> staged += stageOne(workspaceId, turnDirectory, part.url, true)
                else -> Unit
            }
            return StagedTurn(staged, workspaceId, turnDirectory, workspaces)
        } catch (e: Exception) {
            withContext(NonCancellable) {
                runCatching { workspaces.deleteFile(workspaceId, WorkspaceStorageArea.LINUX, turnDirectory, true) }
            }
            throw e
        }
    }

    private suspend fun stageOne(workspaceId: String, directory: String, source: String, audio: Boolean): CodexAppServerTurnInput {
        val uri = Uri.parse(source)
        if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "data") {
            return if (audio) CodexAppServerTurnInput.Audio(source) else CodexAppServerTurnInput.Image(source)
        }
        require(uri.scheme == "file" || uri.scheme == "content") { "Unsupported attachment URI scheme" }
        val extension = extension(uri)
        if (audio) require(extension in CODEX_AUDIO_EXTENSIONS) { "Unsupported audio format" }
        val name = UUID.randomUUID().toString() + if (audio) ".$extension" else extension?.let { ".$it" }.orEmpty()
        val max = if (audio) CODEX_AUDIO_MAX_BYTES else CODEX_IMAGE_MAX_BYTES
        val input = when (uri.scheme) {
            "file" -> {
                val file = File(uri.path ?: throw FileNotFoundException("Attachment is missing"))
                require(file.exists() && file.isFile && file.canRead()) { "Local attachment is missing or unreadable" }
                require(file.length() <= max) { if (audio) "Audio is too large" else "Image is too large" }
                file.inputStream()
            }
            else -> context.contentResolver.openInputStream(uri)
                ?: throw FileNotFoundException("Attachment is missing or unreadable")
        }
        try {
            workspaces.importFile(workspaceId, WorkspaceStorageArea.LINUX, directory, name, input, max)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(if (audio) "Audio is too large or could not be staged" else "Image is too large or could not be staged", e)
        }
        val path = "/$directory/$name"
        return if (audio) CodexAppServerTurnInput.LocalAudio(path) else CodexAppServerTurnInput.LocalImage(path)
    }

    private fun extension(uri: Uri): String? = uri.lastPathSegment?.substringAfterLast('.', "")
        ?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
}
