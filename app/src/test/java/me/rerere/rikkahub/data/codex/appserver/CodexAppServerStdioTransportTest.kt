package me.rerere.rikkahub.data.codex.appserver

import java.io.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import me.rerere.workspace.*

class CodexAppServerStdioTransportTest {
    @Test fun `outbound framing UTF8 escaping rejection and flush`() = runBlocking {
        val fixture = fixture()
        fixture.transport.sendLine("{\"text\":\"雪\\n\"}")
        assertEquals("{\"text\":\"雪\\n\"}\n", fixture.process.stdin.text())
        assertEquals(1, fixture.process.stdin.flushes)
        assertTrue(runCatching { fixture.transport.sendLine("a\nb") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { fixture.transport.sendLine("a\rb") }.exceptionOrNull() is IllegalArgumentException)
        fixture.transport.close()
    }

    @Test fun `concurrent writes are complete frames`() = runBlocking {
        val fixture = fixture()
        coroutineScope { (0 until 100).map { i -> async { fixture.transport.sendLine("frame-$i") } }.awaitAll() }
        val lines = fixture.process.stdin.text().lines().filter { it.isNotEmpty() }
        assertEquals(100, lines.size)
        assertEquals((0 until 100).map { "frame-$it" }.toSet(), lines.toSet())
        fixture.transport.close()
    }

    @Test fun `stdout preserves blank unicode and huge physical lines`() = runBlocking {
        val fixture = fixture()
        val events = async(Dispatchers.Default) { fixture.transport.events.toList() }
        val huge = "雪".repeat(70_000)
        fixture.process.writeStdout("one\n\n$huge\n")
        fixture.process.exit(0)
        val received = events.await()
        assertEquals(listOf("one", "", huge), received.filterIsInstance<CodexAppServerTransportEvent.Line>().map { it.value })
        assertSame(CodexAppServerTransportEvent.Closed, received.last())
    }

    @Test fun `stderr is drained bounded newest and never protocol`() = runBlocking {
        val fixture = fixture()
        val events = async(Dispatchers.Default) { fixture.transport.events.toList() }
        fixture.process.writeStderr("old" + "x".repeat(CODEX_APP_SERVER_STDERR_TAIL_CHARS) + "new")
        fixture.process.exit(7)
        val received = events.await()
        assertTrue(received.none { it is CodexAppServerTransportEvent.Line })
        val failure = (received.single() as CodexAppServerTransportEvent.Failure).cause
            as CodexAppServerProcessExitedException
        assertEquals(7, failure.exitCode)
        assertTrue(failure.stderrTail.length <= CODEX_APP_SERVER_STDERR_TAIL_CHARS)
        assertTrue(failure.stderrTail.endsWith("new"))
        assertFalse(failure.stderrTail.startsWith("old"))
    }

    @Test fun `final stdout before immediate exit precedes terminal`() = runBlocking {
        val fixture = fixture()
        val events = async(Dispatchers.Default) { fixture.transport.events.toList() }
        fixture.process.writeStdout("{\"final\":true}\n")
        fixture.process.exit(0)
        assertEquals(listOf(
            CodexAppServerTransportEvent.Line("{\"final\":true}"),
            CodexAppServerTransportEvent.Closed,
        ), events.await())
    }

    @Test fun `nonzero EOF race retains exit classification`() = runBlocking {
        val fixture = fixture()
        val events = async(Dispatchers.Default) { fixture.transport.events.toList() }
        fixture.process.exit(23)
        val terminal = events.await().single() as CodexAppServerTransportEvent.Failure
        assertEquals(23, (terminal.cause as CodexAppServerProcessExitedException).exitCode)
    }

    @Test fun `stdout EOF while live is terminal failure`() = runBlocking {
        val fixture = fixture()
        val events = async(Dispatchers.Default) { fixture.transport.events.toList() }
        fixture.process.closeStdout()
        val terminal = withTimeout(2_000) { events.await().single() }
        assertTrue((terminal as CodexAppServerTransportEvent.Failure).cause is CodexAppServerUnexpectedStdoutEofException)
        assertFalse(fixture.process.isAlive)
    }

    @Test fun `stdin IOException is exactly one failure`() = runBlocking {
        val fixture = fixture(failWrites = true)
        val events = async(Dispatchers.Default) { fixture.transport.events.toList() }
        assertTrue(runCatching { fixture.transport.sendLine("x") }.exceptionOrNull() is IOException)
        fixture.transport.close(); fixture.transport.close()
        assertEquals(1, events.await().count { it !is CodexAppServerTransportEvent.Line })
        assertEquals(1, fixture.process.destroyCalls)
    }

    @Test fun `stdout IOException while live is failure`() = runBlocking {
        val fixture = fixture(failReads = true)
        val terminal = withTimeout(2_000) { fixture.transport.events.toList().single() }
        assertTrue((terminal as CodexAppServerTransportEvent.Failure).cause is IOException)
    }

    @Test fun `stderr IOException while live is terminal failure`() = runBlocking {
        val fixture = fixture(failStderrReads = true)
        val terminal = withTimeout(2_000) { fixture.transport.events.toList().single() }
        assertTrue((terminal as CodexAppServerTransportEvent.Failure).cause is IOException)
    }

    @Test fun `close is idempotent and unblocks both readers`() = runBlocking {
        val fixture = fixture()
        val events = async(Dispatchers.Default) { fixture.transport.events.toList() }
        fixture.transport.close(); fixture.transport.close()
        assertEquals(listOf(CodexAppServerTransportEvent.Closed), events.await())
        assertEquals(1, fixture.process.destroyCalls)
        assertTrue(fixture.process.stdoutClosed.await(2, TimeUnit.SECONDS))
        assertTrue(fixture.process.stderrClosed.await(2, TimeUnit.SECONDS))
    }

    private fun fixture(
        failWrites: Boolean = false,
        failReads: Boolean = false,
        failStderrReads: Boolean = false,
    ): Fixture {
        val process = AppServerTestProcess(failWrites, failReads, failStderrReads)
        val base = createTempDirectory("stdio").toFile()
        val manager = WorkspaceManager(base, shellRunner = AppServerRecordingRunner(process))
        manager.ensureWorkspace("root")
        return Fixture(process, CodexAppServerStdioTransport(manager.startInteractiveProcess("root", "server")))
    }

    private data class Fixture(val process: AppServerTestProcess, val transport: CodexAppServerStdioTransport)
}

internal class RecordingOutput(private val fail: Boolean) : OutputStream() {
    private val bytes = ByteArrayOutputStream()
    private val flushMonitor = java.lang.Object()
    @Volatile var flushes = 0
    val flushed = CountDownLatch(1)
    @Synchronized override fun write(b: Int) { if (fail) throw IOException("write failed"); bytes.write(b) }
    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
        if (fail) throw IOException("write failed"); bytes.write(b, off, len)
    }
    override fun flush() = synchronized(flushMonitor) {
        flushes++; flushed.countDown(); flushMonitor.notifyAll()
    }
    fun awaitFlushCount(expected: Int, timeoutMillis: Long): Boolean = synchronized(flushMonitor) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (flushes < expected) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            TimeUnit.NANOSECONDS.timedWait(flushMonitor, remaining)
        }
        true
    }
    @Synchronized fun text() = bytes.toString(StandardCharsets.UTF_8.name())
}

