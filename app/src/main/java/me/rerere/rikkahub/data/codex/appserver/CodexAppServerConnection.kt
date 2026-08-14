package me.rerere.rikkahub.data.codex.appserver

import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface CodexAppServerConnectionState {
    data object Created : CodexAppServerConnectionState
    data object Initializing : CodexAppServerConnectionState
    data class Ready(val response: CodexAppServerInitializeResponse) : CodexAppServerConnectionState
    data object Closing : CodexAppServerConnectionState
    data object Closed : CodexAppServerConnectionState
    data class Failed(val cause: Throwable) : CodexAppServerConnectionState
}

class CodexAppServerConnection(
    private val dispatcher: CodexAppServerRequestDispatcher,
    private val clientInfo: CodexAppServerClientInfo,
    private val capabilities: CodexAppServerInitializeCapabilities? = null,
    private val initializeTimeout: Duration = 30.seconds,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val mutableState = MutableStateFlow<CodexAppServerConnectionState>(CodexAppServerConnectionState.Created)
    private var handshake: CompletableDeferred<CodexAppServerInitializeResponse>? = null
    private var handshakeJob: Job? = null

    val state: StateFlow<CodexAppServerConnectionState> = mutableState.asStateFlow()
    val events: Flow<CodexAppServerEvent> = dispatcher.events

    init {
        scope.launch {
            val cause = dispatcher.awaitTerminal()
            lifecycle.withLock {
                when (mutableState.value) {
                    CodexAppServerConnectionState.Closing,
                    CodexAppServerConnectionState.Closed,
                    is CodexAppServerConnectionState.Failed -> Unit
                    else -> failLocked(cause)
                }
            }
        }
    }

    suspend fun initialize(): CodexAppServerInitializeResponse {
        val shared = lifecycle.withLock {
            when (val current = mutableState.value) {
                is CodexAppServerConnectionState.Ready -> return current.response
                is CodexAppServerConnectionState.Failed -> throw CodexAppServerConnectionFailedException(current.cause)
                CodexAppServerConnectionState.Closing,
                CodexAppServerConnectionState.Closed -> throw CodexAppServerConnectionClosedException()
                CodexAppServerConnectionState.Created -> {
                    mutableState.value = CodexAppServerConnectionState.Initializing
                    CompletableDeferred<CodexAppServerInitializeResponse>().also {
                        handshake = it
                        handshakeJob = scope.launch { performHandshake(it) }
                    }
                }
                CodexAppServerConnectionState.Initializing -> checkNotNull(handshake)
            }
        }
        // Awaiting a Deferred does not propagate caller cancellation into the shared handshake.
        return shared.await()
    }

    private suspend fun performHandshake(result: CompletableDeferred<CodexAppServerInitializeResponse>) {
        try {
            val params = CodexAppServerInitializeParams(clientInfo, capabilities)
            val raw = dispatcher.sendRequest(
                method = "initialize",
                params = CodexAppServerJsonRpc().json.encodeToJsonElement(
                    CodexAppServerInitializeParams.serializer(), params
                ),
                timeout = initializeTimeout,
            )
            val response = CodexAppServerJsonRpc().json.decodeFromJsonElement(
                CodexAppServerInitializeResponse.serializer(), raw
            )
            lifecycle.withLock {
                check(mutableState.value == CodexAppServerConnectionState.Initializing) {
                    "Connection stopped during initialize"
                }
                // Holding the lifecycle mutex linearizes this write with close(): once close has
                // established its terminal transition, initialized can never be written afterward.
                dispatcher.sendNotification("initialized")
                check(mutableState.value == CodexAppServerConnectionState.Initializing)
                mutableState.value = CodexAppServerConnectionState.Ready(response)
                result.complete(response)
            }
        } catch (error: Throwable) {
            lifecycle.withLock {
                if (mutableState.value == CodexAppServerConnectionState.Initializing) {
                    failLocked(error)
                    dispatcher.close()
                }
                result.completeExceptionally(terminalException(error))
            }
        }
    }

    internal suspend fun sendRequestAfterReady(
        method: String,
        params: JsonElement? = null,
        timeout: Duration = 30.seconds,
    ): JsonElement {
        lifecycle.withLock {
            if (mutableState.value !is CodexAppServerConnectionState.Ready) {
                throw CodexAppServerNotReadyException(mutableState.value)
            }
        }
        return dispatcher.sendRequest(method, params, timeout)
    }

    private fun failLocked(cause: Throwable) {
        mutableState.value = CodexAppServerConnectionState.Failed(cause)
        handshake?.completeExceptionally(CodexAppServerConnectionFailedException(cause))
    }

    private fun terminalException(error: Throwable): Throwable = when (mutableState.value) {
        CodexAppServerConnectionState.Closed,
        CodexAppServerConnectionState.Closing -> CodexAppServerConnectionClosedException()
        is CodexAppServerConnectionState.Failed -> CodexAppServerConnectionFailedException(error)
        else -> error
    }

    override fun close() = runBlocking {
        lifecycle.withLock {
            when (mutableState.value) {
                CodexAppServerConnectionState.Closed,
                CodexAppServerConnectionState.Closing -> return@withLock
                else -> {
                    mutableState.value = CodexAppServerConnectionState.Closing
                    handshake?.completeExceptionally(CodexAppServerConnectionClosedException())
                    handshakeJob?.cancel()
                    dispatcher.close()
                    mutableState.value = CodexAppServerConnectionState.Closed
                }
            }
        }
        scope.cancel()
    }
}

class CodexAppServerConnectionFailedException(cause: Throwable) :
    Exception("Codex App Server connection failed", cause)

class CodexAppServerConnectionClosedException : Exception("Codex App Server connection is closed")

class CodexAppServerNotReadyException(val connectionState: CodexAppServerConnectionState) :
    IllegalStateException("Codex App Server connection is not ready: $connectionState")
