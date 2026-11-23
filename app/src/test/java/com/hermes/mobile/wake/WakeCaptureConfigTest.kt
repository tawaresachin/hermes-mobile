package com.hermes.mobile.wake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeCaptureConfigTest {
    @Test
    fun `voice recognition source is tried before plain mic at every rate`() {
        val first = WakeCaptureConfig.ORDERED.first()
        assertEquals(WakeCaptureConfig.SOURCE_VOICE_RECOGNITION, first.source)
        assertEquals(WakeCaptureConfig.TARGET_RATE, first.sampleRate)

        listOf(16_000, 44_100, 48_000).forEach { rate ->
            val sources = WakeCaptureConfig.ORDERED.filter { it.sampleRate == rate }.map { it.source }
            assertEquals(listOf(WakeCaptureConfig.SOURCE_VOICE_RECOGNITION, WakeCaptureConfig.SOURCE_MIC), sources)
        }
    }

    @Test
    fun `every ordered rate maps to an exact detector frame`() {
        WakeCaptureConfig.ORDERED.forEach { config ->
            val sourceSamples = WakeCaptureConfig.sourceFrameSize(config.sampleRate)
            assertEquals(
                WakeCaptureConfig.FRAME_SIZE * config.sampleRate,
                sourceSamples * WakeCaptureConfig.TARGET_RATE,
            )
        }
    }

    @Test
    fun `resampling is identity at the detector rate`() {
        val source = ShortArray(WakeCaptureConfig.FRAME_SIZE) { (it % 251).toShort() }
        val output = ShortArray(WakeCaptureConfig.FRAME_SIZE)

        WakeCaptureConfig.resampleFrame(source, source.size, output)

        assertArrayEquals(source, output)
    }

    @Test
    fun `resampling keeps endpoints and detector frame size`() {
        val sourceRate = 48_000
        val source = ShortArray(WakeCaptureConfig.sourceFrameSize(sourceRate)) { (it % 4096).toShort() }
        val output = ShortArray(WakeCaptureConfig.FRAME_SIZE)

        WakeCaptureConfig.resampleFrame(source, source.size, output)

        assertEquals(WakeCaptureConfig.FRAME_SIZE, output.size)
        assertEquals(source.first(), output.first())
        assertEquals(source.last(), output.last())
    }

    @Test
    fun `rms and signal checks expose a dead microphone`() {
        val silence = ShortArray(256)
        val tone = ShortArray(256) { 1000.toShort() }

        assertEquals(0, WakeCaptureConfig.rms(silence, silence.size))
        assertFalse(WakeCaptureConfig.hasSignal(silence, silence.size))
        assertTrue(WakeCaptureConfig.rms(tone, tone.size) > 0)
        assertTrue(WakeCaptureConfig.hasSignal(tone, tone.size))
    }
}
