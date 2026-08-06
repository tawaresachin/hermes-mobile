package com.hermes.mobile.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInDetectorTest {

    private val deafenUntil = 1_000L

    @Test
    fun `two consecutive loud blocks while playing post deafen trigger`() {
        val d = BargeInDetector()
        assertFalse(d.feed(1500f, 2_000L, true, deafenUntil))
        assertTrue(d.feed(1500f, 2_064L, true, deafenUntil))
    }

    @Test
    fun `single loud block does not trigger`() {
        val d = BargeInDetector()
        assertFalse(d.feed(1500f, 2_000L, true, deafenUntil))
    }

    @Test
    fun `deafen window suppresses counting`() {
        val d = BargeInDetector()
        assertFalse(d.feed(1500f, 500L, true, deafenUntil))
        assertFalse(d.feed(1500f, 600L, true, deafenUntil))
        // Counting starts fresh once the deafen window has passed.
        assertFalse(d.feed(1500f, 1_100L, true, deafenUntil))
        assertTrue(d.feed(1500f, 1_200L, true, deafenUntil))
    }

    @Test
    fun `frames while not playing are ignored`() {
        val d = BargeInDetector()
        assertFalse(d.feed(1500f, 2_000L, false, deafenUntil))
        assertFalse(d.feed(1500f, 2_100L, false, deafenUntil))
        // Streak must not carry over from the not-playing period.
        assertFalse(d.feed(1500f, 2_200L, true, deafenUntil))
        assertTrue(d.feed(1500f, 2_300L, true, deafenUntil))
    }

    @Test
    fun `single shot - second trigger false until reset`() {
        val d = BargeInDetector()
        assertFalse(d.feed(1500f, 2_000L, true, deafenUntil))
        assertTrue(d.feed(1500f, 2_100L, true, deafenUntil))
        assertFalse(d.feed(1500f, 2_200L, true, deafenUntil))
        assertFalse(d.feed(1500f, 2_300L, true, deafenUntil))
        d.reset()
        assertFalse(d.feed(1500f, 3_000L, true, deafenUntil))
        assertTrue(d.feed(1500f, 3_100L, true, deafenUntil))
    }

    @Test
    fun `backchannel - single spike then silence does not trigger`() {
        val d = BargeInDetector()
        assertFalse(d.feed(1500f, 2_000L, true, deafenUntil))
        // Sub-threshold block resets the consecutive count.
        assertFalse(d.feed(100f, 2_100L, true, deafenUntil))
        assertFalse(d.feed(1500f, 2_200L, true, deafenUntil))
        assertTrue(d.feed(1500f, 2_300L, true, deafenUntil))
    }

    @Test
    fun `threshold boundary`() {
        val below = BargeInDetector()
        assertFalse(below.feed(1399f, 2_000L, true, deafenUntil))
        assertFalse(below.feed(1399f, 2_100L, true, deafenUntil))

        val above = BargeInDetector()
        assertFalse(above.feed(1401f, 2_000L, true, deafenUntil))
        assertTrue(above.feed(1401f, 2_100L, true, deafenUntil))
    }
}
