package com.hermes.mobile.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordRunControllerTest {
    @Test
    fun `detection waits for the turn then re-arms`() {
        val c = WakeWordRunController()
        assertEquals(WakeRunState.Idle, c.currentState())
        assertEquals(WakeRunState.Listening, c.start())
        assertTrue(c.onDetected())
        assertEquals(WakeRunState.WaitingForTurn, c.currentState())
        assertFalse(c.onDetected())
        assertEquals(WakeRunState.WaitingForTurn, c.pause(WakePauseReason.AssistantTurn))
        assertEquals(WakeRunState.Listening, c.turnFinished())
    }

    @Test
    fun `independent pause reasons cannot resume each other`() {
        val c = WakeWordRunController()
        c.start()
        assertEquals(WakeRunState.Paused, c.pause(WakePauseReason.VoiceScreen))
        assertEquals(WakeRunState.Paused, c.pause(WakePauseReason.AppBackground))
        assertEquals(WakeRunState.Paused, c.resume(WakePauseReason.VoiceScreen))
        assertEquals(WakeRunState.Listening, c.resume(WakePauseReason.AppBackground))
    }

    @Test
    fun `detection is ignored while paused`() {
        val c = WakeWordRunController()
        c.start()
        c.pause(WakePauseReason.ScreenOff)
        assertFalse(c.onDetected())
        assertEquals(WakeRunState.Paused, c.currentState())
        c.resume(WakePauseReason.ScreenOff)
        assertTrue(c.onDetected())
    }

    @Test
    fun `turn end keeps unrelated pause reasons`() {
        val c = WakeWordRunController()
        c.start()
        c.onDetected()
        c.pause(WakePauseReason.AssistantTurn)
        c.pause(WakePauseReason.AppBackground)
        assertEquals(WakeRunState.Paused, c.turnFinished())
        assertEquals(WakeRunState.Listening, c.resume(WakePauseReason.AppBackground))
    }

    @Test
    fun `stop is terminal until explicitly started again`() {
        val c = WakeWordRunController()
        c.start()
        assertTrue(c.onDetected())
        assertEquals(WakeRunState.Idle, c.stop())
        assertEquals(WakeRunState.Idle, c.turnFinished())
        assertEquals(WakeRunState.Listening, c.start())
    }
}
