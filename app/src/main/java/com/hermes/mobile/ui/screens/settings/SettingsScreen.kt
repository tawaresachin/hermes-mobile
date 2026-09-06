package com.hermes.mobile.ui.screens.settings

import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.mobile.ui.theme.HermesMobileTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesMobileTheme {
                SettingsScreenContent(
                    onBackClick = { onBackPressed() },
                    viewModel = viewModel
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isRefreshing by remember { mutableStateOf(false) }
    val isTesting by remember { mutableStateOf(false) }
    val testResult by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection Section
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Connection status icon
                        when (connectionState) {
                            is com.hermes.mobile.ui.settings.ConnectionState.Connected -> {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = "Connected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            is com.hermes.mobile.ui.settings.ConnectionState.Connecting -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.WifiOff,
                                    contentDescription = "Disconnected",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = when (connectionState) {
                                    is com.hermes.mobile.ui.settings.ConnectionState.Connected -> "Connected to Hermes Gateway"
                                    is com.hermes.mobile.ui.settings.ConnectionState.Connecting -> "Connecting..."
                                    else -> "Disconnected"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${connectionState.ip}:${connectionState.port}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                isRefreshing = true
                                viewModel.refreshConnection()
                            },
                            enabled = !isRefreshing && connectionState !is com.hermes.mobile.ui.settings.ConnectionState.Connecting,
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh connection",
                                    tint = MaterialTheme.colorScheme.onPrimaryVariant
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Button(
                            onClick = {
                                isTesting = true
                                testResult = null
                                viewModel.testConnection { result ->
                                    testResult = result
                                    isTesting = false
                                }
                            },
                            enabled = !isTesting && connectionState !is com.hermes.mobile.ui.settings.ConnectionState.Connecting,
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PanoramaFishEye,
                                    contentDescription = "Test connection",
                                    tint = MaterialTheme.colorScheme.onPrimaryVariant
                                )
                            }
                        }
                    }
                    
                    testResult?.let { result ->
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.contains("Success")) 
                                MaterialTheme.colorScheme.success 
                            else 
                                MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Account Section
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Account", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Desktop: hermes-desktop-01", style = MaterialTheme.typography.bodyMedium)
                    Text("Paired: 2025-08-23", style = MaterialTheme.typography.bodySmall)
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Preferences Section
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Preferences", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    var themeEnabled by remember { mutableStateOf(false) }
                    Switch(
                        checked = themeEnabled,
                        onCheckedChange = { themeEnabled = it }
                    )
                    Text("Dark Theme", style = MaterialTheme.typography.bodyMedium)
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Usage Section
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Usage", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        UsageStat("Sessions", "5")
                        Spacer(modifier = Modifier.width(16.dp))
                        UsageStat("Messages", "127")
                        Spacer(modifier = Modifier.width(16.dp))
                        UsageStat("Tokens", "12,450")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // About Section
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow("Version", "1.0.0")
                    InfoRow("Device", "Hermes Mobile")
                    InfoRow("API Key", "••••••••••••••••••••••") // Masked by default
                    // Add show/hide toggle for API key
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show API Key", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = remember { mutableStateOf(false) }.value,
                            onCheckedChange = { /* toggle API key visibility */ }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            Button(
                onClick = { /* Save settings */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply Changes")
            }
        }
    }
}

@Composable
fun UsageStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// Settings ViewModel
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: com.hermes.mobile.data.repository.HermesRepository
) : androidx.lifecycle.ViewModel() {

    // Connection state data class
    data class ConnectionState(
        val ip: String,
        val port: Int,
        val isConnected: Boolean = false
    ) {
        val isConnected get() = isConnected
        val isConnecting get() = !isConnected && ip.isNotEmpty() && port > 0
    }

    // Simple state holder - in production this would use StateFlow or LiveData
    private val _connectionState = androidx.lifecycle.MutableLiveData(ConnectionState(
        ip = "100.89.25.56",  // This would come from actual config
        port = 8642,
        isConnected = true
    ))
    val connectionState: androidx.lifecycle.LiveData<ConnectionState> = _connectionState

    fun refreshConnection() {
        // Simulate refresh - in production this would reload config
        // For now, just update the timestamp
        _connectionState.value = _connectionState.value?.copy(
            ip = "100.89.25.56",  // Would get from actual IP detection
            port = 8642
        )
    }

    fun testConnection(callback: (String) -> Unit) {
        // Simulate connection test
        // In production, this would make an actual API call to /health endpoint
        callback("Connection test: Success")
    }
}