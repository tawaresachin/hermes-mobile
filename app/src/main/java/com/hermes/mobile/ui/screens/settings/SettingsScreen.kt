package com.hermes.mobile.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import dagger.hilt.android.lifecycle.HiltViewModel
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
            _uiState.update { it.copy(baseUrl = saved.baseUrl) }
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
            repository.saveConfig(config)
            try {
                val connected = repository.checkConnectionRaw(config)
                _uiState.update {
                    if (connected) it.copy(connectionStatus = ConnectionStatus.CONNECTED, errorDetail = null)
                    else it.copy(connectionStatus = ConnectionStatus.ERROR, errorDetail = "Server returned error status")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(connectionStatus = ConnectionStatus.ERROR, errorDetail = e.message ?: "Unknown error")
                }
            }
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
                        Button(
                            onClick = { viewModel.login() },
                            enabled = !uiState.isAuthLoading && uiState.email.isNotBlank() && uiState.password.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HermesPrimary)
                        ) {
                            if (uiState.isAuthLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
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
