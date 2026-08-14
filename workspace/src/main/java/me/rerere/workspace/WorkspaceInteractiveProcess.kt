package me.rerere.workspace

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A live process whose streams belong to the caller and whose lifetime is tracked by WorkspaceManager. */
class WorkspaceInteractiveProcess internal constructor(
    private val process: Process,
    private val onClose: (WorkspaceInteractiveProcess) -> Unit,
    private val closeTimeoutMillis: Long = 1_000,
) : Closeable {
    private val closed = AtomicBoolean()
    val stdin: OutputStream get() = process.outputStream
    val stdout: InputStream get() = process.inputStream
    val stderr: InputStream get() = process.errorStream
    val isAlive: Boolean get() = process.isAlive
    fun exitValue(): Int = process.exitValue()
    fun waitFor(): Int = process.waitFor()
    fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit)
    fun destroy() = process.destroy()
    fun destroyForcibly(): Process = process.destroyForcibly()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            runCatching { stdin.close() }
            if (process.isAlive) process.destroy()
            val stopped = runCatching {
                !process.isAlive || process.waitFor(closeTimeoutMillis, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!stopped && process.isAlive) process.destroyForcibly()
        } finally {
            onClose(this)
        }
    }
}
