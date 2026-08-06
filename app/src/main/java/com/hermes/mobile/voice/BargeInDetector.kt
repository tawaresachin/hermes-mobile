package com.hermes.mobile.voice

/**
 * Pure barge-in detector for the voice loop. Given per-block RMS readings
 * from the mic while TTS is playing, it fires ONCE (latching until
 * [reset]) when the user's voice exceeds the threshold on 2 CONSECUTIVE
 * blocks, playback is active and the per-sentence deafen window has
 * passed. Echo-safety is layered on top: the deafen window skips the
 * speaker's own audio right after TTS starts, and the isPlaying gate
 * ignores frames during fetch gaps between sentences.
 */
class BargeInDetector(
    private val threshold: Float = VoiceTuning.BARGE_IN_RMS_THRESHOLD
) {
    private var consecutiveSpeechBlocks = 0
    private var triggered = false

    fun feed(rms: Float, nowMs: Long, isPlaying: Boolean, deafenUntilMs: Long): Boolean {
        if (triggered) return false
        // Echo-safety (a): ignore everything inside the deafen window right
        // after TTS starts (the speaker's own audio still hits the mic).
        // Echo-safety (b): only count while TTS is actually playing.
        if (nowMs < deafenUntilMs || !isPlaying) {
            consecutiveSpeechBlocks = 0
            return false
        }
        return if (rms > threshold) {
            consecutiveSpeechBlocks++
            if (consecutiveSpeechBlocks >= 2) {
                triggered = true
                true
            } else {
                false
            }
        } else {
            consecutiveSpeechBlocks = 0
            false
        }
    }

    fun reset() {
        triggered = false
        consecutiveSpeechBlocks = 0
    }
}
