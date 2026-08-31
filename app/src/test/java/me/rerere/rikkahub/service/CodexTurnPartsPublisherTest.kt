package me.rerere.rikkahub.service

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexTurnPartsPublisherTest {
    @Test
    fun `bursts are coalesced and flush keeps the newest snapshot`() = runBlocking {
        val latest = AtomicReference("0")
        val published = CopyOnWriteArrayList<String>()
        val publisher = CodexTurnPartsPublisher(
            scope = this,
            minIntervalMs = 50L,
            snapshot = { listOf(UIMessagePart.Text(latest.get())) },
            publish = { _, parts -> published += (parts.single() as UIMessagePart.Text).text },
            onFailure = { throw it },
        )
        try {
            publisher.request("turn-1")
            withTimeout(2_000L) {
                while (published.isEmpty()) yield()
            }

            repeat(100) { index ->
                latest.set((index + 1).toString())
                publisher.request("turn-1")
            }
            publisher.flush("turn-1")

            assertEquals("100", published.last())
            assertTrue("expected a coalesced burst, got ${published.size} writes", published.size <= 3)
        } finally {
            publisher.close()
        }
    }
}
