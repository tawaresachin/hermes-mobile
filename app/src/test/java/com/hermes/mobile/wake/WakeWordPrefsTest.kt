package com.hermes.mobile.wake

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeWordPrefsTest {
    @Test
    fun `sensitivity range and default match the released wake contract`() {
        assertEquals(0.30f, WakeWordPrefs.MIN_SENSITIVITY, 0.0001f)
        assertEquals(0.90f, WakeWordPrefs.MAX_SENSITIVITY, 0.0001f)
        assertEquals(0.60f, WakeWordPrefs.DEFAULT_SENSITIVITY, 0.0001f)
    }
}
