package me.rerere.rikkahub.data.codex.appserver

import java.util.Collections
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CodexAppServerRequestDispatcherTest {
    @Test
    fun requestAllocatesIdAndWritesOneJsonDocument() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.sendRequest("thread/example", buildJsonObject { put("value", 7) })
            }
            val line = transport.takeClientLine()
            assertTrue(!line.contains('\n'))
            val decoded = CodexAppServerJsonRpc().decode(line).getOrThrow() as JsonRpcMessage.Request
            assertEquals(JsonRpcId.NumberId(1), decoded.value.id)
            assertEquals(7, decoded.value.params!!.jsonObject["value"]!!.jsonPrimitive.int)
            transport.injectServerLine("""{"id":1,"result":null}""")
            assertEquals(JsonNull, request.await())
        }
    }

    @Test
    fun notificationAndServerResponsesUseCodec() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            dispatcher.sendNotification("item/ping", buildJsonObject { put("x", true) })
            assertTrue(CodexAppServerJsonRpc().decode(transport.takeClientLine()).getOrThrow() is JsonRpcMessage.Notification)

            dispatcher.respondSuccess(JsonRpcId.StringId("approval"), buildJsonObject { put("ok", true) })
            val success = CodexAppServerJsonRpc().decode(transport.takeClientLine()).getOrThrow() as JsonRpcMessage.Response
            assertEquals(JsonRpcId.StringId("approval"), success.value.id)

            dispatcher.respondError(JsonRpcId.StringId("approval"), JsonRpcError(12, "denied"))
            val error = CodexAppServerJsonRpc().decode(transport.takeClientLine()).getOrThrow() as JsonRpcMessage.ErrorResponse
            assertEquals("denied", error.value.error.message)
        }
    }

    @Test
    fun correlatesSuccessAndNumericStringCompatibility() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val request = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.sendRequest("test") }
            transport.takeClientLine()
            transport.injectServerLine("""{"id":"1","result":{"ok":true}}""")
            assertTrue(request.await().jsonObject["ok"]!!.jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun correlatesErrorToCaller() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val request = async(start = CoroutineStart.UNDISPATCHED) { runCatching { dispatcher.sendRequest("test") } }
            transport.takeClientLine()
            transport.injectServerLine("""{"id":1,"error":{"code":-1,"message":"bad"}}""")
            val thrown = request.await().exceptionOrNull()
            assertTrue(thrown is CodexAppServerResponseException)
            assertEquals(-1, (thrown as CodexAppServerResponseException).error.code)
        }
    }

    @Test
    fun concurrentRequestsCorrelateOutOfOrderAndIdsDoNotDuplicate() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val requests = (1..30).map { n ->
                async(start = CoroutineStart.UNDISPATCHED) { n to dispatcher.sendRequest("m$n") }
            }
            val messages = (1..30).map {
                CodexAppServerJsonRpc().decode(transport.takeClientLine()).getOrThrow() as JsonRpcMessage.Request
            }
            assertEquals(30, messages.map { it.value.id }.toSet().size)
            messages.reversed().forEach { message ->
                val n = message.value.method.removePrefix("m")
                transport.injectServerLine("""{"id":${(message.value.id as JsonRpcId.NumberId).value},"result":$n}""")
            }
            assertEquals((1..30).toList(), requests.awaitAll().sortedBy { it.first }.map { it.second.jsonPrimitive.int })
        }
    }

    @Test
    fun notificationAndServerRequestAreDeliveredInInboundOrderWithId() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val events = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.events.take(2).toList() }
            transport.injectServerLine("""{"method":"first","params":{"n":1}}""")
            transport.injectServerLine("""{"id":"approval-7","method":"second"}""")
            val received = withTimeout(2.seconds) { events.await() }
            assertEquals("first", (received[0] as CodexAppServerEvent.UnknownNotification).method)
            assertEquals(JsonRpcId.StringId("approval-7"), (received[1] as CodexAppServerEvent.ServerRequest).id)
        }
    }

    @Test
    fun malformedUnknownAndDuplicateResponsesProduceDiagnostics() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val events = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.events.take(3).toList() }
            transport.injectServerLine("not-json")
            transport.injectServerLine("""{"id":99,"result":null}""")
            transport.injectServerLine("""{"id":99,"result":null}""")
            val received = withTimeout(2.seconds) { events.await() }
            assertTrue(received[0] is CodexAppServerEvent.MalformedInbound)
            assertTrue(received[1] is CodexAppServerEvent.UnknownResponseId)
            assertTrue(received[2] is CodexAppServerEvent.UnknownResponseId)
        }
    }

    @Test
    fun lateResponseAfterCompletionIsSafeAndObservable() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val request = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.sendRequest("test") }
            transport.takeClientLine()
            transport.injectServerLine("""{"id":1,"result":null}""")
            request.await()
            val event = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.events.take(1).toList().single() }
            transport.injectServerLine("""{"id":1,"result":null}""")
            assertTrue(withTimeout(2.seconds) { event.await() } is CodexAppServerEvent.UnknownResponseId)
        }
    }

    @Test
    fun cancellationAndTimeoutAlwaysCleanPendingEntries() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.sendRequest("cancel") }
            transport.takeClientLine()
            cancelled.cancelAndJoin()
            assertEquals(0, dispatcher.pendingRequestCount())

            val timedRequest = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { dispatcher.sendRequest("timeout", timeout = 20.milliseconds) }
            }
            transport.takeClientLine()
            val timeout = timedRequest.await()
            assertTrue(timeout.isFailure)
            assertEquals(0, dispatcher.pendingRequestCount())
        }
    }

    @Test
    fun closeFailsEveryPendingRequestAndCleansMap() = runBlocking {
        val transport = FakeCodexAppServerTransport()
        val dispatcher = CodexAppServerRequestDispatcher(transport)
        val requests = List(2) {
            async(start = CoroutineStart.UNDISPATCHED) { runCatching { dispatcher.sendRequest("wait") } }
        }
        repeat(2) { transport.takeClientLine() }
        dispatcher.close()
        requests.forEach { assertTrue(it.await().exceptionOrNull() is CodexAppServerDispatcherClosedException) }
        assertEquals(0, dispatcher.pendingRequestCount())
    }

    @Test
    fun transportFailureIsObservableAndFailsEveryPendingRequest() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val event = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.events.take(1).toList().single() }
            val request = async(start = CoroutineStart.UNDISPATCHED) { runCatching { dispatcher.sendRequest("wait") } }
            transport.takeClientLine()
            val failure = IllegalStateException("broken pipe")
            transport.injectFailure(failure)
            assertEquals(failure, request.await().exceptionOrNull())
            assertTrue(withTimeout(2.seconds) { event.await() } is CodexAppServerEvent.TransportFailure)
            assertEquals(0, dispatcher.pendingRequestCount())
        }
    }

    @Test
    fun eofFailsPendingRequest() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val request = async(start = CoroutineStart.UNDISPATCHED) { runCatching { dispatcher.sendRequest("wait") } }
            transport.takeClientLine()
            transport.injectEof()
            assertTrue(request.await().exceptionOrNull() is CodexAppServerTransportClosedException)
            assertEquals(0, dispatcher.pendingRequestCount())
        }
    }

    @Test
    fun allocatorDoesNotWrapAtSignedLongLimit() {
        val allocator = CodexAppServerRequestIdAllocator(Long.MAX_VALUE)
        assertEquals(JsonRpcId.NumberId(Long.MAX_VALUE), allocator.allocate())
        assertTrue(runCatching { allocator.allocate() }.isFailure)

        val ids = Collections.synchronizedSet(mutableSetOf<JsonRpcId.NumberId>())
        val concurrent = CodexAppServerRequestIdAllocator()
        val threads = List(8) { Thread { repeat(1_000) { ids += concurrent.allocate() } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(8_000, ids.size)
    }

    private fun fixture(): Fixture = Fixture(FakeCodexAppServerTransport())

    private class Fixture(val transport: FakeCodexAppServerTransport) : AutoCloseable {
        val dispatcher = CodexAppServerRequestDispatcher(transport)
        operator fun component1() = transport
        operator fun component2() = dispatcher
        override fun close() = dispatcher.close()
    }
}
