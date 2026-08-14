package me.rerere.workspace

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** A live process whose streams belong to the caller and whose lifetime is tracked by WorkspaceManager. */
class WorkspaceInteractiveProcess internal constructor(
    private val process: Process,
    private val onClose: (WorkspaceInteractiveProcess) -> Unit,
    private val closeTimeoutMillis: Long = 1_000,
) : Closeable {
    private var closed = false
    val stdin: OutputStream get() = process.outputStream
    val stdout: InputStream get() = process.inputStream
    val stderr: InputStream get() = process.errorStream
    val isAlive: Boolean get() = process.isAlive
    fun exitValue(): Int = process.exitValue()
    fun waitFor(): Int = process.waitFor()
    fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)
    fun destroy() = process.destroy()
    fun destroyForcibly(): Process = process.destroyForcibly()

    @Synchronized
    override fun close() {
        if (closed) return
        runCatching { stdin.close() }
        if (process.isAlive) process.destroy()
        var stopped = awaitStopped()
        if (!stopped && process.isAlive) {
            process.destroyForcibly()
            stopped = awaitStopped()
        }
        if (!stopped && process.isAlive) {
            throw WorkspaceProcessCleanupException("Process remained alive after forceful shutdown")
        }
        closed = true
        onClose(this)
    }

    private fun awaitStopped(): Boolean = runCatching {
        process.waitFor(closeTimeoutMillis, TimeUnit.MILLISECONDS)
    }.getOrElse { !process.isAlive }
}

class WorkspaceProcessCleanupException(message: String) : IllegalStateException(message)
