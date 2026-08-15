package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodexSessionLifecycleAtomicityTest {
    private fun session() = Uuid.random().let { id ->
        ConversationSession(id, Conversation.ofId(id), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), {})
    }

    @Test
    fun `acquire ownership before claim prevents eviction`() {
        val session = session()
        assertTrue(session.tryAcquireLifecycle() == 1)
        assertFalse(session.tryClaimIdleEviction())
        session.cleanup()
    }

    @Test
    fun `eviction claim rejects later acquire and generation`() {
        val session = session()
        assertTrue(session.tryClaimIdleEviction())
        assertNull(session.tryAcquireLifecycle())
        val generation: CompletableJob = Job()
        session.setJob(generation)
        assertTrue(generation.isCancelled)
        assertNull(session.getJob())
        session.cleanup()
    }

    @Test
    fun `generation installed before claim prevents eviction`() {
        val session = session()
        val generation: CompletableJob = Job()
        session.setJob(generation)
        assertFalse(session.tryClaimIdleEviction())
        generation.cancel()
        session.cleanup()
    }

    @Test
    fun `closed session rejects all new ownership`() {
        val session = session()
        session.cleanup()
        assertNull(session.tryAcquireLifecycle())
        assertFalse(session.registerPendingSend(Job()))
        val generation: CompletableJob = Job()
        session.setJob(generation)
        assertTrue(generation.isCancelled)
    }
}
