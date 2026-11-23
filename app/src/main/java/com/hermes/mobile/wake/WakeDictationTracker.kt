package com.hermes.mobile.wake

/**
 * Consecutive-silence rule for one wake-initiated dictation. Pure JVM logic so
 * the two-strike exit is testable without Android or a SpeechRecognizer.
 */
class WakeDictationTracker(private val maxSilentAttempts: Int = DEFAULT_MAX_SILENT_ATTEMPTS) {

    private var silentAttempts = 0

    init {
        require(maxSilentAttempts > 0) { "maxSilentAttempts must be positive" }
    }

    /** A real transcript (final or partial) ends the silence streak. */
    fun onSpeech() {
        silentAttempts = 0
    }

    /**
     * Records one silent attempt (no match / timeout / empty transcript).
     * Returns true when the streak reached the limit and listening must stop.
     */
    fun onSilent(): Boolean {
        silentAttempts++
        return stopped
    }

    fun reset() {
        silentAttempts = 0
    }

    val stopped: Boolean get() = silentAttempts >= maxSilentAttempts
    val count: Int get() = silentAttempts

    companion object {
        const val DEFAULT_MAX_SILENT_ATTEMPTS = 2
        const val STOPPED_LABEL = "Stopped listening"
    }
}
