package com.hermes.mobile.ui.screens.auth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.mobile.ui.theme.HermesMobileTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@AndroidEntryPoint
class PairingActivity : ComponentActivity() {

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            viewModel.processQrResult(result.contents)
        }
    }

    private val viewModel: PairingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesMobileTheme {
                PairingScreenContent(
                    onQrScanRequested = { qrScanLauncher.launch(scanOptions) },
                    onPaired = { url ->
                        // Navigate to chat screen
                        val intent = Intent(this, MainActivity::class.java)
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
        .setCaptureActivity(CaptureActivityAnyOrientation::class.java)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreenContent(
    onQrScanRequested: () -> Unit,
    onPaired: (String) -> Unit,
    viewModel: PairingViewModel
) {
    val qrResult by remember { mutableStateOf<String?>(null) }
    val isScanning by remember { mutableStateOf(false) }
    val error by remember { mutableStateOf<String?>(null) }
    val isProcessing by remember { mutableStateOf(false) }
    val pairedUrl by remember { mutableStateOf<String?>(null) }

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
                        text = error ?: "",
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
                pairedUrl != null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.success,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Successfully paired!",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Connecting to: $pairedUrl",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                    onQrScanRequested()
                },
                enabled = !isProcessing && pairedUrl == null,
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

            Spacer(modifier = Modifier.height(16.dp))

            // Manual entry section
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Or enter details manually", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = remember { mutableStateOf("") }.value,
                        onValueChange = { /* handled in ViewModel */ },
                        label = { Text("Desktop URL") },
                        placeholder = { Text("e.g., 100.89.25.56:8642") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = remember { mutableStateOf("") }.value,
                        onValueChange = { /* handled in ViewModel */ },
                        label = { Text("Pairing Token") },
                        placeholder = { Text("Token from QR code") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { /* process manual entry */ },
                        enabled = true,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Connect Manually")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Help text
            Text(
                text = "Make sure Hermes Agent Desktop is running and QR code is visible",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

// ViewModel for pairing logic
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: com.hermes.mobile.data.repository.HermesRepository
) : androidx.lifecycle.ViewModel() {

    fun processQrResult(qrResult: String): String {
        // Parse QR result - expected format from our plugin: JSON with url and api_key
        try {
            val json = JSONObject(qrResult)
            val url = json.getString("url")
            // Validate URL format
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw IllegalArgumentException("Invalid URL format")
            }
            return url
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid QR code format: ${e.message}")
        }
    }
}

class CaptureActivityAnyOrientation : com.journeyapps.barcodescanner.CaptureActivity {
    // Allow any orientation for QR scanning
}