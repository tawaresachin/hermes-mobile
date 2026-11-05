package com.hermes.mobile.data.local

import android.content.Context

/**
 * UI preferences that are per-device, not server-backed (mirrors the
 * DraftStore pattern). Home panel state survives restarts: open on first
 * launch, then remembered whichever way the user last left it.
 */
object HomeUiStore {
    private const val PREFS = "hermes_home_ui"
    private const val KEY_PANEL_OPEN = "panel_open"

    fun panelOpen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PANEL_OPEN, true)

    fun setPanelOpen(context: Context, open: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PANEL_OPEN, open)
            .apply()
    }
}
