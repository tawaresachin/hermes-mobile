package com.hermes.mobile.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordStateMachineTest {
    private fun over(sm: WakeWordStateMachine, times: Int, startMs: Long = 0L): Boolean {
        var fired = false
        for (i in 0 until times) fired = sm.processScore(0.9f, startMs + i * 100L) || fired
        return fired
    }

    @Test
    fun `fires only after three consecutive over-threshold windows`() {
        val sm = WakeWordStateMachine()
        assertFalse(sm.processScore(0.9f, 0L))
        assertFalse(sm.processScore(0.9f, 100L))
        assertTrue(sm.processScore(0.9f, 200L))
    }

    @Test
    fun `single spike does not fire`() {
        val sm = WakeWordStateMachine()
        assertFalse(sm.processScore(0.9f, 0L))
        assertFalse(sm.processScore(0.1f, 100L))
        assertEquals(0, sm.currentStreak())
        assertFalse(over(sm, 2, 200L))
    }

    @Test
    fun `default sensitivity threshold is inclusive`() {
        val sm = WakeWordStateMachine()
        assertFalse(sm.processScore(0.59f, 0L))
        assertFalse(sm.processScore(WakeWordStateMachine.DEFAULT_SENSITIVITY, 100L))
        assertFalse(sm.processScore(0.6f, 200L))
        assertTrue(sm.processScore(0.6f, 300L))
    }

    @Test
    fun `cooldown suppresses a second fire inside two seconds`() {
        val sm = WakeWordStateMachine()
        assertTrue(over(sm, 3)) // fires at t=200
        assertFalse(over(sm, 3, 300L)) // t=300..500, still inside cooldown
        assertTrue(over(sm, 3, 2_200L)) // t=2200..2400, cooldown elapsed
    }

    @Test
    fun `reset clears streak and cooldown`() {
        val sm = WakeWordStateMachine()
        assertFalse(over(sm, 2))
        sm.reset()
        assertEquals(0, sm.currentStreak())
        assertFalse(over(sm, 2, 1_000L))
        assertTrue(over(sm, 1, 1_200L)) // fresh streak after reset fires immediately
    }
}
