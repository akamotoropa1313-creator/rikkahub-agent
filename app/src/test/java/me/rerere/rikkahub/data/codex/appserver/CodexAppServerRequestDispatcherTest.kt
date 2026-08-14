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
            assertEquals(JsonRpcId.StringId("approval"), error.value.id)
            assertEquals("denied", error.value.error.message)
        }
    }

    @Test
    fun numericAndStringIdsAreNotInterchangeable() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val request = async(start = CoroutineStart.UNDISPATCHED) { dispatcher.sendRequest("test") }
            transport.takeClientLine()
            val diagnostic = async(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.events.take(1).toList().single()
            }
            transport.injectServerLine("""{"id":"1","result":{"ok":true}}""")
            assertEquals(
                CodexAppServerEvent.UnknownResponseId(JsonRpcId.StringId("1")),
                withTimeout(2.seconds) { diagnostic.await() },
            )
            assertTrue(!request.isCompleted)
            transport.injectServerLine("""{"id":1,"result":{"ok":true}}""")
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
            assertEquals(failure, dispatcher.awaitTerminal())
            assertEquivalentFailure(failure, request.await().exceptionOrNull())
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
            dispatcher.awaitTerminal()
            assertTrue(request.await().exceptionOrNull() is CodexAppServerTransportClosedException)
            assertEquals(0, dispatcher.pendingRequestCount())
        }
    }

    @Test
    fun naturalFlowCompletionFailsPendingAndCleansMap() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { dispatcher.sendRequest("wait") }
            }
            transport.takeClientLine()
            transport.completeInbound()
            assertTrue(dispatcher.awaitTerminal() is CodexAppServerTransportClosedException)
            assertTrue(request.await().exceptionOrNull() is CodexAppServerTransportClosedException)
            assertEquals(0, dispatcher.pendingRequestCount())
        }
    }

    @Test
    fun flowExceptionFailsPendingAndCleansMap() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { dispatcher.sendRequest("wait") }
            }
            transport.takeClientLine()
            val failure = IllegalStateException("reader failed")
            transport.failInbound(failure)
            assertEquals(failure, dispatcher.awaitTerminal())
            assertEquivalentFailure(failure, request.await().exceptionOrNull())
            assertEquals(0, dispatcher.pendingRequestCount())
        }
    }

    @Test
    fun terminalTransitionRacingRequestRegistrationCannotLeakPendingEntry() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val gate = transport.pauseWrites()
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { dispatcher.sendRequest("racing") }
            }
            gate.awaitWriteAttempt() // The request is registered and blocked in the fake write.
            val failure = IllegalStateException("transport failed")
            transport.injectFailure(failure)
            assertEquals(failure, dispatcher.awaitTerminal())
            assertEquals(0, dispatcher.pendingRequestCount())
            transport.resumeWrites(gate)
            assertTrue(request.await().isFailure)
            assertEquals(0, dispatcher.pendingRequestCount())
        }
    }

    @Test
    fun requestWriteFailureTerminatesDispatcherAndCleansPending() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val failure = IllegalStateException("request write failed")
            transport.failNextWrite(failure)
            val result = runCatching { dispatcher.sendRequest("write") }
            assertEquals(failure, result.exceptionOrNull())
            assertEquals(failure, dispatcher.awaitTerminal())
            assertEquals(0, dispatcher.pendingRequestCount())
        }
    }

    @Test
    fun notificationWriteFailureFailsExistingPendingRequest() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { dispatcher.sendRequest("wait") }
            }
            transport.takeClientLine()
            val failure = IllegalStateException("notification write failed")
            transport.failNextWrite(failure)
            assertEquals(failure, runCatching { dispatcher.sendNotification("notify") }.exceptionOrNull())
            assertEquals(failure, dispatcher.awaitTerminal())
            assertEquivalentFailure(failure, pending.await().exceptionOrNull())
            assertEquals(0, dispatcher.pendingRequestCount())
        }
    }

    @Test
    fun serverResponseWriteFailuresTerminateDispatcher() = runBlocking {
        fixture().use { (transport, dispatcher) ->
            val failure = IllegalStateException("success response write failed")
            transport.failNextWrite(failure)
            assertEquals(
                failure,
                runCatching { dispatcher.respondSuccess(JsonRpcId.StringId("approval")) }.exceptionOrNull(),
            )
            assertEquals(failure, dispatcher.awaitTerminal())
            assertEquals(0, dispatcher.pendingRequestCount())
        }
        fixture().use { (transport, dispatcher) ->
            val failure = IllegalStateException("error response write failed")
            transport.failNextWrite(failure)
            assertEquals(
                failure,
                runCatching {
                    dispatcher.respondError(JsonRpcId.StringId("approval"), JsonRpcError(1, "no"))
                }.exceptionOrNull(),
            )
            assertEquals(failure, dispatcher.awaitTerminal())
        }
    }

    @Test
    fun everyOutboundApiUsesUnifiedClosedException() = runBlocking {
        val transport = FakeCodexAppServerTransport()
        val dispatcher = CodexAppServerRequestDispatcher(transport)
        dispatcher.close()
        assertTrue(runCatching { dispatcher.sendRequest("x") }.exceptionOrNull() is CodexAppServerDispatcherClosedException)
        assertTrue(runCatching { dispatcher.sendNotification("x") }.exceptionOrNull() is CodexAppServerDispatcherClosedException)
        assertTrue(runCatching { dispatcher.respondSuccess(JsonRpcId.StringId("x")) }.exceptionOrNull() is CodexAppServerDispatcherClosedException)
        assertTrue(
            runCatching { dispatcher.respondError(JsonRpcId.StringId("x"), JsonRpcError(1, "x")) }
                .exceptionOrNull() is CodexAppServerDispatcherClosedException
        )
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

    private fun assertEquivalentFailure(expected: Throwable, actual: Throwable?) {
        assertTrue("Expected a failure, but the operation completed successfully", actual != null)
        assertEquals(expected::class, actual!!::class)
        assertEquals(expected.message, actual.message)
    }

    private class Fixture(val transport: FakeCodexAppServerTransport) : AutoCloseable {
        val dispatcher = CodexAppServerRequestDispatcher(transport)
        operator fun component1() = transport
        operator fun component2() = dispatcher
        override fun close() = dispatcher.close()
    }
}