internal class AppServerTestProcess(
    failWrites: Boolean = false,
    private val failReads: Boolean = false,
    private val failStderrReads: Boolean = false,
) : Process() {
    val stdin = RecordingOutput(failWrites)
    private val stdoutIn = PipedInputStream(256 * 1024)
    private val stdoutOut = PipedOutputStream(stdoutIn)
    private val stderrIn = PipedInputStream(256 * 1024)
    private val stderrOut = PipedOutputStream(stderrIn)
    private val exited = CountDownLatch(1)
    val stdoutClosed = CountDownLatch(1)
    val stderrClosed = CountDownLatch(1)
    @Volatile private var code: Int? = null
    var destroyCalls = 0
    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = if (failReads) FailingInputStream() else
        object : FilterInputStream(stdoutIn) { override fun close() { super.close(); stdoutClosed.countDown() } }
    override fun getErrorStream(): InputStream = if (failStderrReads) FailingInputStream() else
        object : FilterInputStream(stderrIn) { override fun close() { super.close(); stderrClosed.countDown() } }
    fun writeStdout(value: String) { stdoutOut.write(value.toByteArray(StandardCharsets.UTF_8)); stdoutOut.flush() }
    fun writeStderr(value: String) { stderrOut.write(value.toByteArray(StandardCharsets.UTF_8)); stderrOut.flush() }
    fun closeStdout() = stdoutOut.close()
    fun exit(value: Int) { code = value; stdoutOut.close(); stderrOut.close(); exited.countDown() }
    override fun waitFor(): Int { exited.await(); return code!! }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
    override fun exitValue(): Int = code ?: throw IllegalThreadStateException()
    override fun isAlive() = code == null
    override fun destroy() { destroyCalls++; if (isAlive) exit(143) }
    override fun destroyForcibly(): Process { if (isAlive) exit(137); return this }
}

private class FailingInputStream : InputStream() {
    override fun read(): Int = throw IOException("read failed")
    override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("read failed")
}

internal class AppServerRecordingRunner(private val process: Process) : WorkspaceShellRunner {
    lateinit var context: WorkspaceShellContext
    override fun start(context: WorkspaceShellContext): Process { this.context = context; return process }
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult = error("unused")
}
