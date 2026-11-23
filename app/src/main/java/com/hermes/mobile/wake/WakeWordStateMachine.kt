package com.hermes.mobile.wake

/**
 * Pure-Kotlin wake confirmation: one score per inference window.
 *
 * A real phrase holds the score above [sensitivity] across several windows;
 * a stray phoneme spikes one. Mirrors the Hermes Agent engine: N consecutive
 * over-threshold windows fire once, then a cooldown suppresses the rest of
 * the same phrase.
 */
class WakeWordStateMachine(
    val sensitivity: Float = DEFAULT_SENSITIVITY,
    private val confirmationFrames: Int = DEFAULT_CONFIRMATION_FRAMES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    companion object {
        const val DEFAULT_SENSITIVITY = 0.6f
        const val DEFAULT_CONFIRMATION_FRAMES = 3
        const val DEFAULT_COOLDOWN_MS = 2_000L
    }

    private var streak = 0
    private var lastFireMs: Long? = null

    /** Feed one classifier score. [nowMs] must be monotonic. Returns true on a fire. */
    fun processScore(score: Float, nowMs: Long): Boolean {
        if (score < sensitivity) {
            streak = 0
            return false
        }
        streak++
        if (streak < confirmationFrames) return false
        streak = 0
        val last = lastFireMs
        if (last != null && nowMs - last < cooldownMs) return false
        lastFireMs = nowMs
        return true
    }

    /** Clears streak and cooldown (between sessions / after a pause). */
    fun reset() {
        streak = 0
        lastFireMs = null
    }

    /** Current consecutive over-threshold count (tests / diagnostics). */
    fun currentStreak(): Int = streak
}
