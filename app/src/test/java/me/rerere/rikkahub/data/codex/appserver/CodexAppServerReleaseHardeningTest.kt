package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class CodexAppServerReleaseHardeningTest {
    @Test
    fun `method not found is request local and dispatcher remains usable`() {
        runBlocking {
            fixture().use { f ->
                val unsupported = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { f.dispatcher.sendRequest("future/method") }
                }
                f.transport.takeClientLine()
                f.transport.injectServerLine(
                    """{"id":1,"error":{"code":-32601,"message":"Method not found","data":{"future":true}}}""",
                )

                val error = unsupported.await().exceptionOrNull()
                assertTrue(error is CodexAppServerResponseException)
                error as CodexAppServerResponseException
                assertEquals(-32601L, error.error.code)
                assertEquals("Method not found", error.error.message)
                assertEquals(0, f.dispatcher.pendingRequestCount())

                val next = async(start = CoroutineStart.UNDISPATCHED) {
                    f.dispatcher.sendRequest("thread/read")
                }
                f.transport.takeClientLine()
                f.transport.injectServerLine("""{"id":2,"result":{"ok":true}}""")
                assertTrue(next.await().jsonObject["ok"]!!.jsonPrimitive.content.toBoolean())
                assertEquals(0, f.dispatcher.pendingRequestCount())
            }
        }
    }

    @Test
    fun `server overload is request local and dispatcher remains usable`() {
        runBlocking {
            fixture().use { f ->
                val overloaded = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { f.dispatcher.sendRequest("model/list") }
                }
                f.transport.takeClientLine()
                f.transport.injectServerLine(
                    """{"id":1,"error":{"code":-32001,"message":"Server overloaded; retry later."}}""",
                )

                val error = overloaded.await().exceptionOrNull()
                assertTrue(error is CodexAppServerResponseException)
                error as CodexAppServerResponseException
                assertEquals(-32001L, error.error.code)
                assertEquals("Server overloaded; retry later.", error.error.message)
                assertEquals(0, f.dispatcher.pendingRequestCount())

                val next = async(start = CoroutineStart.UNDISPATCHED) {
                    f.dispatcher.sendRequest("account/read")
                }
                f.transport.takeClientLine()
                f.transport.injectServerLine("""{"id":2,"result":{"account":null}}""")
                assertTrue("account" in next.await().jsonObject)
                assertEquals(0, f.dispatcher.pendingRequestCount())
            }
        }
    }

    @Test
    fun `unknown notification and malformed inbound are diagnostic only`() {
        runBlocking {
            fixture().use { f ->
                val diagnostics = async(start = CoroutineStart.UNDISPATCHED) {
                    f.dispatcher.events.take(2).toList()
                }
                f.transport.injectServerLine(
                    """{"method":"future/notification","params":{"v":1,"future":true}}""",
                )
                f.transport.injectServerLine("not-json")

                val events = withTimeout(2.seconds) { diagnostics.await() }
                assertTrue(events[0] is CodexAppServerEvent.UnknownNotification)
                assertTrue(events[1] is CodexAppServerEvent.MalformedInbound)

                val next = async(start = CoroutineStart.UNDISPATCHED) {
                    f.dispatcher.sendRequest("thread/read")
                }
                f.transport.takeClientLine()
                f.transport.injectServerLine("""{"id":1,"result":{"ok":true}}""")
                assertTrue(next.await().jsonObject["ok"]!!.jsonPrimitive.content.toBoolean())
                assertEquals(0, f.dispatcher.pendingRequestCount())
            }
        }
    }

    @Test
    fun `explicit jsonrpc 2_0 and future response fields remain compatible`() {
        runBlocking {
            fixture().use { f ->
                val request = async(start = CoroutineStart.UNDISPATCHED) {
                    f.dispatcher.sendRequest("thread/read")
                }
                f.transport.takeClientLine()
                f.transport.injectServerLine(
                    """{"jsonrpc":"2.0","id":1,"result":{"ok":true,"future":{"nested":7}},"futureEnvelope":true}""",
                )

                val result = request.await().jsonObject
                assertTrue(result["ok"]!!.jsonPrimitive.content.toBoolean())
                assertEquals(7, result["future"]!!.jsonObject["nested"]!!.jsonPrimitive.content.toInt())
                assertEquals(0, f.dispatcher.pendingRequestCount())
            }
        }
    }

    private fun fixture(): Fixture = Fixture(FakeCodexAppServerTransport())

    private class Fixture(val transport: FakeCodexAppServerTransport) : AutoCloseable {
        val dispatcher = CodexAppServerRequestDispatcher(transport)
        override fun close() = dispatcher.close()
    }
}
