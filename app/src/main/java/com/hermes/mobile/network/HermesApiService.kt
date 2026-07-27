package com.hermes.mobile.network

import android.content.Context
import android.content.SharedPreferences
import com.hermes.mobile.data.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class HermesApiService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val PREFS_NAME = "hermes_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_DARK_THEME = "dark_theme"
    }

    fun saveDarkTheme(isDark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, isDark).apply()
    }

    fun isDarkTheme(): Boolean {
        if (!prefs.contains(KEY_DARK_THEME)) return false // default: use system
        return prefs.getBoolean(KEY_DARK_THEME, false)
    }

    /** Returns true if the user has explicitly set a dark theme preference. */
    fun hasDarkThemePreference(): Boolean = prefs.contains(KEY_DARK_THEME)

    /** Expose the SharedPreferences instance for reactive observation. */
    fun prefs(): SharedPreferences = prefs

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val authHeader = config?.let { cfg ->
                    if (cfg.apiKey.isNotBlank()) "Bearer ${cfg.apiKey}" else null
                }
                val newRequest = if (authHeader != null) {
                    request.newBuilder()
                        .header("Authorization", authHeader)
                        .header("Content-Type", "application/json")
                        .build()
                } else {
                    request.newBuilder()
                        .header("Content-Type", "application/json")
                        .build()
                }
                chain.proceed(newRequest)
            }
            .build()
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var config: ServerConfig? = null

    fun updateConfig(cfg: ServerConfig) {
        config = cfg
        // Persist to disk so it survives crash / process death
        prefs.edit()
            .putString(KEY_BASE_URL, cfg.baseUrl)
            .putString(KEY_API_KEY, cfg.apiKey)
            .apply()
    }

    fun getConfig(): ServerConfig? {
        if (config != null) return config
        // Restore from disk if we have it
        val url = prefs.getString(KEY_BASE_URL, null) ?: return null
        val key = prefs.getString(KEY_API_KEY, "") ?: ""
        val restored = ServerConfig(baseUrl = url, apiKey = key)
        config = restored
        return restored
    }

    fun getBaseUrl(): String = config?.baseUrl ?: "http://localhost:8080"

    // ─── Health Check ───

    suspend fun healthCheck(cfg: ServerConfig? = null): Boolean {
        val baseUrl = cfg?.baseUrl?.takeIf { it.isNotBlank() } ?: config?.baseUrl ?: return false
        cfg?.takeIf { it.baseUrl.isNotBlank() }?.let { updateConfig(it) }
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/health")
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }
    }

    // ─── Streaming Chat via SSE (suspend version) ───

    suspend fun streamChat(
        query: String,
        sessionId: String,
        onChunk: (String) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val baseUrl = config?.baseUrl ?: "http://localhost:8080"
            val payload = JSONObject().apply {
                put("query", query)
                put("session_id", sessionId)
                put("stream", true)
            }

            val request = Request.Builder()
                .url("$baseUrl/api/chat/stream")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Accept", "text/event-stream")
                .build()

            val factory = EventSources.createFactory(client)
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            val source = factory.newEventSource(request, object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (completed.get()) return  // already done

                    if (data == "[DONE]") {
                        if (completed.compareAndSet(false, true)) {
                            continuation.resume(Unit)
                        }
                        return
                    }
                    try {
                        val json = JSONObject(data)
                        if (json.has("content")) {
                            onChunk(json.getString("content"))
                        }
                        if (json.optBoolean("done", false)) {
                            if (completed.compareAndSet(false, true)) {
                                continuation.resume(Unit)
                            }
                        }
                    } catch (e: Exception) {
                        onChunk(data)
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    if (completed.compareAndSet(false, true)) {
                        val ex = t ?: IOException("Connection failed: ${response?.code ?: 0}")
                        continuation.resumeWithException(ex)
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    if (completed.compareAndSet(false, true)) {
                        continuation.resume(Unit)
                    }
                }
            })

            continuation.invokeOnCancellation {
                source.cancel()
            }
        }
    }

    // ─── List Sessions ───

    suspend fun listSessions(): List<Map<String, Any>> {
        val baseUrl = config?.baseUrl ?: "http://localhost:8080"
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/api/sessions")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "[]"
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).toMap()
            }
        }
    }

    // ─── Simple Chat (non-streaming) ───

    suspend fun sendChat(
        query: String,
        sessionId: String? = null
    ): String {
        val baseUrl = config?.baseUrl ?: "http://localhost:8080"
        return withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("query", query)
                put("stream", false)
                sessionId?.let { put("session_id", it) }
            }
            val request = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            val response = client.newCall(request).execute()
            response.body?.string() ?: "{}"
        }
    }

    // ─── Delete Session (server-side) ───

    /**
     * Delete a session from the backend server.
     * This is best-effort — a failure does not throw.
     */
    suspend fun deleteSession(sessionId: String): Boolean {
        val baseUrl = config?.baseUrl ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/api/sessions/$sessionId")
                    .delete()
                    .build()
                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (_: Exception) {
                false
            }
        }
    }
}

private fun org.json.JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    keys().forEach { key ->
        val value = get(key)
        map[key] = value
    }
    return map
}
