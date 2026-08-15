package me.rerere.rikkahub.service

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
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

        assertThat(claims.count { it.get() }).isEqualTo(1)
        assertThat(session.isCodexOperationActive).isTrue()
        assertThat(session.tryBeginCodexOperation()).isFalse()

        session.endCodexOperation()
        assertThat(session.tryBeginCodexOperation()).isTrue()
        session.cleanup()
        assertThat(session.isCodexOperationActive).isFalse()
        pool.shutdownNow()
    }
}
