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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
import com.hermes.mobile.ui.screens.chat.ApprovalCard
import com.hermes.mobile.ui.theme.HermesPrimary
import com.hermes.mobile.ui.theme.VoiceCoreWhite
import com.hermes.mobile.ui.theme.VoiceNeonBlue
import com.hermes.mobile.ui.theme.VoiceNeonCyan
import com.hermes.mobile.ui.theme.VoiceNeonMagenta
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

/** The hands-free surface. This is the neon energy sphere this app already
 * shipped once — the look behind `SphereGLRenderer` in the v2.x voice screen —
 * rebuilt in Compose because a 252dp orb needs no GL surface: a dark glass
 * body, a **luminous rim** (shader: `palette * smoothstep(0.68, 0.99, d) *
 * 2.2`), wispy dashed filaments inside it, Siri rings sweeping outward and a
 * white-hot core. The rim is what makes the edge read — a soft glow alone has
 * no silhouette, which is exactly what was missing. It pulses with your voice
 * and is the barge-in button while a reply plays.
 *
 * shortcut: every layer redraws on each animation frame (~16 gradient draws);
 * move the static body to drawWithCache or cap the frame clock if battery
 * ever becomes an issue on this screen. */
@Composable
private fun ConversationOrb(
    phase: ConversationPhase,
    level: State<Float>,
    onTap: () -> Unit,
) {
    val (colorA, colorB) = orbPalette(phase)
    val paused = phase == ConversationPhase.Paused
    // Respect the system "reduce motion" / "remove animations" toggle (the OS
    // setting that maps to ANIMATOR_DURATION_SCALE == 0). Observed live via a
    // ContentObserver so flipping it mid-conversation takes effect without
    // leaving the screen. `reduceMotion` is a plain State (changes rarely); the
    // per-frame animation values (spin / ringT / breathe) are read in the draw
    // scopes below, never here in composition, so a toggle recomposes once
    // and then only redraws the orb — it does not make the screen recompose.
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
    // `animate` = motion is allowed: not paused AND not reduce-motion.
    val animate = !paused && !reduceMotion
    val ringCount = when (phase) {
        ConversationPhase.Listening -> 4
        ConversationPhase.Transcribing,
        ConversationPhase.Thinking,
        ConversationPhase.Speaking -> 3
        ConversationPhase.Off,
        ConversationPhase.Paused -> 2
    }
    val ringSpeed = when (phase) {
        ConversationPhase.Listening -> 1.3f
        ConversationPhase.Transcribing,
        ConversationPhase.Thinking -> 0.8f
        ConversationPhase.Speaking -> 1f
        ConversationPhase.Off,
        ConversationPhase.Paused -> 0.5f
    }
    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing)),
        label = "orb-spin",
    )
    // Sweep at the state's own ring speed, so the wrap never stutters (a
    // fixed period times a fractional speed jumps when the clock resets).
    val ringT by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween((2_600 / ringSpeed).toInt(), easing = LinearEasing)
        ),
        label = "orb-rings",
    )
    // 4s reverse breath, same as the old sphere.
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "orb-breathe",
    )
    // The per-frame values spin / ringT / breathe are read inside the draw
    // scopes below (Canvas + graphicsLayer), never here, so animation frames
    // invalidate the orb's draw only — they never recompose this composable.
    Box(
        modifier = Modifier
            .size(252.dp)
            .graphicsLayer {
                // Breathe only when motion is allowed; otherwise reset to a neutral
                // scale so a stale pre-pause value (up to 1.02) doesn't linger.
                // Read `breathe` in draw scope so it never forces a recomposition.
                if (animate) {
                    scaleX = breathe
                    scaleY = breathe
                } else {
                    scaleX = 1f
                    scaleY = 1f
                }
            }
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
        Canvas(Modifier.size(212.dp).align(Alignment.Center)) {
            val live =
                if (phase == ConversationPhase.Listening) sqrt(level.value.coerceIn(0f, 1f)) else 0f
            // Freeze the clock while paused or reduce-motion (the canvas still
            // redraws each frame — see the shortcut note above). Read `spin` in
            // draw scope, never composition.
            val turn = if (!animate) 0f else (spin * Math.PI / 180.0).toFloat()
            // Wisp drift + ring sweep keep moving even when paused (the calm
            // "breathing" look the original had); only a reduce-motion user
            // freezes them. Read in draw scope so the sweep never recomposes.
            val wispSpin = if (reduceMotion) 0f else spin
            val ringNow = if (reduceMotion) 0.5f else ringT
            val d = size.minDimension
            val r = d / 2f
            val mid = Offset(r, r)
            // THINKING sweeps violet -> magenta and back (the shader grew
            // uHueShift with time so the mix cycled instead of clamping).
            val shift = when (phase) {
                ConversationPhase.Transcribing,
                ConversationPhase.Thinking -> (turn / 6.2831853f * 2.7f) % 1f
                else -> 0f
            }
            val ca = lerp(colorA, colorB, shift)
            val cb = lerp(colorB, colorA, shift)
            // SPEAKING has no mic, so it uses the shader's synthetic
            // amplitude: the reply pulses the core instead of freezing it.
            val vol = when (phase) {
                ConversationPhase.Listening -> live
                ConversationPhase.Speaking -> 0.35f + 0.55f * abs(sin(turn * 1.6)).toFloat()
                else -> 0f
            }
            val pulse = when (phase) {
                ConversationPhase.Listening -> 0.75f + 0.55f * vol
                ConversationPhase.Transcribing,
                ConversationPhase.Thinking -> 0.85f + 0.15f * sin(turn * 2.4).toFloat()
                ConversationPhase.Speaking -> 0.9f + 0.35f * vol
                ConversationPhase.Off,
                ConversationPhase.Paused -> 0.45f + 0.35f * (0.5f + 0.5f * sin(turn * 0.8).toFloat())
            } * (if (paused) 0.5f else 1f)

            // Outer halo. The old sphere used a separate blurred 440dp
            // Canvas; one wide, soft gradient reads the same.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ca.copy(alpha = 0.14f * pulse),
                        cb.copy(alpha = 0.06f * pulse),
                        Color.Transparent,
                    ),
                    center = mid,
                    radius = r * 1.12f,
                ),
                radius = r * 1.12f,
                center = mid,
            )
            clipPath(Path().apply { addOval(Rect(0f, 0f, d, d)) }) {
                // Dark glass body + haze.
                drawCircle(color = VoiceCoreWhite.copy(alpha = 0.02f), radius = r, center = mid)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, VoiceCoreWhite.copy(alpha = 0.08f)),
                        center = mid,
                        radius = r,
                    ),
                    radius = r,
                    center = mid,
                )
                // Wispy plasma ribbons: six dashed cubic curves on a
                // golden-angle spread, drifting with the spin. This stands in
                // for the shader's curl-warped noise zero-crossings — a
                // per-pixel noise field in Compose would cost far more than
                // it is worth at this size.
                val toRad = (Math.PI / 180.0).toFloat()
                fun at(radius: Float, angle: Float) =
                    Offset(mid.x + cos(angle) * radius, mid.y + sin(angle) * radius)
                for (i in 0 until 6) {
                    val a0 = (i * 137.5f + wispSpin * (0.20f + 0.04f * i)) * toRad
                    val out = r * (0.62f + 0.30f * ((i * 0.618f) % 1f))
                    val in1 = r * (0.14f + 0.34f * ((i * 0.382f + 0.21f) % 1f))
                    val in2 = r * (0.14f + 0.34f * ((i * 0.271f + 0.63f) % 1f))
                    val sweep = 1.5f + 1.1f * ((i * 0.427f) % 1f)
                    val head = at(out, a0)
                    val c1 = at(in1, a0 + sweep * 0.30f)
                    val c2 = at(in2, a0 + sweep * 0.72f)
                    val tail = at(out * (0.72f + 0.25f * ((i * 0.55f) % 1f)), a0 + sweep)
                    val wisp = Path().apply {
                        moveTo(head.x, head.y)
                        cubicTo(c1.x, c1.y, c2.x, c2.y, tail.x, tail.y)
                    }
                    drawPath(
                        path = wisp,
                        color = lerp(ca, cb, (i * 0.618f) % 1f)
                            .copy(alpha = (0.45f + 0.35f * vol) * pulse),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(5.dp.toPx(), 9.dp.toPx()),
                                0f,
                            ),
                        ),
                    )
                }
                // Siri rings — the shader's pow(fract(d * rings - t * speed), 14),
                // softened to u^4 so the sweep is visible while it travels.
                val ringColor = lerp(cb, Color.White, 0.3f)
                for (i in 0 until ringCount) {
                    val u = (i.toFloat() / ringCount + ringNow) % 1f
                    // Under reduce-motion the sweep is frozen, so bypass the
                    // travel-based fade or the fixed u=0.5 position renders
                    // near-invisible; show a static, visible ring instead.
                    val fade = if (reduceMotion) 0.4f else u * u * u * u * (1f - smoothstep(0.90f, 0.99f, u))
                    drawCircle(
                        color = ringColor.copy(
                            alpha = (fade * (1.1f + vol * 2.4f) * pulse * 0.6f).coerceIn(0f, 1f)
                        ),
                        radius = r * (0.12f + 0.86f * u),
                        center = mid,
                        style = Stroke(width = 1.4.dp.toPx()),
                    )
                }
                // The luminous rim: a bright band peaking just inside the
                // silhouette, then a crisp hued edge stroke.
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.70f to Color.Transparent,
                            0.90f to ca.copy(alpha = 0.22f * pulse),
                            0.98f to cb.copy(alpha = 0.80f * pulse),
                            1.00f to Color.Transparent,
                        ),
                        center = mid,
                        radius = r,
                    ),
                    radius = r,
                    center = mid,
                )
                // Directional hue, like the shader's mix(colorA, colorB, x).
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(ca, cb),
                        start = Offset(0f, r),
                        end = Offset(d, r),
                    ),
                    radius = r * 0.985f,
                    center = mid,
                    style = Stroke(width = 1.6.dp.toPx()),
                    alpha = (0.7f + 0.3f * pulse).coerceIn(0f, 1f),
                )
                // White-hot core: mix(palette, white, 0.9) * exp(-d * 5.0),
                // pushed hotter by volume.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f * pulse),
                            lerp(ca, Color.White, 0.5f).copy(alpha = 0.30f * pulse),
                            Color.Transparent,
                        ),
                        center = mid,
                        radius = r * 0.34f,
                    ),
                    radius = r * 0.34f,
                    center = mid,
                )
            }
        }
    }
}

/** GLSL's smoothstep, which the rim math above mirrors. */
private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** The neon palette of this app's own v2.x voice sphere (the `VoiceNeon*`
 * tokens in theme/Color.kt): outer/left, then inner/right, per phase. */
private fun orbPalette(phase: ConversationPhase): Pair<Color, Color> = when (phase) {
    ConversationPhase.Listening -> VoiceNeonCyan to VoiceNeonBlue
    ConversationPhase.Transcribing,
    ConversationPhase.Thinking -> VoiceNeonViolet to VoiceNeonMagenta
    ConversationPhase.Speaking -> VoiceNeonBlue to VoiceCoreWhite
    ConversationPhase.Off,
    ConversationPhase.Paused -> VoiceNeonCyan to VoiceNeonBlue
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
