package com.hermes.mobile.ui.screens.settings

import android.net.Uri
import java.net.URI
import java.util.Locale
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.model.ConnectionStatus
import com.hermes.mobile.data.model.ServerConfig
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.network.FetchResult
import com.hermes.mobile.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Server-side Hermes update state (Settings → Server → Update card). */
data class UpdatePanelState(
    val loading: Boolean = false,
    val supported: Boolean = false,
    val upToDate: Boolean = false,
    val behind: Int? = null,
    val currentSha: String = "",
    val latestSha: String = "",
    val branch: String = "",
    val applying: Boolean = false,
    val error: String? = null,
    val installedVersion: String = "",
)


data class SettingsUiState(
    val baseUrl: String = "http://localhost:8642",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val errorDetail: String? = null,
    val isDarkTheme: Boolean = false,
    // Keep Computer Awake (platform-generic: works on any host OS)
    val keepAwake: Boolean = false,
    val awakeMechanism: String? = null,
    // Server plugin identity (protocol handshake)
    val pluginVersion: String = "",
    val pluginProtocol: Int = 0,
    val pluginNeedsUpdate: Boolean = false,
    // Preferences
    // Usage stats
    val sessionsCount: Int = 0,
    val messagesCount: Int = 0,
    val tokensUsed: Long = 0,
    val caveman: Boolean = false,
    val autoApprove: Boolean = false,
    // Cron jobs + skills (server truth, loaded on demand)
    val jobs: List<org.json.JSONObject> = emptyList(),
    val skills: List<Pair<String, String>> = emptyList(),  // name -> description
    val serverListsLoading: Boolean = false,
    val serverListsLoaded: Boolean = false,
    // Non-null when nothing was ever loaded and the last attempt failed; the
    // text carries the HTTP reason (rejected key vs missing route), so the
    // panel doesn't blame an old plugin for a 401.
    val serverListsError: String? = null,
    // Model providers (server-side custom endpoints)
    val providers: List<org.json.JSONObject> = emptyList(),
    val providersLoading: Boolean = false,
    val providersLoaded: Boolean = false,
    val providersError: String? = null,
    // True when the numbers came from the server ledger, false = local fallback.
    val usageIsServer: Boolean = true,
    // Auth fields
    val email: String = "",
    val password: String = "",
    val showPassword: Boolean = false,
    val isAuthLoading: Boolean = false,
    val authError: String? = null,
    val isLoggedIn: Boolean = false,
    val loggedInEmail: String = "",
    // QR setup token (from hermes://connect payload)
    val setupToken: String = "",
    // One-time claim token (from a post-registration claim QR) — signs the
    // app into the user's account
    // Bridge API key (from hermes://connect payload) — persisted for refresh
    val apiKey: String = "",
)

@HiltViewModel

