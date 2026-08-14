package me.rerere.rikkahub.data.codex.appserver

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val handshakeRegistration = Any()
    private val mutableState = MutableStateFlow<CodexAppServerConnectionState>(CodexAppServerConnectionState.Created)
    @Volatile
    private var handshake: Handshake? = null
    private val terminalCause = AtomicReference<Throwable?>(null)
    private val closeStarted = AtomicBoolean(false)

    val state: StateFlow<CodexAppServerConnectionState> = mutableState.asStateFlow()
    val events: Flow<CodexAppServerEvent> = dispatcher.events

    init {
        scope.launch {
            val cause = dispatcher.awaitTerminal()
            val transition = failConnection(cause)
            transition?.deferred?.completeExceptionally(CodexAppServerConnectionFailedException(transition.cause))
        }
    }

    suspend fun initialize(): CodexAppServerInitializeResponse {
        var toStart: Job? = null
        val shared = synchronized(handshakeRegistration) {
            when (val current = mutableState.value) {
                is CodexAppServerConnectionState.Ready -> return current.response
                is CodexAppServerConnectionState.Failed -> throw CodexAppServerConnectionFailedException(current.cause)
                CodexAppServerConnectionState.Closing,
                CodexAppServerConnectionState.Closed -> throw CodexAppServerConnectionClosedException()
                CodexAppServerConnectionState.Initializing -> checkNotNull(handshake).deferred
                CodexAppServerConnectionState.Created -> {
                    val deferred = CompletableDeferred<CodexAppServerInitializeResponse>()
                    lateinit var current: Handshake
                    val job = scope.launch(start = CoroutineStart.LAZY) { performHandshake(current) }
                    current = Handshake(deferred, job)
                    // Register everything close() needs before Initializing becomes observable.
                    handshake = current
                    toStart = job
                    if (!mutableState.compareAndSet(
                            CodexAppServerConnectionState.Created,
                            CodexAppServerConnectionState.Initializing,
                        )
                    ) {
                        deferred.completeExceptionally(CodexAppServerConnectionClosedException())
                        job.cancel()
                    }
                    deferred
                }
            }
        }
        // A synchronous Initializing observer may already have closed the connection.
        if (mutableState.value == CodexAppServerConnectionState.Initializing &&
            handshake?.job === toStart
        ) {
            toStart?.start()
        }
        // Awaiting this Deferred never propagates caller cancellation to the shared handshake Job.
        return shared.await()
    }

    private suspend fun performHandshake(current: Handshake) {
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

            val claimedInitializedWrite = handshake === current &&
                current.initializedWriteClaimed.compareAndSet(false, true) &&
                mutableState.value == CodexAppServerConnectionState.Initializing
            if (!claimedInitializedWrite) throw CodexAppServerConnectionClosedException()

            // No lifecycle guard is held: close() can cancel this suspended write and close the
            // dispatcher. Claiming the write first defines the close-vs-initialized linearization.
            dispatcher.sendNotification("initialized")

            if (handshake !== current || !mutableState.compareAndSet(
                    CodexAppServerConnectionState.Initializing,
                    CodexAppServerConnectionState.Ready(response),
                )
            ) {
                throw CodexAppServerConnectionClosedException()
            } else {
                current.deferred.complete(response)
            }
        } catch (error: Throwable) {
            val transition = failHandshake(current, error)
            if (transition != null) {
                transition.deferred.completeExceptionally(
                    CodexAppServerConnectionFailedException(transition.cause)
                )
                // Resource cleanup never executes in state arbitration.
                dispatcher.close()
            }
        }
    }

    internal suspend fun sendRequestAfterReady(
        method: String,
        params: JsonElement? = null,
        timeout: Duration = 30.seconds,
    ): JsonElement {
        val current = mutableState.value
        if (current !is CodexAppServerConnectionState.Ready) {
            throw CodexAppServerNotReadyException(current)
        }
        return dispatcher.sendRequest(method, params, timeout)
    }

    internal suspend fun respondServerRequestAfterReady(id: JsonRpcId, result: JsonElement) {
        val current = mutableState.value
        if (current !is CodexAppServerConnectionState.Ready) {
            throw CodexAppServerNotReadyException(current)
        }
        dispatcher.respondSuccess(id, result)
    }

    /** Returns a transition only for the first, still-current handshake failure. */
    private fun failHandshake(current: Handshake, cause: Throwable): FailureTransition? {
        if (handshake !== current) return null
        val canonical = canonicalCause(cause)
        return if (mutableState.compareAndSet(
                CodexAppServerConnectionState.Initializing,
                CodexAppServerConnectionState.Failed(canonical),
            )
        ) FailureTransition(current.deferred, canonical) else null
    }

    private fun failConnection(cause: Throwable): FailureTransition? {
        val canonical = canonicalCause(cause)
        while (true) {
            val current = mutableState.value
            if (current == CodexAppServerConnectionState.Closing ||
                current == CodexAppServerConnectionState.Closed ||
                current is CodexAppServerConnectionState.Failed
            ) return null
            if (mutableState.compareAndSet(current, CodexAppServerConnectionState.Failed(canonical))) {
                return if (current == CodexAppServerConnectionState.Initializing) {
                    handshake?.deferred?.let { FailureTransition(it, canonical) }
                } else null
            }
        }
    }

    private fun canonicalCause(candidate: Throwable): Throwable {
        terminalCause.compareAndSet(null, candidate)
        return checkNotNull(terminalCause.get())
    }

    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) return

        var explicitClosePath = false
        var cancelHandshake = false
        while (true) {
            val current = mutableState.value
            when (current) {
                CodexAppServerConnectionState.Closing,
                CodexAppServerConnectionState.Closed -> break
                is CodexAppServerConnectionState.Failed -> break
                else -> if (mutableState.compareAndSet(
                        current,
                        CodexAppServerConnectionState.Closing,
                    )
                ) {
                    explicitClosePath = true
                    cancelHandshake = current == CodexAppServerConnectionState.Created ||
                        current == CodexAppServerConnectionState.Initializing
                    break
                }
            }
        }
        val currentHandshake = handshake
        val cleanup = CloseCleanup(
            deferred = currentHandshake?.deferred?.takeIf { cancelHandshake },
            job = currentHandshake?.job?.takeIf { cancelHandshake },
        )

        cleanup.deferred?.completeExceptionally(CodexAppServerConnectionClosedException())
        cleanup.job?.cancel()
        dispatcher.close()

        if (explicitClosePath) {
            mutableState.compareAndSet(
                CodexAppServerConnectionState.Closing,
                CodexAppServerConnectionState.Closed,
            )
        }
        scope.cancel()
    }

    private class Handshake(
        val deferred: CompletableDeferred<CodexAppServerInitializeResponse>,
        val job: Job,
        val initializedWriteClaimed: AtomicBoolean = AtomicBoolean(false),
    )

    private data class FailureTransition(
        val deferred: CompletableDeferred<CodexAppServerInitializeResponse>,
        val cause: Throwable,
    )

    private data class CloseCleanup(
        val deferred: CompletableDeferred<CodexAppServerInitializeResponse>?,
        val job: Job?,
    )
}

class CodexAppServerConnectionFailedException(cause: Throwable) :
    Exception("Codex App Server connection failed", cause)

class CodexAppServerConnectionClosedException : Exception("Codex App Server connection is closed")

class CodexAppServerNotReadyException(val connectionState: CodexAppServerConnectionState) :
    IllegalStateException("Codex App Server connection is not ready: $connectionState")
