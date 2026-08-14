package me.rerere.rikkahub.data.codex.appserver

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.workspace.WorkspaceInteractiveProcess

const val CODEX_APP_SERVER_STDERR_TAIL_CHARS = 64 * 1024

class CodexAppServerProcessExitedException(
    val exitCode: Int,
    val stderrTail: String,
) : IOException("Codex App Server exited with code $exitCode" +
    if (stderrTail.isBlank()) "" else ": ${stderrTail.takeLast(512)}")

/** Production JSONL transport. Blocking process IO is owned by dedicated daemon threads. */
class CodexAppServerStdioTransport(
    private val process: WorkspaceInteractiveProcess,
) : CodexAppServerTransport {
    private val channel = Channel<CodexAppServerTransportEvent>(Channel.UNLIMITED)
    override val events: Flow<CodexAppServerTransportEvent> = channel.receiveAsFlow()
    private val writerMutex = Mutex()
    private val terminal = AtomicBoolean()
    private val emissionLock = Any()
    private val stderrLock = Any()
    private val stderr = StringBuilder()

    private val stdoutThread = daemon("codex-app-server-stdout") {
        try {
            process.stdout.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    synchronized(emissionLock) {
                        if (!terminal.get()) channel.trySend(CodexAppServerTransportEvent.Line(line))
                    }
                }
            }
        } catch (error: IOException) {
            if (!terminal.get()) fail(error)
        }
    }
    private val stderrThread = daemon("codex-app-server-stderr") {
        try {
            process.stderr.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val chars = CharArray(4096)
                while (true) {
                    val count = reader.read(chars)
                    if (count < 0) break
                    appendStderr(String(chars, 0, count))
                }
            }
        } catch (error: IOException) {
            if (!terminal.get() && process.isAlive) fail(error)
        }
    }
    private val watcherThread = daemon("codex-app-server-watcher") {
        try {
            val code = process.waitFor()
            stderrThread.join(1_000)
            if (code == 0) finish(CodexAppServerTransportEvent.Closed)
            else finish(CodexAppServerTransportEvent.Failure(
                CodexAppServerProcessExitedException(code, stderrTail())
            ))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!terminal.get()) fail(error)
        }
    }

    override suspend fun sendLine(line: String) {
        require('\n' !in line && '\r' !in line) { "JSONL frame must not contain literal CR or LF" }
        writerMutex.withLock {
            check(!terminal.get()) { "Transport is closed" }
            try {
                process.stdin.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
                process.stdin.flush()
            } catch (error: IOException) {
                fail(error)
                throw error
            }
        }
    }

    fun stderrTail(): String = synchronized(stderrLock) { stderr.toString() }

    override fun close() {
        finish(CodexAppServerTransportEvent.Closed)
    }

    private fun fail(error: Throwable) = finish(CodexAppServerTransportEvent.Failure(error))

    private fun finish(event: CodexAppServerTransportEvent) {
        synchronized(emissionLock) {
            if (!terminal.compareAndSet(false, true)) return
            channel.trySend(event)
            channel.close()
        }
        runCatching { process.stdout.close() }
        runCatching { process.stderr.close() }
        runCatching { process.close() }
    }

    private fun appendStderr(text: String) = synchronized(stderrLock) {
        stderr.append(text)
        val excess = stderr.length - CODEX_APP_SERVER_STDERR_TAIL_CHARS
        if (excess > 0) stderr.delete(0, excess)
    }

    private fun daemon(name: String, block: () -> Unit): Thread = Thread(block, name).apply {
        isDaemon = true
        start()
    }
}