class SettingsViewModel @Inject constructor(
    private val repository: HermesRepository,
    private val appUpdate: com.hermes.mobile.update.AppUpdateChecker,
) : ViewModel() {

    /** App self-update state (GitHub Releases, throttled 2x/day). */
    val appUpdateState: kotlinx.coroutines.flow.StateFlow<com.hermes.mobile.update.AppUpdateState> =
        appUpdate.state

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Diag-log upload result: null = idle, else status text ("Uploading…",
    // "Uploaded ✓", or failure). The share sheet opens after the attempt
    // regardless — upload is best-effort.
    private val _diagUploadResult = MutableStateFlow<String?>(null)
    val diagUploadResult: StateFlow<String?> = _diagUploadResult.asStateFlow()

    /** Upload the diag log to the gateway plugin, then return success (share follows). */
    suspend fun uploadDiagLogNow(device: String, version: String, log: String): Boolean {
        _diagUploadResult.value = "Uploading…"
        val ok = try {
            repository.uploadDiagLog(device, version, log)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        _diagUploadResult.value = if (ok) "Uploaded ✓" else "Upload failed — sharing anyway"
        return ok
    }

    // ─── Keep Computer Awake ───

    /** Load the host's keep-awake state when the Settings screen opens. */
    fun loadSystemStatus() {
        viewModelScope.launch {
            try {
                val st = repository.fetchSystemStatus()
                if (st != null) {
                    val compat = try { repository.checkPluginCompat() } catch (_: Exception) { null }
                    _uiState.update {
                        it.copy(
                            keepAwake = st.awake,
                            awakeMechanism = st.awakeMechanism,
                            pluginVersion = st.pluginVersion,
                            pluginProtocol = st.pluginProtocol,
                            pluginNeedsUpdate = compat?.needsUpdate == true,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }

    fun toggleKeepAwake() {
        val target = !_uiState.value.keepAwake
        // Optimistic flip; revert on failure.
        _uiState.update { it.copy(keepAwake = target) }
        viewModelScope.launch {
            val mech = try {
                repository.setKeepAwake(target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (mech == null && target) {
                // POST failed — flip back.
                _uiState.update { it.copy(keepAwake = false) }
            } else {
                // Use the mechanism straight from the POST response — no
                // second fetch, no stale "(null)" from the initial load.
                _uiState.update { it.copy(awakeMechanism = mech) }
            }
        }
    }

    init {
        // Load saved config
        val saved = repository.getSavedConfig()
        if (saved != null) {
            _uiState.update {
                it.copy(
                    baseUrl = saved.baseUrl,
                    setupToken = saved.setupToken,
                    apiKey = saved.apiKey,
                )
            }
        }
        // Load dark theme
        if (repository.hasDarkThemePreference()) {
            _uiState.update { it.copy(isDarkTheme = repository.isDarkTheme()) }
        }
        // Load usage stats
        loadUsageStats()
        _uiState.update { it.copy(caveman = repository.isCaveman(), autoApprove = repository.isAutoApprove()) }
        // Direct-API posture (v0.0.1+): "signed in" == a saved base URL + API
        // key. The old bridge JWT session is gone; do not resurrect it.
        viewModelScope.launch {
            repository.allSessions.collect {
                val cfg = repository.getSavedConfig()
                val paired = cfg != null && cfg.baseUrl.isNotBlank() &&
                    !cfg.apiKey.isNullOrBlank()
                _uiState.update { it.copy(isLoggedIn = paired) }
            }
        }
    }

    fun loadUsageStats() {
        viewModelScope.launch {
            // Server truth (session token ledger) when reachable; local Room
            // counts are only a fallback for offline pairing.
            val server = try { repository.getServerUsageStats() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (server != null) {
                _uiState.update {
                    it.copy(
                        sessionsCount = server.sessions,
                        messagesCount = server.messages,
                        tokensUsed = server.inputTokens + server.outputTokens,
                        usageIsServer = true
                    )
                }
                return@launch
            }
            val stats = repository.getUsageStats()
            _uiState.update {
                it.copy(
                    sessionsCount = stats.sessionsCount,
                    messagesCount = stats.messagesCount,
                    tokensUsed = stats.tokensUsed,
                    usageIsServer = false
                )
            }
        }
    }

    fun updateBaseUrl(url: String) { _uiState.update { it.copy(baseUrl = url) } }
    fun updateEmail(email: String) { _uiState.update { it.copy(email = email) } }
    fun updatePassword(pw: String) { _uiState.update { it.copy(password = pw) } }
    fun togglePasswordVisibility() { _uiState.update { it.copy(showPassword = !it.showPassword) } }
    fun clearAuthError() { _uiState.update { it.copy(authError = null) } }
    fun setError(msg: String) { _uiState.update { it.copy(authError = msg) } }
    fun setSetupToken(token: String) { _uiState.update { it.copy(setupToken = token) } }
    fun setApiKey(key: String) { _uiState.update { it.copy(apiKey = key) } }

    /** Log out = forget the pairing (URL + API key + tokens). */
    fun logout() {
        repository.clearSavedConnection()
        _uiState.update {
            it.copy(baseUrl = "", apiKey = "", setupToken = "",
                    isLoggedIn = false, connectionStatus = com.hermes.mobile.data.model.ConnectionStatus.DISCONNECTED)
        }
    }

    fun toggleCaveman(on: Boolean) {
        _uiState.update { it.copy(caveman = on) }
        repository.saveCaveman(on)
    }

    fun toggleAutoApprove(on: Boolean) {
        _uiState.update { it.copy(autoApprove = on) }
        repository.saveAutoApprove(on)
    }

    /** Throttled app-update check (cached verdict shown while inside
     * the 12h window). */
    fun checkAppUpdate() {
        // force=true: a manual tap must give visible feedback — a live check,
        // not a silent no-op inside the 12h throttle window.
        viewModelScope.launch { appUpdate.check(force = true) }
    }

    fun downloadAppUpdate() {
        viewModelScope.launch { appUpdate.downloadAndInstall() }
    }

    // ── Hermes update (server-side, plugin /api/mobile/update/*) ──
    private val _updateState = MutableStateFlow(UpdatePanelState())
    val updateState: StateFlow<UpdatePanelState> = _updateState.asStateFlow()

    /** Seed the About row instantly (no fetch): version + sha only. */
    fun loadInstalledVersion() {
        viewModelScope.launch {
            val o = try { repository.updateVersion() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            if (o != null) _updateState.update {
                it.copy(installedVersion = o.optString("version", it.installedVersion),
                    currentSha = o.optString("sha", it.currentSha))
            }
        }
    }

    fun loadUpdateInfo(fresh: Boolean = false) {
        viewModelScope.launch {
            _updateState.update { it.copy(loading = true, error = null) }
            val o = try { repository.updateCheck(fresh) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            if (o == null || !o.optBoolean("ok", false)) {
                _updateState.update {
                    it.copy(loading = false,
                        // A refused/failed check must not keep showing the
                        // Update button from an earlier live check: on this
                        // host the button no-ops forever (update refused,
                        // gateway restarts, version unchanged).
                        supported = false,
                        error = o?.optString("error")?.ifBlank { null }
                            ?: "Server does not expose update status (plugin too old?)")
                }
            } else {
                _updateState.update {
                    it.copy(
                        loading = false,
                        supported = true,
                        upToDate = o.optBoolean("up_to_date", false),
                        behind = if (o.isNull("behind")) null else o.optInt("behind"),
                        currentSha = o.optString("current_sha", ""),
                        latestSha = o.optString("latest_sha", ""),
                        branch = o.optString("branch", ""),
                        installedVersion = o.optString("installed_version", ""),
                    )
                }
            }
        }
    }

    /** Fire the detached update + gateway restart, then watch for the swap:
     * health goes down, comes back, and update/check reports up to date. */
    fun applyUpdate() {
        viewModelScope.launch {
            _updateState.update { it.copy(applying = true, error = null) }
            val res = try { repository.updateApply() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            if (res == null || !res.optBoolean("ok", false)) {
                _updateState.update {
                    it.copy(applying = false,
                        error = res?.optString("error")?.ifBlank { null } ?: "Could not start the update")
                }
                return@launch
            }
            // Server will drop (gateway restarts itself): wait out the down
            // window, then poll back up with a fresh check.
            delay(25_000)
            repeat(40) {
                val o = try { repository.updateCheck(fresh = true) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                if (o != null && o.optBoolean("ok", false)) {
                    _updateState.update {
                        it.copy(
                            applying = false, supported = true,
                            upToDate = o.optBoolean("up_to_date", false),
                            behind = if (o.isNull("behind")) null else o.optInt("behind"),
                            currentSha = o.optString("current_sha", ""),
                            latestSha = o.optString("latest_sha", ""),
                            branch = o.optString("branch", ""),
                            installedVersion = o.optString("installed_version", ""),
                        )
                    }
                    return@launch
                }
                delay(8_000)
            }
            _updateState.update {
                it.copy(applying = false, error = "Server still offline after ~5 min — check it manually")
            }
        }
    }

    /** Pull cron jobs + skills from the server (Settings → Server section).
     * One-shot on expand; failures leave the lists empty (never crash). */
    fun loadServerLists() {
        viewModelScope.launch {
            _uiState.update { it.copy(serverListsLoading = true) }
            val jobs = try { repository.listJobs() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { FetchResult(null, 0) }
            val skills = try { repository.listSkills() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { FetchResult(null, 0) }
            val loadedOk = jobs.body != null && skills.body != null
            _uiState.update {
                it.copy(
                    jobs = jobs.body?.let { arr -> (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) } } ?: it.jobs,
                    skills = skills.body?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.let { o ->
                                o.optString("name", "") to o.optString("description", "")
                            }
                        }.filter { it.first.isNotBlank() }
                    } ?: it.skills,
                    serverListsLoading = false,
                    serverListsLoaded = loadedOk || it.serverListsLoaded,
                    // Fresh failure with no previously loaded data → the empty
                    // lists are an artifact, not a fact. A reload after a
                    // successful load keeps showing the stale data.
                    serverListsError = when {
                        loadedOk || it.serverListsLoaded -> null
                        jobs.body == null -> gatewayError(jobs.status, "/api/jobs", skills.body != null)
                        else -> gatewayError(skills.status, "/api/mobile/skills", jobs.body != null)
                    },
                )
            }
        }
    }

    /** Say what the gateway actually answered. A 404 means the route is not
     * mounted (old hermes, or the gateway started before the plugin was
     * deployed) — restarting the gateway mounts it; a 401 means re-pair.
     * When [otherListLoaded] is true the plugin IS loaded (its other route
     * answered 200), so a 404 on this route means the deployed plugin
     * predates it — update the plugin, don't restart. A 200 whose body
     * lacked the expected key is reported as an empty list, not a mystery. */
    private fun gatewayError(status: Int, route: String, otherListLoaded: Boolean = false): String = when {
        status == 0 ->
            "Could not reach the gateway — check the server URL and network."
        status == 401 || status == 403 ->
            "Gateway rejected the API key (HTTP $status) — re-pair this device."
        status == 404 && otherListLoaded ->
            "The gateway has no $route route (HTTP 404) — the deployed plugin is older than this app's plugin routes; update the plugin."
        status == 404 ->
            "The gateway has no $route route (HTTP 404) — the hermes-mobile plugin isn't loaded; restart the gateway."
        status in 200..299 ->
            "The gateway answered $route (HTTP $status) with no data — the panel is empty, not broken."
        else ->
            "The gateway returned HTTP $status for $route."
    }

    // ─── Model providers (Settings card) ───

    fun loadProviders() {
        viewModelScope.launch {
            _uiState.update { it.copy(providersLoading = true, providersError = null) }
            val res = try { repository.providersList() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { FetchResult<org.json.JSONObject>(null, 0) }
            val o = res.body
            _uiState.update {
                if (o == null || !o.optBoolean("ok", false)) it.copy(
                    providersLoading = false,
                    providersError = o?.optString("error")?.ifBlank { null }
                        ?: gatewayError(res.status, "/api/mobile/providers"))
                else {
                    val arr = o.optJSONArray("endpoints")
                    val list = ArrayList<org.json.JSONObject>()
                    if (arr != null) for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                    it.copy(providers = list, providersLoading = false, providersLoaded = true)
                }
            }
        }
    }

    /** upsert: id="" creates. apiKey null = keep existing; "" clears. */
    fun saveProvider(id: String, name: String, baseUrl: String, apiKey: String?,
                     models: List<String>, asDefault: Boolean,
                     onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val body = org.json.JSONObject()
                .put("id", id).put("name", name).put("base_url", baseUrl)
                .put("models", org.json.JSONArray(models))
                .put("model", models.firstOrNull() ?: "")
                .put("make_default", asDefault)
            if (apiKey != null) body.put("api_key", apiKey)
            val o = try { repository.providersSave(body) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            val ok = o?.optBoolean("ok", false) == true
            if (ok) {
                repository.invalidateModelCache()
                val arr = o?.optJSONArray("endpoints")
                val list = ArrayList<org.json.JSONObject>()
                if (arr != null) for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                _uiState.update { it.copy(providers = list, providersError = null) }
            }
            onDone(ok, o?.optString("error")?.ifBlank { null }
                ?: if (o == null) "Could not reach the server" else null)
        }
    }

    fun deleteProvider(id: String, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val o = try { repository.providersDelete(id) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            val ok = o?.optBoolean("ok", false) == true
            if (ok) {
                repository.invalidateModelCache()
                _uiState.update { st -> st.copy(providers = st.providers.filterNot {
                    it.optString("id") == id }) }
            }
            onDone(ok, o?.optString("error")?.ifBlank { null }
                ?: if (o == null) "Could not reach the server" else null)
        }
    }

    fun activateProvider(id: String, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val o = try { repository.providersActivate(id) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            val ok = o?.optBoolean("ok", false) == true
            if (ok) {
                repository.invalidateModelCache()
                loadProviders()   // refresh DEFAULT chip placement
            }
            onDone(ok, o?.optString("error")?.ifBlank { null }
                ?: if (o == null) "Could not reach the server" else null)
        }
    }

    /** Live-probe base_url+key against the server's /models before saving. */
    fun validateProvider(baseUrl: String, apiKey: String?,
                         onDone: (ok: Boolean, reachable: Boolean,
                                  message: String, models: List<String>) -> Unit) {
        viewModelScope.launch {
            val body = org.json.JSONObject().put("base_url", baseUrl)
                .put("name", "validate").put("model", "validate")
            if (!apiKey.isNullOrBlank()) body.put("api_key", apiKey)
            val o = try { repository.providersValidate(body) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            if (o == null) onDone(false, false, "Could not reach the server", emptyList())
            else {
                val arr = o.optJSONArray("models")
                val list = ArrayList<String>()
                if (arr != null) for (i in 0 until arr.length()) list.add(arr.getString(i))
                onDone(o.optBoolean("ok", false), o.optBoolean("reachable", false),
                    o.optString("message"), list)
            }
        }
    }

    fun dismissProvidersError() {
        _uiState.update { it.copy(providersError = null) }
    }

    /** Surface a one-off provider mutation failure on the card. */
    fun providersErrorNow(msg: String) {
        _uiState.update { it.copy(providersError = msg) }
    }

    fun runJobNow(jobId: String) {
        viewModelScope.launch { repository.jobAction(jobId, "run"); loadServerLists() }
    }

    fun toggleJobPaused(jobId: String, paused: Boolean) {
        viewModelScope.launch {
            repository.jobAction(jobId, if (paused) "resume" else "pause")
            loadServerLists()
        }
    }

    val chatFontSp: kotlinx.coroutines.flow.StateFlow<Float> = repository.chatFontSp
    val chatFontManual: kotlinx.coroutines.flow.StateFlow<Boolean> = repository.chatFontManual
    fun setChatFont(sp: Float) {
        repository.setChatFont(sp)
    }

    fun resetChatFont() {
        repository.resetChatFontToAuto()
    }

    fun toggleTheme() {
        val newValue = !_uiState.value.isDarkTheme
        _uiState.update { it.copy(isDarkTheme = newValue) }
        repository.saveDarkTheme(newValue)
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(connectionStatus = ConnectionStatus.CONNECTING) }
            val rawUrl = _uiState.value.baseUrl.trimEnd('/')
            val normalizedUrl = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                "http://$rawUrl"
            } else {
                rawUrl
            }
            // SECURITY: cleartext http ships the Bearer key + chat content
            // unencrypted. QR pairing already restricts to private/tailnet
            // hosts; manual entry must pass the same gate, or a mistyped /
            // pasted public URL exfiltrates the key silently.
            if (normalizedUrl.startsWith("http://") && !isTrustedServerHost(normalizedUrl)) {
                _uiState.update { it.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    errorDetail = "Blocked: public http:// would send your API key unencrypted. Use a Tailscale/LAN address, or https://."
                ) }
                return@launch
            }
            val config = ServerConfig(baseUrl = normalizedUrl, apiKey = _uiState.value.apiKey)
            try {
                val connected = repository.checkConnectionRaw(config)
                if (!connected) {
                    // Distinguish the two failure classes honestly: a server
                    // that answers at all (even 401) is reachable — then the
                    // KEY is wrong; no answer at all is a URL/network problem.
                    val reachable = repository.isServerReachable(config)
                    _uiState.update {
                        if (reachable) it.copy(
                            connectionStatus = ConnectionStatus.ERROR,
                            errorDetail = "Server is reachable but rejected this API key. Re-scan the QR or fix the key."
                        ) else it.copy(
                            connectionStatus = ConnectionStatus.ERROR,
                            errorDetail = "Can't reach the server at this URL. Check the address/port (Tailscale IP:8642) and that the gateway is running."
                        )
                    }
                } else {
                    _uiState.update { it.copy(connectionStatus = ConnectionStatus.CONNECTED, errorDetail = null) }
                }
                // Only persist the URL once the connection actually works.
                // API key IS the credential — nothing else to sign in to.
                if (connected) {
                    repository.saveConfig(
                        ServerConfig(
                            baseUrl = normalizedUrl,
                            apiKey = _uiState.value.apiKey,
                            setupToken = _uiState.value.setupToken,
                        )
                    )
                    _uiState.update { it.copy(isLoggedIn = true) }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                val actionableMsg = when {
                    errorMsg.contains("timeout") -> "Connection timed out. Check the server URL and that the Hermes gateway is running."
                    errorMsg.contains("refused") -> "Connection refused. Is the Hermes gateway running at this address?"
                    errorMsg.contains("401") -> "API key rejected. Check the key in Settings → Account, or scan a fresh QR."
                    errorMsg.contains("403") -> "Access denied. Check your API key in Settings → Account."
                    else -> "Connection failed. Check the URL and API key, or scan a fresh QR."
                }
                _uiState.update {
                    it.copy(connectionStatus = ConnectionStatus.ERROR, errorDetail = actionableMsg)
                }
            }
        }
    }

    /** Re-test the saved connection (URL may have changed on the network). */
    fun refreshFromBridge() {
        val cfg = repository.getSavedConfig()
        if (cfg == null || cfg.baseUrl.isBlank() || cfg.baseUrl == "http://localhost:8642") {
            _uiState.update { it.copy(authError = "Enter your server URL first, then refresh") }
            return
        }
        testConnection()
    }

}

// Helper to format token counts

// ─── ViewModel ───

/** Private/Tailscale/LAN-only guard for server URLs (QR + manual entry).
 * Cleartext Bearer auth must never target a public host. Only loopback,
 * private (RFC1918), CGNAT/link-local IPv4, ULA + link-local IPv6, and a few
 * well-known private hostname namespaces are trusted; everything else — public
 * IPv4/IPv6, bare public hostnames — is rejected. So a Tailscale node in
 * `fd7a::/8` (ULA) or `100.64/10` (CGNAT) works, while a public IPv6 gateway
 * cannot be configured over cleartext. */
fun isTrustedServerHost(rawUrl: String): Boolean {
    // java.net.URI parses bracketed IPv6 uniformly across API levels;
    // android.net.Uri truncates it on API 26–28. Fall back to Uri for the inputs
    // Java rejects (relative, underscore hosts, unbracketed IPv6).
    val host = (try { java.net.URI(rawUrl).host } catch (_: Exception) { null })
        ?: try { Uri.parse(rawUrl).host } catch (_: Exception) { null }
        ?: return false
    val h = host.lowercase()
    if (h == "localhost" || h.endsWith(".local")) return true
    // Bare IPv4 literals: must be in private / CGNAT / link-local ranges.
    val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(h)
    if (ipv4 != null) {
        val o = ipv4.groupValues.drop(1).map { it.toInt() }
        if (o.any { it > 255 }) return false
        val (a, b) = o[0] to o[1]
        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 127 -> true
            a == 100 && b in 64..127 -> true  // Tailscale CGNAT
            a == 169 && b == 254 -> true      // link-local
            else -> false
        }
    }
    // IPv6 literals. Both Java and Android keep the bracketed form
    // (`[fd00::1]`), so strip it. Require a genuine hex/colon literal so a
    // hostname like `fd.example.com` can never fall into the ULA/link-local
    // branches. Accept loopback, link-local (fe80::/10) and ULA (fc00::/7 —
    // includes Tailscale's `fd7a::/8`); public IPv6 fails closed.
    val v6 = h.trim('[', ']')
    if (v6.isNotEmpty() && v6.all { it in '0'..'9' || it in 'a'..'f' || it == ':' }) {
        return when {
            v6 == "::1" -> true
            v6.startsWith("fe") && v6.length >= 3 && v6[2] in '8'..'b' -> true  // link-local
            v6.startsWith("fc") || v6.startsWith("fd") -> true                // ULA / Tailscale
            else -> false
        }
    }
    // Hostnames are trusted only in known-private namespaces (a whitelist, not
    // a scheme rule): Tailscale MagicDNS (`*.ts.net`) and a few LAN suffixes.
    // Any other hostname would ship the Bearer key to a public host, so it is
    // rejected outright. (QR pairing only ever emits a bare IP, handled above.)
    return h.endsWith(".ts.net") ||
        h.endsWith(".internal") || h.endsWith(".lan") ||
        h.endsWith(".home.arpa")
}


fun formatTokens(tokens: Long): String {
    if (tokens < 1000) return tokens.toString()
    return if (tokens < 1_000_000) {
        String.format(Locale.US, "%.1fK", tokens / 1000.0).replace(".0", "")
    } else if (tokens < 1_000_000_000) {
        String.format(Locale.US, "%.1fM", tokens / 1_000_000.0).replace(".0", "")
    } else {
        String.format(Locale.US, "%.1fB", tokens / 1_000_000_000.0).replace(".0", "")
    }
}
