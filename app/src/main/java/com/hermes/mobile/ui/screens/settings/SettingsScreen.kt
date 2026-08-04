package com.hermes.mobile.ui.screens.settings

import android.content.Intent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.auth.AuthManager
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

data class SettingsUiState(
    val baseUrl: String = "http://localhost:8080",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val errorDetail: String? = null,
    val isDarkTheme: Boolean = false,
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
    // app into the user's web-registered account
    val claimToken: String = "",
    // Bridge API key (from hermes://connect payload) — persisted for refresh
    val apiKey: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: HermesRepository,
    val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
        // Observe auth state
        viewModelScope.launch {
            authManager.isLoggedIn.collect { loggedIn ->
                _uiState.update {
                    it.copy(
                        isLoggedIn = loggedIn,
                        loggedInEmail = if (loggedIn) authManager.getEmail() else ""
                    )
                }
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
    fun setClaimToken(token: String) { _uiState.update { it.copy(claimToken = token) } }
    fun setApiKey(key: String) { _uiState.update { it.copy(apiKey = key) } }

    /**
     * Sign in as the user who registered on the web setup page, via the
     * claim token carried by the scanned QR. Claim failures are surfaced,
     * NEVER silently replaced by a device account (that caused the wrong
     * email showing). Only a plain pairing QR (no claim) falls back to the
     * auto-registered device account.
     */
    fun signInAfterPairing() {
        val base = _uiState.value.baseUrl.trimEnd('/')
        if (base.isBlank()) return
        val claim = _uiState.value.claimToken
        if (claim.isNotBlank()) {
            viewModelScope.launch {
                authManager.claimAccount(base, claim)
                    .onSuccess {
                        // Claim is reusable until expiry — keep it so a later
                        // re-scan after logout still signs into this account.
                        _uiState.update { it.copy(authError = null) }
                    }
                    .onFailure { e ->
                        // Expired/invalid claim: tell the user to get a fresh
                        // QR from the setup page — do NOT create a device
                        // account silently.
                        _uiState.update {
                            it.copy(
                                authError = "Sign-in QR expired — reopen the setup page for a fresh QR (${e.message ?: "claim rejected"})"
                            )
                        }
                    }
            }
        } else {
            autoRegisterDeviceIfNeeded()
        }
    }

    /**
     * After a successful QR pairing, auto-create a device account so the
     * bridge is usable immediately. Credentials are generated with
     * SecureRandom and stored ENCRYPTED (SecurePrefs). Failure is silent —
     * the user can still register manually in Settings.
     */
    fun autoRegisterDeviceIfNeeded() {
        if (authManager.isLoggedIn.value) return
        val base = _uiState.value.baseUrl.trimEnd('/')
        if (base.isBlank()) return
        viewModelScope.launch {
            try {
                val random = java.security.SecureRandom()
                val email = "device-${java.lang.Long.toHexString(random.nextLong()).take(8)}@hermesbridge.app"
                // 32 hex chars from a CSPRNG — no dictionary, no pattern.
                val password = buildString {
                    repeat(32) { append("0123456789abcdef"[random.nextInt(16)]) }
                }
                authManager.register(base, email, password)
                    .onSuccess {
                        repository.saveDeviceCredentials(email, password)
                    }
                    .onFailure { _ ->
                        // Don't block pairing — manual register/login still available.
                    }
            } catch (_: Exception) {
                // Never let auto-registration break the connection flow.
            }
        }
    }

    fun register() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(authError = "Email and password are required") }
            return
        }
        if (state.password.length < 8) {
            _uiState.update { it.copy(authError = "Password must be at least 8 characters") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authError = null) }
            val result = authManager.register(state.baseUrl.trimEnd('/'), state.email, state.password)
            result.onFailure { e: Throwable ->
                _uiState.update { it.copy(authError = e.message ?: "Registration failed") }
            }
            _uiState.update { it.copy(isAuthLoading = false, password = "") }
        }
    }

    fun login() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(authError = "Email and password are required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authError = null) }
            val result = authManager.login(state.baseUrl.trimEnd('/'), state.email, state.password)
            result.onFailure { e: Throwable ->
                _uiState.update { it.copy(authError = e.message ?: "Login failed") }
            }
            _uiState.update { it.copy(isAuthLoading = false, password = "") }
        }
    }

    fun logout() {
        authManager.logout()
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
                "https://$rawUrl"
            } else {
                rawUrl
            }
            val config = ServerConfig(baseUrl = normalizedUrl)
            try {
                val connected = repository.checkConnectionRaw(config)
                _uiState.update {
                    if (connected) it.copy(connectionStatus = ConnectionStatus.CONNECTED, errorDetail = null)
                    else it.copy(connectionStatus = ConnectionStatus.ERROR, errorDetail = "Server returned error status")
                }
                // Only persist the URL once the connection actually works
                if (connected) {
                    repository.saveConfig(
                        ServerConfig(
                            baseUrl = normalizedUrl,
                            apiKey = _uiState.value.apiKey,
                            setupToken = _uiState.value.setupToken,
                        )
                    )
                    // Pairing done → sign in: claim QR (web-registered user)
                    // first, else auto-create a device account.
                    signInAfterPairing()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(connectionStatus = ConnectionStatus.ERROR, errorDetail = e.message ?: "Unknown error")
                }
            }
        }
    }

    /** Re-fetch the bridge's preferred URL (Tailscale-first) from /setup/connect. */
    fun refreshFromBridge() {
        val current = _uiState.value.baseUrl.trimEnd('/')
        if (current.isBlank() || current == "http://localhost:8080") {
            _uiState.update { it.copy(authError = "Enter your current server URL first, then refresh") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authError = null) }
            try {
                // Network must run on IO dispatcher — main-thread HTTP throws
                // NetworkOnMainThreadException (which has a NULL message → "Refresh failed: null").
                val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // Auth via bridge API key (from QR) or JWT — persists across restarts
                    val bearer = _uiState.value.apiKey.ifBlank { authManager.getToken().orEmpty() }
                    val setupUrl = "$current/setup/connect" +
                        if (_uiState.value.setupToken.isNotBlank()) "?token=${_uiState.value.setupToken}" else ""
                    val conn = java.net.URL(setupUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("Authorization", "Bearer $bearer")
                    try {
                        val code = conn.responseCode
                        if (code == 200) {
                            val body = conn.inputStream.bufferedReader().readText()
                            org.json.JSONObject(body).optString("url", "").trimEnd('/')
                        } else {
                            throw java.io.IOException("Bridge returned HTTP $code")
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                val preferredUrl = result
                if (preferredUrl.isNotBlank()) {
                    _uiState.update { it.copy(baseUrl = preferredUrl) }
                    repository.saveConfig(
                        ServerConfig(
                            baseUrl = preferredUrl,
                            apiKey = _uiState.value.apiKey,
                            setupToken = _uiState.value.setupToken,
                        )
                    )
                    _uiState.update { it.copy(authError = "Connected via ${preferredUrl.removePrefix("http://").removePrefix("https://")}") }
                    testConnection()
                } else {
                    _uiState.update { it.copy(authError = "Bridge did not return a URL") }
                }
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                val msg = when {
                    e is java.net.UnknownHostException -> "Can't reach bridge — the saved URL is stale. Scan the QR code again for the current URL."
                    e is java.net.SocketTimeoutException -> "Bridge timed out — the saved URL may be stale. Scan the QR code again."
                    else -> "Refresh failed: $detail. Scan the QR code again for the current URL."
                }
                _uiState.update { it.copy(authError = msg) }
            }
            _uiState.update { it.copy(isAuthLoading = false) }
        }
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
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    // QR result handler
    fun handleQrResult(scanned: String, vm: SettingsViewModel) {
        val uri = Uri.parse(scanned)
        val url = when {
            scanned.startsWith("hermes://connect") -> {
                val directUrl = uri.getQueryParameter("url")
                if (!directUrl.isNullOrBlank()) {
                    directUrl.trimEnd('/')
                } else {
                    val host = uri.getQueryParameter("host") ?: ""
                    val port = uri.getQueryParameter("port") ?: "9119"
                    "http://$host:$port"
                }
            }
            scanned.startsWith("http://") || scanned.startsWith("https://") -> {
                scanned.trimEnd('/')
            }
            else -> scanned
        }
        // Capture the one-time setup token for /setup/connect refresh
        val setup = uri.getQueryParameter("setup")
        if (!setup.isNullOrBlank()) {
            vm.setSetupToken(setup)
        }
        // One-time claim token — present only on post-registration claim QRs;
        // signs the app into the user's web-registered account.
        val claim = uri.getQueryParameter("claim")
        if (!claim.isNullOrBlank()) {
            vm.setClaimToken(claim)
        }
        // Capture the bridge API key so refresh works after app restarts
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

            // ─── 1. CONNECTION (primary section — QR-first setup) ───
            SettingsSection("Connection") {
                ConnectionStatusHeader(
                    status = uiState.connectionStatus,
                    baseUrl = uiState.baseUrl,
                    errorDetail = uiState.errorDetail
                )

                // Primary action: scan QR to auto-configure
                Button(
                    onClick = { showQrDialog = true },
                    enabled = uiState.connectionStatus != ConnectionStatus.CONNECTING,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HermesPrimary)
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan QR Code")
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Secondary: manual URL entry + test
                OutlinedTextField(
                    value = uiState.baseUrl,
                    onValueChange = { viewModel.updateBaseUrl(it) },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://localhost:9119") },
                    leadingIcon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.testConnection() },
                    enabled = uiState.connectionStatus != ConnectionStatus.CONNECTING,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.connectionStatus == ConnectionStatus.CONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Test Connection")
                }

                // Refresh from bridge (re-fetches preferred URL — Tailscale-first)
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { viewModel.refreshFromBridge() },
                    enabled = uiState.connectionStatus != ConnectionStatus.CONNECTING,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Refresh URL from Bridge")
                }
                uiState.authError?.let { err ->
                    Text(
                        text = err,
                        color = ErrorRed.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── 2. SECURE CONNECTION (Tailscale) ───
            SettingsSection("Secure Connection") {
                val tailscaleInstalled = remember {
                    try {
                        context.packageManager.getPackageInfo("com.tailscale.ipn", 0)
                        true
                    } catch (_: Exception) { false }
                }
                // Real signal: are we connected THROUGH Tailscale right now?
                val onTailscale = remember(uiState.baseUrl, uiState.connectionStatus) {
                    uiState.baseUrl.startsWith("http://100.") &&
                        uiState.connectionStatus == ConnectionStatus.CONNECTED
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = if (onTailscale) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = when {
                                onTailscale -> "Connected via Tailscale"
                                tailscaleInstalled -> "Tailscale installed"
                                else -> "Tailscale not installed"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when {
                                onTailscale -> "Direct P2P connection to your bridge"
                                tailscaleInstalled -> "Sign in with the same account as the bridge device"
                                else -> "Install the app to get a direct P2P connection"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (!tailscaleInstalled) {
                            // Open Play Store install page
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.tailscale.ipn"))
                                )
                            } catch (_: Exception) {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.tailscale.ipn"))
                                )
                            }
                        } else {
                            // Open Tailscale app (it shows the actual sign-in state)
                            try {
                                context.startActivity(
                                    context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                                )
                            } catch (_: Exception) {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://login.tailscale.com/start"))
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HermesPrimary)
                ) {
                    Icon(
                        if (onTailscale) Icons.Filled.CheckCircle else Icons.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        when {
                            onTailscale -> "Tailscale Active"
                            tailscaleInstalled -> "Open Tailscale"
                            else -> "Install Tailscale"
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── 3. ACCOUNT ───
            SettingsSection("Account") {
                if (uiState.isLoggedIn) {
                    // Logged-in state — compact
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Signed in",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                uiState.loggedInEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.logout() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Log Out")
                    }
                } else {
                    // Login / Register form
                    Text(
                        "Sign in to sync sessions across devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    OutlinedTextField(
                        value = uiState.email,
                        onValueChange = { viewModel.updateEmail(it) },
                        label = { Text("Email") },
                        placeholder = { Text("you@example.com") },
                        leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.password,
                        onValueChange = { viewModel.updatePassword(it) },
                        label = { Text("Password") },
                        placeholder = { Text("Min 8 characters") },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { viewModel.togglePasswordVisibility() }) {
                                Icon(
                                    if (uiState.showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        singleLine = true,
                        visualTransformation = if (uiState.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Auth error
                    uiState.authError?.let { err ->
                        Text(
                            text = err,
                            color = ErrorRed.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.login() },
                            enabled = !uiState.isAuthLoading && uiState.email.isNotBlank() && uiState.password.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HermesPrimary)
                        ) {
                            if (uiState.isAuthLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            }
                            Text("Log In")
                        }
                        OutlinedButton(
                            onClick = { viewModel.register() },
                            enabled = !uiState.isAuthLoading,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Register") }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── 4. APPEARANCE ───
            SettingsSection("Appearance") {
                SettingsToggle(
                    icon = Icons.Filled.DarkMode,
                    title = "Dark Theme",
                    subtitle = if (uiState.isDarkTheme) "Dark mode active" else "Light mode active",
                    checked = uiState.isDarkTheme,
                    onCheckedChange = { viewModel.toggleTheme() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── 5. ABOUT ───
            SettingsSection("About") {
                SettingsInfoRow("Version", LocalContext.current.let { ctx ->
                    try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" }
                    catch (_: Exception) { "?" }
                })
                SettingsInfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                SettingsInfoRow("Android", Build.VERSION.RELEASE)
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://hermes-agent.nousresearch.com")))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Visit Hermes Website")
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
            title = { Text("Setup Guide — End to End") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // Prerequisite section FIRST — Tailscale must be up on
                    // both devices before anything else works.
                    SetupHelpSection(
                        title = "✅ 0. Prerequisite — Tailscale (both devices)",
                        steps = listOf(
                            "Install the free Tailscale app on BOTH the server machine and this phone",
                            "Install from tailscale.com/download (Windows / macOS / Linux / Android)",
                            "Sign in BOTH devices to the SAME Tailscale account and enable the VPN",
                            "Each device gets a 100.x address — that's the secure P2P link to your bridge",
                            "Verify both show online in the Tailscale app before continuing"
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SetupHelpSection(
                        title = "🖥 1. Server-side (on the machine with Hermes Agent)",
                        steps = listOf(
                            "One-line installer (works on Windows/macOS/Linux/Android):",
                            "   curl -fsSL https://raw.githubusercontent.com/tawaresachin/hermes-mobile-bridge/main/install.py | python3 -",
                            "Or use the built-in command:  hermes mobile-serve",
                            "The installer starts the bridge; the console prints a pairing URL",
                            "On that computer, open the printed URL:  http://100.x.x.x:9119/setup?token=…",
                            "Register or log in — the page then shows your 15-min sign-in QR"
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SetupHelpSection(
                        title = "📱 2. App-side (this phone)",
                        steps = listOf(
                            "Install the Hermes Mobile APK (v2.19+)",
                            "Open Settings → tap 'Scan QR Code'",
                            "Aim the camera at the QR shown on the setup page",
                            "The app auto-configures the server URL + key",
                            "It signs in as your registered account — Chat, Voice & Sessions unlock"
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SetupHelpSection(
                        title = "⚠️ Tips & troubleshooting",
                        steps = listOf(
                            "The sign-in QR is valid for 15 minutes after you register on the page",
                            "Expired QR? Reopen the setup page, log in again — a fresh QR appears",
                            "Connection shows 'Connected via Tailscale' when the P2P link is live",
                            "Not connecting? Confirm BOTH devices are online in Tailscale",
                            "Log out → Chat/Voice/Sessions lock until you sign in again"
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
                            setPrompt("Scan Hermes Bridge QR code")
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
    errorDetail: String?
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
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = HermesPrimary,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
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
    onCheckedChange: (Boolean) -> Unit
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HermesPrimary,
                checkedTrackColor = HermesPrimary.copy(alpha = 0.3f)
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
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
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
