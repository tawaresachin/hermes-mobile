package com.hermes.mobile.wake

/**
 * Where a wake event may be delivered. The UI reports this from its current
 * destination so a wake is never consumed by a screen that cannot act on it.
 */
enum class WakeSurface { Chat, Elsewhere }

/** Pure decision for one wake event, independent of Android UI or context. */
enum class WakeDisposition {
    /** Wake word disabled — drop the event entirely. */
    Ignore,
    /** Chat is already visible; it consumes the pending wake itself. */
    HandleInChat,
    /** App is foreground but Chat is not visible — navigate to Chat. */
    OpenChat,
    /** App is in the background — raise a full-screen alert and wait for a tap. */
    ShowAlert,
}

/**
 * App-scoped routing for a detected wake. Holds only the currently visible
 * surface; the caller supplies enabled/foreground state so the decision stays
 * a pure function and can be unit tested without Android.
 */
class WakeCoordinator {
    private var surface: WakeSurface = WakeSurface.Elsewhere

    fun onSurfaceChanged(next: WakeSurface) {
        surface = next
    }

    fun decide(enabled: Boolean, foreground: Boolean): WakeDisposition = when {
        !enabled -> WakeDisposition.Ignore
        surface == WakeSurface.Chat -> WakeDisposition.HandleInChat
        foreground -> WakeDisposition.OpenChat
        else -> WakeDisposition.ShowAlert
    }
}
