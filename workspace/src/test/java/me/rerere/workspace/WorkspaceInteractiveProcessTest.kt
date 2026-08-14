package me.rerere.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class WorkspaceInteractiveProcessTest {
    @Test fun `manager forwards complete context and exposes streams`() {
        val base = createTempDir()
        val process = FakeProcess()
        val runner = RecordingRunner(process)
        val mount = WorkspaceBindMount(File(base, "mounted"), "/mounted")
        val manager = WorkspaceManager(base, shellRunner = runner, bindMounts = listOf(mount))
        manager.ensureWorkspace("root")

        val handle = manager.startInteractiveProcess("root", "exec server", "")
        val context = runner.context!!
        assertEquals("root", context.root)
        assertEquals("exec server", context.command)
        assertEquals(manager.filesDir("root"), context.filesDir)
        assertEquals(manager.linuxDir("root"), context.linuxDir)
        assertEquals(manager.tempDir("root"), context.tempDir)
        assertEquals(listOf(mount), context.bindMounts)
        assertSame(process.outputStream, handle.stdin)
        assertSame(process.inputStream, handle.stdout)
        assertSame(process.errorStream, handle.stderr)
        handle.close(); handle.close()
        assertEquals(1, process.destroyCalls)
    }

    @Test fun `workspace deletion terminates interactive process`() {
        val base = createTempDir()
        val process = FakeProcess()
        val manager = WorkspaceManager(base, shellRunner = RecordingRunner(process))
        manager.ensureWorkspace("root")
        manager.startInteractiveProcess("root", "server")
        manager.deleteWorkspace("root")
        assertFalse(process.isAlive)
    }
}

private class RecordingRunner(private val process: Process) : WorkspaceShellRunner {
    var context: WorkspaceShellContext? = null
    override fun start(context: WorkspaceShellContext): Process { this.context = context; return process }
    override fun execute(context: WorkspaceShellContext) = error("not used")
}

private class FakeProcess : Process() {
    override val outputStream = ByteArrayOutputStream()
    override val inputStream = ByteArrayInputStream("out".toByteArray())
    override val errorStream = ByteArrayInputStream("err".toByteArray())
    private var alive = true
    var destroyCalls = 0
    override fun isAlive() = alive
    override fun destroy() { destroyCalls++; alive = false }
    override fun destroyForcibly(): Process { alive = false; return this }
    override fun waitFor(): Int { alive = false; return 0 }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { alive = false; return true }
    override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
}
