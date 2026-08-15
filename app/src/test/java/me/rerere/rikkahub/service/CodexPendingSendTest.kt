package me.rerere.rikkahub.service

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodexPendingSendTest {
    @Test
    fun `stop cancels a lazy pending admission before any work starts`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        val entered = AtomicBoolean(false)
        val pending = scope.launch(start = CoroutineStart.LAZY) { entered.set(true) }

        session.registerPendingSend(pending)
        session.cancelPendingSends()
        assertTrue(pending.isCancelled)
        assertFalse(pending.start())
        assertFalse(entered.get())
        scope.cancel()
    }

    @Test
    fun `promoted generation is not cancelled as pending`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        val pending = scope.launch(start = CoroutineStart.LAZY) { }

        session.registerPendingSend(pending)
        session.promotePendingSend(pending)
        session.cancelPendingSends()
        assertFalse(pending.isCancelled)
        scope.cancel()
    }
}
