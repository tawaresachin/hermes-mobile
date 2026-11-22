package com.hermes.mobile.ui.screens.settings

import android.net.Uri
import java.util.Locale
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.model.ConnectionStatus
import com.hermes.mobile.data.model.ServerConfig
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.network.FetchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
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

// Immutable view models: JSON is parsed off Main and never held in UiState
// (ME-9 / PE-3). JSONObject has unstable identity and repainted every row.
@Immutable
data class CapabilityItem(
    val name: String,
    val description: String,
    val detail: String,
    val enabled: Boolean? = null,
    val category: String = "",
    val essential: Boolean = false,
)

enum class CapabilitySectionStatus { IDLE, LOADED, FAILED }

@Immutable
data class CapabilitySectionState(
    val status: CapabilitySectionStatus = CapabilitySectionStatus.IDLE,
    val items: List<CapabilityItem> = emptyList(),
    val error: String? = null,
)

@Immutable
data class CapabilitiesState(
    val serverKey: String = "",
    val loading: Boolean = false,
    val plugins: CapabilitySectionState = CapabilitySectionState(),
    val mcpServers: CapabilitySectionState = CapabilitySectionState(),
    val projects: CapabilitySectionState = CapabilitySectionState(),
    val skills: CapabilitySectionState = CapabilitySectionState(),
) {
    /** True only when every section answered successfully (empty is fine). */
    val loaded: Boolean
        get() = plugins.status == CapabilitySectionStatus.LOADED &&
            mcpServers.status == CapabilitySectionStatus.LOADED &&
            projects.status == CapabilitySectionStatus.LOADED &&
            skills.status == CapabilitySectionStatus.LOADED
}

@Immutable
data class AgentProfile(
    val name: String,
    val displayName: String,
    val description: String,
    val model: String,
    val provider: String,
    val skillCount: Int,
    val hasEnv: Boolean,
    val isDefault: Boolean,
)

/** Pure mappers — no Android, no coroutines; unit-tested directly. */
internal fun parseCapabilityItems(body: JSONObject?, key: String): List<CapabilityItem> {
    val arr = body?.takeIf { it.optBoolean("ok", false) }?.optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val item = arr.optJSONObject(i) ?: return@mapNotNull null
        val name = item.optString("name").ifBlank { return@mapNotNull null }
        CapabilityItem(
            name = name,
            description = item.optString("description").ifBlank { item.optString("command") },
            detail = item.optString("version")
                .ifBlank { item.optString("transport") }
                .ifBlank { item.optString("path") },
            enabled = if (item.has("enabled")) item.optBoolean("enabled") else null,
            category = item.optString("category"),
            essential = item.optBoolean("essential", false),
        )
    }
}

private fun toAgentProfile(p: JSONObject): AgentProfile? {
    val name = p.optString("name").ifBlank { return null }
    return AgentProfile(
        name = name,
        displayName = p.optString("display_name").ifBlank { name },
        description = p.optString("description"),
        model = p.optString("model"),
        provider = p.optString("provider"),
        skillCount = p.optInt("skill_count"),
        hasEnv = p.optBoolean("has_env"),
        isDefault = p.optBoolean("is_default"),
    )
}

internal fun parseAgentProfiles(body: JSONObject?): List<AgentProfile> {
    val arr = body?.takeIf { it.optBoolean("ok", false) }?.optJSONArray("profiles")
        ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let(::toAgentProfile)
    }
}

