package com.hermes.mobile.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Response
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        // Primary: secure store (HermesApiService.updateConfig writes here).
        // Fallback: plain store (legacy installs).
        var apiKey = ""
        try {
            apiKey = com.hermes.mobile.security.SecurePrefs.get(context, SECURE_PREFS_NAME)
                .getString(KEY_API_KEY, "") ?: ""
        } catch (_: Exception) { }
        if (apiKey.isBlank()) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        }

        return if (apiKey.isNotBlank()) {
            val authenticatedRequest = originalRequest.newBuilder()
                .header("Authorization", "Bearer $apiKey")
                .build()
            chain.proceed(authenticatedRequest)
        } else {
            chain.proceed(originalRequest)
        }
    }
}
