package me.rerere.rikkahub.service

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexPendingIdleLifecycleTest {
    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test
    fun `pending admission prevents idle and cancellation reschedules it`() {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val calls = AtomicInteger()
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, { calls.incrementAndGet() }, 0)
        session.acquire()
        session.release()
        val pending = scope.launch(start = CoroutineStart.LAZY) { }
        session.registerPendingSend(pending)

        dispatcher.drain()
        assertEquals(0, calls.get())
        assertTrue(session.hasPendingSends)

        session.cancelPendingSends()
        dispatcher.drain()
        assertFalse(session.hasPendingSends)
        assertEquals(1, calls.get())
    }

    @Test
    fun `promotion transfers idle ownership to generation completion`() {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val calls = AtomicInteger()
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, { calls.incrementAndGet() }, 0)
        val pending = scope.launch(start = CoroutineStart.LAZY) { }
        session.registerPendingSend(pending)
        assertTrue(session.promotePendingSendToGeneration(pending).promoted)
        assertFalse(session.hasPendingSends)
        assertEquals(0, calls.get())

        pending.start()
        dispatcher.drain()
        dispatcher.drain()
        assertEquals(1, calls.get())
    }
}
