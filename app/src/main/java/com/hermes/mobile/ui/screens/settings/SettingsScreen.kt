package com.hermes.mobile.ui.screens.settings

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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.model.ConnectionStatus
import com.hermes.mobile.ui.components.HermesWatermark
import com.hermes.mobile.ui.theme.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    paddingValues: PaddingValues,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val chatFontSp by viewModel.chatFontSp.collectAsState()
    val chatFontManual by viewModel.chatFontManual.collectAsState()
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
            modifier = Modifier.fillMaxSize()
        ) {
            // ── Fixed header ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                // E2E setup help — click to see the full pairing guide
                IconButton(onClick = { showSetupHelp = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = "Setup help",
                        tint = HermesPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // ── Scrollable content ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp)
            ) {

            // ─── 1. CONNECTION (merged — URL + QR + Test + Refresh) ───
            SettingsSection("Connection") {
                if (uiState.pluginNeedsUpdate) {
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning, null,
                                tint = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Server plugin needs an update",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                                Text(
                                    "This app requires a newer API protocol than the server's plugin (v${uiState.pluginVersion.ifBlank { "?" }}). Update the plugin, then restart the gateway.",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { uriHandler.openUri(com.hermes.mobile.network.HermesApiService.PLUGIN_RELEASES_URL) }) {
                                Text("Update")
                            }
                        }
                    }
                }
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
 Spacer(modifier = Modifier.height(8.dp))

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
                                        ?.let { "hermes-" + "•".repeat(8) }
                                        ?: "•".repeat(8),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(6.dp))
                        // Value sits right beside the label — weight(1f) pinned
                        // it to the card's far edge, leaving a dead gap mid-row.
                        val shown = if (chatFontSp * 2 % 2f < 0.01f ||
                                         chatFontSp * 2 % 2f > 1.99f)
                            "${chatFontSp.toInt()}" else "%.1f".format(chatFontSp)
                        Text("$shown sp",
                            style = MaterialTheme.typography.labelMedium,
                            color = HermesPrimary)
                        // Inline escape hatch — only once the size is manual.
                        if (chatFontManual) {
                            Spacer(Modifier.weight(1f))
                            Text("Auto (device)",
                                style = MaterialTheme.typography.labelMedium,
                                color = HermesPrimary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { viewModel.resetChatFont() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    // 0.5 sp detents, hairline track + dot thumb —
                    // quiet Material-3 shape, brand-blue fill.
                    Slider(
                        value = chatFontSp,
                        onValueChange = { viewModel.setChatFont(
                            kotlin.math.round(it * 2f) / 2f) },
                        valueRange = 12f..20f,
                        steps = 15,
                        modifier = Modifier.height(28.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = HermesPrimary,
                            activeTrackColor = HermesPrimary,
                            inactiveTrackColor =
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                        track = { state ->
                            val inactive = MaterialTheme.colorScheme
                                .outlineVariant.copy(alpha = 0.55f)
                            val active = HermesPrimary
                            androidx.compose.foundation.Canvas(
                                Modifier.fillMaxWidth().height(28.dp)) {
                                val frac = ((state.value - 12f) / 8f)
                                    .coerceIn(0f, 1f)
                                val inset = 6.dp.toPx()
                                val barW = size.width - inset * 2
                                val y = size.height / 2f - 1.dp.toPx()
                                drawRoundRect(
                                    color = inactive,
                                    topLeft = androidx.compose.ui.geometry.Offset(
                                        inset, y),
                                    size = androidx.compose.ui.geometry.Size(
                                        barW, 2.dp.toPx()),
                                    cornerRadius = androidx.compose.ui.geometry
                                        .CornerRadius(1.dp.toPx()))
                                if (frac > 0f) drawRoundRect(
                                    color = active,
                                    topLeft = androidx.compose.ui.geometry.Offset(
                                        inset, y),
                                    size = androidx.compose.ui.geometry.Size(
                                        barW * frac, 2.dp.toPx()),
                                    cornerRadius = androidx.compose.ui.geometry
                                        .CornerRadius(1.dp.toPx()))
                            }
                        },
                        thumb = {
                            Box(Modifier.size(12.dp)
                                .background(HermesPrimary, CircleShape))
                        },
                    )
                }
                SettingsToggle(
                    icon = Icons.Filled.Compress,
                    title = "Token Optimizer",
                    subtitle = if (uiState.caveman)
                        "Shorter, denser replies"
                    else "Normal reply length",
                    checked = uiState.caveman,
                    onCheckedChange = { viewModel.toggleCaveman(it) }
                )
                SettingsToggle(
                    icon = Icons.Filled.FactCheck,
                    title = "Auto-approve tools",
                    subtitle = if (uiState.autoApprove)
                        "Dangerous commands run unasked"
                    else "Ask before dangerous commands",
                    checked = uiState.autoApprove,
                    onCheckedChange = { viewModel.toggleAutoApprove(it) }
                )
                SettingsToggle(
                    icon = Icons.Filled.PowerSettingsNew,
                    title = "Keep Computer Awake",
                    subtitle = if (uiState.keepAwake) {
                        "Host stays awake" +
                            (uiState.awakeMechanism?.let { " ($it)" } ?: "")
                    } else {
                        "Prevents sleep while Hermes works"
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

            // ─── 7. SERVER (update + cron jobs + skills, live from gateway) ───
            SettingsSection("Server") {
                ServerListsPanel(viewModel = viewModel, uiState = uiState)
            }

            // ─── 7b. PROVIDERS (server-side model provider CRUD) ───
            SettingsSection("Providers") {
                ProvidersCard(viewModel = viewModel, uiState = uiState)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ─── 8. USAGE ───
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
                // Version row — GitHub-releases self-update check (12h
                // throttle). Shows vCurrent; when behind, the refresh
                // arrow becomes an Update button (same language as the
                // Agent Version row below).
                HermesAppVersionRow(viewModel = viewModel)
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
                Spacer(modifier = Modifier.height(8.dp))
                HermesAgentAboutRow(viewModel = viewModel)
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
                            "Needs Python 3.10 or newer — check with:  python3 --version",
                            "Install the plugin:  python3 -m pip install \"git+https://github.com/tawaresachin/hermes-mobile-plugin@v0.0.37\"",
                            "Then run:  hermes-mobile-plugin install",
                            "It sets up the key, phone access and opens the QR page in your browser",
                            "QR file:  ~/.hermes/plugins/hermes-mobile-plugin/qr_code.html",
                            "Show or regenerate it later:  hermes-mobile-plugin qr",
                            "Check it says 'enabled':  hermes plugins list"
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
                        title = "3. If the phone can't connect",
                        steps = listOf(
                            "Allow phone access if you turned it off:  hermes config set platforms.api_server.extra.host 0.0.0.0",
                            "Apply it:  hermes gateway restart",
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

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        // Text + refresh left-aligned with a small gap — no SpaceBetween
        // stretch (it parked a dead hole mid-card).
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
            // Refresh icon immediately after the status text
            if (status != ConnectionStatus.CONNECTING) {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Test connection",
                        modifier = Modifier.size(18.dp),
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
