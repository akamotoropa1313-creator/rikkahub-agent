package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexTurnStopControllerTest {
    @Test
    fun `pending stop interrupts once when event and response reveal same id`() {
        val stop = CodexTurnStopController()
        stop.requestStop()
        assertTrue(stop.onTurnKnown("turn-1"))
        assertFalse(stop.onTurnKnown("turn-1"))
    }

    @Test
    fun `sequential turns each receive exactly one interrupt`() {
        val stop = CodexTurnStopController()
        stop.requestStop()
        assertTrue(stop.onTurnKnown("turn-1"))
        assertFalse(stop.onTurnKnown("turn-1"))
        stop.finishTurn("turn-1")

        assertFalse(stop.onTurnKnown("turn-2"))
        stop.requestStop()
        assertTrue(stop.onTurnKnown("turn-2"))
        assertFalse(stop.onTurnKnown("turn-2"))
        stop.finishTurn("turn-2")
    }
}
