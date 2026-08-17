package me.rerere.rikkahub.data.codex.appserver

import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexAppServerApprovalStdioIntegrationTest {
    private val json = CodexAppServerJsonRpc().json

    @Test
    fun `controlled stdio approval lifecycle preserves authority ids and ready connection`() = runBlocking {
        val process = AppServerTestProcess()
        val manager = WorkspaceManager(
            createTempDirectory("approval-stdio").toFile(),
            shellRunner = AppServerRecordingRunner(process),
        )
        manager.ensureWorkspace("workspace")
        val connection = WorkspaceCodexAppServerConnectionFactory(manager, "0.1.0").create("workspace")
        val rawRequests = Channel<CodexAppServerEvent.ServerRequest>(Channel.UNLIMITED)
        val approvals = Channel<CodexAppServerApprovalEvent>(Channel.UNLIMITED)
        val turnEvents = Channel<CodexAppServerTurnEvent>(Channel.UNLIMITED)
        val rawCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            connection.events.collect { if (it is CodexAppServerEvent.ServerRequest) rawRequests.send(it) }
        }
        val approvalApi = CodexAppServerApprovalApi(connection)
        val approvalCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            approvalApi.events.collect { approvals.send(it) }
        }
        val turnApi = CodexAppServerTurnApi(connection)
        val turnCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            turnApi.events.collect { turnEvents.send(it) }
        }
        try {
            val initializing = async(Dispatchers.Default) { connection.initialize() }
            awaitFlushes(process, 1)
            val initialize = line(process, 0)
            assertEquals("initialize", initialize["method"]!!.jsonPrimitive.content)
            process.writeStdout(response(initialize, buildJsonObject {
                put("userAgent", "codex/test"); put("codexHome", "/tmp")
                put("platformFamily", "unix"); put("platformOs", "linux")
            }))
            initializing.await()
            awaitFlushes(process, 2)
            assertEquals("initialized", line(process, 1)["method"]!!.jsonPrimitive.content)

            val threadApi = CodexAppServerThreadApi(connection)
            val startingThread = async(Dispatchers.Default) { threadApi.startThread() }
            awaitFlushes(process, 3)
            val threadStart = line(process, 2)
            process.writeStdout(response(threadStart, buildJsonObject {
                put("thread", buildJsonObject { put("id", "thread-1") })
                put("model", "gpt"); put("modelProvider", "openai"); put("cwd", "/workspace")
            }))
            assertEquals("thread-1", startingThread.await().thread.id)

            val startingTurn = async(Dispatchers.Default) {
                turnApi.startTurn("thread-1", listOf(CodexAppServerTurnInput.Text("Make changes")))
            }
            awaitFlushes(process, 4)
            val turnStart = line(process, 3)
            process.writeStdout(notification("turn/started", turnParams("inProgress")))
            process.writeStdout(response(turnStart, buildJsonObject { put("turn", turn("inProgress")) }))
            assertEquals("turn-1", startingTurn.await().turn.id)
            assertTrue(receiveTurn(turnEvents) is CodexAppServerTurnEvent.TurnStarted)

            val command = command("inProgress")
            process.writeStdout(notification("item/started", itemParams(command, "startedAtMs", 10)))
            assertTrue(receiveTurn(turnEvents) is CodexAppServerTurnEvent.ItemStarted)
            val commandParams = buildJsonObject {
                put("threadId", "thread-1"); put("turnId", "turn-1"); put("itemId", "command-1")
                put("startedAtMs", 10); put("approvalId", "callback-99")
                put("command", "echo test"); put("cwd", "/workspace"); put("commandActions", JsonArray(emptyList()))
            }
            val writesBeforeCommandApproval = process.stdin.flushes
            process.writeStdout(serverRequest(JsonRpcId.StringId("rpc-command-7"), "item/commandExecution/requestApproval", commandParams))
            val rawCommand = receiveRaw(rawRequests)
            val typedCommand = receiveApproval(approvals) as CodexAppServerApprovalEvent.CommandExecutionRequest
            assertEquals(JsonRpcId.StringId("rpc-command-7"), rawCommand.id)
            assertEquals(JsonRpcId.StringId("rpc-command-7"), typedCommand.requestId)
            assertEquals(writesBeforeCommandApproval, process.stdin.flushes)

            approvalApi.respondCommandApproval(typedCommand.requestId, CodexAppServerCommandApprovalDecision.Accept)
            awaitFlushes(process, writesBeforeCommandApproval + 1)
            assertApprovalResponse(line(process, writesBeforeCommandApproval), JsonRpcId.StringId("rpc-command-7"), "accept")
            process.writeStdout(notification("serverRequest/resolved", resolved("rpc-command-7")))
            assertEquals(JsonRpcId.StringId("rpc-command-7"), (receiveApproval(approvals) as CodexAppServerApprovalEvent.Resolved).requestId)
            process.writeStdout(notification("item/completed", itemParams(command("completed"), "completedAtMs", 11)))
            assertTrue(receiveTurn(turnEvents) is CodexAppServerTurnEvent.ItemCompleted)

            val change = buildJsonObject {
                put("path", "file.txt"); put("kind", buildJsonObject { put("type", "update") }); put("diff", "+new\n")
            }
            process.writeStdout(notification("item/started", itemParams(fileChange(change, "inProgress"), "startedAtMs", 12)))
            assertTrue(receiveTurn(turnEvents) is CodexAppServerTurnEvent.ItemStarted)
            val fileParams = buildJsonObject {
                put("threadId", "thread-1"); put("turnId", "turn-1"); put("itemId", "file-1"); put("startedAtMs", 12)
            }
            val writesBeforeFileApproval = process.stdin.flushes
            process.writeStdout(serverRequest(JsonRpcId.NumberId(42), "item/fileChange/requestApproval", fileParams))
            val rawFile = receiveRaw(rawRequests)
            val typedFile = receiveApproval(approvals) as CodexAppServerApprovalEvent.FileChangeRequest
            assertEquals(JsonRpcId.NumberId(42), rawFile.id); assertEquals(JsonRpcId.NumberId(42), typedFile.requestId)
            assertEquals(writesBeforeFileApproval, process.stdin.flushes)

            approvalApi.respondFileChangeApproval(typedFile.requestId, CodexAppServerFileChangeApprovalDecision.Decline)
            awaitFlushes(process, writesBeforeFileApproval + 1)
            assertApprovalResponse(line(process, writesBeforeFileApproval), JsonRpcId.NumberId(42), "decline")
            process.writeStdout(notification("serverRequest/resolved", resolved(42)))
            assertEquals(JsonRpcId.NumberId(42), (receiveApproval(approvals) as CodexAppServerApprovalEvent.Resolved).requestId)
            process.writeStdout(notification("item/completed", itemParams(fileChange(change, "declined"), "completedAtMs", 13)))
            val completedFile = receiveTurn(turnEvents) as CodexAppServerTurnEvent.ItemCompleted
            assertEquals(CodexAppServerPatchApplyStatus.Declined, (completedFile.item as CodexAppServerItemSnapshot.FileChange).status)
            process.writeStdout(notification("turn/completed", turnParams("completed")))
            assertTrue(receiveTurn(turnEvents) is CodexAppServerTurnEvent.TurnCompleted)
            assertTrue(connection.state.value is CodexAppServerConnectionState.Ready)
            assertTrue(process.isAlive)
        } finally {
            rawCollector.cancelAndJoin(); approvalCollector.cancelAndJoin(); turnCollector.cancelAndJoin()
            rawRequests.close(); approvals.close(); turnEvents.close(); connection.close()
        }
    }

    private suspend fun awaitFlushes(process: AppServerTestProcess, count: Int) =
        assertTrue(withContext(Dispatchers.IO) { process.stdin.awaitFlushCount(count, 2_000) })
    private suspend fun receiveRaw(channel: Channel<CodexAppServerEvent.ServerRequest>) = withTimeout(2_000) { channel.receive() }
    private suspend fun receiveApproval(channel: Channel<CodexAppServerApprovalEvent>) = withTimeout(2_000) { channel.receive() }
    private suspend fun receiveTurn(channel: Channel<CodexAppServerTurnEvent>) = withTimeout(2_000) { channel.receive() }
    private fun line(process: AppServerTestProcess, index: Int) = json.parseToJsonElement(process.stdin.text().lineSequence().filter(String::isNotEmpty).toList()[index]).jsonObject
    private fun response(request: JsonObject, result: JsonObject) = "{\"id\":${request["id"]},\"result\":$result}\n"
    private fun notification(method: String, params: JsonObject) = "{\"method\":${JsonPrimitive(method)},\"params\":$params}\n"
    private fun serverRequest(id: JsonRpcId, method: String, params: JsonObject): String {
        val encodedId = when (id) { is JsonRpcId.StringId -> JsonPrimitive(id.value); is JsonRpcId.NumberId -> JsonPrimitive(id.value) }
        return "{\"id\":$encodedId,\"method\":${JsonPrimitive(method)},\"params\":$params}\n"
    }
    private fun assertApprovalResponse(raw: JsonObject, id: JsonRpcId, decision: String) {
        assertEquals(setOf("id", "result"), raw.keys); assertFalse("jsonrpc" in raw)
        val expectedId = when (id) { is JsonRpcId.StringId -> JsonPrimitive(id.value); is JsonRpcId.NumberId -> JsonPrimitive(id.value) }
        assertEquals(expectedId, raw["id"]); assertEquals(decision, raw["result"]!!.jsonObject["decision"]!!.jsonPrimitive.content)
    }
    private fun turn(status: String) = buildJsonObject { put("id", "turn-1"); put("status", status) }
    private fun turnParams(status: String) = buildJsonObject { put("threadId", "thread-1"); put("turn", turn(status)) }
    private fun command(status: String) = buildJsonObject { put("type", "commandExecution"); put("id", "command-1"); put("command", "echo test"); put("cwd", "/workspace"); put("status", status); put("commandActions", JsonArray(emptyList())) }
    private fun fileChange(change: JsonObject, status: String) = buildJsonObject { put("type", "fileChange"); put("id", "file-1"); put("status", status); put("changes", JsonArray(listOf(change))) }
    private fun itemParams(item: JsonObject, timeKey: String, time: Long) = buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1"); put("item", item); put(timeKey, time) }
    private fun resolved(requestId: Any) = buildJsonObject { put("threadId", "thread-1"); when (requestId) { is String -> put("requestId", requestId); is Int -> put("requestId", requestId); else -> error("unsupported") } }
}
