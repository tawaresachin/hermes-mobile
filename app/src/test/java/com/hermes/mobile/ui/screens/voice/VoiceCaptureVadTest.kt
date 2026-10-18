package com.hermes.mobile.ui.screens.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation loop's only non-trivial new logic is "when did the person
 * stop talking" plus the container it uploads. Both are pure, so they are
 * asserted here instead of tuned blind on a phone.
 */
class VoiceCaptureVadTest {

    private val speech = 5_000.0 // clears the seeded floor (3 × the 1000 seed cap)
    private val silence = 0.0

    private fun detector() = UtteranceDetector()

    /** Feed [ms] of frames worth of one level; returns the events emitted. */
    private fun feed(det: UtteranceDetector, ms: Int, rms: Double): List<UtteranceDetector.Event> =
        (0 until ms / VoiceCapture.FRAME_MS).map { det.accept(rms) }

    @Test
    fun silenceNeverStartsAnUtterance() {
        val det = detector()
        assertTrue(feed(det, 1_000, silence).all { it == UtteranceDetector.Event.NONE })
        assertFalse(det.isSpeaking)
    }

    @Test
    fun speechStartsAndTrailingSilenceEnds() {
        val det = detector()
        val events = feed(det, 300, speech) + feed(det, 1_000, silence)

        assertEquals(UtteranceDetector.Event.STARTED, events.first())
        assertEquals(1, events.count { it == UtteranceDetector.Event.ENDED })
        assertFalse(det.isSpeaking)
        // Only voiced frames count as speech time.
        assertEquals(300, det.voicedMs)
    }

    @Test
    fun endWaitsForTheFullSilenceWindow() {
        val det = detector()
        feed(det, 300, speech)
        // Just under the 700 ms window: still one utterance.
        val early = feed(det, VoiceCapture.SILENCE_END_MS - VoiceCapture.FRAME_MS, silence)
        assertTrue(early.all { it == UtteranceDetector.Event.NONE })
        assertTrue(det.isSpeaking)
    }

    @Test
    fun shortBlipEndsWithTooLittleSpeechToUpload() {
        val det = detector()
        feed(det, VoiceCapture.FRAME_MS * 2, speech)
        val events = feed(det, 1_000, silence)

        assertTrue(events.contains(UtteranceDetector.Event.ENDED))
        // The capture drops these; Whisper would otherwise get a cough.
        assertTrue(det.voicedMs < VoiceCapture.MIN_SPEECH_MS)
    }

    @Test
    fun endlessSpeechIsCutAtTheCap() {
        val det = UtteranceDetector(
            silenceEndMs = 10_000,
            maxUtteranceMs = 300,
        )
        val events = feed(det, 600, speech)
        assertTrue(events.contains(UtteranceDetector.Event.ENDED))
        assertFalse(det.isSpeaking)
    }

    @Test
    fun nextUtteranceStartsFresh() {
        val det = detector()
        feed(det, 300, speech)
        feed(det, 1_000, silence)
        assertEquals(300, det.voicedMs)

        val again = feed(det, 60, speech)
        assertEquals(UtteranceDetector.Event.STARTED, again.first())
        // Counters restarted: 60 ms voiced, not the previous utterance's 300.
        assertEquals(VoiceCapture.FRAME_MS * 2, det.voicedMs)
    }

    @Test
    fun softSpeechInAQuietRoomStillTriggers() {
        val det = detector()
        feed(det, 300, 20.0) // quiet room: floor 20, so the bar is the 250 minimum

        val events = feed(det, 120, 350.0)

        // A fixed 700 threshold would have missed this speaker entirely.
        assertEquals(UtteranceDetector.Event.STARTED, events.first())
        assertTrue(det.isSpeaking)
    }

    @Test
    fun loudRoomToneDoesNotTrigger() {
        val det = detector()
        // A fan at 900 seeds the floor at 900, so the bar is 2700.
        assertTrue(feed(det, 1_000, 900.0).all { it == UtteranceDetector.Event.NONE })
        assertFalse(det.isSpeaking)

        assertEquals(UtteranceDetector.Event.STARTED, feed(det, 60, 4_000.0).first())
    }

    @Test
    fun rmsMatchesTheSampleScale() {
        assertEquals(0.0, rms(ShortArray(10), 10), 0.0001)
        assertEquals(
            32_767.0,
            rms(ShortArray(10) { Short.MAX_VALUE }, 10),
            0.0001
        )
        assertEquals(0.0, rms(ShortArray(10) { Short.MAX_VALUE }, 0), 0.0001)
    }

    @Test
    fun pcmIsLittleEndian() {
        assertArrayEquals(
            byteArrayOf(0x02, 0x01, 0xFF.toByte(), 0xFF.toByte()),
            pcm16Le(shortArrayOf(0x0102, -1), 2)
        )
    }

    @Test
    fun pcmReusesTheProvidedBuffer() {
        val reuse = ByteArray(4) { 0x55 }
        val out = pcm16Le(shortArrayOf(0x0102, -1, 0x7FFF), 2, reuse)

        assertSame(reuse, out) // the capture path must not allocate per frame
        assertArrayEquals(
            byteArrayOf(0x02, 0x01, 0xFF.toByte(), 0xFF.toByte()),
            reuse
        )
    }

    @Test
    fun wavHeaderIsAWhisperAcceptableContainer() {
        val pcm = ByteArray(1_000) { 0x7F }
        val wav = encodeWav(pcm, VoiceCapture.SAMPLE_RATE, 1, 16)

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + pcm.size, le32(wav, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(16, le32(wav, 16))   // PCM fmt chunk size
        assertEquals(1, le16(wav, 20))    // format = PCM
        assertEquals(1, le16(wav, 22))    // mono
        assertEquals(16_000, le32(wav, 24))
        assertEquals(32_000, le32(wav, 28)) // byte rate
        assertEquals(2, le16(wav, 32))    // block align
        assertEquals(16, le16(wav, 34))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(pcm.size, le32(wav, 40))
    }

    private fun le16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
}
