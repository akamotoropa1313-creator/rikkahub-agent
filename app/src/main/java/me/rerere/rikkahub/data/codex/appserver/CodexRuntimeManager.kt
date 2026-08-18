package me.rerere.rikkahub.data.codex.appserver

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import me.rerere.workspace.WorkspaceManager

enum class CodexRuntimeErrorCategory {
    RuntimeMissing, RuntimeUnsupported, RuntimeInstallFailed, RuntimeCorrupt, RuntimeVersionMismatch,
    AppServerLaunchFailed, AppServerExited, InitializeFailed, AuthenticationRequired, AuthenticationFailed,
    UsageLimitExceeded, ProtocolIncompatible, ModelCatalogDecodeFailed, WorkspaceInvalid, NetworkFailure,
    Timeout, Unknown,
}

enum class CodexRuntimePhase {
    IDLE, CHECKING_WORKSPACE, CHECKING_PLATFORM, CHECKING_RUNTIME, DOWNLOADING, VERIFYING,
    INSTALLING, VALIDATING, READY, FAILED,
}

data class CodexRuntimeProgress(
    val phase: CodexRuntimePhase = CodexRuntimePhase.IDLE,
    val detail: String? = null,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val errorCategory: CodexRuntimeErrorCategory? = null,
    val errorMessage: String? = null,
    val version: String? = null,
    val architecture: String? = null,
)

data class CodexRuntimeReady(
    val executable: String,
    val version: String,
    val architecture: String,
    val managed: Boolean,
)

