package com.hermes.mobile.ui.screens.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.data.model.ModelInfo
import com.hermes.mobile.ui.components.AudioPlayer
import com.hermes.mobile.ui.components.BigMicButton
import com.hermes.mobile.ui.components.ModelPickerSheet
import com.hermes.mobile.ui.components.SpeakDialog
import com.hermes.mobile.ui.theme.HermesPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class RecordingStatus {
    data object Idle : RecordingStatus()
    data object Recording : RecordingStatus()
    data object Sending : RecordingStatus()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    onExit: () -> Unit,
    onNewMessage: (String, String?, String?, Boolean, String?) -> Unit = { _, _, _, _, _ -> }
) {
    val vm: VoiceViewModel = hiltViewModel()
    val currentModel by vm.currentModel.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val modelsLoading by vm.modelsLoading.collectAsState()
    val context = LocalContext.current

    var recordingStatus by remember { mutableStateOf<RecordingStatus>(RecordingStatus.Idle) }
    var transcription by remember { mutableStateOf<String?>(null) }
    var transcriptionError by remember { mutableStateOf<String?>(null) }
    var showSpeakDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var audioDataUrl by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val statusText = when (recordingStatus) {
        RecordingStatus.Idle -> "Hold to record"
        RecordingStatus.Recording -> "Recording… tap to stop"
        RecordingStatus.Sending -> "Transcribing…"
    }

    val statusColor = when (recordingStatus) {
        RecordingStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        RecordingStatus.Recording -> Color(0xFFE53935)
        RecordingStatus.Sending -> HermesPrimary
    }

    val hasAudioPermission = ActivityCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun startRecording() {
        if (!hasAudioPermission) {
            val activity = context as? android.app.Activity
            if (activity != null) {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.RECORD_AUDIO), 1001
                )
            }
            return
        }
        recordingStatus = RecordingStatus.Recording
        scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = if (minBuf > 0) minBuf * 2 else 8192
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recordingStatus = RecordingStatus.Idle
                return@launch
            }
            recorder.startRecording()
            val audioData = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val endTime = System.currentTimeMillis() + 30000
            while (recordingStatus == RecordingStatus.Recording && System.currentTimeMillis() < endTime) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) audioData.write(buffer, 0, read)
                delay(10)
            }
            recorder.stop()
            recorder.release()
            if (recordingStatus == RecordingStatus.Recording) {
                recordingStatus = RecordingStatus.Sending
                val wavData = encodeWav(audioData.toByteArray(), 16000, 1, 16)
                val b64 = Base64.encodeToString(wavData, Base64.NO_WRAP)
                vm.transcribe(b64,
                    onSuccess = { text ->
                        transcription = text
                        transcriptionError = null
                        recordingStatus = RecordingStatus.Idle
                        if (text.isNotBlank()) onNewMessage(text, null, null, false, null)
                    }
                )
            } else {
                recordingStatus = RecordingStatus.Idle
            }
        }
    }

    fun stopRecording() {
        if (recordingStatus == RecordingStatus.Recording) {
            recordingStatus = RecordingStatus.Idle
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("Voice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium) },
            navigationIcon = {
                IconButton(onClick = onExit) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (!currentModel.isBlank()) {
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
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { showModelPicker = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Change model")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface
            )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.ModelTraining, contentDescription = null, tint = HermesPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    if (modelsLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        val selectedModel = availableModels.firstOrNull { it.id == currentModel }
                        Text(
                            text = selectedModel?.name ?: currentModel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                BigMicButton(
                    isRecording = recordingStatus == RecordingStatus.Recording,
                    onRecordingStart = { startRecording() },
                    onRecordingStop = { stopRecording() }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = transcription != null || transcriptionError != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (transcriptionError != null)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = if (transcriptionError != null) "Error" else "Transcript",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (transcriptionError != null)
                                MaterialTheme.colorScheme.onErrorContainer
                            else
                                HermesPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = transcription ?: transcriptionError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (transcriptionError != null)
                                MaterialTheme.colorScheme.onErrorContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (transcription != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { transcription = null }) {
                                    Text("Clear")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            FilledTonalButton(
                onClick = { showSpeakDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = HermesPrimary.copy(alpha = 0.12f),
                    contentColor = HermesPrimary
                )
            ) {
                Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Speak", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    SnackbarHost(hostState = snackbarHostState)

    if (showSpeakDialog) {
        SpeakDialog(
            onDismiss = { showSpeakDialog = false },
            onSpoken = { dataUrl ->
                audioDataUrl = dataUrl
                showSpeakDialog = false
            }
        )
    }

    audioDataUrl?.let { url ->
        AudioPlayer(dataUrl = url, onDismiss = { audioDataUrl = null })
    }

    if (showModelPicker) {
        ModelPickerSheet(
            availableModels = availableModels,
            currentModel = currentModel,
            modelsLoading = modelsLoading,
            onSelect = { modelId, global ->
                vm.switchModel(modelId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }
}

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