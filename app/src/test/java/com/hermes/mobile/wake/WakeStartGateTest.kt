package com.hermes.mobile.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeStartGateTest {

    @Test
    fun `microphone is always requested first`() {
        assertEquals(
            WakePermissionStep.Microphone,
            WakePermissionGate.next(false, false, 33),
        )
    }

    @Test
    fun `notifications are requested on 13 plus but never block start`() {
        assertEquals(
            WakePermissionStep.Notifications,
            WakePermissionGate.next(true, false, 33),
        )
        assertEquals(
            WakePermissionStep.Start,
            WakePermissionGate.next(true, false, 32),
        )
        assertEquals(
            WakePermissionStep.Start,
            WakePermissionGate.next(true, true, 35),
        )
    }

    @Test
    fun `a live service confirmation keeps the toggle on`() {
        val gate = WakeStartGate(3_000L)
        val id = gate.begin(1_000L)
        val result = gate.acknowledge(id, listening = true, reason = null, nowMs = 2_000L)

        assertEquals(WakeStartGate.Result(true, null), result)
        assertNull(gate.timeout(id, 4_000L))
    }

    @Test
    fun `a service failure names the reason immediately`() {
        val gate = WakeStartGate(3_000L)
        val id = gate.begin(1_000L)
        val result = gate.acknowledge(
            id, listening = false, reason = "SecurityException: denied", nowMs = 1_100L,
        )

        assertEquals(WakeStartGate.Result(false, "SecurityException: denied"), result)
        assertNull(gate.acknowledge(id, true, null, 1_200L))
    }

    @Test
    fun `no answer within three seconds flips the toggle back`() {
        val gate = WakeStartGate(3_000L)
        val id = gate.begin(1_000L)

        assertNull(gate.timeout(id, 3_999L))
        assertEquals(
            WakeStartGate.Result(false, "wake service did not respond"),
            gate.timeout(id, 4_000L),
        )
    }

    @Test
    fun `a late answer cannot revive an expired attempt`() {
        val gate = WakeStartGate(3_000L)
        val id = gate.begin(1_000L)

        assertNull(gate.acknowledge(id, true, null, 4_001L))
        assertNull(gate.timeout(id, 4_001L))
    }

    @Test
    fun `a stale attempt is ignored`() {
        val gate = WakeStartGate(3_000L)
        val first = gate.begin(0L)
        val second = gate.begin(0L)

        assertNull(gate.acknowledge(first, true, null, 10L))
        assertTrue(gate.acknowledge(second, true, null, 10L)!!.listening)
    }

    @Test
    fun `cancel drops a pending attempt`() {
        val gate = WakeStartGate(3_000L)
        val id = gate.begin(0L)
        gate.cancel()

        assertNull(gate.acknowledge(id, true, null, 1L))
        assertNull(gate.timeout(id, 10_000L))
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
