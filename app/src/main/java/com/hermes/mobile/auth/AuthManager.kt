package com.hermes.mobile.auth

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages authentication state: JWT access tokens, refresh tokens, login/register/refresh.
 *
 * Uses plain SharedPreferences (MODE_PRIVATE) for token storage.
 * Provides reactive [isLoggedIn] state for the UI layer.
 */
@Singleton
class AuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "hermes_auth"
        private const val KEY_JWT = "jwt_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Observable state ──
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()

    private val _userId = MutableStateFlow("")
    val userId: StateFlow<String> = _userId.asStateFlow()

    init {
        // Read stored tokens AFTER lazy fields are initialized
        _isLoggedIn.value = hasStoredTokens()
        _email.value = prefs.getString(KEY_EMAIL, "") ?: ""
        _userId.value = prefs.getString(KEY_USER_ID, "") ?: ""
    }

    // Guard against concurrent refresh
    private val refreshMutex = Mutex()

    // ── Token access (non-suspending, for OkHttp interceptor) ──
    fun getToken(): String? = prefs.getString(KEY_JWT, null)?.takeIf { it.isNotBlank() }

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)?.takeIf { it.isNotBlank() }

    fun getEmail(): String = prefs.getString(KEY_EMAIL, "") ?: ""

    fun getUserId(): String = prefs.getString(KEY_USER_ID, "") ?: ""

    // ── Auth API calls ──

    suspend fun register(serverUrl: String, email: String, password: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/auth/register")
                val payload = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }
                val response = httpPost(url.toString(), payload.toString())
                if (response.first == 200) {
                    val json = JSONObject(response.second)
                    storeTokens(json)
                    Result.success(Unit)
                } else {
                    val detail = try {
                        JSONObject(response.second).optString("detail", "Registration failed")
                    } catch (_: Exception) { "Registration failed" }
                    Result.failure(IOException(detail))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun login(serverUrl: String, email: String, password: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/auth/login")
                val payload = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }
                val response = httpPost(url.toString(), payload.toString())
                if (response.first == 200) {
                    val json = JSONObject(response.second)
                    storeTokens(json)
                    Result.success(Unit)
                } else {
                    val detail = try {
                        JSONObject(response.second).optString("detail", "Invalid credentials")
                    } catch (_: Exception) { "Invalid credentials" }
                    Result.failure(IOException(detail))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun refreshToken(serverUrl: String): Boolean {
        val refreshTok = getRefreshToken() ?: return false
        return refreshMutex.withLock {
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = URL("$serverUrl/auth/refresh")
                    val payload = JSONObject().apply {
                        put("refresh_token", refreshTok)
                    }
                    httpPost(url.toString(), payload.toString())
                }
                if (result.first == 200) {
                    val json = JSONObject(result.second)
                    storeTokens(json)
                    true
                } else {
                    clearTokens()
                    false
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    fun logout() {
        clearTokens()
    }

    // ── Internal helpers ──

    private fun storeTokens(json: JSONObject) {
        prefs.edit()
            .putString(KEY_JWT, json.optString("token", ""))
            .putString(KEY_REFRESH, json.optString("refresh_token", ""))
            .putString(KEY_USER_ID, json.optString("user_id", ""))
            .putString(KEY_EMAIL, json.optString("email", ""))
            .apply()
        _isLoggedIn.value = true
        _email.value = json.optString("email", "")
        _userId.value = json.optString("user_id", "")
    }

    private fun clearTokens() {
        prefs.edit()
            .remove(KEY_JWT)
            .remove(KEY_REFRESH)
            .remove(KEY_USER_ID)
            .remove(KEY_EMAIL)
            .apply()
        _isLoggedIn.value = false
        _email.value = ""
        _userId.value = ""
    }

    private fun hasStoredTokens(): Boolean {
        val jwt = prefs.getString(KEY_JWT, null)
        return !jwt.isNullOrBlank()
    }

    private fun httpPost(urlString: String, jsonBody: String): Pair<Int, String> {
        val url = URL(urlString)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        try {
            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "{}"
            }
            return Pair(code, body)
        } finally {
            conn.disconnect()
        }
    }
}
