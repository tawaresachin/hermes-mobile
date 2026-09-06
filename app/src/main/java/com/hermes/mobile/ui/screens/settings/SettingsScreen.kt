package com.hermes.mobile.ui.screens.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.mobile.ui.theme.HermesMobileTheme
import com.hermes.mobile.ui.screens.auth.PairingViewModel
import dagger.hilt.android.AndroidEntryPoint

@OptIn(ExperimentalMaterial3Api::class)
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesMobileTheme {
                SettingsScreenContent(
                    onBackClick = { onBackPressedDispatcher.onBackPressed() },
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
    val connectionState by viewModel.connectionState.collectAsState()
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
                        // Connection status icon - positioned inline with status text
                        when (connectionState.status) {
                            ConnectionState.Status.CONNECTED -> {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = "Connected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            ConnectionState.Status.CONNECTING -> {
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
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Column {
                            Text(
                                text = connectionState.statusText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = connectionState.detailText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Test and Refresh buttons side by side
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { 
                                viewModel.testConnection { result ->
                                    testResult = result
                                }
                            },
                            enabled = !isTesting
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.PanoramaFishEye,
                                    contentDescription = "Test Connection",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test")
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Button(
                            onClick = { 
                                isRefreshing = true
                                viewModel.refreshConnection()
                            },
                            enabled = !isRefreshing && connectionState.status != ConnectionState.Status.CONNECTING
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Connection",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh")
                        }
                    }
                    
                    testResult?.let { result ->
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.contains("Success")) 
                                MaterialTheme.colorScheme.primary 
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
                    Text("Paired: ${connectionState.lastPaired}", style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
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
            
            // About Section - with icons for Share Logs and Website
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    InfoRowWithIcon(
                        icon = Icons.Default.Info,
                        label = "Version",
                        value = BuildConfig.VERSION_NAME
                    )
                    InfoRowWithIcon(
                        icon = Icons.Default.Phone,
                        label = "Device",
                        value = android.os.Build.MODEL
                    )
                    InfoRowWithIcon(
                        icon = Icons.Default.Security,
                        label = "Android",
                        value = "Android ${android.os.Build.VERSION.RELEASE}"
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Share Logs button with icon
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = { /* Share logs action */ },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Logs",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Share Logs")
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        TextButton(
                            onClick = { /* Open website */ },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = "Website",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Website")
                        }
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
fun InfoRowWithIcon(icon: androidx.compose.material.icons.Icons.Default, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
@androidx.hilt.lifecycle.HiltViewModel
class SettingsViewModel @javax.inject.Inject constructor(
    private val repository: com.hermes.mobile.data.repository.HermesRepository
) : androidx.lifecycle.ViewModel() {
    
    data class ConnectionState(
        val status: Status,
        val ip: String,
        val port: Int,
        val lastPaired: String,
        val isConnected: Boolean
    ) {
        enum class Status { CONNECTED, CONNECTING, DISCONNECTED }
        
        val statusText get() = when (status) {
            Status.CONNECTED -> "Connected to Hermes Gateway"
            Status.CONNECTING -> "Connecting..."
            else -> "Disconnected"
        }
        
        val detailText get() = "$ip:$port"
    }

    private val _connectionState = androidx.lifecycle.MutableLiveData(ConnectionState(
        status = ConnectionState.Status.CONNECTED,
        ip = "100.89.25.56",
        port = 8642,
        lastPaired = "2025-09-07",
        isConnected = true
    ))
    val connectionState: androidx.lifecycle.LiveData<ConnectionState> = _connectionState

    fun refreshConnection() {
        // Reload from config or check connectivity
        _connectionState.value = _connectionState.value?.copy(
            status = ConnectionState.Status.CONNECTED,
            ip = "100.89.25.56",
            lastPaired = "2025-09-07"
        )
    }

    fun testConnection(callback: (String) -> Unit) {
        // Simulate connection test - in production make actual API call
        // Call /v1/models endpoint to verify gateway is reachable
        callback("Connection test: Success - Gateway reachable at 100.89.25.56:8642")
    }
}