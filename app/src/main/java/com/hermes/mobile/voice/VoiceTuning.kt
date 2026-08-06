package com.hermes.mobile.voice

// ═══════════════════════════════════════════════════════════════
// VoiceTuning — single source of truth for voice-loop constants.
// ═══════════════════════════════════════════════════════════════

object VoiceTuning {

    /** VAD speech gate (RMS) in a typical quiet room. */
    const val SPEECH_RMS_BASE = 700f

    /** Barge-in trigger threshold (RMS) — louder than normal speech. */
    const val BARGE_IN_RMS_THRESHOLD = 1400f

    /** Default end-of-utterance silence (ms). */
    const val SILENCE_BASE_MS = 1200L

    /** End-of-utterance silence for long continuous bursts (ms). */
    const val SILENCE_SHORT_MS = 800L

    /** End-of-utterance silence for very short utterances (ms). */
    const val SILENCE_LONG_MS = 1600L

    /** Minimum captured audio for a listen to count (bytes). */
    const val MIN_SPEECH_BYTES = 3200

    /** Hard cap on a single utterance (ms). */
    const val MAX_UTTERANCE_MS = 15000L

    /** After this much speech, allow finalizing on a long pause. */
    const val EARLY_FINALIZE_AFTER_MS = 12000L

    /** Silence length that triggers an early finalize (ms). */
    const val EARLY_FINALIZE_SILENCE_MS = 2000L

    /** Mic deafen right after TTS starts (ms) — the speaker's own audio. */
    const val DEAFEN_MS = 250L

    /** Pause between barge-in and re-arming listening (ms). */
    const val BARGE_IN_SETTLE_MS = 150L

    /** Reference RMS used to normalize mic amplitude for the sphere. */
    const val AMPLITUDE_REF = 6000f
}
