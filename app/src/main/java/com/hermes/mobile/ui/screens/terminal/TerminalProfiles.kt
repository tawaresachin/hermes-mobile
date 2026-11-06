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
    val user: String,
    /** "pw" (default) or "key" — which auth method the profile uses.
     *  Optional in stored JSON: pre-key-auth profiles deserialize as "pw". */
    val authMode: String = "pw"
) {
    fun credentialKey(): String = "pw|$host|$port|$user"

    companion object {
        const val AUTH_PASSWORD = "pw"
        const val AUTH_KEY = "key"

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
                    user = o.getString("u"),
                    authMode = o.optString("a", TerminalProfile.AUTH_PASSWORD)
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
        writeProfiles(context, list + profile)
        prefs(context).edit().putString(KEY_LAST, profile.credentialKey()).apply()
        if (password != null) storePassword(context, profile, password)
    }

    private fun writeProfiles(context: Context, list: List<TerminalProfile>) {
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("n", p.name)
            o.put("h", p.host)
            o.put("p", p.port)
            o.put("u", p.user)
            if (p.authMode != TerminalProfile.AUTH_PASSWORD) o.put("a", p.authMode)
            arr.put(o)
        }
        prefs(context).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun deleteProfile(context: Context, profile: TerminalProfile) {
        val list = profiles(context).filterNot {
            it.host == profile.host && it.port == profile.port && it.user == profile.user
        }
        writeProfiles(context, list)
        // Secret material for the deleted profile goes with it.
        val sp = SecurePrefs.get(context, PREFS)
        sp.edit()
            .remove(profile.credentialKey())
            .remove("keybytes|" + profile.credentialKey())
            .remove("keypass|" + profile.credentialKey())
            .apply()
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

    /** Drop an orphaned encrypted password (profile switched to key auth). */
    fun clearPassword(context: Context, profile: TerminalProfile) {
        SecurePrefs.get(context, PREFS).edit()
            .remove(profile.credentialKey())
            .apply()
    }

    // ── SSH key auth (encrypted) ──
    //
    // The private key material itself is a secret: stored via
    // SecurePrefs (AES-256-GCM), NOT in the plain profile prefs.
    // The passphrase protecting the key file goes with it.
    // Key cap: 1 MiB — OpenSSH/RSA/ECDSA/ed25519 private keys are
    // at most a few KB; a bigger "key" is not a key.
    data class KeyAuth(val keyBytes: ByteArray, val passphrase: String?) {
        override fun equals(other: Any?) = other is KeyAuth &&
            keyBytes.contentEquals(other.keyBytes) && passphrase == other.passphrase
        override fun hashCode(): Int = java.util.Arrays.hashCode(keyBytes)
    }

    /** Cap on persisted key-file bytes (see [saveKeyAuth]). */
    const val KEY_BYTES_CAP = 1_000_000

    fun saveKeyAuth(context: Context, profile: TerminalProfile, keyBytes: ByteArray, passphrase: String?) {
        require(keyBytes.size <= KEY_BYTES_CAP) { "key file too large" }
        // Refuse to persist obviously-non-key content (a dropped APK,
        // a photo, ...): every supported key format carries a
        // recognizable header.
        val head = String(keyBytes.copyOfRange(0, keyBytes.size.coerceAtMost(64)), Charsets.US_ASCII)
        require(
            head.startsWith("-----BEGIN") || head.startsWith("PuTTY-Gen-Key")
        ) { "not a recognized private key file" }
        val sp = SecurePrefs.get(context, PREFS)
        sp.edit()
            .putString("keybytes|" + profile.credentialKey(),
                java.util.Base64.getEncoder().encodeToString(keyBytes))
            .apply()
        if (passphrase.isNullOrBlank()) sp.edit().remove("keypass|" + profile.credentialKey()).apply()
        else sp.edit().putString("keypass|" + profile.credentialKey(), passphrase).apply()
    }

    fun storedKeyAuth(context: Context, profile: TerminalProfile): KeyAuth? =
        try {
            val sp = SecurePrefs.get(context, PREFS)
            val b64 = sp.getString("keybytes|" + profile.credentialKey(), null)
                ?: return null
            val bytes = java.util.Base64.getDecoder().decode(b64)
            val pass = sp.getString("keypass|" + profile.credentialKey(), null)
            KeyAuth(bytes, pass)
        } catch (_: Exception) {
            // Corrupt/cleartext-migrated value: treat as absent rather
            // than crash the screen; the user re-picks the key file.
            null
        }

    fun clearKeyAuth(context: Context, profile: TerminalProfile) {
        val sp = SecurePrefs.get(context, PREFS)
        sp.edit()
            .remove("keybytes|" + profile.credentialKey())
            .remove("keypass|" + profile.credentialKey())
            .apply()
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
