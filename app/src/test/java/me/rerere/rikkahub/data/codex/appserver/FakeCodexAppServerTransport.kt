package me.rerere.rikkahub.data.codex.appserver

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class FakeCodexAppServerTransport : CodexAppServerTransport {
    private val inbound = Channel<CodexAppServerTransportEvent>(Channel.UNLIMITED)
    private val outbound = Channel<String>(Channel.UNLIMITED)
    private val nextWriteFailure = AtomicReference<Throwable?>(null)
    private val writeGate = AtomicReference<WriteGate?>(null)
    override val events: Flow<CodexAppServerTransportEvent> = inbound.receiveAsFlow()

    override suspend fun sendLine(line: String) {
        writeGate.get()?.let {
            it.entered.complete(Unit)
            it.release.await()
        }
        nextWriteFailure.getAndSet(null)?.let { throw it }
        outbound.send(line)
    }

    suspend fun takeClientLine(): String = outbound.receive()
    fun injectServerLine(line: String) = check(inbound.trySend(CodexAppServerTransportEvent.Line(line)).isSuccess)
    fun injectFailure(cause: Throwable) = check(inbound.trySend(CodexAppServerTransportEvent.Failure(cause)).isSuccess)
    fun injectEof() = check(inbound.trySend(CodexAppServerTransportEvent.Closed).isSuccess)
    fun completeInbound() = inbound.close()
    fun failInbound(cause: Throwable) = inbound.close(cause)
    fun failNextWrite(cause: Throwable) = nextWriteFailure.set(cause)

    fun pauseWrites(): WriteGate = WriteGate().also { check(writeGate.compareAndSet(null, it)) }
    fun resumeWrites(gate: WriteGate) {
        check(writeGate.compareAndSet(gate, null))
        gate.release.complete(Unit)
    }

    override fun close() {
        inbound.trySend(CodexAppServerTransportEvent.Closed)
        inbound.close()
        outbound.close()
    }

    class WriteGate internal constructor(
        internal val entered: CompletableDeferred<Unit> = CompletableDeferred(),
        internal val release: CompletableDeferred<Unit> = CompletableDeferred(),
    ) {
        suspend fun awaitWriteAttempt() = entered.await()
    }
}
