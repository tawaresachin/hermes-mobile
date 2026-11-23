package com.hermes.mobile.wake

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Passive wake capture: ordered microphone configurations and the exact linear
 * resample back to the detector's 16 kHz frame contract. No AudioRecord is
 * created here, so selection and resampling are unit-testable on the JVM.
 */
object WakeCaptureConfig {
    const val TARGET_RATE = WakeWordDetector.SAMPLE_RATE
    const val FRAME_SIZE = WakeWordDetector.SAMPLES_PER_CHUNK

    // MediaRecorder.AudioSource constants kept as literals so this object stays
    // pure JVM code (the service passes them straight to AudioRecord).
    const val SOURCE_MIC = 0
    const val SOURCE_VOICE_RECOGNITION = 1

    data class CaptureConfig(val source: Int, val sampleRate: Int) {
        val label: String get() = "${sourceLabel(source)} @ ${sampleRate / 1000} kHz"

        companion object {
            fun sourceLabel(source: Int): String = when (source) {
                SOURCE_VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                SOURCE_MIC -> "MIC"
                else -> "SOURCE_$source"
            }
        }
    }

    /** Voice pipeline first: many phones mute or high-pass plain MIC. */
    val ORDERED: List<CaptureConfig> = listOf(
        CaptureConfig(SOURCE_VOICE_RECOGNITION, 16_000),
        CaptureConfig(SOURCE_MIC, 16_000),
        CaptureConfig(SOURCE_VOICE_RECOGNITION, 44_100),
        CaptureConfig(SOURCE_MIC, 44_100),
        CaptureConfig(SOURCE_VOICE_RECOGNITION, 48_000),
        CaptureConfig(SOURCE_MIC, 48_000),
    )

    /** Source samples that make exactly one 16 kHz detector frame. */
    fun sourceFrameSize(sampleRate: Int): Int = FRAME_SIZE * sampleRate / TARGET_RATE

    /**
     * Linear, endpoint-to-endpoint resample of one full source frame into a
     * 16 kHz detector frame. Identity when the rates already match.
     */
    fun resampleFrame(source: ShortArray, valid: Int, output: ShortArray) {
        require(valid > 1 && valid <= source.size) { "invalid source frame: $valid" }
        require(output.size == FRAME_SIZE) { "output must be $FRAME_SIZE samples" }
        val last = valid - 1
        var j = 0
        while (j < output.size) {
            val p = j.toDouble() * last / (output.size - 1)
            val i = min(p.toInt(), last - 1)
            val frac = p - i
            val a = source[i].toInt()
            val b = source[i + 1].toInt()
            output[j] = (a + (b - a) * frac).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            j++
        }
    }

    /** Bounded RMS of a PCM16 buffer; 0 means a dead source. */
    fun rms(samples: ShortArray, valid: Int): Int {
        if (valid <= 0) return 0
        var sum = 0.0
        var i = 0
        while (i < valid) {
            val v = samples[i].toInt()
            sum += v.toDouble() * v
            i++
        }
        return sqrt(sum / valid).toInt()
    }

    /** A source that returns all-zero samples is muted by the device. */
    fun hasSignal(samples: ShortArray, valid: Int): Boolean {
        var i = 0
        while (i < valid) {
            if (samples[i] != 0.toShort()) return true
            i++
        }
        return false
    }
}
