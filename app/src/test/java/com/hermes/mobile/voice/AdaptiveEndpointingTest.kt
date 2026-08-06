package com.hermes.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveEndpointingTest {

    @Test
    fun `silenceMs gives long window for very short utterances`() {
        assertEquals(1600L, AdaptiveEndpointing.silenceMs(0L, 500L))
        // Utterance band wins over burst band (utterance < 1.2s).
        assertEquals(1600L, AdaptiveEndpointing.silenceMs(2_000L, 500L))
    }

    @Test
    fun `silenceMs gives short window for long continuous bursts`() {
        assertEquals(800L, AdaptiveEndpointing.silenceMs(2_000L, 5_000L))
    }

    @Test
    fun `silenceMs gives base window otherwise`() {
        assertEquals(1200L, AdaptiveEndpointing.silenceMs(0L, 5_000L))
        assertEquals(1200L, AdaptiveEndpointing.silenceMs(1_000L, 5_000L))
        // Boundary: burst exactly at 1.5s still short.
        assertEquals(800L, AdaptiveEndpointing.silenceMs(1_500L, 5_000L))
    }

    @Test
    fun `normalize maps rms into the zero-to-one range against the noise floor`() {
        assertEquals(0.5f, AdaptiveEndpointing.normalize(100f, 50f, 150f), 0.001f)
        assertEquals(1f, AdaptiveEndpointing.normalize(500f, 50f, 150f), 0.001f)
        assertEquals(0f, AdaptiveEndpointing.normalize(10f, 50f, 150f), 0.001f)
    }

    @Test
    fun `normalize clamps and guards degenerate ref`() {
        assertEquals(1f, AdaptiveEndpointing.normalize(10_000f, 100f, 200f), 0.001f)
        // ref <= noise floor is degenerate → 0, never a divide-by-zero.
        assertEquals(0f, AdaptiveEndpointing.normalize(100f, 100f, 100f), 0.001f)
        assertEquals(0f, AdaptiveEndpointing.normalize(100f, 100f, 50f), 0.001f)
    }

    @Test
    fun `shouldEarlyFinalize only after long speech plus long silence`() {
        assertTrue(AdaptiveEndpointing.shouldEarlyFinalize(12_000L, 2_000L))
        assertFalse(AdaptiveEndpointing.shouldEarlyFinalize(11_999L, 2_000L))
        assertFalse(AdaptiveEndpointing.shouldEarlyFinalize(12_000L, 1_999L))
        assertFalse(AdaptiveEndpointing.shouldEarlyFinalize(500L, 5_000L))
    }

    @Test
    fun `noise floor defaults to quiet and tracks the low percentile`() {
        val empty = AdaptiveEndpointing.NoiseFloor()
        assertEquals(150f, empty.estimate(), 0.001f)

        val nf = AdaptiveEndpointing.NoiseFloor()
        for (i in 1..10) nf.feed(i.toFloat())
        // 10th percentile of 1..10 → smallest value.
        assertEquals(1f, nf.estimate(), 0.001f)

        val quiet = AdaptiveEndpointing.NoiseFloor()
        repeat(20) { quiet.feed(0f) }
        assertEquals(0f, quiet.estimate(), 0.001f)
    }
}
