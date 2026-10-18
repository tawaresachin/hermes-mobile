package com.hermes.mobile.ui.screens.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.hermes.mobile.ui.components.BigMicButton
import com.hermes.mobile.ui.components.ModelPickerSheet
import com.hermes.mobile.ui.screens.chat.ApprovalCard
import com.hermes.mobile.ui.theme.HermesPrimary
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private enum class VoicePhase { Idle, Recording, Transcribing, Thinking, Speaking }

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
    val scope = rememberCoroutineScope()

    // Conversation mode keeps the whole loop in the ViewModel; the screen
    // only drives the microphone and the player the phase asks for.
    val turns by vm.turns.collectAsState()
    val convPhase by vm.conversationPhase.collectAsState()
    val convStatus by vm.conversationStatus.collectAsState()
    val convLevel = vm.conversationLevel.collectAsState()
    val pendingApproval by vm.pendingApproval.collectAsState()
    val ttsDataUrl by vm.ttsDataUrl.collectAsState()
    val conversationOn = convPhase != ConversationPhase.Off

    var phase by remember { mutableStateOf(VoicePhase.Idle) }
    var lastPressAt by remember { mutableLongStateOf(0L) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showSessionSheet by remember { mutableStateOf(false) }
    var releaseRequested by remember { mutableStateOf(false) }

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
        else {
            vm.interruptPlayback()
            if (phase == VoicePhase.Speaking) phase = VoicePhase.Idle
        }
    }

    val statusText = when (phase) {
        VoicePhase.Idle -> "Hold to speak, release to send"
        VoicePhase.Recording -> "Listening…"
        VoicePhase.Transcribing -> "Transcribing…"
        VoicePhase.Thinking -> "Hermes is thinking…"
        VoicePhase.Speaking -> "Speaking…"
    }
    val statusColor = when (phase) {
        VoicePhase.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        VoicePhase.Recording -> Color(0xFFE53935)
        VoicePhase.Transcribing -> HermesPrimary
        VoicePhase.Thinking -> HermesPrimary
        VoicePhase.Speaking -> SuccessTint
    }

    val hasAudioPermission = ActivityCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun speakReply(text: String) {
        if (text.isBlank()) { phase = VoicePhase.Idle; return }
        phase = VoicePhase.Speaking
        // The playback payload lives in the ViewModel now, so both voice
        // paths render through one AutoPlayAudio block.
        vm.speakReply(text) { msg -> statusMsg = msg; phase = VoicePhase.Idle }
    }

    fun sendTranscript(text: String) {
        if (text.isBlank()) {
            statusMsg = "Couldn't hear anything — try again"
            phase = VoicePhase.Idle
            return
        }
        statusMsg = null
        phase = VoicePhase.Thinking
        vm.sendTranscribed(
            text,
            onComplete = { reply -> speakReply(reply) },
            onError = { msg -> statusMsg = msg; phase = VoicePhase.Idle },
        )
    }

    fun startRecording() {
        if (phase != VoicePhase.Idle) return
        if (!hasAudioPermission) {
            val activity = context as? android.app.Activity
            if (activity != null) {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.RECORD_AUDIO), 1001
                )
            }
            return
        }
        statusMsg = null
        lastPressAt = System.currentTimeMillis()
        phase = VoicePhase.Recording
        scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = if (minBuf > 0) minBuf * 2 else 8192
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }  // failed init still holds resources
                phase = VoicePhase.Idle
                statusMsg = "Microphone unavailable"
                return@launch
            }
            recorder.startRecording()
            val audioData = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val endTime = System.currentTimeMillis() + 30000
            releaseRequested = false
            val heldMs: Long
            // Blocking AudioRecord.read on Main stalls the UI for the whole
            // 30s window (~100-250ms per frame fill); run capture on IO.
            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    while (!releaseRequested && System.currentTimeMillis() < endTime) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) audioData.write(buffer, 0, read)
                    }
                }
            } finally {
                // Back-press / scope cancellation must not leave the mic held:
                // release runs on EVERY exit path, not just the happy one.
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
            heldMs = System.currentTimeMillis() - lastPressAt
            if (heldMs < 300 || audioData.size() < 3200) {
                phase = VoicePhase.Idle
                statusMsg = "Hold the mic while speaking"
                return@launch
            }
            // Release = send. Transcribe, then immediately run the turn.
            phase = VoicePhase.Transcribing
            val wavData = encodeWav(audioData.toByteArray(), 16000, 1, 16)
            val b64 = Base64.encodeToString(wavData, Base64.NO_WRAP)
            vm.transcribe(
                b64,
                onSuccess = { text -> sendTranscript(text.trim()) },
                onError = { msg -> statusMsg = msg; phase = VoicePhase.Idle }
            )
        }
    }

    fun stopRecording() {
        // Finger released: flip the gate so the read-loop exits and runs
        // encode → transcribe → send. Recording state itself follows the
        // loop's phase write, so the button never sticks.
        releaseRequested = true
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
            if (turns.isEmpty() && statusMsg == null) {
                Text(
                    "Press and hold the mic to speak. Release to send — the answer is spoken back.",
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
            AnimatedVisibility(visible = statusMsg != null, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    text = statusMsg ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }
        }

        // Mic + status
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
            if (conversationOn) {
                ConversationOrb(
                    phase = convPhase,
                    level = convLevel,
                    onTap = { vm.interruptPlayback() },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = conversationStatusText(convPhase, convStatus),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (convPhase == ConversationPhase.Paused)
                        MaterialTheme.colorScheme.error else HermesPrimary,
                    textAlign = TextAlign.Center
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
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { vm.stopConversation() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop conversation")
                }
            } else {
                Box(
                    modifier = Modifier.size(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BigMicButton(
                        isRecording = phase == VoicePhase.Recording,
                        onRecordingStart = { startRecording() },
                        onRecordingStop = { stopRecording() }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        if (hasAudioPermission) {
                            vm.startConversation()
                        } else {
                            (context as? android.app.Activity)?.let { activity ->
                                ActivityCompat.requestPermissions(
                                    activity, arrayOf(Manifest.permission.RECORD_AUDIO), 1001
                                )
                            }
                        }
                    },
                    // Never open a second AudioRecord over a hold-to-talk take.
                    enabled = phase == VoicePhase.Idle
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start conversation")
                }
                // Why a start was refused (not connected, reply running, …).
                convStatus?.let { msg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    // TTS playback — auto-plays the assistant reply, silent UI. The payload
    // is clearable (barge-in and playback end both clear it), so the mode's
    // transitions live in the ViewModel while hold-to-talk resets its own
    // phase here.
    ttsDataUrl?.let { url ->
        AutoPlayAudio(
            dataUrl = url,
            onFinished = {
                vm.onPlaybackFinished()
                if (phase == VoicePhase.Speaking) phase = VoicePhase.Idle
            }
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

private val SuccessTint = Color(0xFF2E7D32)

/** The hands-free surface: a neon bubble that drifts while it waits, swells
 * with your voice while listening, quickens while Hermes works, and is the
 * barge-in button while a reply plays (tap = stop it and listen again). */
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
                    ConversationPhase.Transcribing -> 3_200
                    ConversationPhase.Speaking -> 6_000
                    else -> 9_000
                },
                easing = LinearEasing
            )
        ),
        label = "orb-spin",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            tween(
                when (phase) {
                    ConversationPhase.Thinking,
                    ConversationPhase.Transcribing -> 1_200
                    ConversationPhase.Speaking -> 900
                    else -> 2_600
                },
                easing = FastOutSlowInEasing
            ),
            RepeatMode.Reverse
        ),
        label = "orb-breathe",
    )
    Box(
        modifier = Modifier
            .size(176.dp)
            .clip(CircleShape)
            .clickable(enabled = phase == ConversationPhase.Speaking, onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        // Read the level inside draw, not composition: 33 updates/s must
        // invalidate the canvas only, never rebuild the screen.
        Canvas(Modifier.fillMaxSize()) {
            // sqrt: RMS is dominated by loud peaks; without the curve a soft
            // speaker barely moves the bubble.
            val live =
                if (phase == ConversationPhase.Listening) sqrt(level.value.coerceIn(0f, 1f)) else 0f
            val dim = if (paused) 0.5f else 1f
            // A paused loop costs nothing: not reading the animated values
            // stops the canvas invalidating until the phase moves again.
            val rot = if (paused) 0f else spin
            val swell = if (paused) 1f else breathe
            val center = Offset(size.width / 2f, size.height / 2f)
            val core = size.minDimension * 0.20f * swell * (1f + live * 0.45f)

            // Halo first: light bleeding past the rim is what reads as "neon".
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = 0.30f * dim), Color.Transparent),
                    center = center,
                    radius = core * 3.6f,
                ),
                radius = core * 3.6f,
                center = center,
            )
            // Three orbiting blobs at different speeds: the Siri-ish morph,
            // with no shader and no asset.
            repeat(3) { i ->
                val angle = Math.toRadians(rot * (1.0 + i * 0.35) + i * 120.0)
                val orbit = core * (0.55f + 0.18f * i)
                val blob = Offset(
                    center.x + cos(angle).toFloat() * orbit,
                    center.y + sin(angle).toFloat() * orbit,
                )
                val blobRadius = core * (1.2f - 0.18f * i)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            (if (i == 1) accent else tint).copy(alpha = 0.40f * dim),
                            Color.Transparent,
                        ),
                        center = blob,
                        radius = blobRadius,
                    ),
                    radius = blobRadius,
                    center = blob,
                )
            }
            // Tinted core + bright rim.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        tint.copy(alpha = 0.92f * dim),
                        tint.copy(alpha = 0.30f * dim),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = core * 1.5f,
                ),
                radius = core * 1.5f,
                center = center,
            )
            drawCircle(
                color = tint.copy(alpha = 0.85f * dim),
                radius = core * 1.18f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        Icon(
            imageVector = orbIcon(phase),
            contentDescription = if (phase == ConversationPhase.Speaking) "Stop the reply and listen" else null,
            // Ink on the bright core; the dimmed paused bubble needs white.
            tint = if (paused) Color.White.copy(alpha = 0.80f)
            else Color(0xFF0B1220).copy(alpha = 0.88f),
            modifier = Modifier.size(38.dp),
        )
    }
}

