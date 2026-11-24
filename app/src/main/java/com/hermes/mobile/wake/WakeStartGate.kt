package com.hermes.mobile.wake

/** The next permission step before the wake mic may be started. */
enum class WakePermissionStep { Microphone, Notifications, Start }

/** Pure permission decision: notifications are requested but never mandatory. */
object WakePermissionGate {
    fun next(
        microphoneGranted: Boolean,
        notificationsGranted: Boolean,
        sdkInt: Int,
    ): WakePermissionStep = when {
        !microphoneGranted -> WakePermissionStep.Microphone
        sdkInt >= 33 && !notificationsGranted -> WakePermissionStep.Notifications
        else -> WakePermissionStep.Start
    }
}

/**
 * Enable/start handshake: the toggle is trusted only after the service reports
 * a live microphone. Pure Kotlin so the timing rules are unit-testable.
 */
class WakeStartGate(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    data class Result(val listening: Boolean, val reason: String?)

    private var pending = 0L
    private var deadlineMs = 0L

    /** Begin one enable attempt; returns the id the caller must pass back. */
    fun begin(nowMs: Long): Long {
        pending++
        deadlineMs = nowMs + timeoutMs
        return pending
    }

    /** Accept a service report only for the current attempt and before timeout. */
    fun acknowledge(
        requestId: Long,
        listening: Boolean,
        reason: String?,
        nowMs: Long,
    ): Result? {
        if (requestId <= 0L || requestId != pending) return null
        pending = 0L
        if (nowMs > deadlineMs) return null
        return Result(listening, if (listening) null else reason ?: "wake service did not start")
    }

    /** No confirmation in time: the caller must revert the toggle and show why. */
    fun timeout(requestId: Long, nowMs: Long, detail: String? = null): Result? {
        if (requestId <= 0L || requestId != pending || nowMs < deadlineMs) return null
        pending = 0L
        return Result(false, detail?.takeIf { it.isNotBlank() } ?: "wake service did not respond")
    }

    fun cancel() {
        pending = 0L
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
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
