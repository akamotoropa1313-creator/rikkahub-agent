package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageDeletionTest {
    @Test
    fun `bound Codex delete resets the session before changing history`() = runBlocking {
        val calls = mutableListOf<String>()

        val result = deleteChatMessageSafely(
            resetCodexSession = true,
            resetSession = { calls += "reset" },
            deleteMessage = { calls += "delete" },
            onSessionReset = { calls += "publish-disconnected" },
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("reset", "publish-disconnected", "delete"), calls)
    }

    @Test
    fun `ordinary delete does not reset an unbound conversation`() = runBlocking {
        var resetCalled = false
        var deleteCalled = false

        val result = deleteChatMessageSafely(
            resetCodexSession = false,
            resetSession = { resetCalled = true },
            deleteMessage = { deleteCalled = true },
        )

        assertTrue(result.isSuccess)
        assertFalse(resetCalled)
        assertTrue(deleteCalled)
    }

    @Test
    fun `history guard failure is returned to the UI instead of escaping`() = runBlocking {
        val guardFailure = IllegalStateException(
            "Reset the Codex session before changing history.",
        )

        val result = deleteChatMessageSafely(
            resetCodexSession = false,
            resetSession = {},
            deleteMessage = { throw guardFailure },
        )

        assertTrue(result.isFailure)
        assertSame(guardFailure, result.exceptionOrNull())
    }

    @Test
    fun `message is preserved when the required Codex reset fails`() = runBlocking {
        val resetFailure = IllegalStateException("Codex operation is still running")
        var deleteCalled = false

        val result = deleteChatMessageSafely(
            resetCodexSession = true,
            resetSession = { throw resetFailure },
            deleteMessage = { deleteCalled = true },
        )

        assertTrue(result.isFailure)
        assertSame(resetFailure, result.exceptionOrNull())
        assertFalse(deleteCalled)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is never converted into a visible deletion error`() = runBlocking {
        deleteChatMessageSafely(
            resetCodexSession = false,
            resetSession = {},
            deleteMessage = { throw CancellationException("screen closed") },
        )
        Unit
    }
}
