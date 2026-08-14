package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CodexAppServerConnectionTest {
    @Test fun `Initializing observer can close synchronously without deadlock`() {
        runOnBoundedDaemonThread {
            fixture().use { f ->
                val observer = launch(Dispatchers.Unconfined) {
                    f.connection.state.first { it == CodexAppServerConnectionState.Initializing }
                    f.connection.close()
                }
                val initialize = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { f.connection.initialize() }
                }
                assertTrue(initialize.await().isFailure)
                observer.join()
                assertEquals(CodexAppServerConnectionState.Closed, f.connection.state.value)
                assertEquals(0, f.transport.successfulWriteCount())
                assertEquals(0, f.dispatcher.pendingRequestCount())
            }
        }
    }

    @Test fun `Ready observer can close synchronously without deadlock`() {
        runOnBoundedDaemonThread {
            fixture().use { f ->
                val observer = launch(Dispatchers.Unconfined) {
                    f.connection.state.first { it is CodexAppServerConnectionState.Ready }
                    f.connection.close()
                }
                val initialize = async(start = CoroutineStart.UNDISPATCHED) { f.connection.initialize() }
                f.transport.takeClientLine()
                f.success()
                assertEquals("codex/0.144", initialize.await().userAgent)
                observer.join()
                assertEquals(CodexAppServerConnectionState.Closed, f.connection.state.value)
                assertEquals("{\"method\":\"initialized\"}", f.transport.takeClientLine())
                assertEquals(2, f.transport.successfulWriteCount())
                assertEquals(0, f.dispatcher.pendingRequestCount())
            }
        }
    }

    @Test fun `close cancels a paused initialized write without releasing its gate`() = runBlocking {
        fixture().use { f ->
            val initialize = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { f.connection.initialize() }
            }
            f.transport.takeClientLine()
            val initializedGate = f.transport.pauseWrites()
            f.success()
            initializedGate.awaitWriteAttempt()

            closeOnBoundedDaemonThread(f.connection)

            assertTrue(initialize.await().isFailure)
            assertEquals(CodexAppServerConnectionState.Closed, f.connection.state.value)
            assertEquals(1, f.transport.successfulWriteCount())
            assertEquals(0, f.dispatcher.pendingRequestCount())
        }
    }

    @Test fun `initialize uses exact contract and decodes current response`() = runBlocking {
        fixture().use { f ->
            val call = async(start = CoroutineStart.UNDISPATCHED) { f.connection.initialize() }
            val requestLine = f.transport.takeClientLine()
            val request = json(requestLine)
            assertEquals("initialize", request["method"]!!.jsonPrimitive.content)
            assertEquals(1, request["id"]!!.jsonPrimitive.content.toInt())
            assertFalse("jsonrpc" in request)
            val params = request["params"]!!.jsonObject
            val info = params["clientInfo"]!!.jsonObject
            assertEquals("rikkahub_agent", info["name"]!!.jsonPrimitive.content)
            assertEquals("RikkaHub Agent", info["title"]!!.jsonPrimitive.content)
            assertEquals("0.1.0", info["version"]!!.jsonPrimitive.content)
            assertFalse("capabilities" in params)
            assertFalse(requestLine.contains("experimentalApi"))

            assertFalse(call.isCompleted)
            f.success(extra = ",\"futureField\":true")
            val response = call.await()
            assertEquals("codex/0.144", response.userAgent)
            assertEquals("/home/test/.codex", response.codexHome)
            assertEquals("unix", response.platformFamily)
            assertEquals("linux", response.platformOs)
            assertEquals("{\"method\":\"initialized\"}", f.transport.takeClientLine())
            assertTrue(f.connection.state.value is CodexAppServerConnectionState.Ready)
        }
    }

    @Test fun `concurrent and repeat initialize are single flight and cancellation isolated`() = runBlocking {
        fixture().use { f ->
            val calls = (1..50).map {
                async(start = CoroutineStart.UNDISPATCHED) { f.connection.initialize() }
            }
            val request = f.transport.takeClientLine()
            val cancelled = calls.first()
            cancelled.cancelAndJoin()
            f.success()
            val results = calls.drop(1).awaitAll()
            val initialized = f.transport.takeClientLine()
            assertEquals(49, results.size)
            assertTrue(results.all { it == results.first() })
            assertEquals("initialize", json(request)["method"]!!.jsonPrimitive.content)
            assertEquals("{\"method\":\"initialized\"}", initialized)
            val cached = f.connection.initialize()
            assertSame(results.first(), cached)
            assertEquals(CodexAppServerConnectionState.Ready(cached), f.connection.state.value)
            assertEquals(2, f.transport.successfulWriteCount())
        }
    }

    @Test fun `required missing and wrong types are terminal failures`() = runBlocking {
        listOf(
            """{"userAgent":"x","codexHome":"/x","platformFamily":"unix"}""",
            """{"userAgent":2,"codexHome":"/x","platformFamily":"unix","platformOs":"linux"}""",
            """[]""",
        ).forEach { malformed ->
            fixture().use { f ->
                val call = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.connection.initialize() } }
                f.transport.takeClientLine()
                f.transport.injectServerLine("""{"id":1,"result":$malformed}""")
                assertTrue(call.await().isFailure)
                assertTrue(f.connection.state.value is CodexAppServerConnectionState.Failed)
                assertEquals(0, f.dispatcher.pendingRequestCount())
                assertTrue(runCatching { f.connection.initialize() }.isFailure)
            }
        }
    }

    @Test fun `rpc error transport failure and eof fail handshake`() = runBlocking {
        val actions: List<(Fixture) -> Unit> = listOf(
            { it.transport.injectServerLine("""{"id":1,"error":{"code":-1,"message":"bad"}}""") },
            { it.transport.injectFailure(IllegalStateException("pipe")) },
            { it.transport.injectEof() },
        )
        actions.forEach { terminate ->
            fixture().use { f ->
                val call = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.connection.initialize() } }
                f.transport.takeClientLine()
                terminate(f)
                assertTrue(withTimeout(2.seconds) { call.await() }.isFailure)
                assertTrue(f.connection.state.value is CodexAppServerConnectionState.Failed)
                assertEquals(0, f.dispatcher.pendingRequestCount())
            }
        }
    }

    @Test fun `timeout and initialized write failure are terminal`() = runBlocking {
        fixture(timeoutMs = 20).use { f ->
            val call = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.connection.initialize() } }
            f.transport.takeClientLine()
            assertTrue(withTimeout(2.seconds) { call.await() }.isFailure)
            assertTrue(f.connection.state.value is CodexAppServerConnectionState.Failed)
            assertEquals(0, f.dispatcher.pendingRequestCount())
        }
        fixture().use { f ->
            val call = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.connection.initialize() } }
            f.transport.takeClientLine()
            f.transport.failNextWrite(IllegalStateException("write"))
            f.success()
            assertTrue(call.await().isFailure)
            assertTrue(f.connection.state.value is CodexAppServerConnectionState.Failed)
        }
    }

    @Test fun `close lifecycle is idempotent and close during initialize wins`() = runBlocking {
        fixture().use { f ->
            f.connection.close()
            f.connection.close()
            assertEquals(CodexAppServerConnectionState.Closed, f.connection.state.value)
            assertTrue(runCatching { f.connection.initialize() }.isFailure)
        }
        fixture().use { f ->
            val gate = f.transport.pauseWrites()
            val call = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.connection.initialize() } }
            gate.awaitWriteAttempt()
            val closing = async { f.connection.close() }
            f.transport.resumeWrites(gate)
            closing.await()
            assertTrue(call.await().isFailure)
            assertEquals(CodexAppServerConnectionState.Closed, f.connection.state.value)
        }
        fixture().use { f ->
            val call = async(start = CoroutineStart.UNDISPATCHED) { f.connection.initialize() }
            f.transport.takeClientLine(); f.success(); call.await(); f.transport.takeClientLine()
            f.connection.close(); f.connection.close()
            assertEquals(CodexAppServerConnectionState.Closed, f.connection.state.value)
        }
    }

    @Test fun `ready gate rejects before handshake and allows only ready`() = runBlocking {
        fixture().use { f ->
            assertTrue(runCatching { f.connection.sendRequestAfterReady("test/method") }.exceptionOrNull() is CodexAppServerNotReadyException)
            val init = async(start = CoroutineStart.UNDISPATCHED) { f.connection.initialize() }
            f.transport.takeClientLine()
            assertTrue(runCatching { f.connection.sendRequestAfterReady("test/method") }.isFailure)
            f.success(); init.await(); f.transport.takeClientLine()
            val request = async(start = CoroutineStart.UNDISPATCHED) { f.connection.sendRequestAfterReady("test/method") }
            val line = json(f.transport.takeClientLine())
            f.transport.injectServerLine("""{"id":${line["id"]},"result":{"ok":true}}""")
            assertTrue(request.await().jsonObject["ok"]!!.jsonPrimitive.content.toBoolean())
            f.connection.close()
            assertTrue(runCatching { f.connection.sendRequestAfterReady("test/method") }.isFailure)
        }
    }

    @Test fun `terminal transport after ready updates connection`() = runBlocking {
        fixture().use { f ->
            val call = async(start = CoroutineStart.UNDISPATCHED) { f.connection.initialize() }
            f.transport.takeClientLine(); f.success(); call.await(); f.transport.takeClientLine()
            f.transport.injectFailure(IllegalStateException("lost"))
            withTimeout(2.seconds) {
                while (f.connection.state.value is CodexAppServerConnectionState.Ready) kotlinx.coroutines.yield()
            }
            assertTrue(f.connection.state.value is CodexAppServerConnectionState.Failed)
        }
    }

    private fun fixture(timeoutMs: Long = 2_000): Fixture {
        val transport = FakeCodexAppServerTransport()
        val dispatcher = CodexAppServerRequestDispatcher(transport)
        return Fixture(
            transport, dispatcher,
            CodexAppServerConnection(
                dispatcher,
                CodexAppServerClientInfo(version = "0.1.0"),
                initializeTimeout = timeoutMs.milliseconds,
            )
        )
    }

    private fun runOnBoundedDaemonThread(block: suspend () -> Unit) {
        val executor = daemonExecutor()
        try {
            executor.submit { runBlocking { block() } }.get(2, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun closeOnBoundedDaemonThread(connection: CodexAppServerConnection) {
        val executor = daemonExecutor()
        try {
            executor.submit { connection.close() }.get(2, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun daemonExecutor() = Executors.newSingleThreadExecutor { task ->
        Thread(task, "connection-deadlock-regression").apply { isDaemon = true }
    }

    private fun json(line: String): JsonObject =
        CodexAppServerJsonRpc().json.parseToJsonElement(line).jsonObject

    private data class Fixture(
        val transport: FakeCodexAppServerTransport,
        val dispatcher: CodexAppServerRequestDispatcher,
        val connection: CodexAppServerConnection,
    ) : AutoCloseable {
        fun success(extra: String = "") = transport.injectServerLine(
            """{"id":1,"result":{"userAgent":"codex/0.144","codexHome":"/home/test/.codex","platformFamily":"unix","platformOs":"linux"$extra}}"""
        )
        override fun close() = connection.close()
    }
}
