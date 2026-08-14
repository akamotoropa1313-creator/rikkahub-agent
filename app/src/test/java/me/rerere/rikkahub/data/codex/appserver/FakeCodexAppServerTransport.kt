package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class FakeCodexAppServerTransport : CodexAppServerTransport {
    private val inbound = Channel<CodexAppServerTransportEvent>(Channel.UNLIMITED)
    private val outbound = Channel<String>(Channel.UNLIMITED)
    override val events: Flow<CodexAppServerTransportEvent> = inbound.receiveAsFlow()

    override suspend fun sendLine(line: String) {
        outbound.send(line)
    }

    suspend fun takeClientLine(): String = outbound.receive()
    fun injectServerLine(line: String) = check(inbound.trySend(CodexAppServerTransportEvent.Line(line)).isSuccess)
    fun injectFailure(cause: Throwable) = check(inbound.trySend(CodexAppServerTransportEvent.Failure(cause)).isSuccess)
    fun injectEof() = check(inbound.trySend(CodexAppServerTransportEvent.Closed).isSuccess)

    override fun close() {
        inbound.trySend(CodexAppServerTransportEvent.Closed)
        inbound.close()
        outbound.close()
    }
}