/** Siri-ish gradient pairs: a warm second hue keeps the glow from reading as
 * one flat blob. */
private fun orbColors(phase: ConversationPhase): Pair<Color, Color> = when (phase) {
    ConversationPhase.Listening -> Color(0xFF38BDF8) to Color(0xFF6366F1)
    ConversationPhase.Transcribing -> Color(0xFF22D3EE) to Color(0xFF60A5FA)
    ConversationPhase.Thinking -> Color(0xFFA78BFA) to Color(0xFFF472B6)
    ConversationPhase.Speaking -> Color(0xFF34D399) to Color(0xFF38BDF8)
    ConversationPhase.Paused -> Color(0xFF94A3B8) to Color(0xFF64748B)
    ConversationPhase.Off -> HermesPrimary to HermesPrimary
}

private fun orbIcon(phase: ConversationPhase) = when (phase) {
    ConversationPhase.Thinking -> Icons.Filled.GraphicEq
    ConversationPhase.Speaking -> Icons.AutoMirrored.Filled.VolumeUp
    ConversationPhase.Paused -> Icons.Filled.Pause
    else -> Icons.Filled.Mic // listening, transcribing, off
}

private fun conversationStatusText(phase: ConversationPhase, status: String?): String = when (phase) {
    ConversationPhase.Listening -> "Listening… speak any time"
    ConversationPhase.Transcribing -> "Transcribing…"
    ConversationPhase.Thinking -> "Hermes is thinking…"
    ConversationPhase.Speaking -> "Speaking… tap the circle to interrupt"
    ConversationPhase.Paused -> status ?: "Paused"
    ConversationPhase.Off -> status.orEmpty()
}
