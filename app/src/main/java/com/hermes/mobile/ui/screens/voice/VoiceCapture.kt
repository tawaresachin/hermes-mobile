package com.hermes.mobile.ui.screens.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Continuous microphone capture with an energy VAD, for conversation mode.
 *
 * Speaks the same 16 kHz mono PCM16 the hold-to-talk path uses, but keeps
 * reading instead of taking one fixed window: every voiced run that ends in
 * ~0.7 s of silence is handed to [onUtterance] as raw PCM. The caller
 * encodes/uploads it — this class only decides *when* a person stopped
 * talking, and reports a smoothed loudness so the caller can react live.
 *
 * Lifecycle: [start]/[stop] are idempotent, [release] frees the mic and the
 * scope. Android allows capture only while foregrounded (the screen stops
 * the mode on ON_STOP); nothing here tries to survive that.
 */
class VoiceCapture(
    private val onUtterance: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    /** Smoothed 0..1 loudness, ~33×/s, for the orb's live swell. */
    private val onLevel: (Float) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16_000

        /** 30 ms of audio at [SAMPLE_RATE] — short enough that the VAD's
         * reaction time hides inside the silence window. */
        const val FRAME_MS = 30
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000

        /** RMS that maps to a fully swollen orb (loud speech near the mic). */
        const val LEVEL_FULL_SCALE = 4_000f

        /** Per-frame share of the gap to the new level: swell, not flicker. */
        private const val LEVEL_SMOOTH = 0.35f

        /** Absolute speech floor on the 0..32767 sample scale: below this
         * nothing is speech, whatever the room measures (breathing, hum). */
        const val MIN_SPEECH_RMS = 250.0

        /** Speech must also clear the measured room tone by this factor, so a
         * loud room raises the bar instead of tripping the detector. */
        const val NOISE_MULTIPLIER = 3.0

        /** Utterances shorter than this are clicks/coughs, not speech. */
        const val MIN_SPEECH_MS = 250

        /** Trailing quiet that closes an utterance (a beat of think-time
         * mid-sentence is shorter than this). */
        const val SILENCE_END_MS = 700

        /** Hard cap so a noisy room can't record forever. */
        const val MAX_UTTERANCE_MS = 20_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Opens the mic and reads until [stop]. No-op when already running. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    /** Closes the mic. The read loop releases the recorder on its way out. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Terminal: mic + scope down. */
    fun release() {
        stop()
        scope.cancel()
    }

    @SuppressLint("MissingPermission") // callers gate on RECORD_AUDIO
    private fun CoroutineScope.runLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = if (minBuf > 0) minBuf * 2 else 8192
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() } // failed init still holds resources
            onError("Microphone unavailable")
            return
        }

        val detector = UtteranceDetector()
        val frame = ShortArray(FRAME_SAMPLES)
        val pcm = ByteArray(FRAME_SAMPLES * 2) // reused: 33 frames/s, no churn
        val utterance = ByteArrayOutputStream()
        var smoothLevel = 0f
        try {
            recorder.startRecording()
            while (isActive) {
                val read = recorder.read(frame, 0, frame.size)
                if (read < 0) {
                    onError("Microphone read failed")
                    return
                }
                if (read == 0) continue
                val level = rms(frame, read)
                smoothLevel += ((level / LEVEL_FULL_SCALE).toFloat().coerceIn(0f, 1f) - smoothLevel) *
                    LEVEL_SMOOTH
                onLevel(smoothLevel)
                when (detector.accept(level)) {
                    UtteranceDetector.Event.STARTED -> {
                        utterance.reset()
                        utterance.write(pcm16Le(frame, read, pcm), 0, read * 2)
                    }
                    UtteranceDetector.Event.NONE ->
                        if (detector.isSpeaking) {
                            utterance.write(pcm16Le(frame, read, pcm), 0, read * 2)
                        }
                    UtteranceDetector.Event.ENDED -> {
                        // The closing silence frame is not part of the
                        // utterance; the next STARTED resets the counters.
                        if (detector.voicedMs >= MIN_SPEECH_MS) onUtterance(utterance.toByteArray())
                        utterance.reset()
                    }
                }
            }
        } finally {
            // Every exit path (cancel, error, stop) frees the mic.
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }
}

/**
 * Frame-fed voice-activity state machine: one 30 ms RMS at a time in, an
 * utterance boundary out. Deliberately free of Android types so the timing
 * rules are unit-tested instead of tuned blind on a device.
 *
 * Frames at/after [Event.ENDED] belong to the NEXT utterance.
 */
