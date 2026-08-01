package com.hermes.mobile.network

import android.content.Context
import android.content.SharedPreferences
import com.hermes.mobile.auth.AuthManager
import com.hermes.mobile.data.model.ServerConfig
import com.hermes.mobile.data.model.ModelInfo
import com.hermes.mobile.data.model.ModelListResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
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
    @ApplicationContext private val context: Context,
    private val authManager: AuthManager,
    private val authInterceptor: AuthInterceptor
) {

    companion object {
        private const val PREFS_NAME = "hermes_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SETUP_TOKEN = "setup_token"
        private const val KEY_DARK_THEME = "dark_theme"
    }

    // ── Theme persistence (unchanged) ──
    fun saveDarkTheme(isDark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, isDark).apply()
    }

    fun isDarkTheme(): Boolean {
        if (!prefs.contains(KEY_DARK_THEME)) return false
        return prefs.getBoolean(KEY_DARK_THEME, false)
    }

    fun hasDarkThemePreference(): Boolean = prefs.contains(KEY_DARK_THEME)

    fun prefs(): SharedPreferences = prefs

    // ── HTTP Client with AuthInterceptor ──

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .build()
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Server URL persistence ──

    private var config: ServerConfig? = null

    fun updateConfig(cfg: ServerConfig) {
        config = cfg
        prefs.edit()
            .putString(KEY_BASE_URL, cfg.baseUrl)
            .putString(KEY_API_KEY, cfg.apiKey.orEmpty())
            .putString(KEY_SETUP_TOKEN, cfg.setupToken.orEmpty())
            .apply()
    }

    fun getConfig(): ServerConfig? {
        if (config != null) return config
        val url = prefs.getString(KEY_BASE_URL, null) ?: return null
        val restored = ServerConfig(
            baseUrl = url,
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            setupToken = prefs.getString(KEY_SETUP_TOKEN, "") ?: "",
        )
        config = restored
        return restored
    }

    fun getBaseUrl(): String = config?.baseUrl ?: "http://localhost:8080"

    // ── Token refresh guard (one at a time) ──
    private val refreshLock = Mutex()

    /**
     * Execute a block and retry once on 401 after refreshing the token.
     */
    suspend fun <T> withAuthRetry(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: IOException) {
            if (e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true) {
                val baseUrl = getBaseUrl()
                refreshLock.withLock {
                    val refreshed = authManager.refreshToken(baseUrl)
                    if (refreshed) {
                        return@withLock block()
                    }
                }
            }
            throw e
        }
    }

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
            } catch (_: Exception) {
                false
            }
        }
    }

    // ─── Streaming Chat via SSE ───

    suspend fun streamChat(
        query: String,
        sessionId: String,
        onChunk: (String) -> Unit,
        onToolCall: (String, String, String) -> Unit = { _, _, _ -> },
        onToolResult: (String, String) -> Unit = { _, _ -> },
        attachmentUrl: String = "",
        attachType: String = "",
    ): Unit = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val baseUrl = config?.baseUrl ?: "http://localhost:8080"
            val payload = JSONObject().apply {
                put("query", query)
                put("session_id", sessionId)
                put("stream", true)
                if (attachmentUrl.isNotBlank()) put("attachment_url", attachmentUrl)
                if (attachType.isNotBlank()) put("attachment_type", attachType)
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
                    if (completed.get()) return

                    if (data == "[DONE]") {
                        if (completed.compareAndSet(false, true)) {
                            continuation.resume(Unit)
                        }
                        return
                    }
                    try {
                        val json = JSONObject(data)
                        val eventType = json.optString("type", "text")
                        when (eventType) {
                            "text" -> {
                                val content = json.optString("content", "")
                                if (content.isNotEmpty()) onChunk(content)
                            }
                            "tool_call" -> {
                                val tcId = json.optString("id", "")
                                val name = json.optString("name", "")
                                val args = json.optString("arguments", "")
                                if (tcId.isNotEmpty()) onToolCall(tcId, name, args)
                            }
                            "tool_result" -> {
                                val tcId = json.optString("id", "")
                                val output = json.optString("output", "")
                                val error = json.optString("error", "")
                                onToolResult(tcId, output.ifEmpty { error })
                            }
                            "error" -> {
                                val msg = json.optString("content", "Unknown error")
                                onChunk("⚠️ $msg")
                            }
                        }
                    } catch (_: Exception) {
                        // Fallback: treat as plain text
                        onChunk(data)
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    if (completed.compareAndSet(false, true)) {
                        // If 401, try to refresh token and retry
                        if (response?.code == 401) {
                            val baseUrl = config?.baseUrl ?: "http://localhost:8080"
                            val refreshed = kotlinx.coroutines.runBlocking {
                                authManager.refreshToken(baseUrl)
                            }
                            if (refreshed) {
                                // Retry with new token by creating a new request
                                continuation.resumeWithException(
                                    IOException("401 - Retrying with refreshed token")
                                )
                                return
                            }
                        }
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

    // ─── List Models ───

    suspend fun listModels(sessionId: String = ""): ModelListResponse? {
        val baseUrl = config?.baseUrl ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/api/models" + if (sessionId.isNotBlank()) "?session_id=$sessionId" else ""
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val modelsArr = json.optJSONArray("models") ?: return@withContext null
                    val models = (0 until modelsArr.length()).map { i ->
                        val m = modelsArr.getJSONObject(i)
                        ModelInfo(
                            id = m.optString("id", ""),
                            name = m.optString("name", ""),
                            isVision = m.optBoolean("isVision", false),
                            isFree = m.optBoolean("isFree", false),
                            provider = m.optString("provider", ""),
                            baseUrl = m.optString("baseUrl", "")
                        )
                    }
                    ModelListResponse(
                        models = models,
                        current = json.optString("current", ""),
                        default = json.optString("default", ""),
                        provider = json.optString("provider", "")
                    )
                } else null
            } catch (_: Exception) { null }
        }
    }

    // ─── Switch Model (via chat command) ───

    suspend fun switchModel(sessionId: String, modelName: String, global: Boolean = false): Boolean {
        val query = "/model $modelName${if (global) " --global" else ""}"
        return try {
            val response = sendChat(query, sessionId)
            response.contains("✅") || response.contains("switched")
        } catch (_: Exception) { false }
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

    // ─── Text-to-Speech ───
    // Default: Indian English female voice (edge-tts). Pass a different
    // voice (e.g. "en-IN-PrabhatNeural" male) for variety.
    suspend fun textToSpeech(text: String, voice: String = "en-IN-NeerjaNeural"): ByteArray? {
        val baseUrl = config?.baseUrl ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("text", text)
                    put("voice", voice)
                }
                val request = Request.Builder()
                    .url("$baseUrl/api/tts")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.bytes()
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    // ─── File Upload ───

    suspend fun uploadFile(file: java.io.File, fileName: String, mimeType: String): String? {
        val baseUrl = config?.baseUrl ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val token = authManager.getToken()
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, file.asRequestBody(mimeType.toMediaTypeOrNull()))
                    .build()
                val request = Request.Builder()
                    .url("$baseUrl/api/upload")
                    .post(body)
                    .apply {
                        if (token != null) addHeader("Authorization", "Bearer $token")
                    }
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    json.optString("url")?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

internal fun org.json.JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    keys().forEach { key ->
        val value = get(key)
        map[key] = value
    }
    return map
}
