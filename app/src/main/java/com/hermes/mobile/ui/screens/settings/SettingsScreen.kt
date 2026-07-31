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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.auth.AuthManager
import com.hermes.mobile.data.model.ConnectionStatus
import com.hermes.mobile.data.model.ServerConfig
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.theme.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    fun setApiKey(key: String) { _uiState.update { it.copy(apiKey = key) } }

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

    /** Discover bridge URL from the GitHub registry using email. */
    fun discoverBridge() {
        val state = _uiState.value
        if (state.email.isBlank()) {
            _uiState.update { it.copy(authError = "Enter your email first") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authError = null) }
            try {
                val registryUrl = "https://raw.githubusercontent.com/tawaresachin/hermes-bridge-registry/main/bridges.json"
                val json = fetchJson(registryUrl)
                val entries = json.optJSONArray("entries") ?: org.json.JSONArray()
                val enteredEmail = state.email.trim().lowercase()
                var found = false
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    if (entry.optString("email", "").trim().lowercase() == enteredEmail) {
                        val bridgeUrl = entry.optString("url", "")
                        if (bridgeUrl.isNotBlank()) {
                            _uiState.update { it.copy(baseUrl = bridgeUrl) }
                            val config = ServerConfig(baseUrl = bridgeUrl)
                            repository.saveConfig(config)
                            found = true
                        }
                        break
                    }
                }
                if (!found) {
                    _uiState.update { it.copy(authError = "No bridge found for this email. Enter URL manually or run 'hermes-bridge init' on your server.") }
                }
            } catch (e: Exception) {
                val msg = when {
                    e is java.net.UnknownHostException -> "Can't reach registry — enter URL manually"
                    e is java.net.SocketTimeoutException -> "Registry timed out — enter URL manually"
                    e is org.json.JSONException -> "Registry data error: ${e.message}"
                    e.message != null -> "Registry: ${e.message}"
                    else -> "Discovery failed — enter URL manually"
                }
                _uiState.update { it.copy(authError = msg) }
            }
            _uiState.update { it.copy(isAuthLoading = false) }
        }
    }

    /** Fetch JSON from a URL using HttpURLConnection. */
    private fun fetchJson(urlString: String): org.json.JSONObject {
        val url = java.net.URL(urlString)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                return org.json.JSONObject(body)
            }
            throw java.io.IOException("HTTP $code")
        } finally {
            conn.disconnect()
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
                // Go through the authenticated OkHttp client (JWT auto-attached),
                // so /setup/connect accepts us even after an app restart.
                val setupUrl = "$current/setup/connect" +
                    if (_uiState.value.setupToken.isNotBlank()) "?token=${_uiState.value.setupToken}" else ""
                val conn = java.net.URL(setupUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                // Auth via bridge API key (from QR) or JWT — persists across restarts
                val bearer = _uiState.value.apiKey.ifBlank { authManager.getToken().orEmpty() }
                conn.setRequestProperty("Authorization", "Bearer $bearer")
                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(body)
                    val preferredUrl = json.optString("url", "").trimEnd('/')
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
                } else {
                    _uiState.update { it.copy(authError = "Bridge returned HTTP $code") }
                }
                conn.disconnect()
            } catch (e: Exception) {
                val msg = when {
                    e is java.net.UnknownHostException -> "Can't reach bridge — the saved URL is stale. Scan the QR code again for the current URL."
                    e is java.net.SocketTimeoutException -> "Bridge timed out — the saved URL may be stale. Scan the QR code again."
                    else -> "Refresh failed: ${e.message}. Scan the QR code again for the current URL."
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
        // Capture the bridge API key so refresh works after app restarts
        val apiKey = uri.getQueryParameter("key")
        if (!apiKey.isNullOrBlank()) {
            vm.setApiKey(apiKey)
        }
        vm.updateBaseUrl(url)
        vm.viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            vm.testConnection()
        }
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
            viewModel.viewModelScope.launch {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (bitmap != null) {
                        val w = bitmap.width
                        val h = bitmap.height
                        val pixels = IntArray(w * h)
                        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                        val source = com.google.zxing.RGBLuminanceSource(w, h, pixels)
                        val binaryBitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
                        val result = com.google.zxing.MultiFormatReader().decode(binaryBitmap)
                        if (result?.text != null) {
                            handleQrResult(result.text, viewModel)
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

    CompositionLocalProvider(LocalDarkTheme provides uiState.isDarkTheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))

            // ─── Authentication Section ───
            SettingsSection("Authentication") {
                if (uiState.isLoggedIn) {
                    // Logged in state
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Logged in",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                uiState.loggedInEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Button(
                        onClick = { viewModel.logout() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Log Out") }
                } else {
                    // Login / Register form
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
                    Spacer(modifier = Modifier.height(8.dp))

                    // Auth error
                    uiState.authError?.let { err ->
                        Text(
                            text = err,
                            color = ErrorRed.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = { viewModel.discoverBridge() },
                            enabled = !uiState.isAuthLoading && uiState.email.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Find", maxLines = 1)
                        }
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
                            Text("Log In", maxLines = 1)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.register() },
                        enabled = !uiState.isAuthLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Register", maxLines = 1) }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── Connection Section ───
            SettingsSection("Server Connection") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    val (statusColor, statusText) = when (uiState.connectionStatus) {
                        ConnectionStatus.CONNECTED -> SuccessGreen to "Connected"
                        ConnectionStatus.CONNECTING -> WarningAmber to "Testing..."
                        ConnectionStatus.ERROR -> ErrorRed to "Connection Failed"
                        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant to "Not Connected"
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(statusText, style = MaterialTheme.typography.bodyMedium, color = statusColor)
                }
                uiState.errorDetail?.let { detail ->
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
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
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.testConnection() },
                    enabled = uiState.connectionStatus != ConnectionStatus.CONNECTING,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HermesPrimary)
                ) {
                    if (uiState.connectionStatus == ConnectionStatus.CONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Test Connection")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showQrDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan QR Code")
                }
            }

            // QR mode picker bottom sheet
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

            Spacer(modifier = Modifier.height(12.dp))

            // ─── Tailscale Section ───
            SettingsSection("Secure Connection (Tailscale)") {
                val tailscaleInstalled = remember {
                    try {
                        context.packageManager.getPackageInfo("com.tailscale.ipn", 0)
                        true
                    } catch (_: Exception) { false }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = if (tailscaleInstalled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (tailscaleInstalled) "Tailscale installed" else "Tailscale not installed",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Direct secure connection to your Hermes bridge",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
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
                            // Open Tailscale app for sign-in
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
                        if (tailscaleInstalled) Icons.Filled.CheckCircle else Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (tailscaleInstalled) "Sign in to Tailscale" else "Install Tailscale")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "After signing in, tap \"Refresh\" below — the app re-fetches your Tailscale IP from the bridge.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.refreshFromBridge()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Refresh from Bridge")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── Appearance Section ───
            SettingsSection("Appearance") {
                SettingsToggle(
                    icon = Icons.Filled.DarkMode,
                    title = "Dark Theme",
                    subtitle = if (uiState.isDarkTheme) "Dark mode active" else "Light mode active",
                    checked = uiState.isDarkTheme,
                    onCheckedChange = { viewModel.toggleTheme() }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ─── About Section ───
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
}

// ─── Reusable Components ───

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
