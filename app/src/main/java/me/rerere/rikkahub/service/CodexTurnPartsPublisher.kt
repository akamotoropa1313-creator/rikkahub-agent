package me.rerere.rikkahub.service

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessagePart

/**
 * Coalesces high-frequency App Server deltas before they reach conversation persistence.
 *
 * The protocol collector must stay free to receive reasoning, text, and tool events. Waiting for
 * a full conversation rebuild on every token can otherwise create its own backpressure and make a
 * healthy turn look frozen. The first update is published immediately, following updates are
 * capped to a smooth UI cadence, and [flush] guarantees the terminal snapshot is not lost.
 */
internal class CodexTurnPartsPublisher(
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = 100L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val snapshot: (turnId: String) -> List<UIMessagePart>,
    private val publish: suspend (turnId: String, parts: List<UIMessagePart>) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : Closeable {
    private val lock = Any()
    private val publishMutex = Mutex()
    private val requestedVersions = mutableMapOf<String, Long>()
    private val publishedVersions = mutableMapOf<String, Long>()
    private val lastPublishedAtMs = mutableMapOf<String, Long>()
    private val jobs = mutableMapOf<String, Job>()
    private val failures = mutableMapOf<String, Throwable>()
    private var closed = false

    fun request(turnId: String) {
        synchronized(lock) {
            if (closed || failures.containsKey(turnId)) return
            requestedVersions[turnId] = requestedVersions.getOrDefault(turnId, 0L) + 1L
        }
        schedule(turnId)
    }

    /** Publishes every change requested before this call, including the final turn mutation. */
    suspend fun flush(turnId: String) {
        request(turnId)
        while (true) {
            synchronized(lock) { failures[turnId] }?.let { throw it }
            val active = synchronized(lock) { jobs[turnId] }
            if (active != null) {
                active.join()
                continue
            }
            val dirty = synchronized(lock) {
                requestedVersions.getOrDefault(turnId, 0L) >
                    publishedVersions.getOrDefault(turnId, 0L)
            }
            if (!dirty) return
            try {
                publishLatest(turnId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                recordFailure(turnId, failure)
                throw failure
            }
        }
    }

    fun clear(turnId: String) {
        val job = synchronized(lock) {
            requestedVersions.remove(turnId)
            publishedVersions.remove(turnId)
            lastPublishedAtMs.remove(turnId)
            failures.remove(turnId)
            jobs.remove(turnId)
        }
        job?.cancel()
    }

    private fun schedule(turnId: String) {
        var created: Job? = null
        synchronized(lock) {
            if (closed || failures.containsKey(turnId) || jobs.containsKey(turnId)) return
            if (requestedVersions.getOrDefault(turnId, 0L) <=
                publishedVersions.getOrDefault(turnId, 0L)
            ) return

            created = scope.launch(start = CoroutineStart.LAZY) {
                val waitMs = synchronized(lock) {
                    val elapsed = nowMs() - lastPublishedAtMs.getOrDefault(turnId, Long.MIN_VALUE / 2)
                    (minIntervalMs - elapsed).coerceAtLeast(0L)
                }
                if (waitMs > 0L) delay(waitMs)
                try {
                    publishLatest(turnId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    recordFailure(turnId, failure)
                }
            }
            jobs[turnId] = checkNotNull(created)
        }

        val job = checkNotNull(created)
        job.invokeOnCompletion {
            val retry = synchronized(lock) {
                if (jobs[turnId] === job) jobs.remove(turnId)
                !closed && !failures.containsKey(turnId) &&
                    requestedVersions.getOrDefault(turnId, 0L) >
                    publishedVersions.getOrDefault(turnId, 0L)
            }
            if (retry) schedule(turnId)
        }
        job.start()
    }

    private suspend fun publishLatest(turnId: String) = publishMutex.withLock {
        val version = synchronized(lock) {
            requestedVersions.getOrDefault(turnId, 0L).takeIf {
                it > publishedVersions.getOrDefault(turnId, 0L)
            }
        } ?: return@withLock

        val parts = snapshot(turnId)
        if (parts.isNotEmpty()) publish(turnId, parts)
        synchronized(lock) {
            publishedVersions[turnId] = maxOf(publishedVersions.getOrDefault(turnId, 0L), version)
            lastPublishedAtMs[turnId] = nowMs()
        }
    }

    private fun recordFailure(turnId: String, failure: Throwable) {
        val first = synchronized(lock) {
            if (failures.containsKey(turnId)) false else {
                failures[turnId] = failure
                true
            }
        }
        if (first) onFailure(failure)
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            jobs.values.toList().also { jobs.clear() }
        }
        active.forEach { it.cancel() }
    }
}
