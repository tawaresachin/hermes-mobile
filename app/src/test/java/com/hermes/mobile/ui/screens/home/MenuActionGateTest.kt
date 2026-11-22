package com.hermes.mobile.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The opening tap on the menu must never activate a control revealed by it. */
class MenuActionGateTest {
    private var now = 1_000L
    private val gate = MenuActionGate(guardMs = 250) { now }

    @Test
    fun openingArmsGateAndOnlyLaterTapsRunActions() {
        assertTrue(gate.ready())

        gate.arm()
        assertFalse(gate.ready())

        var runs = 0
        gate.run { runs++ }
        gate.run { runs++ }
        assertEquals(0, runs)

        now += 250
        assertTrue(gate.ready())
        gate.run { runs++ }
        assertEquals(1, runs)
    }

    @Test
    fun rearmingExtendsTheWindow() {
        gate.arm()
        now += 200
        gate.arm()
        now += 100
        assertFalse(gate.ready())
        now += 150
        assertTrue(gate.ready())
    }
}
