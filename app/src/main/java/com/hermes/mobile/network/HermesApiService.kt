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

    // ── Terse-replies mode (Settings "Terse Replies", pref key kept: caveman_mode) ──
    // ON = terse replies to save output tokens. The gateway extracts a
    // leading system-role message as the turn's ephemeral system prompt,
    // so the flag rides the payload — no server change needed.
    fun isCaveman(): Boolean = prefs.getBoolean("caveman_mode", true)
    fun saveCaveman(on: Boolean) {
        prefs.edit().putBoolean("caveman_mode", on).apply()
    }

    // ── Auto-approve (Settings "Auto-approve tools") ──
    // ON = a pending tool approval is answered 'session' automatically the
    // moment it arrives (the user trusts the agent on this device). OFF =
    // the approval card + push waits for a human decision.
    fun isAutoApprove(): Boolean = prefs.getBoolean("auto_approve", false)
    fun saveAutoApprove(on: Boolean) {
        prefs.edit().putBoolean("auto_approve", on).apply()
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

    /** Purge every per-session key this class owns. Called on session
     * delete — without it, srv_session:/session_model(:_slug): entries for
     * dead sessions accumulate in prefs forever. */
    fun forgetSessionKeys(sessionId: String) {
        prefs.edit()
            .remove("srv_session:$sessionId")
            .remove("session_model:$sessionId")
            .remove("session_model_slug:$sessionId")
            .apply()
    }

    // ── HTTP Client with AuthInterceptor ──

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ── Token Optimizer (smart terse application) ──────────────────────
    // Decision core extracted to TerseOptimizer (pure, JVM-unit-tested in
    // app/src/test). Learn per model from real usage frames — no hardcoded
    // model list: probe turns without the directive decide whether a model
    // is verbose (apply) or already terse (skip forever). Verdict persists
    // per model in prefs; switching sessions/models re-evaluates.
    private val terseOptimizer by lazy {
        TerseOptimizer(TerseOptimizer.PrefsStore(prefs))
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


    // ─── Durable runs (POST /v1/runs — survives client disconnect) ───
    //
    // The chat-completions SSE is synchronous: closing the socket makes the
    // gateway INTERRUPT the agent ("SSE client disconnected; interrupted agent
    // task"). /v1/runs is the detached form built for exactly this: the run
    // executes server-side regardless of the transport, its status is
    // pollable, its events are re-subscribable, and stop/steer/approval are
    // explicit routes. All mobile turns go through it (RunController).

    /** Admit a run; returns run_id, or throws IOException with a
     * user-presentable message on rejection (401/429/…). */
    suspend fun startRun(
        serverSessionId: String,
        query: String,
        model: String,
        provider: String?,
        instructions: String?,
    ): String = withContext(Dispatchers.IO) {
        val cfg = config ?: getConfig()
        val baseUrl = cfg?.baseUrl?.takeIf { it.isNotBlank() } ?: "http://localhost:8080"
        val payload = JSONObject().apply {
            put("input", query)
            put("session_id", serverSessionId)
            if (model.isNotBlank()) put("model", model)
            // Provider rides only for NON-alias models (route-pinned aliases
            // reject an explicit provider with 400 — same contract as chat).
            if (!provider.isNullOrBlank() && model !in aliasIds) put("provider", provider)
            if (!instructions.isNullOrBlank()) put("instructions", instructions)
        }
        val request = Request.Builder()
            .url("$baseUrl/v1/runs")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val msg = try {
                    JSONObject(body).optJSONObject("error")?.optString("message", "")
                        ?.ifBlank { null } ?: body.take(120)
                } catch (_: Exception) { body.take(120) }
                throw IOException("${response.code}: $msg")
            }
            JSONObject(body).optString("run_id", "").also {
                if (it.isBlank()) throw IOException("Server accepted the run but returned no run_id")
            }
        }
    }

    /** Live event stream for one run. Frames carry the event name in the
     * JSON body ("event" key) — decoded via RunEventCodec. onClosed fires on
     * terminal event / transport loss; the caller decides whether to poll. */
    fun subscribeRunEvents(
        runId: String,
        onEvent: (com.hermes.mobile.data.runs.RunEventCodec.RunEvent) -> Unit,
        onTransportLost: () -> Unit,
    ): EventSource? {
        val baseUrl = (config ?: getConfig())?.baseUrl?.takeIf { it.isNotBlank() } ?: return null
        val request = Request.Builder()
            .url("$baseUrl/v1/runs/$runId/events")
            .header("Accept", "text/event-stream")
            .build()
        val factory = EventSources.createFactory(client)
        return factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val ev = com.hermes.mobile.data.runs.RunEventCodec.decode(data)
                if (ev != null) onEvent(ev)
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                onTransportLost()
            }
            override fun onClosed(eventSource: EventSource) {
                onTransportLost()
            }
        })
    }

    /** Resolve the LIVE session tip for a server id: compression rotations
     * end a session and fork a child; /messages resolves through the lineage
     * and echoes the tip in its envelope. The run API never returns a
     * rotated id (unlike chat-completions' X-Hermes-Session-Id header), so
     * the app re-reads it after every settled turn to stay on the tip. */
    suspend fun resolveSessionTip(serverSessionId: String): String? = withContext(Dispatchers.IO) {
        val baseUrl = (config ?: getConfig())?.baseUrl?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/sessions/$serverSessionId/messages?limit=1").get().build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                JSONObject(r.body?.string() ?: return@use null).optString("session_id", "").ifBlank { null }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    /** Pollable run status (survives transport loss; output on completion). */
    suspend fun fetchRunStatus(runId: String): JSONObject? = withContext(Dispatchers.IO) {
        val baseUrl = (config ?: getConfig())?.baseUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
        try {
            val request = Request.Builder().url("$baseUrl/v1/runs/$runId").get().build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                JSONObject(r.body?.string() ?: return@use null)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    private suspend fun runAction(path: String, payload: JSONObject?): Boolean =
        withContext(Dispatchers.IO) {
            val baseUrl = (config ?: getConfig())?.baseUrl?.takeIf { it.isNotBlank() }
                ?: return@withContext false
            try {
                val builder = Request.Builder().url("$baseUrl$path")
                val body = payload?.toString()?.toRequestBody(jsonMediaType)
                    ?: ByteArray(0).toRequestBody(jsonMediaType)
                builder.post(body).build().let { req ->
                    client.newCall(req).execute().use { r -> r.isSuccessful }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
        }

    /** Real stop (replaces the closing-the-socket side effect). */
    suspend fun stopRun(runId: String): Boolean =
        runAction("/v1/runs/$runId/stop", JSONObject())

    /** Inject guidance into a RUNNING run (Telegram mid-run steer parity). */
    suspend fun steerRun(runId: String, text: String): Boolean =
        runAction("/v1/runs/$runId/steer", JSONObject().put("input", text))

    /** Resolve a pending approval: once | session | always | deny. */
    suspend fun resolveApproval(runId: String, choice: String, requestId: String): Boolean {
        val payload = JSONObject().put("choice", choice)
        if (requestId.isNotBlank()) payload.put("request_id", requestId)
        return runAction("/v1/runs/$runId/approval", payload)
    }

    // ── Active-run bookkeeping (prefs; survives process death) ──
    // sessionId → runId for every run the server may still be finishing.
    // On app start RunController re-attaches from this map: poll status,
    // finalize the bubble, notify. This is what makes turns durable even if
    // the whole app was killed.

    fun activeRunId(sessionId: String): String? =
        prefs.getString("active_run:$sessionId", null)?.takeIf { it.isNotBlank() }

    fun saveActiveRun(sessionId: String, runId: String) {
        prefs.edit().putString("active_run:$sessionId", runId).apply()
    }

    fun clearActiveRun(sessionId: String) {
        prefs.edit().remove("active_run:$sessionId").apply()
    }

    fun sessionsWithActiveRuns(): List<String> =
        prefs.all.keys.filter { it.startsWith("active_run:") }
            .map { it.removePrefix("active_run:") }

    /** Last observed SESSION-total usage per session — the per-turn token
     * delta is (new total - last total); runs report session totals, not
     * per-turn usage like the old chat-completions frame. */
    fun lastUsageTotals(sessionId: String): Pair<Long, Long> =
        prefs.getLong("usage_in:$sessionId", 0L) to prefs.getLong("usage_out:$sessionId", 0L)

    fun saveUsageTotals(sessionId: String, inputTotal: Long, outputTotal: Long) {
        prefs.edit()
            .putLong("usage_in:$sessionId", inputTotal)
            .putLong("usage_out:$sessionId", outputTotal)
            .apply()
    }

    /** Token Optimizer surface for the run path (same learner the legacy
     * stream uses — one verdict per model, no second state machine). */
    fun terseDecision(modelId: String): Boolean =
        terseOptimizer.shouldApply(modelId, isCaveman())

    fun terseObserve(modelId: String, completionTokens: Long, directiveApplied: Boolean) =
        terseOptimizer.observe(modelId, completionTokens, directiveApplied)

    // ─── Session fork (branch this chat from here) ───

    /** POST /api/sessions/{serverId}/fork — the server copies the transcript
     * into a NEW session id we choose (client-side UUID keeps the local id
     * scheme intact). Returns the new server id. */
    suspend fun forkSession(serverSessionId: String, newId: String, title: String): String? =
        withContext(Dispatchers.IO) {
            val baseUrl = (config ?: getConfig())?.baseUrl?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            try {
                val payload = JSONObject().put("id", newId).put("title", title)
                val request = Request.Builder()
                    .url("$baseUrl/api/sessions/$serverSessionId/fork")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    JSONObject(r.body?.string() ?: return@use null)
                        .optJSONObject("session")?.optString("id", "")?.ifBlank { null }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        }

    // ─── Cron jobs + skills (server truth for the Settings screens) ───

    suspend fun listJobs(): JSONArray? = withContext(Dispatchers.IO) {
        val baseUrl = (config ?: getConfig())?.baseUrl?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        try {
            val request = Request.Builder().url("$baseUrl/api/jobs?include_disabled=true").get().build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                JSONObject(r.body?.string() ?: return@use null).optJSONArray("jobs")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    suspend fun jobAction(jobId: String, action: String): Boolean =
        runAction("/api/jobs/$jobId/$action", JSONObject())

    suspend fun listSkills(): JSONArray? = withContext(Dispatchers.IO) {
        val baseUrl = (config ?: getConfig())?.baseUrl?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        try {
            val request = Request.Builder().url("$baseUrl/v1/skills").get().build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body?.string() ?: return@use null
                val o = JSONObject(body)
                o.optJSONArray("skills") ?: o.optJSONArray("data") ?: o.optJSONArray("items")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    // ─── Hermes update (plugin: /api/mobile/update/*) ───────────
    /** GET /api/mobile/update/check — report-only; wraps `hermes update --check`. */
    suspend fun updateCheck(fresh: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        val baseUrl = (config ?: getConfig())?.baseUrl?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        try {
            val url = "$baseUrl/api/mobile/update/check" + if (fresh) "?fresh=1" else ""
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                JSONObject(r.body?.string() ?: return@use null)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    /** POST /api/mobile/update/apply — detached `hermes update` + gateway
     * restart on the server. Returns accepted, not completion: the caller
     * re-checks health + update/check to confirm the swap. */
    suspend fun updateApply(): JSONObject? = withContext(Dispatchers.IO) {
        val baseUrl = (config ?: getConfig())?.baseUrl?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/mobile/update/apply").post("{}".toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { r ->
                JSONObject(r.body?.string() ?: return@use null)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    // ─── Server-driven slash commands (plugin: /api/mobile/commands) ───

    /** The SAME command set Telegram's setMyCommands renders (core registry +
     * plugin + skill commands). Null when the plugin route is absent (older
     * install) — the app then falls back to its hardcoded list. */
    suspend fun fetchServerCommands(): List<ServerCommand>? = withContext(Dispatchers.IO) {
        val obj = getJson("/api/mobile/commands") ?: return@withContext null
        if (!obj.optBoolean("ok", false)) return@withContext null
        val arr = obj.optJSONArray("commands") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val name = c.optString("name", "")
                if (name.isBlank()) continue
                add(ServerCommand(
                    name = name,
                    description = c.optString("description", ""),
                    category = c.optString("category", ""),
                    argsHint = c.optString("args_hint", ""),
                    source = c.optString("source", "core")
                ))
            }
        }
    }

    /** Expand a skill slash command into the invocation message the gateway
     * injects for Telegram. Returns null for core/unknown commands (the app
     * runs those with its local handlers) — the caller then sends the raw
     * text to the agent as before. */
    suspend fun resolveSkillCommand(command: String, args: String): String? =
        withContext(Dispatchers.IO) {
            val baseUrl = (config ?: getConfig())?.baseUrl?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            try {
                val payload = JSONObject().put("command", command).put("args", args)
                val request = Request.Builder()
                    .url("$baseUrl/api/mobile/command/resolve")
                    .post(payload.toString().toRequestBody(jsonMediaType)).build()
                client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val o = JSONObject(r.body?.string() ?: return@use null)
                    if (!o.optBoolean("ok", false)) null
                    else o.optString("expanded", "").takeIf { it.isNotBlank() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
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

    // ─── File Upload ───

    suspend fun uploadFile(file: java.io.File, fileName: String, mimeType: String, sessionId: String = ""): Pair<String, String>? {
        // (relative download URL, absolute server path) — the path rides the
        // media note so the agent can read the file directly (Telegram parity).
        val baseUrl = config?.baseUrl ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, file.asRequestBody(mimeType.toMediaTypeOrNull()))
                    .build()
                // hermes-mobile-qr plugin route: /api/audio/upload?session_id=<sid>
                // -> {url:"/api/audio/download/<sid>/<stored>", path:"..."}. The URL is
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
                        val url = json.optString("url").takeIf { it.isNotEmpty() }
                        val path = json.optString("path")
                        url?.let { it to path }
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

/** A slash command as the gateway reports it: core/plugin/skill tier,
 * the description the Telegram menu shows, and an arg hint so the app
 * can tell "runs now" from "needs <arg>". */
data class ServerCommand(
    val name: String,
    val description: String,
    val category: String,
    val argsHint: String,
    val source: String
)
