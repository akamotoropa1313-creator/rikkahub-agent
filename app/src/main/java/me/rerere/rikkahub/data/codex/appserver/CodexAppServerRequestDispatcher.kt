package me.rerere.rikkahub.data.codex.appserver

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class CodexAppServerRequestDispatcher(
    private val transport: CodexAppServerTransport,
    private val codec: CodexAppServerJsonRpc = CodexAppServerJsonRpc(),
    private val idAllocator: CodexAppServerRequestIdAllocator = CodexAppServerRequestIdAllocator(),
    private val defaultTimeout: Duration = 5.minutes,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val pending = mutableMapOf<JsonRpcId.NumberId, CompletableDeferred<JsonElement>>()
    private var terminalCause: Throwable? = null
    private val terminal = CompletableDeferred<Throwable>()
    private val mutableEvents = MutableSharedFlow<CodexAppServerEvent>(extraBufferCapacity = 64)

    /** Notifications, server requests, and diagnostics in transport arrival order. */
    val events: Flow<CodexAppServerEvent> = mutableEvents.asSharedFlow()

    init {
        scope.launch {
            try {
                transport.events.collect { event ->
                    when (event) {
                        is CodexAppServerTransportEvent.Line -> consumeLine(event.value)
                        is CodexAppServerTransportEvent.Failure -> terminate(
                            event.cause,
                            CodexAppServerEvent.TransportFailure(event.cause),
                        )
                        CodexAppServerTransportEvent.Closed -> terminate(
                            CodexAppServerTransportClosedException(),
                            CodexAppServerEvent.TransportClosed,
                        )
                    }
                }
                terminate(
                    CodexAppServerTransportClosedException(),
                    CodexAppServerEvent.TransportClosed,
                )
            } catch (error: CancellationException) {
                if (currentTerminalCause() == null) throw error
            } catch (error: Throwable) {
                terminate(error, CodexAppServerEvent.TransportFailure(error))
            }
        }
    }

    suspend fun sendRequest(
        method: String,
        params: JsonElement? = null,
        timeout: Duration = defaultTimeout,
    ): JsonElement {
        val response = CompletableDeferred<JsonElement>()
        val id = synchronized(stateLock) {
            throwIfTerminalLocked()
            val allocated = idAllocator.allocate()
            check(pending.put(allocated, response) == null)
            allocated
        }
        try {
            sendLineSafely(codec.encode(JsonRpcRequest(id, method, params)))
            return withTimeout(timeout) { response.await() }
        } finally {
            synchronized(stateLock) { pending.remove(id, response) }
        }
    }

    suspend fun sendNotification(method: String, params: JsonElement? = null) {
        sendLineSafely(codec.encode(JsonRpcNotification(method, params)))
    }

    suspend fun respondSuccess(id: JsonRpcId, result: JsonElement = JsonNull) {
        sendLineSafely(codec.encode(JsonRpcResponse(id, result)))
    }

    suspend fun respondError(id: JsonRpcId, error: JsonRpcError) {
        sendLineSafely(codec.encode(JsonRpcErrorResponse(id, error)))
    }

    private suspend fun sendLineSafely(line: String) {
        synchronized(stateLock) { throwIfTerminalLocked() }
        try {
            transport.sendLine(line)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            terminate(error, CodexAppServerEvent.TransportFailure(error))
            throw error
        }
    }

    private suspend fun consumeLine(line: String) {
        codec.decode(line).fold(
            onSuccess = { message ->
                when (message) {
                    is JsonRpcMessage.Response -> complete(message.value.id, Result.success(message.value.result))
                    is JsonRpcMessage.ErrorResponse -> complete(
                        message.value.id,
                        Result.failure(CodexAppServerResponseException(message.value.error)),
                    )
                    is JsonRpcMessage.Notification,
                    is JsonRpcMessage.Request -> mutableEvents.emit(message.toCodexAppServerEventOrNull()!!)
                }
            },
            onFailure = { mutableEvents.emit(CodexAppServerEvent.MalformedInbound(line, it)) },
        )
    }

    private suspend fun complete(id: JsonRpcId, result: Result<JsonElement>) {
        val deferred = synchronized(stateLock) {
            when (id) {
                is JsonRpcId.NumberId -> pending.remove(id)
                is JsonRpcId.StringId -> null
            }
        }
        if (deferred == null) {
            mutableEvents.emit(CodexAppServerEvent.UnknownResponseId(id))
        } else {
            result.fold(deferred::complete, deferred::completeExceptionally)
        }
    }

    /**
     * Linearizes every terminal path under [stateLock]. Once the cause is published, registration
     * and every outbound API reject new work. Pending entries are detached atomically, so a request
     * can be either in the detached batch or rejected, but can never be inserted behind cleanup.
     */
    private fun terminate(cause: Throwable, diagnostic: CodexAppServerEvent?) {
        val toFail = synchronized(stateLock) {
            if (terminalCause != null) return
            terminalCause = cause
            pending.values.toList().also { pending.clear() }
        }
        toFail.forEach { it.completeExceptionally(cause) }
        terminal.complete(cause)
        runCatching { transport.close() }
        // Terminal correctness never waits for a diagnostic collector.
        if (diagnostic != null) mutableEvents.tryEmit(diagnostic)
        scope.cancel()
    }

    private fun throwIfTerminalLocked() {
        terminalCause?.let { throw CodexAppServerDispatcherClosedException(it) }
    }

    private fun currentTerminalCause(): Throwable? = synchronized(stateLock) { terminalCause }

    internal fun pendingRequestCount(): Int = synchronized(stateLock) { pending.size }
    internal suspend fun awaitTerminal(): Throwable = terminal.await()

    override fun close() {
        terminate(CodexAppServerDispatcherClosedException(), diagnostic = null)
    }
}

class CodexAppServerResponseException(val error: JsonRpcError) :
    Exception("JSON-RPC error ${error.code}: ${error.message}")

class CodexAppServerDispatcherClosedException(cause: Throwable? = null) :
    Exception("Codex App Server dispatcher is closed", cause)

class CodexAppServerTransportClosedException : Exception("Codex App Server transport reached EOF or closed")
