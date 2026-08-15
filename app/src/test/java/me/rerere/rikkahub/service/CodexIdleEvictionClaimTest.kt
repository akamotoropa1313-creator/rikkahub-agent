package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodexIdleEvictionClaimTest {
    @Test
    fun `accepted pending registration defeats later eviction claim`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        val pending = scope.launch(start = CoroutineStart.LAZY) { }
        assertTrue(session.registerPendingSend(pending))
        assertFalse(session.tryClaimIdleEviction())
        assertTrue(session.hasPendingSends)
        session.cleanup()
    }

    @Test
    fun `successful eviction claim rejects stale session registration`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        assertTrue(session.tryClaimIdleEviction())
        val pending = scope.launch(start = CoroutineStart.LAZY) { }
        assertFalse(session.registerPendingSend(pending))
        assertFalse(session.hasPendingSends)
        pending.cancel()
        session.cleanup()
    }
}
