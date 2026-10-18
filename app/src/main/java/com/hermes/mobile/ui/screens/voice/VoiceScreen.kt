package com.hermes.mobile.ui.screens.voice

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
        ModelPickerSheet(
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

/** The hands-free surface: a GPT-live style bubble — a soft, molten sphere
 * with slow counter-rotating lobes, no rim and no icon, that swells with your
 * voice and doubles as the barge-in button while a reply plays. */
@Composable
private fun ConversationOrb(
    phase: ConversationPhase,
    level: State<Float>,
    onTap: () -> Unit,
) {
    val (tint, accent) = orbColors(phase)
    val paused = phase == ConversationPhase.Paused
    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(
                when (phase) {
                    ConversationPhase.Thinking,
                    ConversationPhase.Transcribing -> 9_000
                    ConversationPhase.Speaking -> 14_000
                    else -> 26_000
                },
                easing = LinearEasing
            )
        ),
        label = "orb-spin",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            tween(3_400, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "orb-breathe",
    )
    // A slow second clock stops the lobes from tracing the same circle, which
    // is what makes the motion read as fluid rather than mechanical.
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(7_300, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "orb-drift",
    )
    Box(
        modifier = Modifier
            .size(220.dp)
            .clip(CircleShape)
            .clickable(
                enabled = phase == ConversationPhase.Speaking || phase == ConversationPhase.Off,
                onClickLabel = when (phase) {
                    ConversationPhase.Off -> "Start conversation"
                    ConversationPhase.Speaking -> "Stop the reply and listen"
                    else -> null
                },
                onClick = onTap,
            ),
    ) {
        // Read the level inside draw, not composition: 33 updates/s must
        // invalidate the canvas only, never rebuild the screen.
        Canvas(Modifier.fillMaxSize()) {
            val live =
                if (phase == ConversationPhase.Listening) sqrt(level.value.coerceIn(0f, 1f)) else 0f
            val dim = if (paused) 0.45f else 1f
            // A paused loop costs nothing: not reading the animated values
            // stops the canvas invalidating until the phase moves again.
            val rot = if (paused) 0f else spin
            val swell = if (paused) 1f else breathe
            val flow = if (paused) 0f else drift
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            val core = radius * 0.56f * swell * (1f + live * 0.10f)

            // Light bleeding past the rim: the glow is the "live" part.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = 0.20f * dim), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            // Four lobes, alternating direction, orbiting and breathing out of
            // phase. No shader, no asset — just overlapping soft gradients.
            repeat(4) { i ->
                val direction = if (i % 2 == 0) 1.0 else -1.0
                val angle = Math.toRadians(
                    rot * direction * (1.0 + 0.22 * i) + i * 90.0 + flow * 18.0
                )
                val orbit = core * (0.30f + 0.07f * i + 0.12f * flow)
                val blob = Offset(
                    center.x + cos(angle).toFloat() * orbit,
                    center.y + sin(angle).toFloat() * orbit * 0.86f,
                )
                val blobRadius = core * (1.00f - 0.09f * i)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            (if (i % 2 == 1) accent else tint).copy(alpha = 0.40f * dim),
                            Color.Transparent,
                        ),
                        center = blob,
                        radius = blobRadius,
                    ),
                    radius = blobRadius,
                    center = blob,
                )
            }
            // Body: a lit sphere, not a flat disc — the gradient centre sits
            // above the middle so the light appears to fall from the top left.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.60f * dim),
                        tint.copy(alpha = 0.78f * dim),
                        tint.copy(alpha = 0.32f * dim),
                        Color.Transparent,
                    ),
                    center = Offset(center.x - core * 0.14f, center.y - core * 0.18f),
                    radius = core * 1.42f,
                ),
                radius = core * 1.42f,
                center = center,
            )
            // Soft specular cap.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.34f * dim),
                        Color.Transparent,
                    ),
                    center = Offset(center.x - core * 0.34f, center.y - core * 0.40f),
                    radius = core * 0.92f,
                ),
                radius = core * 0.92f,
                center = Offset(center.x - core * 0.34f, center.y - core * 0.40f),
            )
        }
    }
}

/** Gradient pairs: a second hue keeps the sphere from reading as one flat
 * blob (and gives the lobes something to flow between). */
private fun orbColors(phase: ConversationPhase): Pair<Color, Color> = when (phase) {
    ConversationPhase.Listening -> Color(0xFF38BDF8) to Color(0xFF6366F1)
    ConversationPhase.Transcribing -> Color(0xFF22D3EE) to Color(0xFF60A5FA)
    ConversationPhase.Thinking -> Color(0xFFA78BFA) to Color(0xFFF472B6)
    ConversationPhase.Speaking -> Color(0xFF34D399) to Color(0xFF38BDF8)
    ConversationPhase.Paused -> Color(0xFF94A3B8) to Color(0xFF64748B)
    ConversationPhase.Off -> Color(0xFF5B8DEF) to Color(0xFF8B5CF6)
}

private fun conversationStatusText(phase: ConversationPhase, status: String?): String = when (phase) {
    ConversationPhase.Listening -> "Listening… speak any time"
    ConversationPhase.Transcribing -> "Transcribing…"
    ConversationPhase.Thinking -> "Hermes is thinking…"
    ConversationPhase.Speaking -> "Speaking… tap the circle to interrupt"
    ConversationPhase.Paused -> status ?: "Paused"
    ConversationPhase.Off -> status ?: "Tap to start — hands-free, and Hermes speaks back."
}
