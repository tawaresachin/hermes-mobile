package com.hermes.mobile.ui.screens.settings

import com.hermes.mobile.BuildConfig
import android.content.Intent
import java.io.File
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.model.ConnectionStatus
import com.hermes.mobile.data.model.ServerConfig
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.components.HermesWatermark
import com.hermes.mobile.ui.theme.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ─── ViewModel ───

/** Private/Tailscale/LAN-only guard for server URLs (QR + manual entry).
 * Cleartext Bearer auth must never target a public host. */
fun isTrustedServerHost(rawUrl: String): Boolean {
    val host = try {
        Uri.parse(rawUrl).host?.lowercase() ?: return false
    } catch (_: Exception) {
        return false
    }
    if (host == "localhost" || host.endsWith(".local")) return true
    // Bare IPv4 literals: must be in private / CGNAT / link-local ranges.
    val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(host)
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
    // Hostnames: allow (personal tailnet names resolve to CGNAT IPs;
    // the DNS-rebinding residual is acceptable for this device).
    return true
}

data class SettingsUiState(
    val baseUrl: String = "http://localhost:8080",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val errorDetail: String? = null,
    val isDarkTheme: Boolean = false,
    // Keep Computer Awake (platform-generic: works on any host OS)
    val keepAwake: Boolean = false,
    val awakeMechanism: String? = null,
    // Preferences
    // Usage stats
    val sessionsCount: Int = 0,
    val messagesCount: Int = 0,
    val tokensUsed: Long = 0,
    val caveman: Boolean = false,
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
) : ViewModel() {

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
                    _uiState.update {
                        it.copy(keepAwake = st.awake, awakeMechanism = st.awakeMechanism)
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
        _uiState.update { it.copy(caveman = repository.isCaveman()) }
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

    val chatFontSp: kotlinx.coroutines.flow.StateFlow<Float> = repository.chatFontSp
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
        if (cfg == null || cfg.baseUrl.isBlank() || cfg.baseUrl == "http://localhost:8080") {
            _uiState.update { it.copy(authError = "Enter your server URL first, then refresh") }
            return
        }
        testConnection()
    }

}

// Helper to format token counts
fun formatTokens(tokens: Long): String {
    if (tokens < 1000) return tokens.toString()
    return if (tokens < 1_000_000) {
        String.format("%.1fK", tokens / 1000.0).replace(".0", "")
    } else if (tokens < 1_000_000_000) {
        String.format("%.1fM", tokens / 1_000_000.0).replace(".0", "")
    } else {
        String.format("%.1fB", tokens / 1_000_000_000.0).replace(".0", "")
    }
}

