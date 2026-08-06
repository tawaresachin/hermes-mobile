package com.hermes.mobile.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted SharedPreferences for SECRETS ONLY (auth tokens, device
 * credentials). Values are AES256-GCM encrypted at rest.
 *
 * MIUI/Xiaomi safety: EncryptedSharedPreferences can throw during startup
 * on some ROMs (key store failures). We catch Throwable and fall back to
 * plain MODE_PRIVATE so the app NEVER crashes — a degraded-but-working
 * store beats a crash. New installs get full encryption; devices where
 * encryption is unavailable get the plain fallback.
 */
object SecurePrefs {

    /** Prefs name used for the auto-paired device account credentials. */
    const val DEVICE_PREFS = "hermes_device"

    fun get(context: Context, name: String): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // MIUI/Xiaomi key-store failure or a pre-existing plain file —
            // never let crypto break the app. Flag the downgrade so a
            // maintainer sees it in the diag log instead of it being silent.
            try {
                com.hermes.mobile.DiagLog.w(
                    "SEC",
                    "EncryptedSharedPreferences unavailable for '$name' — plain fallback: ${t.javaClass.simpleName}: ${t.message}"
                )
            } catch (_: Exception) {}
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
    }
}
