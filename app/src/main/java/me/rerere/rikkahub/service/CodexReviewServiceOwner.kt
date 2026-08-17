package me.rerere.rikkahub.service

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerReviewTarget
import kotlin.uuid.Uuid

/** UI commands for native review without leaking protocol-only targets into Stop handling. */
sealed interface CodexReviewAction {
    data class Start(val target: CodexAppServerReviewTarget) : CodexReviewAction
    data object Stop : CodexReviewAction
}

/**
 * Owns native review operations independently of a ChatVM lifecycle.
 *
 * The actual Codex operation lease remains owned by [ChatService.startCodexReview]. This owner
 * only keeps that suspend call alive across navigation and pins the ConversationSession with a
 * reference until the server reports a terminal turn. Stop deliberately requests the existing
 * turn interrupt path and does not cancel the owner job before terminal authority arrives.
 */
object CodexReviewServiceOwner {
    private data class Key(val service: ChatService, val conversationId: Uuid)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<Key, Job>()

    fun start(service: ChatService, conversationId: Uuid, target: CodexAppServerReviewTarget) {
        val key = Key(service, conversationId)
        synchronized(jobs) {
            if (jobs[key]?.isActive == true) {
                service.addError(
                    IllegalStateException("A Codex review is already running."),
                    conversationId,
                    title = "Codex review",
                )
                return
            }

            service.addConversationReference(conversationId)
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    service.startCodexReview(conversationId, target)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    service.addError(failure, conversationId, title = "Codex review failed")
                } finally {
                    service.removeConversationReference(conversationId)
                    synchronized(jobs) {
                        if (jobs[key] === job) jobs.remove(key)
                    }
                }
            }
            jobs[key] = job
            job.start()
        }
    }

    fun stop(service: ChatService, conversationId: Uuid) {
        scope.launch {
            service.stopGeneration(conversationId)
        }
    }

    internal fun isRunning(service: ChatService, conversationId: Uuid): Boolean =
        jobs[Key(service, conversationId)]?.isActive == true
}
