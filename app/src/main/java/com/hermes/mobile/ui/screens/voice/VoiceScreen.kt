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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import com.hermes.mobile.ui.theme.VoiceCoreWhite
import com.hermes.mobile.ui.theme.VoiceNeonCyan
import com.hermes.mobile.ui.theme.VoiceNeonViolet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
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
            .background(MaterialTheme.colorScheme.background)
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
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface
            )
        )

        // Session selector row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
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
            // Near-black rounded stage: the orb's translucent layers need a
            // dark, contained backdrop to read as luminous rather than flat.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF05060A))
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
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
            }
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

/** The voice conversation orb: a boundary-free ball of light. A feathered
 * bloom halo and a body gradient whose alpha reaches zero across the outer
 * third of its radius replace the old opaque silhouette; travelling wisps
 * add slow turbulence. Breathing and mic level drive scale/brightness.
 *
 * The 252dp box stays the barge-in tap target; every animated value is read
 * inside the draw scope (or graphicsLayer), so frames redraw without ever
 * recomposing the screen. */
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
    val paused = phase == ConversationPhase.Paused
    val animate = !paused && !reduceMotion
    val transition = rememberInfiniteTransition(label = "orb")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing)),
        label = "orb-drift",
    )
    // Slow breath; graphicsLayer reads this in draw, never in composition.
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(4_200, easing = LinearEasing), RepeatMode.Reverse),
        label = "orb-breathe",
    )
    Box(
        modifier = Modifier
            .size(252.dp)
            .graphicsLayer {
                scaleX = if (animate) breathe else 1f
                scaleY = if (animate) breathe else 1f
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
        // Level is read inside draw: 33 mic updates/s invalidate the canvas
        // only, never rebuild the screen.
        Canvas(Modifier.size(252.dp)) {
            val t = if (reduceMotion) 0.6f else drift * 6.2831853f
            val live = if (phase == ConversationPhase.Listening) {
                sqrt(level.value.coerceIn(0f, 1f))
            } else {
                0f
            }
            val energy = when (phase) {
                ConversationPhase.Listening -> 0.35f + 0.65f * live
                ConversationPhase.Speaking -> 0.45f + 0.35f * abs(sin(t * 1.7f)).toFloat()
                ConversationPhase.Transcribing,
                ConversationPhase.Thinking -> 0.42f + 0.12f * sin(t * 2.1f).toFloat()
                ConversationPhase.Off,
                ConversationPhase.Paused -> 0.28f
            }.coerceIn(0.18f, 1f) * (if (paused) 0.7f else 1f)

            // Iridescent drift across the palette; frozen but visible under
            // reduce-motion.
            val hue = if (reduceMotion) 0f else 0.5f + 0.5f * sin(t * 0.65f)
            val core = lerp(VoiceCoreWhite, Color(0xFFFFD9F2), hue * 0.45f)
            val cyan = lerp(VoiceNeonCyan, Color(0xFF7CF9FF), hue * 0.35f)
            val violet = lerp(VoiceNeonViolet, Color(0xFFB073FF), (1f - hue) * 0.35f)
            val deep = Color(0xFF0A1C55)

            val r = size.minDimension * 0.34f
            val cx = size.width / 2f
            val cy = size.height * 0.47f
            val mid = Offset(cx, cy)

            // 1. Feathered bloom halo. Alpha reaches zero at the drawn edge,
            // so there is no silhouette to trace, only light falling off
            // into the near-black stage.
            // Kept inside the 252dp canvas so alpha reaches zero before the
            // canvas clips: a clipped gradient would leave a crisp edge.
            val bloomR = r * 1.35f
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to cyan.copy(alpha = 0.30f * energy),
                        0.26f to violet.copy(alpha = 0.22f * energy),
                        0.58f to deep.copy(alpha = 0.10f * energy),
                        1.00f to Color.Transparent,
                    ),
                    center = mid,
                    radius = bloomR,
                ),
                radius = bloomR,
                center = mid,
                blendMode = BlendMode.Screen,
            )

            // 2. Body: hot core -> cyan -> violet -> deep blue, with alpha
            // dying across the outer third of the radius. No clip path:
            // the gas has no boundary to reveal.
            val bodyR = r * 1.25f
            val bodyCenter = Offset(cx - r * 0.10f, cy - r * 0.14f)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to core.copy(alpha = 0.96f),
                        0.14f to cyan.copy(alpha = 0.88f),
                        0.34f to cyan.copy(alpha = 0.64f),
                        0.54f to violet.copy(alpha = 0.56f),
                        0.68f to violet.copy(alpha = 0.26f),
                        0.84f to deep.copy(alpha = 0.08f),
                        1.00f to Color.Transparent,
                    ),
                    center = bodyCenter,
                    radius = bodyR,
                ),
                radius = bodyR,
                center = bodyCenter,
            )

            // 3. Slow internal turbulence: three travelling wisps, drawn
            // additively so they read as light inside the gas, not shapes.
            for (i in 0 until 3) {
                val phase = t + i * 2.0943951f
                val wx = cx + cos(phase * 0.8f) * r * 0.44f
                val wy = cy + sin(phase * 0.6f) * r * 0.38f
                val wr = r * (0.70f + 0.12f * sin(phase * 1.3f))
                val wCenter = Offset(wx, wy)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            (if (i == 1) violet else cyan)
                                .copy(alpha = (0.18f + 0.12f * energy).coerceIn(0f, 1f)),
                            Color.Transparent,
                        ),
                        center = wCenter,
                        radius = wr,
                    ),
                    radius = wr,
                    center = wCenter,
                    blendMode = BlendMode.Screen,
                )
            }

            // 4. Bright diffuse core, breathing with mic level.
            val hx = cx - r * 0.08f
            val hy = cy - r * 0.12f
            val coreR = r * (0.32f + 0.10f * energy)
            val hotCenter = Offset(hx, hy)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = (0.50f + 0.30f * energy).coerceIn(0f, 1f)),
                        core.copy(alpha = 0.30f * energy),
                        Color.Transparent,
                    ),
                    center = hotCenter,
                    radius = coreR,
                ),
                radius = coreR,
                center = hotCenter,
                blendMode = BlendMode.Screen,
            )
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
