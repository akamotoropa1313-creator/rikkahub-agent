package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Test
import kotlin.uuid.Uuid

class CodexConversationOperationTest {
    @Test
    fun `opening starting and running share one atomic busy lease`() {
        val id = Uuid.random()
        val session = ConversationSession(
            id = id,
            initial = Conversation.ofId(id, Uuid.random()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onIdle = {},
        )
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val claims = (1..32).map {
            pool.submit<Boolean> {
                start.await()
                session.tryBeginCodexOperation()
            }
        }
        start.countDown()

        assertEquals(1, claims.count { it.get() })
        assertTrue(session.isCodexOperationActive)
        assertFalse(session.tryBeginCodexOperation())

        session.endCodexOperation()
        assertTrue(session.tryBeginCodexOperation())
        session.cleanup()
        assertFalse(session.isCodexOperationActive)
        pool.shutdownNow()
    }
}
