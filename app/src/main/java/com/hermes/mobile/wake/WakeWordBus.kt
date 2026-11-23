package com.hermes.mobile.wake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun publishEnabled(value: Boolean) { _enabled.value = value }

    /** Called by the detector and by DEBUG's simulate-wake action. */
    fun emitWake() { _wakes.value += 1 }

    fun consumeWake() { _wakes.value = 0L }

    fun publishWakeIntent() { _wakeIntents.value += 1 }

    fun emitChatWake() { _chatWakes.value += 1 }

    fun consumeChatWake() { _chatWakes.value = 0L }
}
