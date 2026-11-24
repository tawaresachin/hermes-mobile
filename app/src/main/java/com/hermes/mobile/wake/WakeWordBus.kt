package com.hermes.mobile.wake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A snapshot of what the wake mic is actually doing, surfaced in Settings. */
data class WakeDiagnostics(
    val state: String = "Off",
    val source: String = "—",
    val rms: Int = 0,
    val maxScore: Float = 0f,
    val fires: Int = 0,
    val lastError: String = "—",
    val statusReason: String = "",
    val revision: Long = 0L,
)

/** Service's answer to one enable request. A revision makes it replay-safe. */
data class WakeStartAck(
    val listening: Boolean = false,
    val reason: String = "",
    val revision: Long = 0L,
)

/** Same-process signals between the foreground service and Compose UI.
 *  Every signal is a StateFlow counter so a cold-start event replays to a
 *  collector that subscribes after the event was published. */
object WakeWordBus {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Sequence number so a DEBUG simulate tap is not lost while Chat is off-screen. */
    private val _wakes = MutableStateFlow(0L)
    val wakes: StateFlow<Long> = _wakes.asStateFlow()

    /** A wake-alert notification (or its full-screen intent) was tapped. */
    private val _wakeIntents = MutableStateFlow(0L)
    val wakeIntents: StateFlow<Long> = _wakeIntents.asStateFlow()

    /** One-shot hand-off from the global coordinator to ChatScreen. */
    private val _chatWakes = MutableStateFlow(0L)
    val chatWakes: StateFlow<Long> = _chatWakes.asStateFlow()

    /** Live capture facts for the release-visible status line. */
    private val _diagnostics = MutableStateFlow(WakeDiagnostics())
    val diagnostics: StateFlow<WakeDiagnostics> = _diagnostics.asStateFlow()

    private val _startAck = MutableStateFlow(WakeStartAck())
    val startAck: StateFlow<WakeStartAck> = _startAck.asStateFlow()

    fun publishEnabled(value: Boolean) { _enabled.value = value }

    fun publishDiagnostics(value: WakeDiagnostics) { _diagnostics.value = value }

    fun publishStartListening() {
        _startAck.value = WakeStartAck(true, "", _startAck.value.revision + 1)
    }

    fun publishStartFailed(reason: String) {
        _startAck.value = WakeStartAck(false, reason, _startAck.value.revision + 1)
    }

    /** Called by the detector and by DEBUG's simulate-wake action. */
    fun emitWake() { _wakes.value += 1 }

    fun consumeWake() { _wakes.value = 0L }

    fun publishWakeIntent() { _wakeIntents.value += 1 }

    fun emitChatWake() { _chatWakes.value += 1 }

    fun consumeChatWake() { _chatWakes.value = 0L }
}