internal class UtteranceDetector(
    private val minSpeechRms: Double = VoiceCapture.MIN_SPEECH_RMS,
    private val silenceEndMs: Int = VoiceCapture.SILENCE_END_MS,
    private val maxUtteranceMs: Int = VoiceCapture.MAX_UTTERANCE_MS,
    private val frameMs: Int = VoiceCapture.FRAME_MS,
) {
    private companion object {
        /** A first frame this loud is speech or clipping, not room tone:
         * seeding the floor from it would deafen the detector. */
        const val MAX_NOISE_SEED = 1_000.0

        /** Quiet-frame adaptation per frame (~1 s to follow the room). */
        const val NOISE_ADAPT = 0.05
    }

    enum class Event { NONE, STARTED, ENDED }

    private var speaking = false
    private var voiced = 0
    private var quiet = 0
    private var total = 0

    /** Measured room tone; negative until the first frame seeds it. */
    private var noiseFloor = -1.0

    val isSpeaking: Boolean get() = speaking

    /** Speech time of the utterance that just ended (or the one in flight). */
    val voicedMs: Int get() = voiced

    /** What speech has to clear. A fixed level either misses soft speech on a
     * low-gain mic or fires on a loud room; this tracks both. */
    private fun threshold(): Double =
        if (noiseFloor < 0.0) minSpeechRms
        else maxOf(minSpeechRms, noiseFloor * VoiceCapture.NOISE_MULTIPLIER)

    fun accept(rms: Double): Event {
        if (noiseFloor < 0.0) {
            noiseFloor = minOf(rms, MAX_NOISE_SEED)
        } else if (!speaking && rms < threshold()) {
            // Learn only from frames already judged quiet, so speech never
            // lifts the floor and the bar follows this device and room.
            noiseFloor += (rms - noiseFloor) * NOISE_ADAPT
        }
        val speechRms = threshold()
        if (!speaking) {
            if (rms < speechRms) return Event.NONE
            speaking = true
            voiced = frameMs
            quiet = 0
            total = frameMs
            return Event.STARTED
        }
        total += frameMs
        if (rms >= speechRms) {
            voiced += frameMs
            quiet = 0
        } else {
            quiet += frameMs
        }
        // Trailing silence closes the utterance even when it was short: a
        // cough must not keep the buffer open (the capture drops < 250 ms).
        val closed = quiet >= silenceEndMs
        if (closed || total >= maxUtteranceMs) {
            speaking = false
            return Event.ENDED
        }
        return Event.NONE
    }
}

/** RMS of the first [len] samples, on the 0..32767 scale of PCM16. */
internal fun rms(frame: ShortArray, len: Int): Double {
    if (len <= 0) return 0.0
    var sum = 0.0
    for (i in 0 until len) {
        val v = frame[i].toDouble()
        sum += v * v
    }
    return sqrt(sum / len)
}

/** Little-endian PCM16 — WAV and Whisper both want LE, `Short` is host-order.
 * Pass [out] to avoid a per-frame allocation on the capture path. */
internal fun pcm16Le(frame: ShortArray, len: Int, out: ByteArray = ByteArray(len * 2)): ByteArray {
    val n = minOf(len, frame.size, out.size / 2)
    for (i in 0 until n) {
        val v = frame[i].toInt()
        out[i * 2] = (v and 0xFF).toByte()
        out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }
    return out
}

/** Minimal 44-byte WAV wrapper — the server's Whisper path takes a file, and
 * both voice paths must send byte-identical containers. */
internal fun encodeWav(
    pcmData: ByteArray,
    sampleRate: Int,
    channels: Int,
    bitsPerSample: Int
): ByteArray {
    val totalDataLen = pcmData.size + 36
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val buffer = ByteArrayOutputStream(44 + pcmData.size)

    buffer.write("RIFF".toByteArray())
    writeInt(buffer, totalDataLen)
    buffer.write("WAVE".toByteArray())

    buffer.write("fmt ".toByteArray())
    writeInt(buffer, 16)
    writeShort(buffer, 1.toShort())
    writeShort(buffer, channels.toShort())
    writeInt(buffer, sampleRate)
    writeInt(buffer, byteRate)
    writeShort(buffer, (channels * bitsPerSample / 8).toShort())
    writeShort(buffer, bitsPerSample.toShort())

    buffer.write("data".toByteArray())
    writeInt(buffer, pcmData.size)
    buffer.write(pcmData)

    return buffer.toByteArray()
}

private fun writeInt(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xff)
    out.write((value shr 8) and 0xff)
    out.write((value shr 16) and 0xff)
    out.write((value shr 24) and 0xff)
}

private fun writeShort(out: ByteArrayOutputStream, value: Short) {
    out.write(value.toInt() and 0xff)
    out.write((value.toInt() shr 8) and 0xff)
}
