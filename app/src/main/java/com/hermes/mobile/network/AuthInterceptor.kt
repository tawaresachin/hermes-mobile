package com.hermes.mobile.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that attaches the API key to every request.
 * Hermes Agent uses simple Bearer token auth (API_SERVER_KEY).
 */
@Singleton
class AuthInterceptor @Inject constructor(
    @ApplicationContext private val context: Context
) : Interceptor {

    companion object {
        private const val PREFS_NAME = "hermes_config"
        private const val SECURE_PREFS_NAME = "hermes_config_secure"
        private const val KEY_API_KEY = "api_key"
    }

    /** Cached encrypted store. Building EncryptedSharedPreferences means a
     * KeyStore master-key derivation + full prefs-file decrypt EVERY time —
     * this runs on every HTTP request (including every Coil image load).
     * SharedPreferences instances are internally thread-safe and live-view
     * updates, so one cached instance stays correct after pairing/logout.
     * The API key is cached in memory and refreshed on logout/pairing. */
    private val securePrefs by lazy {
        try {
            com.hermes.mobile.security.SecurePrefs.get(context, SECURE_PREFS_NAME)
        } catch (_: Exception) { null }
    }

    /** In-memory key cache. Refreshed when updateApiKey() is called
     * (pairing/logout), not on every request. */
    private var cachedApiKey: String? = null

    /** Force-refresh the in-memory key cache. Called on pairing
     * and logout so the next request uses fresh credentials. */
    fun refreshKeyCache() {
        cachedApiKey = null
    }

    private fun readApiKey(): String {
        cachedApiKey?.let { return it }
        val prefs = securePrefs?.getString(KEY_API_KEY, "").orEmpty()
        if (prefs.isNotBlank()) { cachedApiKey = prefs; return prefs }
        val plain = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacy = plain.getString(KEY_API_KEY, "") ?: ""
        if (legacy.isNotBlank()) cachedApiKey = legacy
        return legacy
    }

    /** Host of the configured gateway. The Bearer key is only ever sent
     * there: message content can contain absolute URLs, and an unconditional
     * header would hand the key to whatever host that URL names. */
    private fun configuredHost(): String? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("base_url", "") ?: return null
        if (raw.isBlank()) return null
        return try {
            java.net.URI(raw).host?.lowercase()
        } catch (_: Exception) { null }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        // Pairing validates the host, but a config can reach prefs by other
        // paths. Enforce the same private-host rule at the HTTP choke point so
        // a bearer token or SOUL body never rides cleartext to a public host.
        if (originalRequest.url.scheme.equals("http", ignoreCase = true) &&
            !ServerHostGuard.isTrustedHttpHost(originalRequest.url.host)
        ) {
            throw IOException("Cleartext gateway traffic is limited to private hosts")
        }
        val apiKey = readApiKey()
        // Only send the key when we KNOW the configured host. A null base
        // host used to mean "attach to anything": during pairing (or with a
        // partially migrated config) an image/attachment URL could receive
        // the bearer token before base_url was persisted.
        val baseHost = configuredHost()
        val sameServer = baseHost != null &&
            originalRequest.url.host.equals(baseHost, ignoreCase = true)

        return if (apiKey.isNotBlank() && sameServer) {
            val authenticatedRequest = originalRequest.newBuilder()
                .header("Authorization", "Bearer $apiKey")
                .build()
            chain.proceed(authenticatedRequest)
        } else {
            chain.proceed(originalRequest)
        }
    }
}
