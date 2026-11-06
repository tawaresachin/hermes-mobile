package com.hermes.mobile.ui.screens.terminal

import android.content.Context
import android.content.SharedPreferences
import com.hermes.mobile.security.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Terminal connection profiles + crash-restore tail.
 *
 * Secrets layout (mirrors the app's existing credential pattern):
 *  - host / port / user / profile names are NOT secrets: plain app-private
 *    prefs ("hermes_terminal"), same treatment as known_hosts fingerprints
 *    (public identity material, OpenSSH known_hosts precedent)
 *  - passwords ARE secrets: SecurePrefs (AES256-GCM at rest; MIUI plain
 *    fallback is already DiagLog-flagged by SecurePrefs)
 *  - the crash-restore tail is output text of whatever the user saw: plain
 *    prefs, capped, cleared on disconnect
 */
data class TerminalProfile(
    val name: String,
    val host: String,
    val port: Int,
    val user: String
) {
    fun credentialKey(): String = "pw|$host|$port|$user"

    companion object {
        fun nameFor(host: String, port: Int, user: String): String =
            "$user@$host:$port"
    }
}

object TerminalProfileStore {
    private const val PREFS = "hermes_terminal"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_LAST = "last_used"
    private const val KEY_RESTORE_HOST = "restore_host"
    private const val KEY_RESTORE_PORT = "restore_port"
    private const val KEY_RESTORE_USER = "restore_user"
    private const val KEY_RESTORE_TAIL = "restore_tail"
    private const val RESTORE_TAIL_CAP = 6_000

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── profiles ──

    fun profiles(context: Context): List<TerminalProfile> {
        val raw = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                TerminalProfile(
                    name = o.optString("n"),
                    host = o.getString("h"),
                    port = o.getInt("p"),
                    user = o.getString("u")
                )
            }
        } catch (_: Exception) {
            // Corrupt JSON: drop the list rather than crash the screen.
            emptyList()
        }
    }

    fun saveProfile(context: Context, profile: TerminalProfile, password: String?) {
        val list = profiles(context).filterNot {
            it.host == profile.host && it.port == profile.port && it.user == profile.user
        }
        val arr = JSONArray()
        for (p in list + profile) {
            val o = JSONObject()
            o.put("n", p.name)
            o.put("h", p.host)
            o.put("p", p.port)
            o.put("u", p.user)
            arr.put(o)
        }
        prefs(context).edit().putString(KEY_PROFILES, arr.toString()).apply()
        prefs(context).edit().putString(KEY_LAST, profile.credentialKey()).apply()
        if (password != null) storePassword(context, profile, password)
    }

    fun deleteProfile(context: Context, profile: TerminalProfile) {
        val list = profiles(context).filterNot {
            it.host == profile.host && it.port == profile.port && it.user == profile.user
        }
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("n", p.name)
            o.put("h", p.host)
            o.put("p", p.port)
            o.put("u", p.user)
            arr.put(o)
        }
        prefs(context).edit().putString(KEY_PROFILES, arr.toString()).apply()
        // Secret material for the deleted profile goes with it.
        SecurePrefs.get(context, PREFS).edit()
            .remove(profile.credentialKey()).apply()
    }

    fun lastUsedKey(context: Context): String? =
        prefs(context).getString(KEY_LAST, null)

    fun lastUsed(context: Context): TerminalProfile? =
        profiles(context).firstOrNull { it.credentialKey() == lastUsedKey(context) }

    // ── passwords (encrypted) ──

    fun storePassword(context: Context, profile: TerminalProfile, password: String) {
        SecurePrefs.get(context, PREFS).edit()
            .putString(profile.credentialKey(), password)
            .apply()
    }

    fun storedPassword(context: Context, profile: TerminalProfile): String? =
        try {
            SecurePrefs.get(context, PREFS).getString(profile.credentialKey(), null)
        } catch (_: Exception) {
            null
        }

    // ── crash / disconnect restore tail ──

    /** Persist the visible context so a relaunch isn't a blank page. */
    fun saveRestore(context: Context, host: String, port: Int, user: String, outputTail: String) {
        val tail = outputTail.takeLast(RESTORE_TAIL_CAP)
        prefs(context).edit()
            .putString(KEY_RESTORE_HOST, host)
            .putInt(KEY_RESTORE_PORT, port)
            .putString(KEY_RESTORE_USER, user)
            .putString(KEY_RESTORE_TAIL, tail)
            .apply()
    }

    fun clearRestore(context: Context) {
        prefs(context).edit()
            .remove(KEY_RESTORE_HOST)
            .remove(KEY_RESTORE_PORT)
            .remove(KEY_RESTORE_USER)
            .remove(KEY_RESTORE_TAIL)
            .apply()
    }

    data class RestoreRecord(
        val host: String,
        val port: Int,
        val user: String,
        val tail: String
    )

    fun restoreRecord(context: Context): RestoreRecord? {
        val p = prefs(context)
        val host = p.getString(KEY_RESTORE_HOST, null) ?: return null
        val user = p.getString(KEY_RESTORE_USER, null) ?: ""
        val port = p.getInt(KEY_RESTORE_PORT, 22)
        val tail = p.getString(KEY_RESTORE_TAIL, null) ?: ""
        return RestoreRecord(host, port, user, tail)
    }
}
