package com.hermes.mobile.ui.screens.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.ui.components.AutoPlayAudio
import com.hermes.mobile.ui.components.BigMicButton
import com.hermes.mobile.ui.components.ModelPickerSheet
import com.hermes.mobile.ui.theme.HermesPrimary
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class VoicePhase { Idle, Recording, Transcribing, Thinking, Speaking }

private data class VoiceTurn(
    val userText: String,
    var assistantText: String = "",
    var done: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(onExit: () -> Unit) {
    val vm: VoiceViewModel = hiltViewModel()
    val currentModel by vm.currentModel.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val modelsLoading by vm.modelsLoading.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val selectedSessionId by vm.selectedSessionId.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(VoicePhase.Idle) }
    var lastPressAt by remember { mutableLongStateOf(0L) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showSessionSheet by remember { mutableStateOf(false) }
    var ttsDataUrl by remember { mutableStateOf<String?>(null) }
    var releaseRequested by remember { mutableStateOf(false) }
    val turns = remember { mutableStateListOf<VoiceTurn>() }
    val streamBuilder = remember { StringBuilder() }

    // Back closes playback/sheets first, then exits the screen.
    BackHandler(enabled = ttsDataUrl != null || showModelPicker || showSessionSheet) {
        if (showModelPicker) showModelPicker = false
        else if (showSessionSheet) showSessionSheet = false
        else ttsDataUrl = null
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
        vm.speak(
            text.take(2000), // voice answers stay snappy; bubbles show the full text
            onSuccess = { url -> ttsDataUrl = url },
            onError = { statusMsg = it; phase = VoicePhase.Idle }
        )
    }

    fun sendTranscript(text: String) {
        if (text.isBlank()) {
            statusMsg = "Couldn't hear anything — try again"
            phase = VoicePhase.Idle
            return
        }
        statusMsg = null
        val turn = VoiceTurn(userText = text)
        turns.add(turn)
        phase = VoicePhase.Thinking
        vm.sendTranscribed(
            text = text,
            onChunk = { chunk ->
                streamBuilder.append(chunk)
                // mutate last item; trigger recomposition via list swap
                turns[turns.size - 1] = turn.copy(assistantText = streamBuilder.toString())
            },
            onComplete = { reply ->
                streamBuilder.setLength(0)
                turns[turns.size - 1] = turn.copy(
                    assistantText = reply.ifBlank { turn.assistantText }, done = true
                )
                speakReply(reply.ifBlank { turn.assistantText })
            },
            onError = { msg ->
                streamBuilder.setLength(0)
                turns[turns.size - 1] = turn.copy(assistantText = "", done = true)
                turns.removeLastOrNull()
                statusMsg = msg
                phase = VoicePhase.Idle
            }
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
            title = { Text("Voice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium) },
            navigationIcon = {
                IconButton(onClick = onExit) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
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
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
        }
    }

    // TTS playback — auto-plays the assistant reply, silent UI
    ttsDataUrl?.let { url ->
        AutoPlayAudio(
            dataUrl = url,
            onFinished = { ttsDataUrl = null; phase = VoicePhase.Idle }
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
                            turns.clear()
                            streamBuilder.setLength(0)
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
                                    turns.clear()
                                    streamBuilder.setLength(0)
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

private fun encodeWav(
    pcmData: ByteArray,
    sampleRate: Int,
    channels: Int,
    bitsPerSample: Int
): ByteArray {
    val totalDataLen = pcmData.size + 36
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val buffer = ByteArrayOutputStream(44 + pcmData.size)

    buffer.write("RIFF".toByteArray())
    writeInt(buffer, totalDataLen)
    buffer.write("WAVE".toByteArray())

    buffer.write("fmt ".toByteArray())
    writeInt(buffer, 16)
    writeShort(buffer, 1.toShort())
    writeShort(buffer, channels.toShort())
    writeInt(buffer, sampleRate)
    writeInt(buffer, byteRate)
    writeShort(buffer, (channels * bitsPerSample / 8).toShort())
    writeShort(buffer, bitsPerSample.toShort())

    buffer.write("data".toByteArray())
    writeInt(buffer, pcmData.size)
    buffer.write(pcmData)

    return buffer.toByteArray()
}

private fun writeInt(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xff)
    out.write((value shr 8) and 0xff)
    out.write((value shr 16) and 0xff)
    out.write((value shr 24) and 0xff)
}

private fun writeShort(out: ByteArrayOutputStream, value: Short) {
    out.write(value.toInt() and 0xff)
    out.write((value.toInt() shr 8) and 0xff)
}
