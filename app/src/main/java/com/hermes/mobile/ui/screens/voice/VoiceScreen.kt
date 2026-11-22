package com.hermes.mobile.ui.screens.voice

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermes.mobile.ui.components.AutoPlayAudio
import com.hermes.mobile.ui.components.ModelPickerSheet
import com.hermes.mobile.ui.components.ModelPickerSheetWithContext
import com.hermes.mobile.ui.screens.chat.ApprovalCard
import com.hermes.mobile.ui.theme.HermesPrimary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen() {
    val vm: VoiceViewModel = hiltViewModel()
    val currentModel by vm.currentModel.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val modelsLoading by vm.modelsLoading.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val selectedSessionId by vm.selectedSessionId.collectAsState()
    val context = LocalContext.current

    // Conversation mode keeps the whole loop in the ViewModel; the screen
    // only drives the microphone and the player the phase asks for.
    val turns by vm.turns.collectAsState()
    val convPhase by vm.conversationPhase.collectAsState()
    val convStatus by vm.conversationStatus.collectAsState()
    val convLevel = vm.conversationLevel.collectAsState()
    val pendingApproval by vm.pendingApproval.collectAsState()
    val ttsDataUrl by vm.ttsDataUrl.collectAsState()
    val conversationOn = convPhase != ConversationPhase.Off

    var showModelPicker by remember { mutableStateOf(false) }
    var showSessionSheet by remember { mutableStateOf(false) }

    // The permission prompt answers the tap that asked for it: on grant the
    // mode starts immediately, so the orb appears instead of a dead button.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startConversation()
        else vm.reportConversationStatus("Microphone permission is needed for conversation mode.")
    }
    fun beginConversation() {
        val granted = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.startConversation()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val capture = remember {
        VoiceCapture(
            onUtterance = { pcm ->
                val wav = encodeWav(pcm, VoiceCapture.SAMPLE_RATE, 1, 16)
                vm.onUtterance(Base64.encodeToString(wav, Base64.NO_WRAP))
            },
            onError = { msg -> vm.stopConversation(msg) },
            onLevel = { vm.onLevel(it) },
        )
    }
    DisposableEffect(Unit) {
        onDispose { capture.release() } // the mic must never outlive the screen
    }
    // Only the listening phase holds the mic open; every other phase
    // (thinking, speaking, paused) releases it — including TTS playback.
    LaunchedEffect(convPhase) {
        if (convPhase == ConversationPhase.Listening) capture.start() else capture.stop()
    }
    val view = LocalView.current
    DisposableEffect(conversationOn) {
        view.keepScreenOn = conversationOn
        onDispose { view.keepScreenOn = false }
    }
    // Android allows capture only in the foreground, so backgrounding ends
    // the mode instead of pretending to keep listening.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                vm.stopOnScreenExit()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Back closes playback/sheets first, then exits the screen.
    BackHandler(enabled = ttsDataUrl != null || showModelPicker || showSessionSheet) {
        if (showModelPicker) showModelPicker = false
        else if (showSessionSheet) showSessionSheet = false
        else vm.interruptPlayback()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        TopAppBar(
            // Outer NavHost padding already reserves the status bar; the
            // bar's default insets would add it AGAIN (blank band on top).
            windowInsets = WindowInsets(0.dp),
            // Voice is a bottom tab — no back arrow (consistent with the
            // rest of the tab screens); onExit still ends the session.
            title = { Text("Voice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium) },
            actions = {
                if (currentModel.isNotBlank()) {
                    val displayName = availableModels.firstOrNull { it.id == currentModel }?.name
                        ?: currentModel.substringAfterLast("/").take(15)
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = HermesPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = { showModelPicker = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Change model")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF000000),
                titleContentColor = Color(0xFFE2E7FC),
                actionIconContentColor = Color(0xFFE2E7FC)
            )
        )

        // Session selector row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF12141C))
                .clickable { showSessionSheet = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Forum, contentDescription = null, tint = HermesPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            val current = sessions.firstOrNull { it.id == selectedSessionId }
            Text(
                text = current?.title?.takeIf { it.isNotBlank() }
                    ?: if (selectedSessionId != null) "Conversation" else "New conversation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "change",
                style = MaterialTheme.typography.labelMedium,
                color = HermesPrimary,
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = HermesPrimary, modifier = Modifier.size(18.dp))
        }

        // Live transcript turns
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            if (turns.isEmpty() && convStatus == null) {
                Text(
                    "Tap the circle to start a hands-free conversation — speak any time, the answer is spoken back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp)
                )
            }
            turns.forEach { turn ->
                // user
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                        color = HermesPrimary.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth(0.85f).widthIn(max = 420.dp)
                    ) {
                        Text(
                            turn.userText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                // assistant
                if (turn.assistantText.isNotBlank() || !turn.done) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Surface(
                            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(0.85f).widthIn(max = 420.dp)
                        ) {
                            Text(
                                text = if (turn.assistantText.isBlank()) "…" else turn.assistantText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Orb + status. The orb is the screen: idle shows it too, and a tap
        // starts the mode, so there is never a dead mic in the way.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Landscape and small screens can't fit the orb, status,
                // approval card and Stop button; scrolling beats clipping
                // the button off-screen.
                .verticalScroll(rememberScrollState())
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Full-bleed black surface: the glass sphere floats on the screen
            // itself, with no card or stage rectangle behind it.
            ConversationOrb(
                phase = convPhase,
                level = convLevel,
                onTap = {
                    when (convPhase) {
                        ConversationPhase.Speaking -> vm.interruptPlayback()
                        ConversationPhase.Off -> beginConversation()
                        else -> Unit
                    }
                },
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = conversationStatusText(convPhase, convStatus),
                style = MaterialTheme.typography.bodyMedium,
                color = if (convPhase == ConversationPhase.Paused ||
                    (convPhase == ConversationPhase.Off && convStatus != null)
                ) MaterialTheme.colorScheme.error else HermesPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            // The run's approval is answered right here: leaving for Chat
            // ends the mode (the mic goes with this screen).
            pendingApproval?.let { approval ->
                Spacer(modifier = Modifier.height(10.dp))
                ApprovalCard(
                    approval = approval,
                    onResolve = { choice -> vm.resolveApproval(choice) },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (conversationOn) {
                TextButton(onClick = { vm.stopConversation() }) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop conversation")
                }
            } else {
                OutlinedButton(onClick = { beginConversation() }) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start conversation")
                }
            }
        }
    }

    // TTS playback — auto-plays the assistant reply, silent UI. The payload
    // is clearable (barge-in and playback end both clear it), so every phase
    // transition lives in the ViewModel.
    ttsDataUrl?.let { url ->
        AutoPlayAudio(
            dataUrl = url,
            onFinished = { vm.onPlaybackFinished() }
        )
    }

    if (showModelPicker) {
        ModelPickerSheetWithContext(
            context = context,
            availableModels = availableModels,
            currentModel = currentModel,
            modelsLoading = modelsLoading,
            onSelect = { modelId, providerSlug, global ->
                vm.switchModel(modelId, providerSlug, global)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }

    if (showSessionSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(
            onDismissRequest = { showSessionSheet = false },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    "Voice session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                )
                // New conversation pinned first — always available.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showSessionSheet = false
                            vm.clearTurns()
                            vm.startNewSession()
                        }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.AddComment, contentDescription = null, tint = HermesPrimary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("New conversation", style = MaterialTheme.typography.bodyLarge, color = HermesPrimary)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (s.id == selectedSessionId)
                                        HermesPrimary.copy(alpha = 0.08f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    vm.selectSession(s.id)
                                    vm.clearTurns()
                                    showSessionSheet = false
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    s.title?.takeIf { it.isNotBlank() }
                                        ?: "Chat ${s.id.take(6)}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${s.messageCount} messages · " +
                                        SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(s.updatedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (s.id == selectedSessionId) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = HermesPrimary)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// Glass-sky palette, sampled from the reference recording.
private val SKY_TOP = Color(0xFF3E6EE9)
private val SKY_TOP_PALE = Color(0xFF6178F1)
private val SKY_MID = Color(0xFF8FA9F4)
private val SKY_CENTRE = Color(0xFFE2E7FC)
private val SKY_BOTTOM = Color(0xFFB9C7F8)
private val GLASS_RIM = Color(0xFF6E86F2)

// Gradient stops are constant, so they are built once rather than on each frame.
private val SKY_STOPS = arrayOf(
    0.00f to SKY_TOP.copy(alpha = 0.94f),
    0.18f to SKY_TOP_PALE.copy(alpha = 0.94f),
    0.38f to SKY_MID.copy(alpha = 0.94f),
    0.55f to SKY_CENTRE.copy(alpha = 0.94f),
    1.00f to SKY_BOTTOM.copy(alpha = 0.94f),
)
private val HIGHLIGHT_STOPS = arrayOf(
    0.00f to Color(0xFFF4F7FF).copy(alpha = 0.38f),
    0.55f to Color(0xFFF4F7FF).copy(alpha = 0.10f),
    1.00f to Color(0xFFF4F7FF).copy(alpha = 0f),
)
private val FEATHER_STOPS = arrayOf(
    0.00f to Color.Transparent,
    0.97f to Color.Transparent,
    1.00f to Color.Black,
)

/** One drifting cloud: position/radius are fractions of the sphere radius.
 * Colours are built once, not on every draw frame. */
private data class CloudBlob(
    val x: Float,
    val y: Float,
    val radius: Float,
    val inner: Color,
    val mid: Color,
    val phase: Float,
    val speed: Float,
) {
    val stops = arrayOf(0.00f to inner, 0.55f to mid, 1.00f to Color.Transparent)
}

private val CLOUD_BLOBS = listOf(
    CloudBlob(-0.44f, -0.10f, 0.60f,
        Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.20f), 0.0f, 1.00f),
    CloudBlob(-0.08f, 0.05f, 0.50f,
        Color.White.copy(alpha = 0.44f), Color.White.copy(alpha = 0.24f), 1.7f, 0.82f),
    CloudBlob(0.26f, -0.12f, 0.56f,
        Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.18f), 3.1f, 1.18f),
    CloudBlob(0.52f, 0.08f, 0.44f,
        Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.21f), 4.6f, 0.91f),
)

/** The voice orb is a translucent glass sphere holding a blue sky with slow
 * white clouds — not a neon ring. It is 60% of the Voice surface width and
 * sits directly on the black screen. All animation is read inside draw() or
 * graphicsLayer, so frames repaint without recomposing the screen, and the
 * sphere stays the barge-in tap target. */
@Composable
private fun ConversationOrb(
    phase: ConversationPhase,
    level: State<Float>,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    var reduceMotion by remember { mutableStateOf(readReduceMotion(context)) }
    DisposableEffect(context) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduceMotion = readReduceMotion(context)
            }
        }
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val animate = !reduceMotion && phase != ConversationPhase.Paused
    val transition = rememberInfiniteTransition(label = "orb")
    // One scalar drives every cloud; its period is 12 s, individual blobs
    // use different speed multipliers for roughly 8-14 s cycles.
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
        label = "orb-cloud-drift",
    )
    // 1.0 -> 1.015 -> 1.0 over ~5 s.
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(2_500, easing = LinearEasing), RepeatMode.Reverse),
        label = "orb-breathe",
    )

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // ~431 px at 720 px wide == 60% of the surface.
        val sphere = maxWidth * 0.6f
        Box(
            modifier = Modifier
                .size(sphere)
                .graphicsLayer {
                    val live = if (phase == ConversationPhase.Listening) {
                        sqrt(level.value.coerceIn(0f, 1f))
                    } else 0f
                    val scale = if (animate) breathe + live * 0.012f else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .clickable(
                    enabled = phase == ConversationPhase.Speaking || phase == ConversationPhase.Off,
                    onClickLabel = when (phase) {
                        ConversationPhase.Off -> "Start conversation"
                        ConversationPhase.Speaking -> "Stop the reply and listen"
                        else -> null
                    },
                    onClick = onTap,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().clip(CircleShape)) {
                val r = size.minDimension / 2f
                val cx = size.width / 2f
                val cy = size.height / 2f
                // Reduced motion freezes the drift at a readable cloud band.
                val t = (if (reduceMotion) 0.14f else drift) * 6.2831853f
                val energy = if (phase == ConversationPhase.Listening) {
                    0.35f + 0.65f * sqrt(level.value.coerceIn(0f, 1f))
                } else 0.35f

                // 1. Sky: blue at the top, pale through the middle, lavender
                // at the bottom. The Canvas clip gives the sphere its edge.
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = SKY_STOPS,
                        startY = 0f,
                        endY = size.height,
                    ),
                    size = size,
                )

                // 2. Volumetric highlight, ~15% above centre.
                val hlCenter = Offset(cx, cy - r * 0.15f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = HIGHLIGHT_STOPS,
                        center = hlCenter,
                        radius = r * 0.95f,
                    ),
                    center = hlCenter,
                    radius = r * 0.95f,
                )

                // 3. Cloud band: four soft white blobs that drift sideways,
                // bob, and rotate slowly as a field.
                val rot = sin(t * 0.42f) * 0.06f
                val cosR = cos(rot)
                val sinR = sin(rot)
                for (c in CLOUD_BLOBS) {
                    val px = c.x + sin(t * c.speed + c.phase) * 0.14f
                    val py = c.y + cos(t * c.speed * 0.7f + c.phase) * 0.045f
                    val rx = px * cosR - py * sinR
                    val ry = px * sinR + py * cosR
                    val cc = Offset(cx + rx * r, cy + ry * r)
                    val cr = c.radius * r * (1f + 0.04f * energy)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = c.stops,
                            center = cc,
                            radius = cr,
                        ),
                        center = cc,
                        radius = cr,
                    )
                }

                // 4. Faint inner glass edge, top-left only.
                drawArc(
                    color = GLASS_RIM.copy(alpha = 0.25f),
                    startAngle = 176f,
                    sweepAngle = 94f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = r * 0.018f),
                )

                // 5. Feather the outer 3% into the black screen.
                val mid = Offset(cx, cy)
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = FEATHER_STOPS,
                        center = mid,
                        radius = r,
                    ),
                    center = mid,
                    radius = r,
                )
            }
        }
    }
}

private fun conversationStatusText(phase: ConversationPhase, status: String?): String = when (phase) {
    ConversationPhase.Listening -> "Listening… speak any time"
    ConversationPhase.Transcribing -> "Transcribing…"
    ConversationPhase.Thinking -> "Hermes is thinking…"
    ConversationPhase.Speaking -> "Speaking… tap the orb to interrupt"
    ConversationPhase.Paused -> status ?: "Paused"
    ConversationPhase.Off -> status ?: "Tap to start — hands-free, and Hermes speaks back."
}

/** The OS "reduce motion" / "remove animations" toggle reads as
 *  `ANIMATOR_DURATION_SCALE == 0`. Treated as reduce-motion when true; fails
 *  safe (motion stays on) if the value can't be read. */
private fun readReduceMotion(context: Context): Boolean = try {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
} catch (_: Exception) {
    false
}
