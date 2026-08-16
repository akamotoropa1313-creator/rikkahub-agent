package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexFinalAuditStopPolicyTest {
    @Test
    fun `active turn is interruptible`() {
        assertTrue(shouldRecordCodexStop(activeTurnId = "turn-1", reviewStarting = false, runtimeCapabilityBusy = false))
    }

    @Test
    fun `normal turn start waiting for id preserves stop intent`() {
        // ChatService already holds the conversation-level Codex operation lease. A runtime that
        // is not capability-busy therefore represents the normal turn/start path even before the
        // exact turn ID arrives.
        assertTrue(shouldRecordCodexStop(activeTurnId = null, reviewStarting = false, runtimeCapabilityBusy = false))
    }

    @Test
    fun `review start waiting for id remains interruptible`() {
        assertTrue(shouldRecordCodexStop(activeTurnId = null, reviewStarting = true, runtimeCapabilityBusy = true))
    }

    @Test
    fun `capability only operation cannot arm the next turn`() {
        assertFalse(shouldRecordCodexStop(activeTurnId = null, reviewStarting = false, runtimeCapabilityBusy = true))
    }
}
