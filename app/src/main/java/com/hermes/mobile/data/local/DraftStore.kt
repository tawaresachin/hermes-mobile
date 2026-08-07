package com.hermes.mobile.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-session input drafts (Telegram-style: your half-typed message
 * survives leaving the chat). Plain prefs — drafts are not secrets.
 */
object DraftStore {
    private const val PREFS = "hermes_drafts"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun get(sessionId: String): String =
        prefs?.getString("draft_$sessionId", "") ?: ""

    fun set(sessionId: String, text: String) {
        val p = prefs ?: return
        if (text.isBlank()) {
            p.edit().remove("draft_$sessionId").apply()
        } else {
            p.edit().putString("draft_$sessionId", text).apply()
        }
    }

    fun clear(sessionId: String) {
        prefs?.edit()?.remove("draft_$sessionId")?.apply()
    }
}
