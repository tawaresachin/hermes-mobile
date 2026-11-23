package com.hermes.mobile.wake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Same-process signals between the foreground service and Compose UI. */
object WakeWordBus {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Sequence number so a DEBUG simulate tap is not lost while Chat is off-screen. */
    private val _wakes = MutableStateFlow(0L)
    val wakes: StateFlow<Long> = _wakes.asStateFlow()

    fun publishEnabled(value: Boolean) { _enabled.value = value }

    /** Called by the detector and by DEBUG's simulate-wake action. */
    fun emitWake() { _wakes.value += 1 }

    fun consumeWake() { _wakes.value = 0L }
}