// ─── Screen ───

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    paddingValues: PaddingValues,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val chatFontSp by viewModel.chatFontSp.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    // Load the host computer's keep-awake state on screen open (idempotent,
    // silent when not connected yet).
    LaunchedEffect(Unit) { viewModel.loadSystemStatus() }

    /** Private/Tailscale/LAN-only guard for QR-derived server URLs. */
    fun isTrustedBridgeHost(rawUrl: String): Boolean = isTrustedServerHost(rawUrl)

    // QR result handler
    fun handleQrResult(scanned: String, vm: SettingsViewModel) {
        val uri = Uri.parse(scanned)
        val url = when {
            scanned.startsWith("hermes://connect") -> {
                // Plugin QR format: hermes://connect?url=<enc>&key=<enc>
                (uri.getQueryParameter("url") ?: "").trimEnd('/')
            }
            scanned.startsWith("http://") || scanned.startsWith("https://") -> {
                scanned.trimEnd('/')
            }
            else -> scanned
        }
        // SECURITY: only accept private/tailnet hosts from a QR — a phishing
        // QR (or a re-scanned screenshot) must not redirect credentials
        // (apiKey/claim/setup tokens) to an attacker's server.
        if (!isTrustedBridgeHost(url)) {
            vm.setError("Blocked host: only private/Tailscale/LAN addresses are accepted")
            return
        }
        // API key from the QR — the sole credential (direct API posture)
        val apiKey = uri.getQueryParameter("key")
        if (!apiKey.isNullOrBlank()) {
            vm.setApiKey(apiKey)
        }
        vm.updateBaseUrl(url)
        vm.testConnection()
    }

    // QR scanner launcher (camera)
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleQrResult(result.contents, viewModel)
        }
    }
    // QR image picker (gallery upload)
    val qrImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.viewModelScope.launch(Dispatchers.Default) {
                try {
                    // Decode OFF main thread and DOWNSAMPLED — a 12-48MP photo
                    // decoded at full size + IntArray(w*h) is 48-190MB and OOMs.
                    val bounds = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input, null, bounds)
                    }
                    var sample = 1
                    while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) {
                        sample *= 2
                    }
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sample
                    }
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input, null, opts)
                    }
                    if (bitmap != null) {
                        val w = bitmap.width
                        val h = bitmap.height
                        val pixels = IntArray(w * h)
                        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                        val source = com.google.zxing.RGBLuminanceSource(w, h, pixels)
                        val binaryBitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
                        val result = com.google.zxing.MultiFormatReader().decode(binaryBitmap)
                        if (result?.text != null) {
                            withContext(Dispatchers.Main) {
                                handleQrResult(result.text, viewModel)
                            }
                        }
                    }
                } catch (e: Exception) {
                    viewModel.setError("Failed to decode QR: ${e.message}")
                }
            }
        }
    }
    // QR mode selector dialog
    var showQrDialog by remember { mutableStateOf(false) }
    // E2E setup help dialog
    var showSetupHelp by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalDarkTheme provides uiState.isDarkTheme) {
        Box(modifier = Modifier.fillMaxSize()) {
        HermesWatermark()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                // E2E setup help — click to see the full pairing guide
                IconButton(onClick = { showSetupHelp = true }) {
                    Icon(
                        imageVector = Icons.Filled.HelpOutline,
                        contentDescription = "Setup help",
                        tint = HermesPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ─── 1. CONNECTION (merged — URL + QR + Test + Refresh) ───
            SettingsSection("Connection") {
                ConnectionStatusHeader(
                    status = uiState.connectionStatus,
                    baseUrl = uiState.baseUrl,
                    errorDetail = uiState.errorDetail,
                    onRefresh = { viewModel.refreshFromBridge() }
                )

                // Server URL field (no refresh icon here)
                OutlinedTextField(
                    value = uiState.baseUrl,
                    onValueChange = { viewModel.updateBaseUrl(it) },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://100.89.25.56:8642") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Action buttons: QR + Test side by side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // QR Code button
                    Button(
                        onClick = { showQrDialog = true },
                        enabled = uiState.connectionStatus != ConnectionStatus.CONNECTING,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HermesPrimary)
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("QR Code")
                    }

                    // Test Connection button
                    OutlinedButton(
                        onClick = { viewModel.testConnection() },
                        enabled = uiState.connectionStatus != ConnectionStatus.CONNECTING,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (uiState.connectionStatus == ConnectionStatus.CONNECTING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Test")
                        }
                    }
                }
                uiState.authError?.let { err ->
                    Text(
                        text = err,
                        color = ErrorRed.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── 3. ACCOUNT ───
            SettingsSection("Account") {
                if (uiState.isLoggedIn) {
                    // Logged-in state — show masked API key
                    var isApiKeyVisible by remember { mutableStateOf(false) }
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Connected via API Key", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    if (isApiKeyVisible) uiState.apiKey
                                    else uiState.apiKey.takeIf { it.startsWith("hermes-") }
                                        ?.let { "hermes-" + "•".repeat(16) }
                                        ?: "•".repeat(16),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                            TextButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                Icon(
                                    imageVector = if (isApiKeyVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = if (isApiKeyVisible) "Hide" else "Show",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.logout() }) {
                            Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Log Out")
                        }
                    }
                } else {
                    // Not logged in — show API key input
                    Text(
                        "Enter your API key to connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    var showApiKey by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = uiState.apiKey,
                        onValueChange = { viewModel.setApiKey(it) },
                        label = { Text("API Key") },
                        placeholder = { Text("hermes-xxxx...") },
                        leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                        trailingIcon = {
                            TextButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                    contentDescription = if (showApiKey) "Hide" else "Show",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.testConnection() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HermesPrimary)
                    ) {
                        Text("Connect")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── 6. PREFERENCES ───
            SettingsSection("Preferences") {
                // Chat text size: same key ChatViewModel reads live via prefs.
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.TextFormat, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Chat Text Size",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f))
                        Text("${chatFontSp.toInt()} sp",
                            style = MaterialTheme.typography.labelMedium,
                            color = HermesPrimary)
                    }
                    Slider(
                        value = chatFontSp,
                        onValueChange = { viewModel.setChatFont(it) },
                        valueRange = 12f..20f,
                        steps = 7,
                        colors = SliderDefaults.colors(
                            thumbColor = HermesPrimary,
                            activeTrackColor = HermesPrimary)
                    )
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { viewModel.resetChatFont() }) {
                            Text("Auto (device)")
                        }
                    }
                    Text("Applies instantly across the app",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SettingsToggle(
                    icon = Icons.Filled.Compress,
                    title = "Token Optimizer",
                    subtitle = if (uiState.caveman)
                        "Shorter replies from verbose models"
                    else "Normal replies. Auto-compression stays on",
                    checked = uiState.caveman,
                    onCheckedChange = { viewModel.toggleCaveman(it) }
                )
                SettingsToggle(
                    icon = Icons.Filled.PowerSettingsNew,
                    title = "Keep Computer Awake",
                    subtitle = if (uiState.keepAwake) {
                        "Holding the computer awake" +
                            (uiState.awakeMechanism?.let { " ($it)" } ?: "")
                    } else {
                        "Holds the host device awake while the gateway is busy"
                    },
                    checked = uiState.keepAwake,
                    onCheckedChange = { viewModel.toggleKeepAwake() }
                )
                SettingsToggle(
                    icon = Icons.Filled.DarkMode,
                    title = "Dark Theme",
                    subtitle = if (uiState.isDarkTheme) "Dark mode active" else "Light mode active",
                    checked = uiState.isDarkTheme,
                    onCheckedChange = { viewModel.toggleTheme() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── 7. USAGE ───
            SettingsSection("Usage") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            uiState.sessionsCount.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = HermesPrimary
                        )
                        Text("Sessions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            uiState.messagesCount.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = HermesPrimary
                        )
                        Text("Messages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            formatTokens(uiState.tokensUsed),
                            style = MaterialTheme.typography.headlineSmall,
                            color = HermesPrimary
                        )
                        Text("Tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Manual refresh + honest source note.
                    IconButton(
                        onClick = { viewModel.loadUsageStats() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh usage",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (uiState.usageIsServer)
                        "From the Hermes server token ledger"
                    else
                        "Local counts — server not reachable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── 8. ABOUT ───
            SettingsSection("About") {
                // Version row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Version",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "v${LocalContext.current.packageManager.getPackageInfo(LocalContext.current.packageName, 0).versionName ?: "?"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // Device row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "${Build.MANUFACTURER} ${Build.MODEL}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // OS row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Android",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = Build.VERSION.RELEASE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Plain text buttons (no box)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val diagUploadResult by viewModel.diagUploadResult.collectAsState()
                    val scope = rememberCoroutineScope()
                    TextButton(
                        onClick = {
                            val diagFile = File(context.filesDir, "diag.log")
                            val logText = if (diagFile.exists()) diagFile.readText() else "(no diag.log)"
                            val device = "${Build.MANUFACTURER} ${Build.MODEL}"
                            val version = try {
                                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                            } catch (_: Exception) { "?" }
                            val crashText = File(context.filesDir, "crashes").listFiles()
                                ?.filter { it.name.startsWith("crash_") }
                                ?.maxByOrNull { it.lastModified() }
                                ?.readText() ?: "(no crash dumps)"
                            val combined = buildString {
                                appendLine("=== DIAG LOG (last 24h) ===")
                                appendLine(logText)
                                appendLine()
                                appendLine("=== CRASH DUMP (newest) ===")
                                appendLine(crashText)
                            }
                            scope.launch {
                                viewModel.uploadDiagLogNow(device, version, logText)
                                try {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "Hermes log $version")
                                        putExtra(Intent.EXTRA_TEXT, combined)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Share logs"))
                                } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = HermesPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(diagUploadResult ?: "Share Logs", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = HermesPrimary)
                        }
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://hermes-agent.nousresearch.com")))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp), tint = HermesPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Website", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = HermesPrimary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── E2E setup help dialog (server prerequisites + app steps) ──
    if (showSetupHelp) {
        AlertDialog(
            onDismissRequest = { showSetupHelp = false },
            confirmButton = {
                Button(onClick = { showSetupHelp = false }) { Text("Got it") }
            },
            title = { Text("How to connect") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // Current direct-API flow (v0.0.1+): plugin QR -> 8642.
                    SetupHelpSection(
                        title = "1. On your computer",
                        steps = listOf(
                            "Install the plugin once:  pip install git+https://github.com/tawaresachin/hermes-mobile-plugin",
                            "Then run:  hermes-mobile-plugin install",
                            "Start Hermes:  hermes gateway run",
                            "Show the pairing QR:  hermes-mobile-plugin qr"
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SetupHelpSection(
                        title = "2. On this phone",
                        steps = listOf(
                            "Settings → tap 'QR Code' → scan the code on your computer",
                            "You can also pick a QR screenshot from the gallery",
                            "URL and key fill in by themselves",
                            "Tap 'Test' — green means you are connected"
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SetupHelpSection(
                        title = "3. Different Wi-Fi networks?",
                        steps = listOf(
                            "Same Wi-Fi: skip this step.",
                            "Different networks: install free Tailscale on BOTH devices",
                            "Sign in to the same Tailscale account, turn the VPN on",
                            "Scan the QR again — it will carry the new address"
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SetupHelpSection(
                        title = "Good to know",
                        steps = listOf(
                            "The QR contains your key — do not share it",
                            "Connection lost? On the computer run:  hermes-mobile-plugin qr",
                            "Log out clears the pairing; scan again to reconnect"
                        )
                    )
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // QR mode picker bottom sheet (Camera / Gallery)
    if (showQrDialog) {
        ModalBottomSheet(
            onDismissRequest = { showQrDialog = false },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Connect with QR",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
                HorizontalDivider()

                // Scan with camera
                Surface(
                    onClick = {
                        showQrDialog = false
                        val options = ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("Scan the Hermes QR code")
                            setBeepEnabled(false)
                            setOrientationLocked(false)
                            addExtra("SCAN_ORIENTATION", "portrait")
                        }
                        qrLauncher.launch(options)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = HermesPrimary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Scan with Camera", style = MaterialTheme.typography.bodyLarge)
                            Text("Point camera at the QR code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()

                // Choose from gallery
                Surface(
                    onClick = {
                        showQrDialog = false
                        qrImagePicker.launch("image/*")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = null, tint = HermesPrimary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Choose from Gallery", style = MaterialTheme.typography.bodyLarge)
                            Text("Pick a screenshot of the QR code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()

                // Cancel
                Surface(
                    onClick = { showQrDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        }
    }
}

// ─── Reusable Components ───

/** Prominent status header for the Connection section. */
@Composable
private fun ConnectionStatusHeader(
    status: ConnectionStatus,
    baseUrl: String,
    errorDetail: String?,
    onRefresh: () -> Unit
) {
    val (statusColor, statusText) = when (status) {
        ConnectionStatus.CONNECTED -> SuccessGreen to "Connected"
        ConnectionStatus.CONNECTING -> WarningAmber to "Testing..."
        ConnectionStatus.ERROR -> ErrorRed to "Connection Failed"
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant to "Not Connected"
    }

    // Human-readable route label (Tailscale / Tunnel / LAN)
    val routeLabel = remember(baseUrl) {
        when {
            baseUrl.startsWith("http://100.") -> "via Tailscale"
            baseUrl.contains("trycloudflare.com") -> "via Cloudflare Tunnel"
            baseUrl.startsWith("http://192.168.") || baseUrl.startsWith("http://10.") ||
                baseUrl.startsWith("http://172.16.") || baseUrl.startsWith("http://172.17.") ||
                baseUrl.startsWith("http://172.18.") || baseUrl.startsWith("http://172.19.") ||
                baseUrl.startsWith("http://172.2") -> "via Local Network"
            baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1") -> "on this device"
            else -> ""
        }
    }

    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (status == ConnectionStatus.CONNECTED && routeLabel.isNotBlank()) {
                        "$statusText $routeLabel"
                    } else statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
            }
            // Refresh icon next to status
            if (status != ConnectionStatus.CONNECTING) {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Test connection",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (errorDetail != null && status == ConnectionStatus.ERROR) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                errorDetail,
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        // Telegram style: small muted uppercase section label, then a flat
        // card with thin dividers between rows.
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                content = content
            )
        }
    }
}

@Composable
fun SettingsToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.6f else 0.38f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1.0f else 0.38f))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.6f else 0.38f))
        }
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HermesPrimary,
                checkedTrackColor = HermesPrimary.copy(alpha = 0.3f),
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        )
    }
}

@Composable
fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A titled block of numbered steps inside the E2E setup help dialog. */
@Composable
fun SetupHelpSection(title: String, steps: List<String>) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = HermesPrimary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    steps.forEachIndexed { index, step ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(HermesPrimary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = HermesPrimary
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = step,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
