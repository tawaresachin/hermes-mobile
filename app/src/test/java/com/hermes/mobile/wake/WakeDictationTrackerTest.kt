package com.hermes.mobile.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeDictationTrackerTest {
    @Test
    fun `two silent results stop the dictation`() {
        val tracker = WakeDictationTracker()
        assertFalse(tracker.onSilent())
        assertTrue(tracker.onSilent())
        assertTrue(tracker.stopped)
        assertEquals(2, tracker.count)
    }

    @Test
    fun `one silent then speech continues`() {
        val tracker = WakeDictationTracker()
        assertFalse(tracker.onSilent())
        tracker.onSpeech()
        assertFalse(tracker.stopped)
        assertFalse(tracker.onSilent())
        assertTrue(tracker.onSilent())
        assertTrue(tracker.stopped)
    }

    @Test
    fun `reset clears the streak`() {
        val tracker = WakeDictationTracker()
        assertFalse(tracker.onSilent())
        tracker.reset()
        assertEquals(0, tracker.count)
        assertFalse(tracker.onSilent())
        assertFalse(tracker.stopped)
    }
}
