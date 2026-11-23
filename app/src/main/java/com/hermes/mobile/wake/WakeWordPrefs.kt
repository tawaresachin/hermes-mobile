package com.hermes.mobile.wake

import android.content.Context

/** Non-secret wake-word settings. Secrets stay in SecurePrefs. */
object WakeWordPrefs {
    private const val PREFS_NAME = "hermes_wake_word"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SENSITIVITY = "sensitivity"

    const val MIN_SENSITIVITY = 0.5f
    const val MAX_SENSITIVITY = 0.9f
    const val DEFAULT_SENSITIVITY = WakeWordStateMachine.DEFAULT_SENSITIVITY

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun sensitivity(context: Context): Float =
        prefs(context).getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
            .coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)

    fun setSensitivity(context: Context, value: Float) =
        prefs(context).edit().putFloat(KEY_SENSITIVITY, value.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)).apply()
}