internal fun parseActiveAgentProfile(body: JSONObject?): AgentProfile? {
    val p = body?.takeIf { it.optBoolean("ok", false) }?.optJSONObject("profile") ?: return null
    return toAgentProfile(p)
}

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
    val disabledSkills: Set<String> = emptySet(),
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
    // Server-scoped cards: typed, immutable, cleared on logout/re-pairing (MM-2)
    val profiles: List<AgentProfile> = emptyList(),
    val profilesLoading: Boolean = false,
    val profilesLoaded: Boolean = false,
    val profilesError: String? = null,
    val activeProfile: AgentProfile? = null,
    val capabilities: CapabilitiesState = CapabilitiesState(),
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

    // Single-flight + generation guards: a stale response from a previous
    // expand/reload/server must never overwrite the current one (ME-8).
    private var capabilitiesJob: Job? = null
    private var capabilitiesGeneration = 0
    private var profilesJob: Job? = null
    private var profilesGeneration = 0

    /** Forget everything that belongs to the previous server (MM-2). */
    private fun clearServerScopedState() {
        capabilitiesGeneration++
        capabilitiesJob?.cancel()
        capabilitiesJob = null
        profilesGeneration++
        profilesJob?.cancel()
        profilesJob = null
        _uiState.update {
            it.copy(
                profiles = emptyList(),
                activeProfile = null,
                profilesLoading = false,
                profilesLoaded = false,
                profilesError = null,
                capabilities = CapabilitiesState(),
                jobs = emptyList(),
                skills = emptyList(),
                disabledSkills = emptySet(),
                serverListsLoading = false,
                serverListsLoaded = false,
                serverListsError = null,
                providers = emptyList(),
                providersLoading = false,
                providersLoaded = false,
                providersError = null,
            )
        }
    }

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
        clearServerScopedState()
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

    /** Toggle a skill on the server. The switch is SERVER-truth: the
     *  local set is updated only AFTER the request succeeds, relative to
     *  the CURRENT state (a manifest refresh may have landed in flight).
     *  A rejection (unknown skill, essential skill, unreachable gateway,
     *  500) applies nothing — the switch stays as the server knows it. */
    fun toggleSkill(name: String, enabled: Boolean) {
        viewModelScope.launch {
            val ok = repository.toggleSkill(name, enabled)
            _uiState.update { st ->
                if (!ok) return@update st
                val disabled = st.disabledSkills.toMutableSet()
                if (enabled) disabled.remove(name) else disabled.add(name)
                st.copy(disabledSkills = disabled)
            }
        }
    }

    /** Toggle a skill from the Capabilities inspector. Replaces one immutable
     *  row — no JSONObject copy/round-trip on Main (ME-9). */
    fun toggleCapabilitySkill(name: String, enabled: Boolean) {
        viewModelScope.launch {
            val ok = repository.toggleSkill(name, enabled)
            if (!ok) return@launch
            _uiState.update { st ->
                val skills = st.capabilities.skills
                st.copy(
                    capabilities = st.capabilities.copy(
                        skills = skills.copy(
                            items = skills.items.map {
                                if (it.name == name) it.copy(enabled = enabled) else it
                            }
                        )
                    )
                )
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
            // The plugin manifest (≥ 0.0.40) carries per-skill `enabled` —
            // seed the switches from server truth. Core /v1/skills and older
            // plugins lack the flag → keep the local state (don't guess).
            val skillsParsed: Pair<List<Pair<String, String>>, Set<String>?>? =
                skills.body?.let { arr ->
                    val names = ArrayList<Pair<String, String>>()
                    val seedDisabled = LinkedHashSet<String>()
                    var carriesEnabled = false
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = o.optString("name", "")
                        if (name.isBlank()) continue
                        names.add(name to o.optString("description", ""))
                        if (o.has("enabled")) {
                            carriesEnabled = true
                            if (!o.optBoolean("enabled")) seedDisabled.add(name)
                        }
                    }
                    Pair(names, if (carriesEnabled) seedDisabled.toSet() else null)
                } ?: null
            _uiState.update {
                it.copy(
                    jobs = jobs.body?.let { arr -> (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) } } ?: it.jobs,
                    skills = skillsParsed?.first ?: it.skills,
                    disabledSkills = skillsParsed?.second ?: it.disabledSkills,
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

    // ─── Agent Profiles (Settings card) ───

    fun profilesErrorNow(msg: String) { _uiState.update { it.copy(profilesError = msg) } }

    /** Cancel an in-flight profile load when the card collapses (ME-8). */
    fun cancelProfilesLoad() {
        profilesGeneration++
        profilesJob?.cancel()
        profilesJob = null
        _uiState.update { it.copy(profilesLoading = false) }
    }

    /** Single-flight load: list + active in one background coroutine, parsed
     *  into immutable models off Main (ME-8 / ME-9). */
    fun loadAgentProfiles() {
        profilesJob?.cancel()
        val generation = ++profilesGeneration
        profilesJob = viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(profilesLoading = true, profilesError = null) }
            val res = try { repository.listAgentProfiles() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { FetchResult<JSONObject>(null, 0) }
            currentCoroutineContext().ensureActive()
            val active = try { repository.getActiveAgentProfile() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            if (generation != profilesGeneration) return@launch
            val o = res.body
            val ok = o?.optBoolean("ok", false) == true
            _uiState.update {
                if (!ok) it.copy(
                    profilesLoading = false,
                    profilesError = o?.optString("error")?.ifBlank { null }
                        ?: gatewayError(res.status, "/api/mobile/agent-profiles"))
                else it.copy(
                    profiles = parseAgentProfiles(o),
                    activeProfile = parseActiveAgentProfile(active),
                    profilesLoading = false,
                    profilesLoaded = true,
                )
            }
        }
    }

    /** Kept for existing callers; the list load also refreshes the active row. */
    fun loadActiveAgentProfile() { loadAgentProfiles() }

    /** Create a new agent profile. */
    fun createAgentProfile(name: String, displayName: String, description: String, model: String?, provider: String?, soul: String?,
                           onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val body = org.json.JSONObject()
                .put("name", name)
                .put("display_name", displayName)
                .put("description", description)
            if (model != null) body.put("model", model)
            if (provider != null) body.put("provider", provider)
            if (soul != null) body.put("soul", soul)
            val res = try { repository.createAgentProfile(body) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { FetchResult<org.json.JSONObject>(null, 0) }
            val o = res.body
            val ok = o?.optBoolean("ok", false) == true
            if (ok) {
                loadAgentProfiles()
            }
            onDone(ok, o?.optString("error")?.ifBlank { null }
                ?: gatewayError(res.status, "/api/mobile/agent-profiles"))
        }
    }

    /** Update an existing agent profile. */
    fun updateAgentProfile(name: String, displayName: String?, description: String?, model: String?, provider: String?,
                           onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val body = org.json.JSONObject()
            if (displayName != null) body.put("display_name", displayName)
            if (description != null) body.put("description", description)
            if (model != null) body.put("model", model)
            if (provider != null) body.put("provider", provider)
            val res = try { repository.updateAgentProfile(name, body) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { FetchResult<org.json.JSONObject>(null, 0) }
            val o = res.body
            val ok = o?.optBoolean("ok", false) == true
            if (ok) {
                loadAgentProfiles()
            }
            onDone(ok, o?.optString("error")?.ifBlank { null }
                ?: gatewayError(res.status, "/api/mobile/agent-profiles/$name"))
        }
    }

    /** Delete an agent profile. */
    fun deleteAgentProfile(name: String, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val res = try { repository.deleteAgentProfile(name) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { FetchResult<org.json.JSONObject>(null, 0) }
            val o = res.body
            val ok = o?.optBoolean("ok", false) == true
            if (ok) {
                loadAgentProfiles()
            }
            onDone(ok, o?.optString("error")?.ifBlank { null }
                ?: gatewayError(res.status, "/api/mobile/agent-profiles/$name"))
        }
    }

    /** Set the active agent profile. */
    fun setActiveAgentProfile(name: String, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val body = org.json.JSONObject().put("name", name)
            val res = try { repository.setActiveAgentProfile(body) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { FetchResult<org.json.JSONObject>(null, 0) }
            val o = res.body
            val ok = o?.optBoolean("ok", false) == true
            if (ok) loadAgentProfiles()
            onDone(ok, o?.optString("error")?.ifBlank { null }
                ?: gatewayError(res.status, "/api/mobile/agent-profiles/active"))
        }
    }

    /** Read SOUL.md for a profile. A failure is reported separately so an
     *  unreadable file is never shown as an empty document (ME-13). */
    fun loadAgentProfileSoul(name: String, onDone: (content: String?, error: String?) -> Unit) {
        viewModelScope.launch {
            val o = try { repository.getAgentProfileSoul(name) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            if (o != null && o.optBoolean("ok", false)) {
                onDone(o.optString("content", ""), null)
            } else {
                onDone(null, o?.optString("error")?.ifBlank { null }
                    ?: "Could not read SOUL.md for this profile.")
            }
        }
    }

    /** Write SOUL.md for a profile. */
    fun saveAgentProfileSoul(name: String, content: String, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val body = org.json.JSONObject().put("content", content)
            val res = try { repository.putAgentProfileSoul(name, body) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { FetchResult<org.json.JSONObject>(null, 0) }
            val o = res.body
            val ok = o?.optBoolean("ok", false) == true
            onDone(ok, o?.optString("error")?.ifBlank { null }
                ?: gatewayError(res.status, "/api/mobile/agent-profiles/$name/soul"))
        }
    }

    private data class CapabilityFetch(
        val items: List<CapabilityItem>,
        val ok: Boolean,
        val status: Int,
        val error: String?,
    )

    /** Cancel an in-flight capabilities load when the card collapses (ME-8). */
    fun cancelCapabilitiesLoad() {
        capabilitiesGeneration++
        capabilitiesJob?.cancel()
        capabilitiesJob = null
        _uiState.update { it.copy(capabilities = it.capabilities.copy(loading = false)) }
    }

    private suspend fun fetchCapabilitySection(
        route: String,
        arrayKey: String,
        fetch: suspend () -> FetchResult<JSONObject>,
    ): CapabilityFetch {
        val res = try { fetch() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { FetchResult<JSONObject>(null, 0) }
        currentCoroutineContext().ensureActive()
        val body = res.body
        val ok = body?.optBoolean("ok", false) == true
        return CapabilityFetch(
            items = if (ok) parseCapabilityItems(body, arrayKey) else emptyList(),
            ok = ok,
            status = res.status,
            error = body?.optString("error")?.ifBlank { null },
        )
    }

    /** Failure is a first-class section state, never an empty successful list. */
    private fun capabilityState(
        fetch: CapabilityFetch,
        route: String,
        anyOtherLoaded: Boolean,
    ): CapabilitySectionState = if (fetch.ok) {
        CapabilitySectionState(CapabilitySectionStatus.LOADED, fetch.items)
    } else {
        CapabilitySectionState(
            status = CapabilitySectionStatus.FAILED,
            error = fetch.error ?: gatewayError(fetch.status, route, anyOtherLoaded),
        )
    }

    private fun sectionFromAll(body: JSONObject, key: String, route: String): CapabilitySectionState =
        if (body.optJSONArray(key) != null) {
            CapabilitySectionState(CapabilitySectionStatus.LOADED, parseCapabilityItems(body, key))
        } else {
            CapabilitySectionState(
                status = CapabilitySectionStatus.FAILED,
                error = gatewayError(200, route, true),
            )
        }

    /** One fast `/capabilities/all` call; per-section calls run in parallel only
     *  as fallback so HTTP status can still be reported (ME-6 / ME-7). Parsing
     *  and mapping happen off Main; stale loads are dropped by generation (ME-8). */
    fun loadCapabilities() {
        capabilitiesJob?.cancel()
        val generation = ++capabilitiesGeneration
        capabilitiesJob = viewModelScope.launch(Dispatchers.Default) {
            val key = repository.getSavedConfig()?.baseUrl.orEmpty()
            _uiState.update { it.copy(capabilities = CapabilitiesState(serverKey = key, loading = true)) }

            val all = try { repository.getJson("/api/mobile/capabilities/all") }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()

            val routes = listOf(
                "/api/mobile/capabilities/plugins",
                "/api/mobile/capabilities/mcp-servers",
                "/api/mobile/capabilities/projects",
                "/api/mobile/capabilities/skills",
            )
            val keys = listOf("plugins", "mcp_servers", "projects", "skills")

            val next = if (all?.optBoolean("ok", false) == true) {
                CapabilitiesState(
                    serverKey = key,
                    plugins = sectionFromAll(all, keys[0], routes[0]),
                    mcpServers = sectionFromAll(all, keys[1], routes[1]),
                    projects = sectionFromAll(all, keys[2], routes[2]),
                    skills = sectionFromAll(all, keys[3], routes[3]),
                )
            } else {
                val results = listOf(
                    async { fetchCapabilitySection(routes[0], keys[0]) { repository.listCapabilitiesPlugins() } },
                    async { fetchCapabilitySection(routes[1], keys[1]) { repository.listCapabilitiesMcpServers() } },
                    async { fetchCapabilitySection(routes[2], keys[2]) { repository.listCapabilitiesProjects() } },
                    async { fetchCapabilitySection(routes[3], keys[3]) { repository.listCapabilitiesSkills() } },
                ).awaitAll()
                currentCoroutineContext().ensureActive()
                val anyLoaded = results.count { it.ok }
                CapabilitiesState(
                    serverKey = key,
                    plugins = capabilityState(results[0], routes[0], anyLoaded > 0),
                    mcpServers = capabilityState(results[1], routes[1], anyLoaded > 0),
                    projects = capabilityState(results[2], routes[2], anyLoaded > 0),
                    skills = capabilityState(results[3], routes[3], anyLoaded > 0),
                )
            }
            if (generation != capabilitiesGeneration || !isActive) return@launch
            _uiState.update { it.copy(capabilities = next.copy(loading = false)) }
        }
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

    /** Silent health check when Settings opens: keep the header honest without
     * waiting for the user to press Test (ME-1), mirroring Home's check. */
    fun checkSavedConnection() {
        viewModelScope.launch {
            val saved = repository.getSavedConfig()
            if (saved == null || saved.baseUrl.isBlank()) {
                _uiState.update { it.copy(connectionStatus = ConnectionStatus.DISCONNECTED) }
                return@launch
            }
            _uiState.update { it.copy(connectionStatus = ConnectionStatus.CONNECTING) }
            val status = repository.checkConnection(saved)
            _uiState.update { it.copy(connectionStatus = status) }
        }
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
                    // A new server means the cached profiles/capabilities belong
                    // to another machine (MM-2).
                    if (repository.getSavedConfig()?.baseUrl != normalizedUrl) {
                        clearServerScopedState()
                    }
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
