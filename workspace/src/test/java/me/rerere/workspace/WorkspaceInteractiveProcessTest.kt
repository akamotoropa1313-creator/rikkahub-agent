package me.rerere.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class WorkspaceInteractiveProcessTest {
    @Test fun `start uses runner and forwards complete context and streams`() {
        val base = createTempDirectory("interactive").toFile()
        val process = ControlledProcess()
        val runner = RecordingRunner { process }
        val mount = WorkspaceBindMount(File(base, "mounted"), "/mounted")
        val manager = WorkspaceManager(base, shellRunner = runner, bindMounts = listOf(mount))
        manager.ensureWorkspace("root")
        val cwd = File(manager.filesDir("root"), "sub").apply { mkdirs() }

        val handle = manager.startInteractiveProcess("root", "exec server", "sub")
        val context = runner.context!!
        assertEquals(1, runner.starts)
        assertEquals("root", context.root)
        assertEquals("exec server", context.command)
        assertEquals("sub", context.cwd)
        assertEquals(manager.filesDir("root"), context.filesDir)
        assertEquals(manager.linuxDir("root"), context.linuxDir)
        assertEquals(manager.tempDir("root"), context.tempDir)
        assertEquals(cwd, context.workingDir)
        assertEquals(listOf(mount), context.bindMounts)
        assertSame(process.getOutputStream(), handle.stdin)
        assertSame(process.getInputStream(), handle.stdout)
        assertSame(process.getErrorStream(), handle.stderr)
        handle.close()
    }

    @Test fun `close is idempotent closes stdin and tries graceful destroy`() {
        val process = ControlledProcess()
        val handle = managed(process)
        handle.close(); handle.close()
        assertTrue(process.stdin.closed)
        assertEquals(1, process.destroyCalls)
        assertEquals(0, process.forceCalls)
    }

    @Test fun `normal destroy refusal forces then performs bounded wait`() {
        val process = ControlledProcess(ignoreDestroy = true)
        managed(process).close()
        assertEquals(1, process.forceCalls)
        assertEquals(2, process.timedWaitCalls)
        assertFalse(process.isAlive)
    }

    @Test fun `cleanup failure prevents deletion and remains explicit`() {
        val base = createTempDirectory("interactive").toFile()
        val process = ControlledProcess(ignoreDestroy = true, ignoreForce = true)
        val manager = WorkspaceManager(base, shellRunner = RecordingRunner { process })
        manager.ensureWorkspace("root")
        manager.startInteractiveProcess("root", "server")
        assertThrows(WorkspaceProcessCleanupException::class.java) { manager.deleteWorkspace("root") }
        assertTrue(manager.workspaceDir("root").exists())
        assertTrue(process.isAlive)
    }

    @Test fun `deletion kills interactive process before directory`() {
        val base = createTempDirectory("interactive").toFile()
        val process = ControlledProcess()
        val manager = WorkspaceManager(base, shellRunner = RecordingRunner { process })
        manager.ensureWorkspace("root")
        manager.startInteractiveProcess("root", "server")
        assertTrue(manager.deleteWorkspace("root"))
        assertFalse(process.isAlive)
        assertFalse(manager.workspaceDir("root").exists())
    }

    @Test fun `start and delete race cannot orphan process`() {
        val base = createTempDirectory("interactive").toFile()
        val process = ControlledProcess()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = RecordingRunner {
            entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); process
        }
        val manager = WorkspaceManager(base, shellRunner = runner)
        manager.ensureWorkspace("root")
        val started = CountDownLatch(1)
        val startThread = thread { manager.startInteractiveProcess("root", "server"); started.countDown() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val deleted = CountDownLatch(1)
        val deleteThread = thread { manager.deleteWorkspace("root"); deleted.countDown() }
        assertFalse(deleted.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(started.await(2, TimeUnit.SECONDS)); assertTrue(deleted.await(2, TimeUnit.SECONDS))
        startThread.join(); deleteThread.join()
        assertFalse(process.isAlive)
    }

    @Test fun `background still receives bind mounts and deletion kills it`() {
        val base = createTempDirectory("interactive").toFile()
        val process = ControlledProcess()
        val mount = WorkspaceBindMount(File(base, "mount"), "/mount")
        val runner = RecordingRunner { process }
        val manager = WorkspaceManager(base, shellRunner = runner, bindMounts = listOf(mount))
        manager.ensureWorkspace("root")
        manager.startBackground("root", "server")
        assertEquals(listOf(mount), runner.context!!.bindMounts)
        manager.deleteWorkspace("root")
        assertFalse(process.isAlive)
        assertTrue(manager.listBackground("root").isEmpty())
    }

    private fun managed(process: ControlledProcess) =
        WorkspaceInteractiveProcess(process, {}, closeTimeoutMillis = 1)
}

private class RecordingRunner(private val factory: () -> Process) : WorkspaceShellRunner {
    var context: WorkspaceShellContext? = null
    var starts = 0
    override fun start(context: WorkspaceShellContext): Process {
        starts++; this.context = context; return factory()
    }
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult = error("not used")
}

private class CloseTrackingOutputStream : ByteArrayOutputStream() {
    var closed = false
    override fun close() { closed = true; super.close() }
}

private class ControlledProcess(
    private val ignoreDestroy: Boolean = false,
    private val ignoreForce: Boolean = false,
) : Process() {
    val stdin = CloseTrackingOutputStream()
    private val stdout = ByteArrayInputStream("out".toByteArray())
    private val stderr = ByteArrayInputStream("err".toByteArray())
    @Volatile private var alive = true
    var destroyCalls = 0
    var forceCalls = 0
    var timedWaitCalls = 0
    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr
    override fun isAlive() = alive
    override fun destroy() { destroyCalls++; if (!ignoreDestroy) alive = false }
    override fun destroyForcibly(): Process { forceCalls++; if (!ignoreForce) alive = false; return this }
    override fun waitFor(): Int { alive = false; return 0 }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { timedWaitCalls++; return !alive }
    override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
}
