package com.hermes.mobile.network

import android.content.Context
import android.content.SharedPreferences
import com.hermes.mobile.auth.AuthManager
import com.hermes.mobile.data.model.ServerConfig
import com.hermes.mobile.data.model.ModelInfo
import com.hermes.mobile.data.model.ModelListResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        // SECURITY: the secure store MUST use a DIFFERENT file name than
        // the plain store. Sharing "hermes_config" made
        // EncryptedSharedPreferences throw ("pre-existing plain file") and
        // silently fall back to plaintext at rest. A distinct name keeps
        // apiKey/setupToken encrypted on fresh installs.
        private const val SECURE_PREFS_NAME = "hermes_config_secure"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SETUP_TOKEN = "setup_token"

        /** A stream that stays silent this long is dead (hung provider,
         *  silently dropped connection) — kill it instead of waiting out
         *  the 300s OkHttp read timeout. */
        private const val STREAM_IDLE_TIMEOUT_MS = 90_000L
        private const val WATCHDOG_POLL_MS = 2_000L
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

    // ── Device account (auto-registered after QR pairing) ──
    // Stored ENCRYPTED (AES256-GCM via SecurePrefs) — these are live
    // credentials, never plaintext on disk.
    private val devicePrefs: SharedPreferences
        get() = com.hermes.mobile.security.SecurePrefs.get(context, com.hermes.mobile.security.SecurePrefs.DEVICE_PREFS)

    fun saveDeviceCredentials(email: String, password: String) {
        devicePrefs.edit()
            .putString("device_email", email)
            .putString("device_password", password)
            .apply()
    }

    fun getDeviceCredentials(): Pair<String, String>? {
        val email = devicePrefs.getString("device_email", null) ?: return null
        val password = devicePrefs.getString("device_password", null) ?: return null
        return email to password
    }

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
    // baseUrl stays in plain prefs (non-secret); apiKey + setupToken go to
    // SecurePrefs (AES256-GCM at rest) — same store device creds use.

    // Written on Main (updateConfig) and read from IO threads (healthCheck
    // / streamChat) — publication must be visible across dispatchers.
    @Volatile
    private var config: ServerConfig? = null

    // Built ONCE (lazy): SecurePrefs.get() creates a MasterKey +
    // EncryptedSharedPreferences (KeyStore init + file decrypt) — doing
    // that on EVERY access was blocking the Main thread on each 5s poll.
    private val secretPrefs: SharedPreferences by lazy {
        com.hermes.mobile.security.SecurePrefs.get(context, SECURE_PREFS_NAME)
    }

    fun updateConfig(cfg: ServerConfig) {
        val prev = config
        config = cfg
        if (prev == cfg) {
            // Unchanged — skip the prefs write. healthCheck polls every 5s
            // and passes a fresh object; writing on every poll is needless
            // disk I/O.
            return
        }
        prefs.edit()
            .putString(KEY_BASE_URL, cfg.baseUrl)
            .apply()
        secretPrefs.edit()
            .putString(KEY_API_KEY, cfg.apiKey.orEmpty())
            .putString(KEY_SETUP_TOKEN, cfg.setupToken.orEmpty())
            .apply()
    }

    fun getConfig(): ServerConfig? {
        if (config != null) return config
        val url = prefs.getString(KEY_BASE_URL, null) ?: return null
        // Legacy rescue: pre-fix installs stored keys in the PLAIN
        // "hermes_config" file (name-collision bug). Migrate them into the
        // secure store once and scrub the plain copy.
        var apiKey = secretPrefs.getString(KEY_API_KEY, "") ?: ""
        var setupToken = secretPrefs.getString(KEY_SETUP_TOKEN, "") ?: ""
        if (apiKey.isEmpty() || setupToken.isEmpty()) {
            val legacyPlain = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val legacyKey = legacyPlain.getString(KEY_API_KEY, "") ?: ""
            val legacyToken = legacyPlain.getString(KEY_SETUP_TOKEN, "") ?: ""
            if (apiKey.isEmpty()) apiKey = legacyKey
            if (setupToken.isEmpty()) setupToken = legacyToken
            if (legacyKey.isNotEmpty() || legacyToken.isNotEmpty()) {
                secretPrefs.edit()
                    .putString(KEY_API_KEY, apiKey)
                    .putString(KEY_SETUP_TOKEN, setupToken)
                    .apply()
                legacyPlain.edit().remove(KEY_API_KEY).remove(KEY_SETUP_TOKEN).apply()
            }
        }
        val restored = ServerConfig(
            baseUrl = url,
            apiKey = apiKey,
            setupToken = setupToken,
        )
        config = restored
        return restored
    }

    fun getBaseUrl(): String = config?.baseUrl ?: "http://localhost:8080"

    // ─── Health Check ───

    suspend fun healthCheck(cfg: ServerConfig? = null): Boolean {
        val baseUrl = cfg?.baseUrl?.takeIf { it.isNotBlank() }
            ?: config?.baseUrl
            ?: return false
        if (cfg?.baseUrl?.isNotBlank() == true && cfg != config) updateConfig(cfg)
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/health")
                    .get()
                    .build()
                // response.use closes the body — a leaked body pins a socket
                // (readTimeout 300s) that can't be pooled; the 5s poll loop
                // would accumulate connections over time.
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
        onModelReverted: (String) -> Unit = {},
        onTurnEnd: () -> Unit = {},
        onOpen: () -> Unit = {},
        attachmentUrl: String = "",
        attachType: String = "",
        multiAgent: Boolean = false,
        replyTo: String? = null,
    ): Unit = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val baseUrl = config?.baseUrl ?: "http://localhost:8080"
            val payload = JSONObject().apply {
                put("query", query)
                put("session_id", sessionId)
                put("stream", true)
                if (multiAgent) put("multi_agent", true)
                if (replyTo.isNullOrBlank().not()) put("reply_to", replyTo)
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
            // Idle watchdog: a silently dead connection (no events) would
            // otherwise hold the typing indicator until the 300s OkHttp
            // read timeout. Track last-event time; kill on silence.
            var lastEventMs = System.currentTimeMillis()
            val source = factory.newEventSource(request, object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    lastEventMs = System.currentTimeMillis()
                    onOpen()
                }
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (completed.get()) return
                    lastEventMs = System.currentTimeMillis()

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
                            // Server auto-reverted this session's model to
                            // the default after a hard provider failure.
                            "model_reverted" -> {
                                onModelReverted(json.optString("content", ""))
                            }
                            // Follow-up turn boundary: previous bubble is
                            // complete, a fresh one starts for the next turn.
                            "turn_end" -> {
                                onTurnEnd()
                            }
                            "error" -> {
                                val msg = json.optString("content", "Unknown error")
                                onChunk("⚠️ $msg")
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
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
                            // Refresh off this OkHttp callback thread — a
                            // runBlocking here would pin a dispatcher thread
                            // per failed stream.
                            CoroutineScope(Dispatchers.IO).launch {
                                val refreshed = authManager.refreshToken(baseUrl)
                                val ex = if (refreshed) {
                                    IOException("401 - Retrying with refreshed token")
                                } else {
                                    IOException("401 - Auth failed after refresh")
                                }
                                continuation.resumeWithException(ex)
                            }
                            return
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

            // Idle watchdog: fires when no event arrived for the idle
            // window (dead connection, hung provider). Cancels the source
            // and surfaces a retryable failure.
            val watchdog = CoroutineScope(Dispatchers.IO).launch {
                while (!completed.get()) {
                    delay(WATCHDOG_POLL_MS)
                    if (!completed.get() &&
                        System.currentTimeMillis() - lastEventMs > STREAM_IDLE_TIMEOUT_MS
                    ) {
                        if (completed.compareAndSet(false, true)) {
                            source.cancel()
                            continuation.resumeWithException(
                                IOException("Stream idle — no data for ${STREAM_IDLE_TIMEOUT_MS / 1000}s")
                            )
                        }
                        return@launch
                    }
                }
            }
            continuation.invokeOnCancellation {
                source.cancel()
                watchdog.cancel()
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
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use null
                        val json = JSONObject(body)
                        val modelsArr = json.optJSONArray("models") ?: return@use null
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
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
        }
    }

    // ─── Keep Computer Awake (platform-generic) ───

    data class SystemStatus(
        val os: String,
        val platform: String,
        val python: String,
        val awake: Boolean,
        val awakeMechanism: String?
    )

    suspend fun getSystemStatus(): SystemStatus? {
        val baseUrl = config?.baseUrl ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl/api/system/status").get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: return@use null)
                        SystemStatus(
                            os = json.optString("os", ""),
                            platform = json.optString("platform", ""),
                            python = json.optString("python", ""),
                            awake = json.optBoolean("awake", false),
                            awakeMechanism = json.optString("awake_mechanism", "")
                                .ifBlank { null }
                                .takeUnless { it == "null" }
                        )
                    } else null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
        }
    }

    suspend fun setSystemAwake(awake: Boolean): String? {
        val baseUrl = config?.baseUrl ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().put("awake", awake)
                val request = Request.Builder()
                    .url("$baseUrl/api/system/awake")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val m = JSONObject(response.body?.string() ?: "{}")
                            .optString("mechanism", "")
                        // org.json quirk: JSON null surfaces as the string "null"
                        if (m.isBlank() || m == "null") null else m
                    } else null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
        }
    }

    // ─── Follow-up to a running agent (Cursor-style) ───

    /** Queue a follow-up on the session's ACTIVE stream (returns 409 if none). */
    suspend fun sendFollowUp(sessionId: String, query: String): Boolean {
        val baseUrl = config?.baseUrl ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("session_id", sessionId)
                    put("query", query)
                }
                val request = Request.Builder()
                    .url("$baseUrl/api/chat/followup")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { false }
        }
    }

    // ─── Session status/source badges (server truth) ───

    /** Fetch the server's per-session status (idle/working/done/error) +
     * source (app/voice/swarm) — the local DB has no such fields. */
    suspend fun fetchServerSessionStatus(): Map<String, Pair<String, String>> {
        val baseUrl = config?.baseUrl ?: return emptyMap()
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl/api/sessions").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyMap()
                    val arr = JSONArray(response.body?.string() ?: return@use emptyMap())
                    buildMap {
                        for (i in 0 until arr.length()) {
                            val s = arr.getJSONObject(i)
                            val id = s.optString("id", "")
                            if (id.isNotBlank()) {
                                put(
                                    id,
                                    s.optString("status", "idle") to
                                        s.optString("source", "app")
                                )
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { emptyMap() }
        }
    }

    // ─── Per-session push channel (response_ready) ───

    /** Subscribe to the session's SSE event channel. The server PUSHES a
     * 'response_ready' event the moment a response is saved — the chat
     * patches instantly instead of polling. Returns the source (cancel it
     * to stop); keepalive comments are ignored by the SSE parser. */
    fun subscribeSessionEvents(
        sessionId: String,
        onResponseReady: (String) -> Unit,
        onFailure: (Throwable?) -> Unit
    ): okhttp3.sse.EventSource? {
        val baseUrl = config?.baseUrl ?: return null
        val request = Request.Builder()
            .url("$baseUrl/api/sessions/$sessionId/events")
            .header("Accept", "text/event-stream")
            .build()
        val factory = EventSources.createFactory(client)
        return factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val json = JSONObject(data)
                    if (json.optString("type") == "response_ready") {
                        val content = json.optString("content", "")
                        if (content.isNotBlank()) onResponseReady(content)
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                onFailure(t)
            }
            override fun onClosed(eventSource: EventSource) {
                // Treat close as down — the fallback poll + resubscribe kick in.
                onFailure(null)
            }
        })
    }

    // ─── Switch Model (via chat command) ───

    suspend fun switchModel(sessionId: String, modelName: String, global: Boolean = false): Boolean {
        val query = "/model $modelName${if (global) " --global" else ""}"
        return try {
            val response = sendChat(query, sessionId)
            response.contains("✅") || response.contains("switched")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
                response.use { it.isSuccessful }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Fetch a session's messages from the server (source of truth).
     * Returns list of {role, content, timestamp} or null on failure.
     * Used to repair a last response lost to an interrupted stream.
     */
    suspend fun fetchSessionMessages(sessionId: String): List<JSONObject>? {
        val baseUrl = config?.baseUrl ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/api/sessions/$sessionId/messages")
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    val arr = JSONArray(body)
                    buildList {
                        for (i in 0 until arr.length()) {
                            add(arr.getJSONObject(i))
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    // ─── Diag log upload (production support) ───
    // Ships the on-device diag.log to the bridge, which stores it under
    // STORE_PATH/logs/ for the user/maintainer to pull for analysis.
    suspend fun uploadDiagLog(device: String, version: String, log: String): Boolean {
        val baseUrl = config?.baseUrl ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("device", device)
                    put("version", version)
                    put("log", log)
                }.toString()
                val request = Request.Builder()
                    .url("$baseUrl/api/diag/log")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
                    response.close()
                    null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    // ─── Speech-to-text (Whisper on bridge server) ───

    /**
     * Transcribe raw 16kHz mono PCM WAV bytes via the bridge's whisper
     * endpoint. Returns the transcript, or null on any failure (caller
     * falls back to the system SpeechRecognizer).
     */
    suspend fun transcribeAudio(wav: ByteArray, lang: String? = null): String? {
        val baseUrl = config?.baseUrl ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val urlBuilder = "$baseUrl/api/stt".toHttpUrlOrNull()?.newBuilder()?.apply {
                    if (!lang.isNullOrBlank()) addQueryParameter("lang", lang)
                }
                val request = Request.Builder()
                    .url(urlBuilder?.build() ?: return@withContext null)
                    .post(wav.toRequestBody("audio/wav".toMediaType()))
                    .build()
                // AuthInterceptor adds the Authorization header (replace
                // semantics) to every request — no manual header needed.
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "{}")
                        json.optString("text").takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, file.asRequestBody(mimeType.toMediaTypeOrNull()))
                    .build()
                val request = Request.Builder()
                    .url("$baseUrl/api/upload")
                    .post(body)
                    .build()
                // AuthInterceptor handles the Authorization header.
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "{}")
                        json.optString("url")?.takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
}
