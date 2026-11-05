package com.hermes.mobile.data.local

import android.content.Context

/**
 * UI preferences that are per-device, not server-backed (mirrors the
 * DraftStore pattern). Home drawer state survives restarts: CLOSED by
 * default (the scrim must never cover the sessions list on first
 * launch), then remembered whichever way the user last left it.
 */
object HomeUiStore {
    private const val PREFS = "hermes_home_ui"
    // v2 key: ignores any legacy `panel_open` value (0.1.7's open-by-
    // default could persist a `true` that made 0.1.8's full-screen scrim
    // launch stuck-open, covering the header and sessions list).
    // Bump on any release that can leave the drawer in a blocking state.
    private const val KEY_PANEL_OPEN = "drawer_open_v2"

    fun panelOpen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PANEL_OPEN, false)

    fun setPanelOpen(context: Context, open: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PANEL_OPEN, open)
            .apply()
    }
}
