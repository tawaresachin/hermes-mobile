package com.hermes.mobile.wake

import android.content.Context

/** Non-secret wake-word settings. Secrets stay in SecurePrefs. */
object WakeWordPrefs {
    private const val PREFS_NAME = "hermes_wake_word"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_NEW_SESSION = "new_session_on_wake"
    private const val KEY_BACKGROUND = "background_listening"
    private const val KEY_SPEAK_REPLIES = "speak_replies"

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

    fun startNewSessionOnWake(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NEW_SESSION, true)

    fun setStartNewSessionOnWake(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_NEW_SESSION, value).apply()

    fun backgroundListening(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BACKGROUND, true)

    fun setBackgroundListening(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_BACKGROUND, value).apply()

    /** Speak each assistant reply after a hands-free wake turn (default on). */
    fun speakReplies(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEAK_REPLIES, true)

    fun setSpeakReplies(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SPEAK_REPLIES, value).apply()
}
