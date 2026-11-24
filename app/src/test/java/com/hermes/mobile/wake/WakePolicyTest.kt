package com.hermes.mobile.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakePolicyTest {

    @Test
    fun `capture backoff grows then caps at ten seconds`() {
        assertEquals(1_000L, WakeRetryPolicy.nextDelayMs(0))
        assertEquals(2_000L, WakeRetryPolicy.nextDelayMs(1))
        assertEquals(4_000L, WakeRetryPolicy.nextDelayMs(2))
        assertEquals(8_000L, WakeRetryPolicy.nextDelayMs(3))
        assertEquals(10_000L, WakeRetryPolicy.nextDelayMs(4))
        assertEquals(10_000L, WakeRetryPolicy.nextDelayMs(99))
    }

    @Test
    fun `capture and pause reasons become short status words`() {
        assertEquals("microphone is silent", WakeStatusText.captureReason("VR @ 16 kHz: silent"))
        assertEquals("detector init failed", WakeStatusText.captureReason("Wake detector failed"))
        assertEquals("no supported sample rate", WakeStatusText.captureReason("MIC: rate unavailable"))
        assertEquals("screen off", WakeStatusText.pauseReason("ScreenOff"))
        assertEquals("assistant is replying", WakeStatusText.pauseReason("AssistantTurn"))
        assertEquals("VOICE_RECOGNITION 16 kHz", WakeCaptureConfig.ORDERED.first().label)
        assertNull(WakeStatusText.captureReason("—"))
    }
}
