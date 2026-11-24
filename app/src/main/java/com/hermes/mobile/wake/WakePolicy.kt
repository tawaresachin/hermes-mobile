package com.hermes.mobile.wake

/** Capped backoff for a wake mic that failed to open or lost its read loop. */
object WakeRetryPolicy {
    const val INITIAL_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 10_000L

    /** Failure 0 starts at 1s; failures 4+ settle at the 10s cap. */
    fun nextDelayMs(failureCount: Int): Long {
        if (failureCount <= 0) return INITIAL_DELAY_MS
        return minOf(MAX_DELAY_MS, INITIAL_DELAY_MS shl minOf(failureCount, 4))
    }
}

/** Short words for the one status line; full detail stays in DiagLog. */
object WakeStatusText {
    fun captureReason(lastError: String): String? = when {
        lastError.contains("silent", ignoreCase = true) -> "microphone is silent"
        lastError.contains("no data", ignoreCase = true) -> "no microphone data"
        lastError.contains("not initialized", ignoreCase = true) -> "microphone unavailable"
        lastError.contains("start failed", ignoreCase = true) -> "microphone start failed"
        lastError.contains("rate unavailable", ignoreCase = true) -> "no supported sample rate"
        lastError.contains("detector", ignoreCase = true) -> "detector init failed"
        lastError.contains("read failed", ignoreCase = true) -> "microphone read failed"
        else -> null
    }

    fun pauseReason(reason: String): String = when (reason) {
        "AssistantTurn" -> "assistant is replying"
        "VoiceScreen" -> "voice screen open"
        "Dictation" -> "dictation in progress"
        "AppBackground" -> "app in background"
        "ScreenOff" -> "screen off"
        "AudioFocus" -> "audio focus in use"
        "RouteChange" -> "audio route changed"
        "MicrophoneUnavailable" -> "microphone unavailable"
        else -> reason.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
    }
}
