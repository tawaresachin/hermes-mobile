package com.hermes.mobile.network

import android.content.Context
import android.content.SharedPreferences
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
         *  silently dropped connection) - kill it instead of waiting out
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

    fun prefs(): SharedPreferences = prefs

    /** App context for resource-driven defaults (screen-class font size). */
    val contextRes: Context get() = context

    // ── Server session continuity ──
    // The app's local session UUID maps to the server's state.db session id
    // (returned in X-Hermes-Session-Id; rotates when Hermes auto-compresses
    // the transcript). Sending the header makes the server load/append REAL
    // history — same pipeline as Telegram/CLI, including auto context
    // compression — instead of starting a fresh context each message.
    fun serverIdFor(localSessionId: String): String? =
        prefs.getString("srv_session:$localSessionId", null)

    fun saveServerId(localSessionId: String, serverId: String) {
        if (serverId.isBlank() || serverId == serverIdFor(localSessionId)) return
        prefs.edit().putString("srv_session:$localSessionId", serverId).apply()
    }

    // ── Caveman mode (user's "Context Compression" setting) ──
    // ON = terse replies to save output tokens. The gateway extracts a
    // leading system-role message as the turn's ephemeral system prompt,
    // so the flag rides the payload — no server change needed.
    fun isCaveman(): Boolean = prefs.getBoolean("caveman_mode", true)
    fun saveCaveman(on: Boolean) {
        prefs.edit().putBoolean("caveman_mode", on).apply()
    }

    // ── Per-session model selection ──
    // Each chat remembers ITS model: switching sessions must never leak one
    // session's pick into another, and reloads must not reset it.
    fun savedModelForSession(sessionId: String): String? =
        prefs.getString("session_model:$sessionId", null)

    fun savedModelSlugForSession(sessionId: String): String =
        prefs.getString("session_model_slug:$sessionId", "") ?: ""

    fun saveModelForSession(sessionId: String, modelId: String, providerSlug: String) {
        prefs.edit()
            .putString("session_model:$sessionId", modelId)
            .putString("session_model_slug:$sessionId", providerSlug)
            .apply()
    }

    // ── HTTP Client with AuthInterceptor ──

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Helper: build OpenAI-format messages array from session history + new query
    private fun buildOpenAIMessages(sessionId: String, query: String): List<Map<String, String>> {
        val messages = mutableListOf<Map<String, String>>()
        // Session history is managed server-side (X-Hermes-Session-Id);
        // only the new user turn goes on the wire.
        if (isCaveman()) {
            // Measured vs plain: ~30% fewer output tokens on explanatory
            // answers with this wording (weak "answer terse" phrasing got
            // only ~15% — the model's persona prompt overrode it).
            messages.add(mapOf(
                "role" to "system",
                "content" to "COMPRESSION DIRECTIVE (overrides verbosity " +
                    "habits): answer in the fewest tokens that keep every " +
                    "technical fact. No greetings, no preambles, no " +
                    "sign-offs, no restating the question, no bullet " +
                    "padding. Fragments allowed. Use short words. Keep " +
                    "code, names, numbers, exact error text verbatim. " +
                    "Never explain the directive. " +
                    "Bad: Sure! A context window is basically the amount of " +
                    "text. Good: Context window: max tokens model sees per " +
                    "call. History+prompt+output share it."
            ))
        }
        messages.add(mapOf("role" to "user", "content" to query))
        return messages
    }

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
    // SecurePrefs (AES256-GCM at rest) - same store device creds use.

    // Written on Main (updateConfig) and read from IO threads (healthCheck
    // / streamChat) - publication must be visible across dispatchers.
    @Volatile
    private var config: ServerConfig? = null

    // Ids advertised by GET /v1/models are model_routes ALIASES: the server
    // pins them to a provider and 400s any request that also sends one
    // ("Remove 'provider' or use 'X'"). Cached on every healthCheck.
    @Volatile
    private var aliasIds: Set<String> = emptySet()

    // Built ONCE (lazy): SecurePrefs.get() creates a MasterKey +
    // EncryptedSharedPreferences (KeyStore init + file decrypt) - doing
    // that on EVERY access was blocking the Main thread on each 5s poll.
    private val secretPrefs: SharedPreferences by lazy {
        com.hermes.mobile.security.SecurePrefs.get(context, SECURE_PREFS_NAME)
    }

    fun updateConfig(cfg: ServerConfig) {
        val prev = config
        config = cfg
        if (prev == cfg) {
            // Unchanged - skip the prefs write. healthCheck polls every 5s
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

    /** Forget the pairing entirely (logout). */
    fun clearConfig() {
        config = null
        prefs.edit().remove(KEY_BASE_URL).apply()
        secretPrefs.edit().remove(KEY_API_KEY).remove(KEY_SETUP_TOKEN).apply()
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

    /** Plain GET of `path` (no auth). True on any HTTP response — even 401 —
     * because it proves the server is REACHABLE. */
    suspend fun isReachable(cfg: ServerConfig, path: String = "/api/audio/health"): Boolean {
        val base = cfg.baseUrl.trimEnd('/') + path
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(base).get().build())
                    .execute().use { true }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { false }
        }
    }

    suspend fun healthCheck(cfg: ServerConfig? = null): Boolean {
        val baseUrl = cfg?.baseUrl?.takeIf { it.isNotBlank() }
            ?: config?.baseUrl
            ?: return false
        val apiKey = cfg?.apiKey?.takeIf { it.isNotBlank() }
            ?: config?.apiKey?.takeIf { it.isNotBlank() }
            ?: ""
        if (cfg?.baseUrl?.isNotBlank() == true && cfg != config) updateConfig(cfg)
        return withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder()
                    .url("$baseUrl/v1/models")
                    .get()
                // Explicit header: do not rely only on interceptor prefs
                // (apply() is async, test key must win now).
                if (apiKey.isNotBlank()) {
                    builder.header("Authorization", "Bearer $apiKey")
                }
                val request = builder.build()
                // response.use closes the body - a leaked body pins a socket
                // (readTimeout 300s) that can't be pooled; the 5s poll loop
                // would accumulate connections over time.
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    // Side-capture: refresh the model_routes alias set while
                    // we're here (streamChat omits provider for these).
                    try {
                        val body = resp.body?.string() ?: ""
                        val arr = JSONObject(body).optJSONArray("data")
                        if (arr != null) {
                            aliasIds = (0 until arr.length())
                                .mapNotNull { i -> arr.optJSONObject(i)?.optString("id") }
                                .toSet()
                        }
                    } catch (_: Exception) { }
                    true
                }
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
        // Server's real tool chrome: hermes.tool.progress frames carry
        // {tool, emoji, label, toolCallId, status: running|completed|failed}.
        onToolProgress: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
        onModelReverted: (String) -> Unit = {},
        onAttachment: (String, String) -> Unit = { _, _ -> },
        onTurnEnd: () -> Unit = {},
        onOpen: () -> Unit = {},
        onUsage: (Long, Long) -> Unit = { _, _ -> },
        attachmentUrl: String = "",
        attachType: String = "",
        replyTo: String? = null,
        model: String? = null,
        provider: String? = null,
    ): Unit = withContext(Dispatchers.IO) {
        val cfg = config ?: getConfig()
        val baseUrl = cfg?.baseUrl?.takeIf { it.isNotBlank() } ?: "http://localhost:8080"
        val resolvedKey = cfg?.apiKey?.takeIf { it.isNotBlank() } ?: ""
        // Build OpenAI-compatible chat completion request.
        // REPLY CONTEXT: the quote chip is UI-only — the server has no
        // reply-to field, so the quoted text rides INSIDE the user message
        // (and therefore into persisted history): the agent sees exactly
        // what the user is replying to.
        val wireQuery: String = if (!replyTo.isNullOrBlank()) {
            val quoted = replyTo.trim().replace("\n", " ").take(400)
            "Re: \"" + quoted + "\"\n\n" + query
        } else query
        val messages = buildOpenAIMessages(sessionId, wireQuery)
        // Dynamic default: server inventory decides. No hardcoded model id.
        // Resolved here (suspend scope) - not inside the callback below.
        val safeModel = if (!model.isNullOrBlank()) model else fetchDefaultModelId()
        val payload = JSONObject().apply {
            put("model", safeModel)
            put("messages", JSONArray(messages))
            put("stream", true)
            // Provider rides only for NON-alias models: /v1/models aliases are
            // route-pinned server-side and reject an explicit provider (400).
            if (!provider.isNullOrBlank() && safeModel !in aliasIds) {
                put("provider", provider)
            }
        }

        val reqBuilder = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("Accept", "text/event-stream")
        // Declare OUR session id from the very first turn (local UUID is a
        // valid server id — verified live); switch to the server's id once
        // one is known (e.g. after compression rotation).
        reqBuilder.header(
            "X-Hermes-Session-Id",
            serverIdFor(sessionId)?.takeIf { it.isNotBlank() } ?: sessionId
        )
        if (resolvedKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $resolvedKey")
        }
        val request = reqBuilder.build()
        suspendCancellableCoroutine { continuation ->

            val factory = EventSources.createFactory(client)
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            // Idle watchdog: a silently dead connection (no events) would
            // otherwise hold the typing indicator until the 300s OkHttp
            // read timeout. Track last-event time; kill on silence.
            var lastEventMs = System.currentTimeMillis()
            val source = factory.newEventSource(request, object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    lastEventMs = System.currentTimeMillis()
                    // Persist the (possibly rotated by compression) server id
                    // so the NEXT message continues the same transcript.
                    response.header("X-Hermes-Session-Id")?.takeIf { it.isNotBlank() }?.let {
                        saveServerId(sessionId, it)
                    }
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
                    // Tool chrome rides the SSE `event:` line (server frames
                    // carry NO "type" field in the body). Match it here —
                    // otherwise the frame falls through to the plain-text
                    // fallback and raw JSON leaks into the bubble.
                    if (type == "hermes.tool.progress") {
                        try {
                            val o = JSONObject(data)
                            onToolProgress(
                                o.optString("toolCallId", ""),
                                o.optString("emoji", "⚙️"),
                                o.optString("tool", ""),
                                o.optString("label", ""),
                                o.optString("status", "running")
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) { }
                        return
                    }
                    try {
                        val json = JSONObject(data)
                        // Final usage frame (may arrive with or without choices):
                        // persist real token counts instead of showing 0.
                        json.optJSONObject("usage")?.let { u ->
                            val pt = u.optLong("prompt_tokens", 0L)
                            val ct = u.optLong("completion_tokens", 0L)
                            if (pt + ct > 0) onUsage(pt, ct)
                        }
                        // OpenAI SSE format: {"choices":[{"delta":{"content":".."},"message":{...}}]}
                        // Server sends this. Must handle before custom type check.
                        val choices = json.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val first = choices.optJSONObject(0)
                            if (first != null) {
                                val delta = first.optJSONObject("delta")
                                val deltaContent = delta?.optString("content", "") ?: ""
                                if (deltaContent.isNotEmpty()) {
                                    onChunk(deltaContent)
                                    return
                                }
                                val message = first.optJSONObject("message")
                                val msgContent = message?.optString("content", "") ?: ""
                                if (msgContent.isNotEmpty()) {
                                    onChunk(msgContent)
                                    return
                                }
                                val finish = first.optString("finish_reason", "")
                                // Server error chunk: surface it, do not stay silent.
                                if (finish.isNotEmpty()) {
                                    val errObj = json.optJSONObject("error")
                                        ?: first.optJSONObject("error")
                                    val errMsg = errObj?.optString("message", "") ?: ""
                                    if (errMsg.isNotEmpty()) {
                                        onChunk("Error: $errMsg")
                                    }
                                    return
                                }
                            }
                        }
                        val eventType = json.optString("type", "")
                        if (eventType.isEmpty() && choices == null) {
                            // Plain text fallback
                            if (data.isNotEmpty()) onChunk(data)
                            return
                        }
                        when (eventType) {
                            "text" -> {
                                val content = json.optString("content", "")
                                if (content.isNotEmpty()) onChunk(content)
                            }
                            "hermes.tool.progress" -> {
                                onToolProgress(
                                    json.optString("toolCallId", ""),
                                    json.optString("emoji", "⚙️"),
                                    json.optString("tool", ""),
                                    json.optString("label", ""),
                                    json.optString("status", "running")
                                )
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
                            // Telegram: media + caption arrive together. The
                            // server emits this in-stream (before [DONE])
                            // when the reply includes a session upload -
                            // apply the image/file to the bubble NOW.
                            "attachment" -> {
                                onAttachment(
                                    json.optString("url", ""),
                                    json.optString("attach_type", "")
                                )
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
                        // 401 = the saved API key is wrong/revoked. There is no
                        // refresh flow (API-key auth), so surface a clear error.
                        if (response?.code == 401) {
                            continuation.resumeWithException(
                                IOException("401 - API key rejected. Re-scan the QR code or update the key in Settings."))
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
                                IOException("Stream idle - no data for ${STREAM_IDLE_TIMEOUT_MS / 1000}s")
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
        val cfg = config ?: getConfig()
        val baseUrl = cfg?.baseUrl?.takeIf { it.isNotBlank() } ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/v1/models" + if (sessionId.isNotBlank()) "?session_id=$sessionId" else ""
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use null
                        // Try parsing as array directly first (mobile plugin format)
                        var models: List<ModelInfo>? = null
                        var currentModel = ""
                        try {
                            val arr = JSONArray(body)
                            models = (0 until arr.length()).map { i ->
                                val m = arr.getJSONObject(i)
                                ModelInfo(
                                    id = m.optString("id", ""),
                                    name = m.optString("name", ""),
                                    isVision = m.optBoolean("isVision", false),
                                    isFree = m.optBoolean("isFree", false),
                                    provider = m.optString("provider", ""),
                                    baseUrl = m.optString("baseUrl", "")
                                )
                            }
                        } catch (_: Exception) {
                            // Try parsing as object with "models" field (legacy format)
                            val json = JSONObject(body)
                            val modelsArr = json.optJSONArray("models")
                            if (modelsArr != null) {
                                models = (0 until modelsArr.length()).map { i ->
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
                                currentModel = json.optString("current", "")
                            } else {
                                // Try OpenAI format: {"object": "list", "data": [...]}
                                val dataArr = json.optJSONArray("data")
                                if (dataArr != null) {
                                    models = (0 until dataArr.length()).map { i ->
                                        val m = dataArr.getJSONObject(i)
                                        ModelInfo(
                                            id = m.optString("id", ""),
                                            name = m.optString("id", ""), // Use id as name if no name
                                            isVision = m.optBoolean("is_vision", m.optBoolean("vision", false)),
                                            isFree = false,
                                            provider = m.optString("owned_by", ""),
                                            baseUrl = ""
                                        )
                                    }
                                }
                            }
                        }
                        if (models == null || models.isEmpty()) return@use null
                        ModelListResponse(
                            models = models,
                            current = currentModel,
                            default = "",
                            provider = ""
                        )
                    } else null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
        }
    }

    // ─── Model Inventory (same source as the Hermes dashboard/TUI picker) ───
    // GET /api/model/options returns providers[] with slug, name, models[].
    // Groups, ids and the default derive 100% from the response - no
    // hardcoded model names anywhere in this file.
    suspend fun fetchModelOptions(): ModelListResponse? {
        val cfg = config ?: getConfig()
        val base = cfg?.baseUrl?.takeIf { it.isNotBlank() } ?: return null
        val key = cfg.apiKey?.takeIf { it.isNotBlank() } ?: ""
        return withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url("$base/api/model/options").get()
                if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string() ?: return@use null)
                    val current = json.optString("model", "")
                    val currentProvider = json.optString("provider", "")
                    val out = mutableListOf<ModelInfo>()
                    val providers = json.optJSONArray("providers") ?: return@use null
                    for (i in 0 until providers.length()) {
                        val p = providers.optJSONObject(i) ?: continue
                        val slug = p.optString("slug", "")
                        val label = p.optString("name", "").ifBlank { slug }
                        if (label.isBlank()) continue
                        val arr = p.optJSONArray("models") ?: continue
                        for (j in 0 until arr.length()) {
                            val raw = arr.optString(j, "").trim()
                            if (raw.isEmpty()) continue
                            // The request sends provider and model SEPARATELY
                            // (server combines them). Prefixing the id with the
                            // slug here made the server double-prefix colon
                            // slugs: "custom:custom:freellm/qwen..." -> 404.
                            // Bare ids stay bare; slash ids keep their slash.
                            val free = raw.contains(":free", ignoreCase = true) ||
                                raw.contains("-free", ignoreCase = true)
                            out.add(ModelInfo(id = raw, name = raw, isFree = free,
                                provider = label, providerSlug = slug))
                        }
                    }
                    if (out.isEmpty()) return@use null
                    ModelListResponse(models = out, current = current, default = current, provider = currentProvider)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
        }
    }

    // Dynamic server default for sends with no model yet. No hardcoded id.
    suspend fun fetchDefaultModelId(): String {
        val opts = try { fetchModelOptions() } catch (_: Exception) { null }
        if (opts != null) {
            if (opts.current.isNotBlank() && opts.models.any { it.id == opts.current }) return opts.current
            opts.models.firstOrNull()?.let { return it.id }
        }
        throw IOException("No model available - check connection")
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

    /** INTERRUPT the running agent (Telegram interrupt mode): the app's Stop
     * button calls this while streaming, then cancels the local SSE job.
     *
     * The api_server has no cancel route (the old /v1/chat/completions/cancel
     * is 404 — verified), but it reaps the agent as soon as the SSE connection
     * closes (api_server.py: `_reap_disconnected_agent_processes`, source
     * "api_server_sse_disconnect"). So closing our own stream IS the interrupt;
     * there is nothing remote left to do. Always succeeds. */
    suspend fun cancelChat(sessionId: String): Boolean = true

    // ─── Session status/source badges (server truth) ───

    /** Fetch the server's per-session status (idle/working/done/error) +
     * source (app/voice/swarm) - the local DB has no such fields. */
    suspend fun fetchServerSessionStatus(): Map<String, Pair<String, String>> {
        val baseUrl = config?.baseUrl ?: return emptyMap()
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl/api/sessions").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyMap()
                    val body = response.body?.string() ?: return@use emptyMap()
                    // api_server wraps the list: {object:"list", data:[...]}
                    val arr = if (body.trimStart().startsWith("[")) JSONArray(body)
                        else JSONObject(body).optJSONArray("data") ?: return@use emptyMap()
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

    // ─── Server usage truth (Settings → Usage) ───

    /** Sum real per-session token/message counts from GET /api/sessions
     * (paginated, capped at 5 pages so a huge history can't hang settings).
     * Returns Triple(messages, inputTokens, outputTokens) or null offline. */
    data class ServerUsage(
        val sessions: Int,
        val messages: Int,
        val inputTokens: Long,
        val outputTokens: Long
    )

    suspend fun fetchServerUsage(): ServerUsage? {
        val cfg = config ?: getConfig()
        val base = cfg?.baseUrl?.takeIf { it.isNotBlank() } ?: return null
        val key = cfg.apiKey?.takeIf { it.isNotBlank() } ?: ""
        return withContext(Dispatchers.IO) {
            try {
                var offset = 0
                var sessions = 0
                var messages = 0
                var tin = 0L
                var tout = 0L
                for (page in 0 until 5) {
                    val url = "$base/api/sessions?limit=200&offset=$offset"
                    val builder = Request.Builder().url(url).get()
                    if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
                    client.newCall(builder.build()).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val json = JSONObject(response.body?.string() ?: return@use)
                        val arr = json.optJSONArray("data") ?: return@use
                        for (i in 0 until arr.length()) {
                            val s = arr.optJSONObject(i) ?: continue
                            sessions++
                            messages += s.optInt("message_count", 0)
                            tin += s.optLong("input_tokens", 0L)
                            tout += s.optLong("output_tokens", 0L)
                        }
                        if (!json.optBoolean("has_more", false) || arr.length() == 0) {
                            offset = -1
                            return@use
                        }
                    }
                    if (offset < 0) break
                    offset += 200
                }
                if (sessions == 0) null else ServerUsage(sessions, messages, tin, tout)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
        }
    }

    // ─── Per-session push channel (response_ready) ───

    /** Subscribe to the session's SSE event channel. The server PUSHES a
     * 'response_ready' event the moment a response is saved - the chat
     * patches instantly instead of polling. Returns the source (cancel it
     * to stop); keepalive comments are ignored by the SSE parser. */
    fun subscribeSessionEvents(
        sessionId: String,
        onResponseReady: (String, Long, String, String) -> Unit,
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
                        if (content.isNotBlank()) {
                            onResponseReady(
                                content,
                                json.optLong("ts", 0L),
                                json.optString("attachment_url", ""),
                                json.optString("attachment_type", "")
                            )
                        }
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                onFailure(t)
            }
            override fun onClosed(eventSource: EventSource) {
                // Treat close as down - the fallback poll + resubscribe kick in.
                onFailure(null)
            }
        })
    }

    // ─── Switch Model (via dedicated endpoint) ───

    suspend fun switchModel(sessionId: String, modelName: String, global: Boolean = false): Boolean {
        // Direct API has no server-side switch endpoint (POST /v1/models/switch
        // is 404). Model travels per chat request, so switch is local-only.
        // Any non-blank server-advertised id is accepted - no hardcoded list.
        if (modelName.isBlank()) return false
        return true
    }

    /** Context window for a model via the plugin's resolver (server truth,
     * cached server-side). providerSlug is e.g. "custom:freellm". Returns
     * null when unknown/offline — the UI hides the meter instead of guessing. */
    /** GET $path as JSON (Bearer auth). Null on any failure. Used by slash
     * commands (/skills, /version) that need server truth. */
    suspend fun getJson(path: String): JSONObject? {
        val cfg = config ?: getConfig()
        val base = cfg?.baseUrl?.takeIf { it.isNotBlank() } ?: return null
        val key = cfg.apiKey?.takeIf { it.isNotBlank() } ?: ""
        return withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(base.trimEnd('/') + path).get()
                if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
                client.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    JSONObject(resp.body?.string() ?: return@use null)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { null }
        }
    }

    /** Live server-side context fill for a session (active-row token sum;
     * accurate even after a compression rotation while the app was away).
     * Returns 0 when unknown (older gateway without the route). */
    suspend fun fetchContextUsage(localSessionId: String): Long {
        val sid = serverIdFor(localSessionId) ?: localSessionId
        val json = getJson("/api/mobile/context-usage?session_id=" +
            java.net.URLEncoder.encode(sid, "UTF-8"))
        return json?.optLong("last_prompt_tokens", 0L) ?: 0L
    }

    suspend fun fetchContextWindow(modelId: String, providerSlug: String?): Long {
        if (modelId.isBlank()) return 0L
        val cfg = config ?: getConfig()
        val base = cfg?.baseUrl?.takeIf { it.isNotBlank() } ?: return 0L
        val key = cfg.apiKey?.takeIf { it.isNotBlank() } ?: ""
        return withContext(Dispatchers.IO) {
            try {
                val url = "$base/api/mobile/context-window?model=" +
                    java.net.URLEncoder.encode(modelId, "UTF-8") +
                    "&provider=" + java.net.URLEncoder.encode(providerSlug ?: "", "UTF-8")
                val builder = Request.Builder().url(url).get()
                if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use 0L
                    val json = JSONObject(response.body?.string() ?: return@use 0L)
                    json.optLong("context_length", 0L)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { 0L }
        }
    }

    // ─── Simple Chat (non-streaming) ───

    suspend fun sendChat(
        query: String,
        sessionId: String? = null
    ): String {
        val baseUrl = config?.baseUrl ?: return ""
        val model = fetchDefaultModelId()
        return withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("model", model)
                put("query", query)
                put("stream", false)
                sessionId?.let { put("session_id", it) }
            }
            val request = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
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
                    // Hermes api_server wraps the list: {object:"list", data:[...]}.
                    // Accept a bare array too (legacy/other servers).
                    val arr = if (body.trimStart().startsWith("[")) JSONArray(body)
                        else JSONObject(body).optJSONArray("data") ?: return@withContext null
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
                // semantics) to every request - no manual header needed.
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

    suspend fun uploadFile(file: java.io.File, fileName: String, mimeType: String, sessionId: String = ""): String? {
        val baseUrl = config?.baseUrl ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, file.asRequestBody(mimeType.toMediaTypeOrNull()))
                    .build()
                // hermes-mobile-qr plugin route: /api/audio/upload?session_id=<sid>
                // -> {url:"/api/audio/download/<sid>/<stored>"}. The URL is
                // relative; bubbles resolve it against baseUrl on display.
                val urlBuilder = ("$baseUrl/api/audio/upload").toHttpUrlOrNull()?.newBuilder()?.apply {
                    if (sessionId.isNotBlank()) addQueryParameter("session_id", sessionId)
                }
                val request = Request.Builder()
                    .url(urlBuilder?.build() ?: return@withContext null)
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

    /** Download a session attachment (e.g. /uploads/...) as raw bytes.
     * Telegram-style: tapping a media/file bubble downloads it - the same
     * AuthInterceptor attaches the Bearer token automatically. Returns null
     * on any failure. */
    suspend fun downloadAttachment(relUrl: String): ByteArray? {
        val baseUrl = config?.baseUrl ?: return null
        val url = if (relUrl.startsWith("http")) relUrl else baseUrl.trimEnd('/') + relUrl
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes() else null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    // ── Audio STT / TTS (hermes-mobile-qr plugin routes) ──────────────

    suspend fun transcribeAudio(audioB64: String, mimeType: String = "audio/wav", model: String? = null): String {
        val baseUrl = config?.baseUrl ?: throw RuntimeException("Not connected")
        val url = "$baseUrl/api/audio/transcribe"
        val requestJson = JSONObject()
        requestJson.put("audio_b64", audioB64)
        requestJson.put("mime_type", mimeType)
        if (model != null && model.isNotEmpty()) {
            requestJson.put("model", model)
        }
        val payload = requestJson.toString()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw RuntimeException("Transcribe HTTP ${resp.code}: $body")
                }
                val responseJson = JSONObject(body)
                if (!responseJson.optBoolean("ok", false)) {
                    throw RuntimeException("Transcribe: ${responseJson.optString("error", "unknown")}")
                }
                responseJson.optString("text", "")
            }
        }
    }

    suspend fun speakText(text: String, model: String? = null): String {
        val baseUrl = config?.baseUrl ?: throw RuntimeException("Not connected")
        val url = "$baseUrl/api/audio/speak"
        val requestJson = JSONObject()
        requestJson.put("text", text)
        if (model != null && model.isNotEmpty()) {
            requestJson.put("model", model)
        }
        val payload = requestJson.toString()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw RuntimeException("Speak HTTP ${resp.code}: $body")
                }
                val responseJson = JSONObject(body)
                if (!responseJson.optBoolean("ok", false)) {
                    throw RuntimeException("Speak: ${responseJson.optString("error", "unknown")}")
                }
                val dataUrl = responseJson.optString("data_url", "")
                if (dataUrl.isEmpty()) throw RuntimeException("Speak: no audio returned")
                dataUrl
            }
        }
    }
}
