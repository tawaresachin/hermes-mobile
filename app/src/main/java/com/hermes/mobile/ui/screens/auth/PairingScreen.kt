package com.hermes.mobile.ui.screens.auth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.mobile.ui.theme.HermesMobileTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.regex.Pattern

@OptIn(ExperimentalMaterial3Api::class)
@AndroidEntryPoint
class PairingActivity : ComponentActivity() {

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            viewModel.processQrResult(result.contents, onResult = { qrConfig ->
                viewModel.onQrScanned(qrConfig)
            })
        }
    }

    private val viewModel: PairingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesMobileTheme {
                PairingScreenContent(
                    onQrScanRequested = { qrScanLauncher.launch(scanOptions) },
                    qrConfig = viewModel.qrConfig,
                    error = viewModel.error,
                    isProcessing = viewModel.isProcessing,
                    onPaired = { url ->
                        val intent = Intent(this, ChatActivity::class.java).apply {
                            putExtra("desktop_url", url)
                        }
                        startActivity(intent)
                        finish()
                    },
                    viewModel = viewModel
                )
            }
        }
    }

    private val scanOptions = ScanOptions()
        .setPrompt("Scan QR code to connect to Hermes Desktop")
        .setBeepEnabled(true)
        .setBarcodeImageEnabled(true)
        .setOrientationLocked(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreenContent(
    onQrScanRequested: () -> Unit,
    qrConfig: PairingViewModel.QRConfig?,
    error: String?,
    isProcessing: Boolean,
    onPaired: (String) -> Unit,
    viewModel: PairingViewModel
) {
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair with Desktop") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Connection status
            when {
                error != null -> {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                isProcessing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Processing QR code...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                qrConfig != null -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "QR Code Detected",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            
                            Divider(color = MaterialTheme.colorScheme.divider)
                            
                            QRConfigDetails(qrConfig = qrConfig)
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // API Key with show/hide toggle
                            QRConfigApiKey(apiKey = qrConfig.apiKey)
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Button(
                                onClick = { onPaired(qrConfig.url) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = true
                            ) {
                                Text("Connect to ${qrConfig.tailscaleIp}")
                            }
                        }
                    }
                }
                else -> {
                    Text(
                        text = "Connect your mobile app to Hermes Agent Desktop",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // QR Scanner Button
            Button(
                onClick = {
                    // Request camera permission first
                    val permission = ContextCompat.checkSelfPermission(
                        LocalContext.current,
                        Manifest.permission.CAMERA
                    )
                    if (permission == PackageManager.PERMISSION_GRANTED) {
                        onQrScanRequested()
                    } else {
                        // Request permission (this would need a launcher in real code)
                        Toast.makeText(context, "Camera permission required", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isProcessing && qrConfig == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Scan QR Code")
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                }
            }

            // Instructions
            Text(
                text = "Open Hermes Desktop, run 'hermes-mobile-plugin qr', and scan the QR code shown in browser",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun QRConfigDetails(qrConfig: PairingViewModel.QRConfig) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoRow("Server URL", qrConfig.url)
        InfoRow("Tailscale IP", qrConfig.tailscaleIp)
        InfoRow("API Key", "••••••••••••••••••••••")
        InfoRow("Compression", if (qrConfig.contextCompression) "Enabled" else "Disabled")
        InfoRow("Version", qrConfig.version)
    }
}

@Composable
fun QRConfigApiKey(apiKey: String) {
    var showKey by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("API Key", style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = if (showKey) apiKey else "••••••••••••••••••••••••••••••••••••••••••••••••••",
            onValueChange = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            suffix = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (showKey) "Hide API key" else "Show API key"
                    )
                }
            }
        )
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ViewModel for pairing logic
@androidx.hilt.lifecycle.HiltViewModel
class PairingViewModel @javax.inject.Inject constructor(
    private val repository: com.hermes.mobile.data.repository.HermesRepository
) : androidx.lifecycle.ViewModel() {

    data class QRConfig(
        val url: String,
        val apiKey: String,
        val tailscaleIp: String,
        val version: String,
        val contextCompression: Boolean
    )

    var qrConfig by mutableStateOf<QRConfig?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var isProcessing by mutableStateOf(false)
        private set

    fun processQrResult(qrResult: String, onResult: (QRConfig) -> Unit) {
        isProcessing = true
        error = null
        
        try {
            // Parse QR result - expect JSON format from our plugin
            val json = JSONObject(qrResult)
            val url = json.optString("url")
            val apiKey = json.optString("api_key")
            val tailscaleIp = json.optString("tailscale_ip")
            val version = json.optString("version")
            val compression = json.optBoolean("context_compression", true)
            
            // Validate required fields
            if (url.isEmpty() || !url.startsWith("http://") && !url.startsWith("https://")) {
                throw IllegalArgumentException("Invalid URL format in QR code")
            }
            
            qrConfig = QRConfig(
                url = url,
                apiKey = apiKey,
                tailscaleIp = tailscaleIp,
                version = version,
                contextCompression = compression
            )
            
            onResult(qrConfig!!)
        } catch (e: Exception) {
            error = "Invalid QR code format: ${e.message}"
        } finally {
            isProcessing = false
        }
    }
    
    fun onQrScanned(config: QRConfig) {
        // Save configuration for later use
        qrConfig = config
    }
}