class CodexRuntimeProvisioningException(
    val category: CodexRuntimeErrorCategory,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal data class CodexRuntimeAsset(
    val architecture: String,
    val assetName: String,
    val archiveSha256: String,
    val archiveEntry: String,
)

/**
 * Provisions one app-managed, pinned OpenAI Codex standalone binary and exposes it to every PRoot
 * through [RUNTIME_BIND_ROOT]. The normal path never invokes apt/npm/node/dpkg/debconf/tzdata, so
 * fresh Ubuntu Workspaces cannot block on package-manager prompts. Downloads are verified against
 * OpenAI's published SHA-256 before atomic installation.
 */
class CodexRuntimeManager(
    private val workspaceManager: WorkspaceManager,
    private val httpClient: OkHttpClient,
    private val runtimeBaseDir: File,
) {
    private val rootLocks = ConcurrentHashMap<String, Mutex>()
    private val installLock = Mutex()
    private val states = ConcurrentHashMap<String, MutableStateFlow<CodexRuntimeProgress>>()
    private val downloadClient by lazy {
        httpClient.newBuilder().callTimeout(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES).build()
    }

    fun state(root: String): StateFlow<CodexRuntimeProgress> =
        states.getOrPut(root) { MutableStateFlow(CodexRuntimeProgress()) }.asStateFlow()

    suspend fun ensureReady(root: String): CodexRuntimeReady = rootLocks.getOrPut(root) { Mutex() }.withLock {
        val state = states.getOrPut(root) { MutableStateFlow(CodexRuntimeProgress()) }
        try {
            publish(state, CodexRuntimePhase.CHECKING_WORKSPACE, "Workspaceを確認しています")
            if (!workspaceManager.hasRootfs(root)) {
                fail(CodexRuntimeErrorCategory.WorkspaceInvalid, "Workspace Linux環境が準備できていません")
            }

            publish(state, CodexRuntimePhase.CHECKING_PLATFORM, "OSとアーキテクチャを確認しています")
            val platform = runWorkspace(root, "uname -s; uname -m", 10_000)
            val lines = platform.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (platform.timedOut) fail(CodexRuntimeErrorCategory.Timeout, "Workspace platform check timed out")
            if (platform.exitCode != 0 || lines.size < 2 || lines[0] != "Linux") {
                fail(CodexRuntimeErrorCategory.RuntimeUnsupported, "Codex runtime requires a Linux Workspace")
            }
            val asset = assetFor(lines[1])
                ?: fail(CodexRuntimeErrorCategory.RuntimeUnsupported, "Unsupported Workspace architecture: ${lines[1]}")
            state.value = state.value.copy(architecture = asset.architecture, version = VALIDATED_VERSION)

            val installDir = File(runtimeBaseDir, "$VALIDATED_VERSION/${asset.architecture}")
            val executable = File(installDir, "codex")
            publish(state, CodexRuntimePhase.CHECKING_RUNTIME, "Codex runtimeを確認しています", asset)
            validateInstalled(root, executable, asset)?.let { ready ->
                publish(state, CodexRuntimePhase.READY, "Codex $VALIDATED_VERSION · ${asset.architecture}", asset)
                return@withLock ready
            }

            installLock.withLock {
                if (validateInstalled(root, executable, asset) == null) {
                    provision(state, asset, installDir, executable)
                }
            }

            publish(state, CodexRuntimePhase.VALIDATING, "Codex App Serverを検証しています", asset)
            val ready = validateInstalled(root, executable, asset)
                ?: fail(CodexRuntimeErrorCategory.RuntimeCorrupt, "Installed Codex runtime failed validation")
            publish(state, CodexRuntimePhase.READY, "Codex $VALIDATED_VERSION · ${asset.architecture}", asset)
            ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CodexRuntimeProvisioningException) {
            state.value = state.value.copy(
                phase = CodexRuntimePhase.FAILED,
                errorCategory = failure.category,
                errorMessage = failure.message,
            )
            throw failure
        } catch (failure: Throwable) {
            val wrapped = CodexRuntimeProvisioningException(
                classifyProvisioningFailure(failure),
                failure.message ?: "Codex runtime setup failed",
                failure,
            )
            state.value = state.value.copy(
                phase = CodexRuntimePhase.FAILED,
                errorCategory = wrapped.category,
                errorMessage = wrapped.message,
            )
            throw wrapped
        }
    }

    suspend fun repair(root: String): CodexRuntimeReady {
        states.getOrPut(root) { MutableStateFlow(CodexRuntimeProgress()) }.value =
            CodexRuntimeProgress(phase = CodexRuntimePhase.IDLE, detail = "Codex runtimeを再検証します")
        return ensureReady(root)
    }

    private suspend fun provision(
        state: MutableStateFlow<CodexRuntimeProgress>,
        asset: CodexRuntimeAsset,
        installDir: File,
        executable: File,
    ) {
        runtimeBaseDir.mkdirs()
        val usable = runtimeBaseDir.usableSpace
        if (usable in 1 until MIN_FREE_BYTES) {
            fail(CodexRuntimeErrorCategory.RuntimeInstallFailed, "Codex runtime用の空き容量が不足しています")
        }
        installDir.mkdirs()
        installDir.listFiles()?.filter { it.name.startsWith(".codex.staging-") || it.name.endsWith(".part") }
            ?.forEach(File::delete)
        val temp = File(installDir, "${asset.assetName}.part")
        temp.delete()
        downloadWithRetry(state, asset, temp)

        publish(state, CodexRuntimePhase.VERIFYING, "ダウンロードを検証しています", asset)
        if (!sha256(temp).equals(asset.archiveSha256, ignoreCase = true)) {
            temp.delete()
            fail(CodexRuntimeErrorCategory.RuntimeCorrupt, "Codex runtimeの整合性確認に失敗しました")
        }

        publish(state, CodexRuntimePhase.INSTALLING, "Codex runtimeを導入しています", asset)
        val staging = File(installDir, ".codex.staging-${System.nanoTime()}")
        val previous = File(installDir, ".codex.previous")
        try {
            extractSingleTarGz(temp, staging, asset.archiveEntry)
            if (!staging.setExecutable(true, false) || !staging.setReadable(true, false)) {
                fail(CodexRuntimeErrorCategory.RuntimeInstallFailed, "Codex runtime permissions could not be set")
            }
            previous.delete()
            if (executable.exists() && !executable.renameTo(previous)) {
                fail(CodexRuntimeErrorCategory.RuntimeInstallFailed, "Existing Codex runtime could not be staged for update")
            }
            if (!staging.renameTo(executable)) {
                previous.takeIf(File::exists)?.renameTo(executable)
                fail(CodexRuntimeErrorCategory.RuntimeInstallFailed, "Codex runtime could not be installed atomically")
            }
            File(installDir, "INSTALL-METADATA").writeText(
                "version=$VALIDATED_VERSION\nasset=${asset.assetName}\nsha256=${asset.archiveSha256}\nsource=$RELEASE_BASE/rust-v$VALIDATED_VERSION\n",
            )
            previous.delete()
        } finally {
            staging.delete()
            temp.delete()
        }
    }

    private suspend fun validateInstalled(root: String, executable: File, asset: CodexRuntimeAsset): CodexRuntimeReady? {
        if (!executable.isFile || !executable.canExecute()) return null
        val visiblePath = "$RUNTIME_BIND_ROOT/$VALIDATED_VERSION/${asset.architecture}/codex"
        val version = runWorkspace(root, "${shellQuote(visiblePath)} --version", 15_000)
        if (version.timedOut || version.exitCode != 0 || !version.stdout.contains(VALIDATED_VERSION)) return null
        val appServer = runWorkspace(root, "${shellQuote(visiblePath)} app-server --help >/dev/null 2>&1", 15_000)
        if (appServer.timedOut || appServer.exitCode != 0) return null
        return CodexRuntimeReady(visiblePath, VALIDATED_VERSION, asset.architecture, managed = true)
    }

    private suspend fun runWorkspace(root: String, command: String, timeoutMs: Long) =
        runInterruptible(Dispatchers.IO) { workspaceManager.executeCommand(root, command, timeoutMillis = timeoutMs) }

    private suspend fun downloadWithRetry(
        state: MutableStateFlow<CodexRuntimeProgress>,
        asset: CodexRuntimeAsset,
        target: File,
    ) {
        var last: Throwable? = null
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                target.delete()
                downloadOnce(state, asset, target, attempt + 1)
                return
            } catch (cancelled: CancellationException) {
                target.delete()
                throw cancelled
            } catch (failure: Throwable) {
                target.delete()
                last = failure
                if (attempt + 1 < DOWNLOAD_ATTEMPTS) delay(750L * (attempt + 1))
            }
        }
        throw CodexRuntimeProvisioningException(
            CodexRuntimeErrorCategory.NetworkFailure,
            "OpenAI公式Codex runtimeをダウンロードできませんでした",
            last,
        )
    }

    private suspend fun downloadOnce(
        state: MutableStateFlow<CodexRuntimeProgress>,
        asset: CodexRuntimeAsset,
        target: File,
        attempt: Int,
    ) = runInterruptible(Dispatchers.IO) {
        publish(state, CodexRuntimePhase.DOWNLOADING, "OpenAI公式Codex runtimeをダウンロードしています ($attempt/$DOWNLOAD_ATTEMPTS)", asset)
        val request = Request.Builder()
            .url("$RELEASE_BASE/rust-v$VALIDATED_VERSION/${asset.assetName}")
            .get()
            .build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                fail(CodexRuntimeErrorCategory.NetworkFailure, "Codex runtime download failed: HTTP ${response.code}")
            }
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 }
            target.outputStream().buffered().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var readTotal = 0L
                    var nextReport = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Codex runtime download cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        readTotal += read
                        if (readTotal >= nextReport || readTotal == total) {
                            state.value = CodexRuntimeProgress(
                                phase = CodexRuntimePhase.DOWNLOADING,
                                detail = "OpenAI公式Codex runtimeをダウンロードしています ($attempt/$DOWNLOAD_ATTEMPTS)",
                                bytesRead = readTotal,
                                totalBytes = total,
                                version = VALIDATED_VERSION,
                                architecture = asset.architecture,
                            )
                            nextReport = readTotal + 512 * 1024
                        }
                    }
                }
            }
        }
    }

    private fun extractSingleTarGz(archive: File, target: File, expectedEntry: String) {
        GZIPInputStream(archive.inputStream().buffered()).use { input ->
            while (true) {
                val header = ByteArray(512)
                val read = input.readFullyOrEnd(header)
                if (read == 0) break
                if (read != 512) throw EOFException("Truncated Codex archive header")
                if (header.all { it == 0.toByte() }) break
                val name = header.tarString(0, 100).removePrefix("./")
                val size = header.tarOctal(124, 12)
                val type = header[156].toInt().toChar()
                if (name == expectedEntry && (type == '0' || type == '\u0000')) {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> input.copyExactly(output, size) }
                    input.skipFully(size.tarPadding())
                    return
                }
                input.skipFully(size + size.tarPadding())
            }
        }
        fail(CodexRuntimeErrorCategory.RuntimeCorrupt, "Official Codex archive did not contain the expected executable")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun publish(
        state: MutableStateFlow<CodexRuntimeProgress>,
        phase: CodexRuntimePhase,
        detail: String,
        asset: CodexRuntimeAsset? = null,
    ) {
        state.value = CodexRuntimeProgress(
            phase = phase,
            detail = detail,
            version = if (asset != null) VALIDATED_VERSION else state.value.version,
            architecture = asset?.architecture ?: state.value.architecture,
        )
    }

    companion object {
        const val VALIDATED_VERSION = "0.146.0"
        const val SUPPORTED_MIN_VERSION = VALIDATED_VERSION
        const val SUPPORTED_MAX_VERSION = VALIDATED_VERSION
        const val RUNTIME_BIND_ROOT = "/rikkahub_runtime"
        const val RUNTIME_HOST_DIR_NAME = "codex_runtime"
        private const val RELEASE_BASE = "https://github.com/openai/codex/releases/download"
        private const val MIN_FREE_BYTES = 320L * 1024L * 1024L
        private const val DOWNLOAD_ATTEMPTS = 3
        private const val DOWNLOAD_TIMEOUT_MINUTES = 5L

        internal val ASSETS = listOf(
            CodexRuntimeAsset(
                architecture = "aarch64",
                assetName = "codex-aarch64-unknown-linux-musl.tar.gz",
                archiveSha256 = "975bac91562abeedeb8f79636d51a86649b31f34a9de6a3bcb059565b6cf1f87",
                archiveEntry = "codex-aarch64-unknown-linux-musl",
            ),
            CodexRuntimeAsset(
                architecture = "x86_64",
                assetName = "codex-x86_64-unknown-linux-musl.tar.gz",
                archiveSha256 = "5ba3b9405543953081f661d0854d266f76e2abbe51d41349355a36de7673776a",
                archiveEntry = "codex-x86_64-unknown-linux-musl",
            ),
        )

        internal fun assetFor(rawArchitecture: String): CodexRuntimeAsset? {
            val normalized = when (rawArchitecture.trim().lowercase()) {
                "arm64", "aarch64" -> "aarch64"
                "amd64", "x86_64" -> "x86_64"
                else -> rawArchitecture.trim().lowercase()
            }
            return ASSETS.firstOrNull { it.architecture == normalized }
        }

        internal fun classifyProvisioningFailure(failure: Throwable): CodexRuntimeErrorCategory {
            val text = generateSequence(failure) { it.cause }.take(5).joinToString(" ") { it.message.orEmpty() }.lowercase()
            return when {
                "timed out" in text || "timeout" in text -> CodexRuntimeErrorCategory.Timeout
                "network" in text || "http " in text || "unable to resolve host" in text || "connect" in text -> CodexRuntimeErrorCategory.NetworkFailure
                "space" in text || "no space" in text -> CodexRuntimeErrorCategory.RuntimeInstallFailed
                else -> CodexRuntimeErrorCategory.RuntimeInstallFailed
            }
        }

        private fun fail(category: CodexRuntimeErrorCategory, message: String): Nothing =
            throw CodexRuntimeProvisioningException(category, message)

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read < 0) break
        offset += read
    }
    return offset
}

private fun ByteArray.tarString(offset: Int, length: Int): String {
    val end = (offset until offset + length).firstOrNull { this[it] == 0.toByte() } ?: offset + length
    return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
}

private fun ByteArray.tarOctal(offset: Int, length: Int): Long =
    tarString(offset, length).trim().trimEnd('\u0000').let { if (it.isBlank()) 0L else it.toLong(8) }

private fun Long.tarPadding(): Long = (512L - (this % 512L)).let { if (it == 512L) 0L else it }

private fun InputStream.skipFully(bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) remaining -= skipped
        else if (read() >= 0) remaining--
        else throw EOFException("Truncated Codex archive")
    }
}

private fun InputStream.copyExactly(output: java.io.OutputStream, bytes: Long) {
    var remaining = bytes
    val buffer = ByteArray(128 * 1024)
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) throw EOFException("Truncated Codex executable")
        output.write(buffer, 0, read)
        remaining -= read
    }
}
