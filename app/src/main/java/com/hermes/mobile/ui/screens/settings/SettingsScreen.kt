package com.hermes.mobile.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.hermes.mobile.data.model.ConnectionStatus
import com.hermes.mobile.data.model.ServerConfig
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.theme.*
import com.hermes.mobile.ui.theme.LocalDarkTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ───

data class SettingsUiState(
    val baseUrl: String = "http://localhost:8080",
    val apiKey: String = "",
    val showApiKey: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val errorDetail: String? = null,
    val isDarkTheme: Boolean = false,
    val voiceEnabled: Boolean = true,
    val ttsEnabled: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: HermesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Load saved config into UI fields so user doesn't re-type every time
        val saved = repository.getSavedConfig()
        if (saved != null) {
            _uiState.value = _uiState.value.copy(
                baseUrl = saved.baseUrl,
                apiKey = saved.apiKey
            )
        }
        // Load saved dark theme preference
        if (repository.hasDarkThemePreference()) {
            _uiState.value = _uiState.value.copy(
                isDarkTheme = repository.isDarkTheme()
            )
        }
    }

    fun updateBaseUrl(url: String) {
        _uiState.value = _uiState.value.copy(baseUrl = url)
    }

    fun updateApiKey(key: String) {
        _uiState.value = _uiState.value.copy(apiKey = key)
    }

    fun toggleApiKeyVisibility() {
        _uiState.value = _uiState.value.copy(showApiKey = !_uiState.value.showApiKey)
    }

    fun toggleTheme() {
        val newValue = !_uiState.value.isDarkTheme
        _uiState.value = _uiState.value.copy(isDarkTheme = newValue)
        repository.saveDarkTheme(newValue)
    }

    fun toggleVoice() {
        _uiState.value = _uiState.value.copy(voiceEnabled = !_uiState.value.voiceEnabled)
    }

    fun toggleTts() {
        _uiState.value = _uiState.value.copy(ttsEnabled = !_uiState.value.ttsEnabled)
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(connectionStatus = ConnectionStatus.CONNECTING)
            val rawUrl = _uiState.value.baseUrl.trimEnd('/')
            // Auto-add https:// if no scheme specified
            val normalizedUrl = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                "https://$rawUrl"
            } else {
                rawUrl
            }
            val config = ServerConfig(
                baseUrl = normalizedUrl,
                apiKey = _uiState.value.apiKey
            )
            // Save immediately so chat screen uses it
            repository.saveConfig(config)
            try {
                val connected = repository.checkConnectionRaw(config)
                if (connected) {
                    _uiState.value = _uiState.value.copy(connectionStatus = ConnectionStatus.CONNECTED, errorDetail = null)
                } else {
                    _uiState.value = _uiState.value.copy(
                        connectionStatus = ConnectionStatus.ERROR,
                        errorDetail = "Server returned error status"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    errorDetail = e.message ?: "Unknown error"
                )
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

        // Header
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))

        // ─── Connection Section ───
        SettingsSection("Server Connection") {
            // Connection Status Badge
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
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor
                )
            }
            // Show detailed error message if available
            uiState.errorDetail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = { viewModel.updateBaseUrl(it) },
                label = { Text("Server URL") },
                placeholder = { Text("http://localhost:8080") },
                leadingIcon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = { viewModel.updateApiKey(it) },
                label = { Text("API Key") },
                placeholder = { Text("Optional") },
                leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { viewModel.toggleApiKeyVisibility() }) {
                        Icon(
                            if (uiState.showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null
                        )
                    }
                },
                singleLine = true,
                visualTransformation = if (uiState.showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.testConnection() },
                enabled = uiState.connectionStatus != ConnectionStatus.CONNECTING,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HermesPrimary
                )
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

        Spacer(modifier = Modifier.height(12.dp))

        // ─── Voice Section ───
        SettingsSection("Voice & Audio") {
            SettingsToggle(
                icon = Icons.Filled.Mic,
                title = "Voice Input",
                subtitle = if (uiState.voiceEnabled) "Voice recording enabled" else "Voice recording disabled",
                checked = uiState.voiceEnabled,
                onCheckedChange = { viewModel.toggleVoice() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsToggle(
                icon = Icons.Filled.VolumeUp,
                title = "Text-to-Speech",
                subtitle = if (uiState.ttsEnabled) "Responses read aloud" else "Text only",
                checked = uiState.ttsEnabled,
                onCheckedChange = { viewModel.toggleTts() }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ─── About Section ───
        SettingsSection("About") {
            SettingsInfoRow("Version", LocalContext.current.let { ctx ->
                try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
                } catch (e: Exception) { "?" }
            })
            SettingsInfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            SettingsInfoRow("Android", Build.VERSION.RELEASE)
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hermes-agent.nousresearch.com"))
                    context.startActivity(intent)
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
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
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
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
fun SettingsInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
