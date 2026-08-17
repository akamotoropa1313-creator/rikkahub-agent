package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) {
    // 会话状态
    val state = MutableStateFlow(initial)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val sendTrackingLock = Any()
    private val pendingSendJobs = linkedSetOf<Job>()
    private var evictionClaimed = false
    private var closed = false
    fun registerPendingSend(job: Job): Boolean {
        val added = synchronized(sendTrackingLock) {
            if (evictionClaimed || closed) false else pendingSendJobs.add(job)
        }
        if (added) cancelIdleCheck()
        return added
    }
    data class SendPromotion(val promoted: Boolean, val previous: Job?)
    fun promotePendingSendToGeneration(job: Job): SendPromotion = synchronized(sendTrackingLock) {
        if (!pendingSendJobs.remove(job)) return@synchronized SendPromotion(false, null)
        val previous = _generationJob.getAndUpdate { job }
        installJobCompletion(job)
        SendPromotion(true, previous)
    }
    fun removePendingSend(job: Job): Boolean {
        val removed = synchronized(sendTrackingLock) { pendingSendJobs.remove(job) }
        if (removed && !isInUse) scheduleIdleCheck()
        return removed
    }
    fun cancelPendingSends() {
        val jobs = synchronized(sendTrackingLock) {
            pendingSendJobs.toList().also { pendingSendJobs.clear() }
        }
        jobs.forEach(Job::cancel)
        if (jobs.isNotEmpty() && !isInUse) scheduleIdleCheck()
    }
    val hasPendingSends: Boolean get() = synchronized(sendTrackingLock) { pendingSendJobs.isNotEmpty() }
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating || hasPendingSends
    fun tryClaimIdleEviction(): Boolean = synchronized(sendTrackingLock) {
        if (closed || evictionClaimed || refCount.get() > 0 || _generationJob.value?.isActive == true || pendingSendJobs.isNotEmpty()) {
            false
        } else {
            evictionClaimed = true
            true
        }
    }
    fun releaseIdleEvictionClaim() = synchronized(sendTrackingLock) { evictionClaimed = false }
    private val codexOperationActive = AtomicBoolean(false)
    private val _codexOperationBusy = MutableStateFlow(false)
    val codexOperationBusy: StateFlow<Boolean> = _codexOperationBusy.asStateFlow()
    fun tryBeginCodexOperation(): Boolean = codexOperationActive.compareAndSet(false, true).also { if (it) _codexOperationBusy.value = true }
    fun endCodexOperation() { codexOperationActive.set(false); _codexOperationBusy.value = false }
    val isCodexOperationActive: Boolean get() = codexOperationActive.get()

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    @Volatile var codexRuntime: CodexChatRuntime? = null
        private set
    private var codexStateJob: Job? = null
    private var codexCapabilitiesJob: Job? = null
    private var codexReviewJob: Job? = null
    private val _codexState = MutableStateFlow<CodexConversationUiState>(CodexConversationUiState.Disconnected)
    val codexState: StateFlow<CodexConversationUiState> = _codexState.asStateFlow()
    private val _codexCapabilities = MutableStateFlow(CodexCapabilitiesUiState())
    val codexCapabilities: StateFlow<CodexCapabilitiesUiState> = _codexCapabilities.asStateFlow()
    private val _codexReview = MutableStateFlow(CodexReviewUiState())
    val codexReview: StateFlow<CodexReviewUiState> = _codexReview.asStateFlow()

    @Synchronized fun replaceCodexRuntime(runtime: CodexChatRuntime?) {
        val previous = codexRuntime
        codexRuntime = runtime
        codexStateJob?.cancel()
        codexCapabilitiesJob?.cancel()
        codexReviewJob?.cancel()
        codexStateJob = runtime?.let { installed ->
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                installed.state.collect { _codexState.value = it }
            }
        }
        codexCapabilitiesJob = runtime?.let { installed ->
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                installed.capabilities.collect { _codexCapabilities.value = it }
            }
        }
        codexReviewJob = runtime?.let { installed -> scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            installed.review.collect { _codexReview.value = it }
        } }
        if (runtime == null) { _codexCapabilities.value = CodexCapabilitiesUiState(); _codexReview.value = CodexReviewUiState() }
        if (runtime == null && previous == null) _codexState.value = CodexConversationUiState.Disconnected
        if (previous !== runtime) previous?.close()
    }

    @Synchronized fun detachCodexRuntime(expected: CodexChatRuntime, preserveState: Boolean = true): Boolean {
        if (codexRuntime !== expected) return false
        codexRuntime = null
        codexStateJob?.cancel()
        codexStateJob = null
        codexCapabilitiesJob?.cancel()
        codexCapabilitiesJob = null
        codexReviewJob?.cancel(); codexReviewJob = null; _codexReview.value = CodexReviewUiState()
        _codexCapabilities.value = CodexCapabilitiesUiState()
        if (!preserveState) _codexState.value = CodexConversationUiState.Disconnected
        return true
    }

    internal fun tryAcquireLifecycle(): Int? = synchronized(sendTrackingLock) {
        if (evictionClaimed || closed) null else refCount.incrementAndGet()
    }

    fun tryAcquire(): Int? {
        val refs = tryAcquireLifecycle() ?: return null
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$refs)")
        return refs
    }

    fun acquire(): Int = checkNotNull(tryAcquire()) { "Conversation session is being evicted" }

    fun release(): Int {
        val refs = synchronized(sendTrackingLock) { refCount.decrementAndGet() }
        Log.d(TAG, "release $id (refs=$refs)")
        if (refs <= 0) scheduleIdleCheck()
        return refs
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    fun setJob(job: Job?) {
        // Atomic swap so two concurrent setJob callers can't race-write a stale job.
        // The previous code (cancel() then assign) had a window where two writers could
        // each read the prior value, A cancels old, B reads old (already cancelled,
        // no-op), A writes newA, B writes newB → A's job is untracked but still running;
        // getJob() returns B; stopGeneration only cancels B; A leaks until completion.
        val previous = synchronized(sendTrackingLock) {
            if (closed || evictionClaimed) {
                job?.cancel()
                return
            }
            _generationJob.getAndUpdate { job }
        }
        previous?.cancel()
        job?.let(::installJobCompletion)
    }

    private fun installJobCompletion(job: Job) {
        job.invokeOnCompletion {
            _generationJob.compareAndSet(job, null)
            if (refCount.get() <= 0) scheduleIdleCheck()
        }
    }

    fun getJob(): Job? = _generationJob.value

    fun publishCodexState(state: CodexConversationUiState) { _codexState.value = state }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(idleTimeoutMs)
            if (!isInUse) onIdle(id)
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        synchronized(sendTrackingLock) {
            if (closed) return
            closed = true
            evictionClaimed = true
        }
        // Use getAndUpdate (same as setJob) so cleanup() is consistent with the atomic
        // swap used elsewhere. Direct .value = null would bypass the CAS and could
        // theoretically race with a concurrent setJob that's running post-removal
        // (e.g., a coroutine that had already acquired a session reference before
        // dropSession removed it from the map). In practice the risk is tiny because
        // cleanup() is only called after removal, but correctness still matters.
        val job = _generationJob.getAndUpdate { null }
        job?.cancel()
        cancelPendingSends()
        idleCheckJob?.cancel()
        idleCheckJob = null
        replaceCodexRuntime(null)
        endCodexOperation()
    }
}
