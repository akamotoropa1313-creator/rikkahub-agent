package me.rerere.rikkahub.data.codex.appserver

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.workspace.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceCodexAppServerLauncherTest {
    @Test fun `launcher forwards exact command root and cwd through manager`() {
        val process = AppServerTestProcess()
        val runner = AppServerRecordingRunner(process)
        val base = createTempDirectory("launcher").toFile()
        val manager = WorkspaceManager(base, shellRunner = runner)
        manager.ensureWorkspace("workspace")
        File(manager.filesDir("workspace"), "project").mkdirs()

        val transport = WorkspaceCodexAppServerLauncher(manager).launch("workspace", "project")
        assertEquals("exec codex app-server --listen stdio://", runner.context.command)
        assertEquals("workspace", runner.context.root)
        assertEquals("project", runner.context.cwd)
        assertEquals(File(manager.filesDir("workspace"), "project"), runner.context.workingDir)
        transport.close()
    }

    @Test fun `dispatcher request response survives immediate process exit`() = runBlocking {
        val process = AppServerTestProcess()
        val runner = AppServerRecordingRunner(process)
        val base = createTempDirectory("launcher").toFile()
        val manager = WorkspaceManager(base, shellRunner = runner)
        manager.ensureWorkspace("workspace")
        val transport = WorkspaceCodexAppServerLauncher(manager).launch("workspace")
        val dispatcher = CodexAppServerRequestDispatcher(transport)

        val response = async(Dispatchers.Default) { dispatcher.sendRequest("test/method") }
        assertTrue(withContext(Dispatchers.IO) {
            process.stdin.flushed.await(2, java.util.concurrent.TimeUnit.SECONDS)
        })
        val request = CodexAppServerJsonRpc().json.parseToJsonElement(process.stdin.text().trim()).jsonObject
        assertEquals("test/method", request["method"]!!.jsonPrimitive.content)
        val id = request["id"]!!.jsonPrimitive.content
        process.writeStdout("{\"id\":$id,\"result\":{\"ok\":true}}\n")
        process.exit(0)

        assertTrue(withTimeout(2_000) { response.await().jsonObject["ok"]!!.jsonPrimitive.content.toBoolean() })
        dispatcher.close()
    }
}
