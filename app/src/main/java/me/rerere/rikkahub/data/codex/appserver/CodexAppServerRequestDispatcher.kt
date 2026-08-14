package me.rerere.rikkahub.data.codex.appserver

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
    private val pending = ConcurrentHashMap<JsonRpcId.NumberId, CompletableDeferred<JsonElement>>()
    private val closed = AtomicBoolean(false)
    private val mutableEvents = MutableSharedFlow<CodexAppServerEvent>(extraBufferCapacity = 64)

    /** Notifications, server requests, and diagnostics in transport arrival order. */
    val events: Flow<CodexAppServerEvent> = mutableEvents.asSharedFlow()

    init {
        scope.launch {
            transport.events.collect { event ->
                when (event) {
                    is CodexAppServerTransportEvent.Line -> consumeLine(event.value)
                    is CodexAppServerTransportEvent.Failure -> failTransport(event.cause)
                    CodexAppServerTransportEvent.Closed -> failTransport(
                        CodexAppServerTransportClosedException(),
                        CodexAppServerEvent.TransportClosed,
                    )
                }
            }
        }
    }

    suspend fun sendRequest(
        method: String,
        params: JsonElement? = null,
        timeout: Duration = defaultTimeout,
    ): JsonElement {
        check(!closed.get()) { "Dispatcher is closed" }
        val id = idAllocator.allocate()
        val response = CompletableDeferred<JsonElement>()
        check(pending.putIfAbsent(id, response) == null)
        try {
            if (closed.get()) throw CodexAppServerDispatcherClosedException()
            try {
                transport.sendLine(codec.encode(JsonRpcRequest(id, method, params)))
            } catch (error: Throwable) {
                if (error !is CancellationException) failTransport(error)
                throw error
            }
            return withTimeout(timeout) { response.await() }
        } catch (error: Throwable) {
            response.completeExceptionally(error)
            throw error
        } finally {
            pending.remove(id, response)
        }
    }

    suspend fun sendNotification(method: String, params: JsonElement? = null) {
        check(!closed.get()) { "Dispatcher is closed" }
        transport.sendLine(codec.encode(JsonRpcNotification(method, params)))
    }

    suspend fun respondSuccess(id: JsonRpcId, result: JsonElement = JsonNull) {
        check(!closed.get()) { "Dispatcher is closed" }
        transport.sendLine(codec.encode(JsonRpcResponse(id, result)))
    }

    suspend fun respondError(id: JsonRpcId, error: JsonRpcError) {
        check(!closed.get()) { "Dispatcher is closed" }
        transport.sendLine(codec.encode(JsonRpcErrorResponse(id, error)))
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
        val numericId = when (id) {
            is JsonRpcId.NumberId -> id
            is JsonRpcId.StringId -> id.value.toLongOrNull()?.let(JsonRpcId::NumberId)
        }
        val deferred = numericId?.let(pending::remove)
        if (deferred == null) {
            mutableEvents.emit(CodexAppServerEvent.UnknownResponseId(id))
        } else {
            result.fold(deferred::complete, deferred::completeExceptionally)
        }
    }

    private suspend fun failTransport(
        cause: Throwable,
        event: CodexAppServerEvent = CodexAppServerEvent.TransportFailure(cause),
    ) {
        mutableEvents.emit(event)
        failPending(cause)
        if (closed.compareAndSet(false, true)) {
            transport.close()
            scope.cancel()
        }
    }

    private fun failPending(cause: Throwable) {
        pending.entries.forEach { (id, deferred) ->
            if (pending.remove(id, deferred)) deferred.completeExceptionally(cause)
        }
    }

    /** Visible for lifecycle/leak assertions without exposing the pending map itself. */
    internal fun pendingRequestCount(): Int = pending.size

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        failPending(CodexAppServerDispatcherClosedException())
        transport.close()
        scope.cancel()
    }
}

class CodexAppServerResponseException(val error: JsonRpcError) :
    Exception("JSON-RPC error ${error.code}: ${error.message}")

class CodexAppServerDispatcherClosedException : Exception("Codex App Server dispatcher closed")
class CodexAppServerTransportClosedException : Exception("Codex App Server transport reached EOF or closed")
