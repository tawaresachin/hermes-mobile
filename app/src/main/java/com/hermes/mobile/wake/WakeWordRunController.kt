package com.hermes.mobile.wake

import java.util.EnumSet

/** Why wake capture is temporarily suspended. */
enum class WakePauseReason {
    AssistantTurn, VoiceScreen, Dictation, AppBackground, ScreenOff, AudioFocus, RouteChange,
    MicrophoneUnavailable,
}

enum class WakeRunState { Idle, Listening, Paused, WaitingForTurn }

/**
 * Pure lifecycle for wake capture. The service owns the mic; this class only
 * decides when it may be held. Multiple pause reasons cannot race each other.
 */
class WakeWordRunController {
    private val pauses = EnumSet.noneOf(WakePauseReason::class.java)
    private var state = WakeRunState.Idle

    @Synchronized fun currentState(): WakeRunState = state

    @Synchronized fun start(): WakeRunState {
        if (state == WakeRunState.Idle) {
            pauses.clear()
            state = WakeRunState.Listening
        }
        return state
    }

    @Synchronized fun pause(reason: WakePauseReason): WakeRunState {
        if (state == WakeRunState.Idle) return state
        pauses.add(reason)
        if (state == WakeRunState.Listening) state = WakeRunState.Paused
        return state
    }

    @Synchronized fun resume(reason: WakePauseReason): WakeRunState {
        pauses.remove(reason)
        if (state == WakeRunState.Paused && pauses.isEmpty()) state = WakeRunState.Listening
        return state
    }

    /** Returns true only for the first detection in a turn. */
    @Synchronized fun onDetected(): Boolean {
        if (state != WakeRunState.Listening) return false
        pauses.clear()
        state = WakeRunState.WaitingForTurn
        return true
    }

    /** Called when dictation + the assistant turn are finished. */
    @Synchronized fun turnFinished(): WakeRunState {
        if (state == WakeRunState.Idle) return state
        // The assistant turn's own pause is over; other owners (background,
        // screen off, Voice screen) must keep the mic closed.
        pauses.remove(WakePauseReason.AssistantTurn)
        state = if (pauses.isEmpty()) WakeRunState.Listening else WakeRunState.Paused
        return state
    }

    @Synchronized fun stop(): WakeRunState {
        pauses.clear()
        state = WakeRunState.Idle
        return state
    }
}
