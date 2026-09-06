package com.hermes.mobile.voice

/**
 * Adaptive endpointing: turn-taking decisions for the whisper VAD loop.
 * Pure functions plus a small noise-floor estimator — no Android
 * dependencies, fully unit-testable on the JVM.
 */
object AdaptiveEndpointing {

    /**
     * Silence (ms) that ends an utterance, based on cadence:
     * - Very short utterances (<1.2s) get a LONG window — the user is
     *   still warming up, don't cut them off.
     * - Long continuous bursts (>=1.5s of uninterrupted speech) get a
     *   SHORT window — mid-flow pauses are brief, react fast.
     * - Everything else gets the base window.
     */
    fun silenceMs(burstMs: Long, utteranceMs: Long): Long = when {
        utteranceMs < 1200L -> VoiceTuning.SILENCE_LONG_MS
        burstMs >= 1500L -> VoiceTuning.SILENCE_SHORT_MS
        else -> VoiceTuning.SILENCE_BASE_MS
    }

    /**
     * Map an RMS sample to a 0..1 amplitude against the room's noise
     * floor. [ref] is the caller's peak-anchored reference
     * (max(AMPLITUDE_REF, peak)); a degenerate ref (<= floor) yields 0.
     */
    fun normalize(rms: Float, noiseFloor: Float, ref: Float): Float {
        if (ref <= noiseFloor) return 0f
        return ((rms - noiseFloor) / (ref - noiseFloor)).coerceIn(0f, 1f)
    }

    /** Long utterance + long pause → stop waiting, finalize now. */
    fun shouldEarlyFinalize(speechMs: Long, silenceMs: Long): Boolean =
        speechMs >= VoiceTuning.EARLY_FINALIZE_AFTER_MS &&
            silenceMs >= VoiceTuning.EARLY_FINALIZE_SILENCE_MS

    /**
     * Running noise-floor estimator: the 10th percentile of recent RMS
     * samples (silence dominates the tail, so the 10th pct tracks the
     * room's ambient level). Defaults to 150f before any samples arrive.
     */
    class NoiseFloor(private val capacity: Int = 200) {
        private val samples = ArrayDeque<Float>()

        fun feed(rms: Float) {
            if (samples.size >= capacity) samples.removeFirst()
            samples.addLast(rms)
        }

        fun estimate(): Float {
            if (samples.isEmpty()) return 150f
            val sorted = samples.sorted()
            val idx = ((sorted.size - 1) * 0.1).toInt()
            return sorted[idx]
        }
    }
}
