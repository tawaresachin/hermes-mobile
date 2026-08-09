package com.hermes.mobile.ui.screens.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.DiagLog
import com.hermes.mobile.data.model.ModelInfo
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.components.HermesWatermark
import com.hermes.mobile.ui.components.ModelPickerSheet
import com.hermes.mobile.ui.theme.LocalDarkTheme
import com.hermes.mobile.ui.theme.VoiceNeonBlue
import com.hermes.mobile.ui.theme.VoiceNeonCyan
import com.hermes.mobile.ui.theme.VoiceNeonRed
import com.hermes.mobile.ui.theme.VoiceNeonViolet
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.coroutines.resume
import javax.inject.Inject
import androidx.compose.foundation.gestures.detectTapGestures
import android.os.SystemClock
import android.view.Choreographer
import com.hermes.mobile.voice.AdaptiveEndpointing
import com.hermes.mobile.voice.BargeInDetector
import com.hermes.mobile.voice.EchoRejection
import com.hermes.mobile.voice.VoiceTuning
import java.util.concurrent.atomic.AtomicBoolean

// ═══════════════════════════════════════════════════════════════
// VoiceViewModel — JARVIS Sphere voice loop
// ═══════════════════════════════════════════════════════════════

@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val repository: HermesRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ── Session (voice queries go into their own session) ──
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    // ── Voice mode state ──
    private val _voiceModeState = MutableStateFlow(SphereState.IDLE)
    val voiceModeState: StateFlow<SphereState> = _voiceModeState.asStateFlow()

    private val _voiceTranscript = MutableStateFlow("")
    val voiceTranscript: StateFlow<String> = _voiceTranscript.asStateFlow()

    private val _ttsProgress = MutableStateFlow(0f)
    val ttsProgress: StateFlow<Float> = _ttsProgress.asStateFlow()

    private val _voiceAmplitude = MutableStateFlow(0f)
    val voiceAmplitude: StateFlow<Float> = _voiceAmplitude.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _language = MutableStateFlow(VoiceLanguage.ENGLISH)
    val language: StateFlow<VoiceLanguage> = _language.asStateFlow()

    // ── Reply mode: stream (per-sentence, fast start) vs full (whole
    // reply synthesized as a few large chunks — smoother prosody, slower
    // first audio). Persisted across sessions. ──
    private val _fullResponseMode = MutableStateFlow(
        context.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)
            .getBoolean("full_response_mode", false)
    )
    val fullResponseMode: StateFlow<Boolean> = _fullResponseMode.asStateFlow()

    fun setFullResponseMode(on: Boolean) {
        _fullResponseMode.value = on
        context.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("full_response_mode", on).apply()
    }

    // ── Model selection (same live picker as chat — server-side active model) ──
    private val _currentModel = MutableStateFlow("")
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    private var voiceModeJob: Job? = null
    private var ttsPlaybackJob: Job? = null
    private var bargeInJob: Job? = null
    private var errorRevertJob: Job? = null
    private val bargeInTriggered = AtomicBoolean(false)
    @Volatile private var vadDeafenUntilMs = 0L
    @Volatile private var ttsPlayingNow = false
    /** When the current SPEAKING cycle began — barge-in is ignored for the
     *  first 2s (the user's speech is settling + the loudest echo burst). */
    @Volatile private var speakingCycleStartedAt = 0L
    private var initJob: Job? = null
    @Volatile private var lastSpokenSentence: String = ""
    @Volatile private var lastSpokenAt: Long = 0L
    @Volatile private var voiceRunning = false
    @Volatile private var voicePaused = false

    init {
        // Bottom-tab open: RESUME the latest session (voice keeps talking in
        // the same conversation). A fresh session only when none exists.
        initJob = viewModelScope.launch {
            try {
                if (_sessionId.value == null) {
                    val last = repository.getLastSession()
                    _sessionId.value = if (last != null) last.id else repository.createSession().id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "Failed to create session: ${e.message}"
            }
        }
    }

    /** Home voice card: force a NEW session (the tab itself resumes last). */
    fun startNewSession() {
        initJob?.cancel()
        initJob = viewModelScope.launch {
            try {
                val session = repository.createSession()
                _sessionId.value = session.id
                lastSpokenSentence = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "Failed to create session: ${e.message}"
            }
        }
    }

    fun startVoiceLoop() {
        voiceRunning = true
        voicePaused = false
        voiceModeJob?.cancel()
        voiceModeJob = viewModelScope.launch {
            // Wait for session init
            var session = _sessionId.value
            while (session == null && isActive) {
                session = _sessionId.value
                delay(100)
            }
            if (session == null) {
                _voiceTranscript.value = "No session — check connection"
                setVoiceModeState(SphereState.AWAITING)
                return@launch
            }
            try {
                while (isActive) {
                    // ── LISTENING ──
                    // Fresh barge-in latch per cycle — a stale trigger must
                    // not suppress this turn's recognition/enqueues.
                    bargeInTriggered.set(false)
                    setVoiceModeState(SphereState.LISTENING)
                    _voiceTranscript.value = "Listening…"
                    val recognized = listenOnce()
                    if (recognized == null) {
                        // no speech / recognizer hiccup — brief pause, re-listen
                        delay(400)
                        continue
                    }
                    // JARVIS-style echo rejection: right after TTS the recognizer
                    // may pick up the speaker's tail or our own voice. If the
                    // transcript is (mostly) the last spoken sentence, skip it;
                    // if the user's speech is appended after an echo prefix,
                    // salvage just their part. Only applies within 4s of TTS —
                    // a stale match later would wrongly eat a real question.
                    val cleaned = if (System.currentTimeMillis() - lastSpokenAt < 4000L) {
                        EchoRejection.stripEchoPrefix(recognized, lastSpokenSentence)
                    } else {
                        recognized
                    }
                    if (cleaned.isBlank()) continue
                    appendVoiceTranscript(cleaned)

                    // ── THINKING + STREAMING ──
                    setVoiceModeState(SphereState.THINKING)
                    _voiceTranscript.value = "Thinking…"

                    // TTS sentence queue: plays each complete sentence the
                    // moment it arrives, so audio starts ~1s in instead of
                    // waiting for the whole reply + full synthesis.
                    val ttsQueue = Channel<String>(Channel.UNLIMITED)
                    ttsPlaybackJob = viewModelScope.launch {
                        var first = true
                        for (sentence in ttsQueue) {
                            if (first) {
                                first = false
                                setVoiceModeState(SphereState.SPEAKING)
                                speakingCycleStartedAt = SystemClock.elapsedRealtime()
                                // Arm the barge-in monitor for the whole
                                // SPEAKING cycle (fresh AudioRecord per
                                // reply; the deafen window refreshes per
                                // sentence inside playTtsSentenceBlocking).
                                armBargeInMonitor()
                            }
                            playTtsSentenceBlocking(sentence)
                        }
                    }

                    // Send to server & stream response (60s cap — a stalled
                    // SSE stream must not freeze the voice loop for minutes).
                    val pending = StringBuilder()
                    DiagLog.d("VOICE", "stream start session=$session q='${recognized.take(40)}'")
                    try {
                        withTimeout(60_000) {
                            repository.sendMessage(
                                sessionId = session,
                                query = recognized,
                                onChunk = { chunk ->
                                    // Voice must ONLY speak the user-friendly
                                    // assistant text — never raw tool execution
                                    // JSON (```tool_call {...}``` blocks from the
                                    // text-based tool-calling fallback).
                                    val clean = chunk.replace(TOOL_CALL_BLOCK, " ")
                                    if (clean.isBlank()) return@sendMessage
                                    pending.append(clean)
                                    _voiceTranscript.value = pending.toString()
                                    // Stream mode: flush complete sentences
                                    // to the TTS queue as they arrive. Full
                                    // mode keeps accumulating — the whole
                                    // reply is chunked once, at stream end.
                                    if (!_fullResponseMode.value && !bargeInTriggered.get()) {
                                        val (done, rest) = splitSentences(pending.toString())
                                        if (done.isNotEmpty()) {
                                            done.forEach { ttsQueue.trySend(it) }
                                            pending.setLength(0)
                                            pending.append(rest)
                                        }
                                    }
                                }
                            )
                        }
                    } catch (e: TimeoutCancellationException) {
                        // Reply stream stalled — speak what we got and keep
                        // the loop alive instead of dying.
                        DiagLog.w("VOICE", "stream TIMED OUT after 60s, got ${pending.length} chars")
                        Log.w("VoiceScreen", "Reply stream timed out")
                    } finally {
                        // Always flush the trailing partial + close, even if
                        // sendMessage throws — otherwise the queue consumer
                        // would block on the open channel forever.
                        val finalText = pending.toString().trim()
                        if (finalText.isNotBlank() && !bargeInTriggered.get()) {
                            if (_fullResponseMode.value) {
                                // Speak the COMPLETE reply as a few large
                                // chunks (≤3500 chars each, sentence-aligned)
                                // — under the server's 4000-char TTS cap,
                                // keeps whole-sentence prosody.
                                chunkForTts(finalText).forEach { ttsQueue.trySend(it) }
                            } else {
                                ttsQueue.trySend(finalText)
                            }
                        }
                        ttsQueue.close()
                    }
                    ttsPlaybackJob?.join()

                    // ── AWAITING (brief pause before re-arming; skip if the
                    // user already interrupted playback to speak) ──
                    if (_voiceModeState.value != SphereState.LISTENING) {
                        disarmBargeInMonitor()
                        setVoiceModeState(SphereState.AWAITING)
                        _voiceTranscript.value = "Awaiting…"
                        delay(500) // brief pause before re-arming
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                disarmBargeInMonitor()
                // Transient ERROR state — auto-reverts to IDLE in ~2.5s.
                showVoiceError("Error: ${e.message}")
            }
        }
    }

    fun stopVoiceLoop() {
        voiceRunning = false
        voicePaused = false
        voiceModeJob?.cancel()
        ttsPlaybackJob?.cancel()
        disarmBargeInMonitor()
        _voiceModeState.value = SphereState.IDLE
        _voiceAmplitude.value = 0f
        _ttsProgress.value = 0f
    }

    /**
     * Background/foreground transitions (privacy + battery):
     * STOP → kill mic + TTS immediately (no recording/speaking while the
     * app is not visible); START → resume the loop when it was running.
     */
    fun pauseVoice() {
        if (!voiceRunning) return
        voicePaused = true
        voiceModeJob?.cancel()
        ttsPlaybackJob?.cancel()
        disarmBargeInMonitor()
        _voiceModeState.value = SphereState.IDLE
        _voiceAmplitude.value = 0f
    }

    fun resumeVoice() {
        if (!voiceRunning || !voicePaused) return
        voicePaused = false
        startVoiceLoop()
    }

    fun setVoiceAmplitude(amp: Float) {
        _voiceAmplitude.value = amp.coerceIn(0f, 1f)
    }

    fun setLanguage(lang: VoiceLanguage) {
        if (_language.value != lang) {
            _language.value = lang
            // Re-arm immediately so the new locale takes effect on the next
            // recognition (interrupts any ongoing listen/playback cleanly).
            voiceModeJob?.cancel()
            ttsPlaybackJob?.cancel()
            disarmBargeInMonitor()
            _voiceTranscript.value = "Listening… (${lang.label})"
            startVoiceLoop()
        }
    }

    // ── Model management (mirrors chat — live list, server-side switch) ──

    fun loadModels() {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            _modelsLoading.value = true
            try {
                val response = repository.listModels(sid)
                if (response != null) {
                    _availableModels.value = response.models
                    _currentModel.value = response.current
                }
            } catch (_: Exception) { }
            _modelsLoading.value = false
        }
    }

    fun switchModel(modelId: String, global: Boolean = false) {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            val success = repository.switchModel(sid, modelId, global)
            if (success) {
                _currentModel.value = modelId
                if (global) {
                    // Reload to show the new global default
                    loadModels()
                }
            }
        }
    }

    private fun setVoiceModeState(state: SphereState) {
        if (_voiceModeState.value != state) {
            DiagLog.d("VOICE", "state ${_voiceModeState.value} -> $state")
        }
        _voiceModeState.value = state
    }

    /**
     * Transient ERROR state (visual-only): shows the failure message, flips
     * the sphere to ERROR, and auto-reverts to IDLE after ~2.5s. Does NOT
     * touch barge-in/VAD state — the caller owns disarm/arm decisions.
     */
    private fun showVoiceError(message: String) {
        _errorMessage.value = message
        _voiceTranscript.value = message
        setVoiceModeState(SphereState.ERROR)
        errorRevertJob?.cancel()
        errorRevertJob = viewModelScope.launch {
            delay(2500)
            // Only revert if nothing else moved the state meanwhile
            // (pause/stop/barge-in all land on IDLE/LISTENING already).
            if (_voiceModeState.value == SphereState.ERROR) {
                _errorMessage.value = null
                setVoiceModeState(SphereState.IDLE)
            }
        }
    }

    private fun appendVoiceTranscript(text: String) {
        _voiceTranscript.value = if (_voiceTranscript.value.isBlank()) text else "${_voiceTranscript.value}\n$text"
    }

    fun interruptTts() {
        ttsPlaybackJob?.cancel()
        disarmBargeInMonitor()
        _ttsProgress.value = 0f
        // Immediately re-arm listening
        if (_voiceModeState.value == SphereState.SPEAKING) {
            setVoiceModeState(SphereState.LISTENING)
        }
    }

    /**
     * Barge-in monitor: while TTS is speaking, a second AudioRecord listens
     * for the user's voice. On 2 consecutive loud blocks (past the deafen
     * window, playback active) it fires triggerBargeIn(), which kills
     * playback and re-arms listening. One monitor per SPEAKING cycle.
     */
    private fun armBargeInMonitor() {
        val previous = bargeInJob
        // CRITICAL: must run on IO — the tight blocking AudioRecord.read
        // loop on the Main dispatcher (viewModelScope default) starved the
        // main thread for the whole TTS playback -> ANR -> the system
        // killed the app ('crash after some time', no Java crash file).
        bargeInJob = viewModelScope.launch(Dispatchers.IO) {
            // Never overlap recorders — join the previous monitor first.
            previous?.cancelAndJoin()
            val sampleRate = 16_000
            val minBuf = android.media.AudioRecord.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = max(minBuf * 2, sampleRate / 2) // ≥0.5s of buffer
            var record: android.media.AudioRecord? = null
            val detector = BargeInDetector()
            try {
                // ECHO-SAFETY (c/d): capture via VOICE_COMMUNICATION — the
                // platform applies acoustic echo cancellation at the HAL
                // level. MIC + the AcousticEchoCanceler EFFECT looked
                // available but was a no-op in practice: the TTS echo
                // reached the detector and self-triggered barge-in on long
                // replies (the user's "response gets terminated").
                record = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                record.startRecording()
                // Second layer: best-effort NoiseSuppressor on the monitor
                // session too (keeps the fixed barge-in threshold honest in
                // noisy rooms).
                try {
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        val ns = android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)
                        ns?.setEnabled(true)
                    }
                } catch (_: Exception) {}
                val buf = ShortArray(1024)
                while (isActive) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val nowMs = SystemClock.elapsedRealtime()
                    // Warm-up guard: never self-trigger during the reply's
                    // opening — the user's own speech is still settling and
                    // the first echo burst is at its loudest.
                    if (speakingCycleStartedAt > 0L && nowMs - speakingCycleStartedAt < 2000L) {
                        continue
                    }
                    var sumSq = 0.0
                    for (i in 0 until n) sumSq += buf[i].toDouble() * buf[i]
                    val rms = kotlin.math.sqrt(sumSq / n).toFloat()
                    if (detector.feed(rms, nowMs, ttsPlayingNow, vadDeafenUntilMs)) {
                        // Run the trigger OUTSIDE this job so the
                        // cancelAndJoin inside triggerBargeIn can't cancel
                        // the trigger mid-flight.
                        viewModelScope.launch { triggerBargeIn() }
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("VoiceScreen", "Barge-in monitor failed: ${e.message}")
            } finally {
                try { record?.stop() } catch (_: Exception) {}
                try { record?.release() } catch (_: Exception) {}
            }
        }
    }

    private fun disarmBargeInMonitor() {
        bargeInJob?.cancel()
        bargeInJob = null
    }

    /**
     * Barge-in: the user spoke over TTS. Kill playback + the monitor,
     * settle briefly (so the speaker's audio stops ringing), then re-arm
     * LISTENING. The loop's AWAITING block is skipped because the state
     * is already LISTENING when it checks.
     */
    private suspend fun triggerBargeIn() {
        if (!bargeInTriggered.compareAndSet(false, true)) return
        if (_voiceModeState.value != SphereState.SPEAKING || ttsPlaybackJob?.isActive != true) return
        DiagLog.i("VOICE", "BARGE-IN triggered")
        ttsPlaybackJob?.cancel()
        bargeInJob?.cancelAndJoin()
        bargeInJob = null
        delay(VoiceTuning.BARGE_IN_SETTLE_MS)
        setVoiceModeState(SphereState.LISTENING)
        _voiceTranscript.value = "Listening…"
        _ttsProgress.value = 0f
    }

    /**
     * One listen cycle. SpeechRecognizer-first: Google's recognizer is
     * free, serverless and more accurate than whisper. Falls back to the
     * bridge's whisper STT (silent, offline-capable) when the system
     * recognizer is unavailable or returns nothing.
     */
    private suspend fun listenOnce(): String? {
        val systemText = try {
            listenOnceSystem()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("VoiceScreen", "System listen failed, falling back: ${e.message}")
            null
        }
        if (!systemText.isNullOrBlank()) return systemText
        return listenOnceWhisper()
    }

    /**
     * SILENT capture for the bridge's whisper STT: records 16 kHz mono PCM
     * directly (no SpeechRecognizer → NO beep), auto-stops after ~1.2s of
     * silence, wraps in a WAV header and uploads to /api/stt. The RMS level
     * still drives the sphere wave (same 0..1 amplitude path).
     */
    private suspend fun listenOnceWhisper(): String? = withContext(Dispatchers.IO) {
        val sampleRate = 16_000
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            sampleRate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf * 2, sampleRate / 2) // ≥0.5s of buffer
        var record: android.media.AudioRecord? = null
        try {
            // NOISE CANCELLATION: capture via VOICE_COMMUNICATION so the
            // platform applies acoustic echo cancellation AND noise
            // suppression at the HAL level (the raw-MIC path passes the
            // room's hum/fan/AC straight into the VAD and whisper).
            record = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            record.startRecording()
            // Second layer: best-effort NoiseSuppressor effect on the
            // capture session (harmless no-op if the HAL already handles it).
            try {
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    val ns = android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)
                    ns?.setEnabled(true)
                }
            } catch (_: Exception) {}
            val pcm = java.io.ByteArrayOutputStream()
            val buf = ShortArray(1024)
            var speechStarted = false
            var lastSpeechMs = 0L
            var lastSpeechBlockAt = -1L
            var utteranceStartMs = -1L
            var burstStartMs = -1L
            var utteranceMs = 0L
            var burstMs = 0L
            var peakRms = 0f
            val startMs = System.currentTimeMillis()
            val deadline = startMs + VoiceTuning.MAX_UTTERANCE_MS
            var wroteBytes = 0
            // Adaptive VAD: track the room's noise floor CONTINUOUSLY —
            // the first 500ms seed the estimator, then every SILENT block
            // (below the gate = ambient) keeps feeding it, so a changing
            // room (fan switches on, AC kicks in) re-adapts instead of
            // false-triggering on the new noise level.
            val noiseFloor = AdaptiveEndpointing.NoiseFloor()
            while (System.currentTimeMillis() < deadline) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                val now = System.currentTimeMillis()
                var sumSq = 0.0
                for (i in 0 until n) sumSq += buf[i].toDouble() * buf[i]
                val rms = kotlin.math.sqrt(sumSq / n).toFloat()
                if (now - startMs < 500) noiseFloor.feed(rms)
                val floor = noiseFloor.estimate()
                peakRms = max(peakRms, rms)
                // Normalized 0..1 amplitude (ref grows with the loudest
                // peak so quiet speech still registers on the sphere).
                setVoiceAmplitude(
                    AdaptiveEndpointing.normalize(rms, floor, max(VoiceTuning.AMPLITUDE_REF, peakRms))
                )
                val speechGate = max(VoiceTuning.SPEECH_RMS_BASE, floor * 3 + 150)
                if (rms > speechGate) {
                    lastSpeechMs = now
                    if (utteranceStartMs < 0) utteranceStartMs = now
                    utteranceMs = now - utteranceStartMs
                    if (!speechStarted) {
                        speechStarted = true
                        burstStartMs = now
                        burstMs = 0L
                    } else if (now - lastSpeechBlockAt > 300) {
                        // Long pause → a fresh burst (cadence tracking).
                        burstStartMs = now
                        burstMs = 0L
                    } else {
                        burstMs = now - burstStartMs
                    }
                    lastSpeechBlockAt = now
                } else {
                    // Below the gate = ambient noise → keep learning the floor.
                    noiseFloor.feed(rms)
                }
                if (speechStarted) {
                    // Write PCM little-endian into the growing WAV payload.
                    val bytes = java.nio.ByteBuffer.allocate(n * 2)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) bytes.putShort(buf[i])
                    pcm.write(bytes.array())
                    wroteBytes += n * 2
                    // Adaptive end-of-speech: short bursts end fast, long
                    // utterances get a generous pause before finalizing.
                    val silenceMs = now - lastSpeechMs
                    val timeout = if (AdaptiveEndpointing.shouldEarlyFinalize(utteranceMs, silenceMs)) {
                        VoiceTuning.EARLY_FINALIZE_SILENCE_MS
                    } else {
                        AdaptiveEndpointing.silenceMs(burstMs, utteranceMs)
                    }
                    if (silenceMs > timeout) break // silence → auto-stop
                }
            }
            if (!speechStarted || wroteBytes < VoiceTuning.MIN_SPEECH_BYTES) return@withContext null // <0.1s speech
            val langHint = _language.value.speechLocale.substringBefore('-').lowercase()
            repository.transcribeAudio(buildWavHeader(pcm.size()) + pcm.toByteArray(), langHint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("VoiceScreen", "Whisper capture failed: ${e.message}")
            null
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            try { record?.release() } catch (_: Exception) {}
            setVoiceAmplitude(0f)
        }
    }

    /** Standard 44-byte RIFF header for 16 kHz mono 16-bit PCM. */
    private fun buildWavHeader(dataLen: Int): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataLen); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(16_000); buf.putInt(16_000 * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataLen)
        return buf.array()
    }

    /** Android SpeechRecognizer path (fallback — has the system beep). */
    private suspend fun listenOnceSystem(): String? {
        var recognizer: android.speech.SpeechRecognizer? = null
        val result: String? = try {
            withTimeout(15_000) {
                suspendCancellableCoroutine<String?> { cont ->
                    // Use Android SpeechRecognizer with amplitude callbacks
                    val rec = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
                    recognizer = rec
                    if (rec == null) {
                        cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, _language.value.speechLocale)
                        putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        // End-of-speech silence wait: ~1.8s — a good balance
                        // between reacting fast and NOT cutting the user off
                        // mid-thought (1200ms was too eager — it fired while
                        // the user paused and TTS talked over them).
                        putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                        putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                    }
                    var destroyed = false
                    fun destroyRec() {
                        if (!destroyed) {
                            destroyed = true
                            try { rec.cancel() } catch (_: Exception) {}
                            try { rec.destroy() } catch (_: Exception) {}
                        }
                    }
                    rec.setRecognitionListener(object : android.speech.RecognitionListener {
                        override fun onReadyForSpeech(params: android.os.Bundle?) {}
                        override fun onBeginningOfSpeech() {
                            _voiceTranscript.value = "Listening…"
                        }
                        override fun onRmsChanged(rmsdB: Float) {
                            // Convert dB to 0..1 amplitude (roughly -50dB = silence, -10dB = loud)
                            val amp = ((rmsdB + 50f) / 40f).coerceIn(0f, 1f)
                            setVoiceAmplitude(amp)
                        }
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            setVoiceAmplitude(0f)
                        }
                        override fun onError(error: Int) {
                            destroyRec()
                            cont.resume(null)
                        }
                        override fun onResults(results: android.os.Bundle?) {
                            val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull() ?: ""
                            destroyRec()
                            cont.resume(text.takeIf { it.isNotBlank() })
                        }
                        override fun onPartialResults(partialResults: android.os.Bundle?) {
                            val matches = partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                            matches?.firstOrNull()?.let { _voiceTranscript.value = it }
                        }
                        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                    })
                    rec.startListening(intent)

                    cont.invokeOnCancellation {
                        destroyRec()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // The recognizer never called back (silent MIUI failure after
            // repeated cycles) — clean up so the next cycle gets a fresh
            // recognizer instead of hanging forever.
            Log.w("VoiceScreen", "Speech recognition timed out")
            try { recognizer?.cancel() } catch (_: Exception) {}
            try { recognizer?.destroy() } catch (_: Exception) {}
            null
        }
        return result
    }

    /**
     * Split text into (complete sentences, trailing partial). Splits ONLY
     * on real sentence enders — NOT newlines: LLM bullet lists
     * ("1. x\n2. y") otherwise become 3-4 tiny TTS calls with dead air
     * between them, which is what makes voice replies sound robotic.
     */
    private fun splitSentences(text: String): Pair<List<String>, String> {
        val done = mutableListOf<String>()
        var last = 0
        for (i in text.indices) {
            val c = text[i]
            // Don't split decimals ("3.5") or abbreviations ("Mr." → keep whole)
            val prevDigit = i > 0 && text[i - 1].isDigit()
            val nextDigit = i + 1 < text.length && text[i + 1].isDigit()
            val isDecimal = c == '.' && (prevDigit || nextDigit)
            if (!isDecimal && (c == '.' || c == '!' || c == '?' || c == '।')) {
                val s = text.substring(last, i + 1).trim()
                if (s.isNotBlank()) done.add(s)
                last = i + 1
            }
        }
        return done to text.substring(last).trim()
    }

    /**
     * Split a complete reply into TTS chunks of ≤ maxLen chars, breaking
     * at sentence boundaries so each synthesis keeps natural prosody and
     * stays under the server's 4000-char TTS cap. Used by full-response
     * mode (fewer, larger TTS calls than per-sentence streaming).
     */
    private fun chunkForTts(text: String, maxLen: Int = 3500): List<String> {
        val (sentences, tail) = splitSentences(text)
        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotBlank()) chunks.add(cur.toString().trim())
            cur.setLength(0)
        }
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + s.length > maxLen) flush()
            cur.append(s).append(' ')
        }
        if (tail.isNotBlank()) {
            if (cur.isNotEmpty() && cur.length + tail.length > maxLen) flush()
            cur.append(tail)
        }
        flush()
        return chunks
    }

    /** Fetch TTS audio for ONE sentence and play it to completion (cancellable). */
    private suspend fun playTtsSentenceBlocking(sentence: String) {
        if (sentence.isBlank()) return
        lastSpokenSentence = sentence
        lastSpokenAt = System.currentTimeMillis()
        try {
            _ttsProgress.value = 0f
            // Fetch TTS audio from bridge (voice matched to selected language).
            // 20s cap — a hung bridge must not freeze the whole queue.
            val audioBytes = try {
                withTimeout(20_000) { repository.textToSpeech(sentence, _language.value.ttsVoice) }
            } catch (e: TimeoutCancellationException) {
                Log.w("VoiceScreen", "TTS fetch timed out, skipping sentence")
                null
            } ?: return
            _ttsProgress.value = 0.1f

            // Play via MediaPlayer. tempFile is deleted in the OUTER finally
            // so error paths (prepare/start throws) can't leak mp3s.
            val tempFile = java.io.File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            var player: android.media.MediaPlayer? = null

            // writeBytes + MediaPlayer.prepare() are BLOCKING — running them
            // on the Main dispatcher (viewModelScope default) janked/ANR'd
            // the app during speech. prepare() parses the whole mp3.
            withContext(Dispatchers.IO) {
                tempFile.writeBytes(audioBytes)
                val p = android.media.MediaPlayer()
                try {
                    p.setDataSource(tempFile.absolutePath)
                    p.prepare()
                    player = p
                } catch (t: Throwable) {
                    // Never leak the half-built player (it's not assigned yet)
                    try { p.release() } catch (_: Exception) {}
                    throw t
                }
            }
            try {
                player?.start()
                // Echo-safety (a): deafen the barge-in mic right after
                // playback starts — the speaker's own audio is still
                // hitting the mic at full volume.
                vadDeafenUntilMs = SystemClock.elapsedRealtime() + VoiceTuning.DEAFEN_MS
                ttsPlayingNow = true

                val duration = player?.duration?.toFloat() ?: 0f
                val progressJob = viewModelScope.launch {
                    try {
                        // Guard with ttsPlayingNow + try/catch: reading
                        // isPlaying/currentPosition on a RELEASED player
                        // throws IllegalStateException — and the playback
                        // job's finally releases the player while this
                        // poller may still be between delays (barge-in
                        // race). Uncaught = viewModelScope crash.
                        while (isActive && ttsPlayingNow) {
                            val pos = try {
                                player?.currentPosition?.toFloat() ?: 0f
                            } catch (_: Exception) {
                                0f
                            }
                            _ttsProgress.value = (pos / duration).coerceIn(0f, 1f)
                            delay(100)
                        }
                    } finally {
                        _ttsProgress.value = 1f
                    }
                }

                // Wait for completion (cancellation propagates via delay →
                // CancellationException caught below). 30s timeout so a
                // stuck MediaPlayer can never freeze the voice loop.
                try {
                    withTimeout(30_000) {
                        try {
                            while (player?.isPlaying == true) {
                                delay(200)
                            }
                        } catch (_: IllegalStateException) {
                            // Player released underneath us (interrupt path) — done.
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w("VoiceScreen", "TTS playback stuck, skipping sentence")
                } finally {
                    progressJob.cancel()
                    ttsPlayingNow = false
                    // Stop BEFORE release so audio halts within the 200ms
                    // poll tick (release alone can leave the tail playing).
                    try { player?.stop() } catch (_: Exception) {}
                    player?.release()
                }
            } finally {
                try { tempFile.delete() } catch (_: Exception) {}
            }
        } catch (_: CancellationException) {
            _ttsProgress.value = 0f
        } catch (e: Exception) {
            Log.e("VoiceScreen", "TTS sentence playback failed", e)
            _ttsProgress.value = 0f
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}

// ═══════════════════════════════════════════════════════════════
// VoiceScreen — full-screen JARVIS sphere
// ═══════════════════════════════════════════════════════════════

@Composable
fun VoiceScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vm: VoiceViewModel = hiltViewModel()

    // Home's voice card requests a NEW session — consume the flag and
    // switch the VM to a fresh session (the tab itself resumes the last).
    LaunchedEffect(Unit) {
        if (com.hermes.mobile.VoiceNav.pendingNewSession) {
            com.hermes.mobile.VoiceNav.pendingNewSession = false
            vm.startNewSession()
        }
    }

    val state by vm.voiceModeState.collectAsState()
    val transcript by vm.voiceTranscript.collectAsState()
    val ttsProgress by vm.ttsProgress.collectAsState()
    val amplitude by vm.voiceAmplitude.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val language by vm.language.collectAsState()
    val sessionId by vm.sessionId.collectAsState()
    val currentModel by vm.currentModel.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val modelsLoading by vm.modelsLoading.collectAsState()
    val fullResponseMode by vm.fullResponseMode.collectAsState()

    var showModelPicker by remember { mutableStateOf(false) }

    // Load the live model list once the voice session exists
    LaunchedEffect(sessionId) {
        if (sessionId != null) vm.loadModels()
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.startVoiceLoop()
        } else {
            vm.setVoiceAmplitude(0f)
        }
    }

    // Start the loop when the screen appears (if permission granted)
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED) {
            vm.startVoiceLoop()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Privacy/battery: pause mic + TTS when the app goes to background,
    // resume the loop on return, stop completely when leaving the screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.pauseVoice()
                Lifecycle.Event.ON_START -> vm.resumeVoice()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopVoiceLoop()
        }
    }

    JarvisSphere(
        state = state,
        amplitude = amplitude,
        ttsProgress = ttsProgress,
        transcript = errorMessage ?: transcript,
        language = language,
        onLanguageSelected = { vm.setLanguage(it) },
        currentModel = currentModel,
        onOpenModelPicker = {
            vm.loadModels()
            showModelPicker = true
        },
        fullResponseMode = fullResponseMode,
        onToggleFullResponse = { vm.setFullResponseMode(!fullResponseMode) },
        onTap = {
            when (state) {
                SphereState.SPEAKING -> vm.interruptTts()
                else -> { /* no-op */ }
            }
        },
        onExit = onExit,
        modifier = modifier
    )

    // ── Model picker (same shared sheet as chat) ──
    if (showModelPicker) {
        ModelPickerSheet(
            availableModels = availableModels,
            currentModel = currentModel,
            modelsLoading = modelsLoading,
            onSelect = { modelId, global -> vm.switchModel(modelId, global = global) },
            onDismiss = { showModelPicker = false }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// JARVIS Sphere — full-screen voice mode UI
// ═══════════════════════════════════════════════════════════════

enum class SphereState {
    IDLE,       // Breathing, ready to listen
    LISTENING,  // Reacting to voice amplitude
    THINKING,   // Processing, orbiting particles
    SPEAKING,   // TTS playing, waveform + progress
    AWAITING,   // Response done, auto-re-arming
    ERROR       // Voice error — transient, auto-reverts to IDLE (~2.5s)
}

// ── Voice state + regexes (module-level: allocated once, not per frame) ──

private val TOOL_CALL_BLOCK = Regex(
    "```tool_call\\s*\\n?\\{.*?\\}\\n?```",
    RegexOption.DOT_MATCHES_ALL
)

/**
 * Capped-rate frame driver for the sphere's GLSurfaceView
 * (RENDERMODE_WHEN_DIRTY). Choreographer-driven: fires on every vsync but
 * only calls requestRender() once the per-state frame interval has elapsed
 * (elapsed-time gate = ratio-based skip, so a 120Hz display naturally
 * renders the capped 10/30fps without extra bookkeeping). Each rendered
 * frame advances the renderer's sim clock and uploads the current state
 * uniforms via setVisualState. Start/stop is tied to the lifecycle observer
 * so privacy-pause freezes rendering entirely (zero GL work in background).
 */
private class SphereFrameDriver(
    private val renderer: SphereGLRenderer,
    private val glViewProvider: () -> GLSurfaceView?,
    private val stateProvider: () -> SphereState,
    private val amplitudeProvider: () -> Float,
    private val progressProvider: () -> Float,
    private val phaseProvider: () -> Float
) {
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }
    @Volatile private var running = false
    private var lastFrameNanos = 0L

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        DiagLog.d("GL", "driver start")
        choreographer.postFrameCallback(frameCallback)
    }

    fun stop() {
        if (!running) return
        running = false
        DiagLog.d("GL", "driver stop")
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (!running) return
        // Always re-arm; the elapsed-time gate below decides whether this
        // vsync actually renders (ratio-based skip at high refresh rates).
        choreographer.postFrameCallback(frameCallback)
        val state = stateProvider()
        val intervalNs = frameIntervalNs(state)
        if (lastFrameNanos == 0L || frameTimeNanos - lastFrameNanos >= intervalNs) {
            lastFrameNanos = frameTimeNanos
            val vol = when (state) {
                SphereState.LISTENING -> amplitudeProvider()
                // SPEAKING: same synthetic amplitude the old per-volume
                // effect used — blue + white-hot core pulses with TTS.
                SphereState.SPEAKING -> 0.35f + 0.55f * abs(sin(phaseProvider())).toFloat()
                else -> 0f
            }
            renderer.advanceTime(intervalNs / 1_000_000_000f)
            renderer.setVisualState(
                state = when (state) {
                    SphereState.IDLE -> SphereGLRenderer.STATE_IDLE
                    SphereState.LISTENING -> SphereGLRenderer.STATE_LISTENING
                    SphereState.THINKING -> SphereGLRenderer.STATE_THINKING
                    SphereState.SPEAKING -> SphereGLRenderer.STATE_SPEAKING
                    SphereState.AWAITING -> SphereGLRenderer.STATE_AWAITING
                    SphereState.ERROR -> SphereGLRenderer.STATE_ERROR
                },
                volume = vol,
                progress = progressProvider(),
                hueShift = 0f
            )
            glViewProvider()?.requestRender()
        }
    }

    /** Per-state frame caps: idle 10fps (8-12 band), error 20fps (flash
     * needs more frames), active (LISTENING/THINKING/SPEAKING) ~30fps. */
    private fun frameIntervalNs(state: SphereState): Long {
        val fps = when (state) {
            SphereState.IDLE, SphereState.AWAITING -> 10f
            SphereState.ERROR -> 20f
            else -> 30f
        }
        return (1_000_000_000L / fps).toLong()
    }
}

// ─── Voice languages: STT locale (Android SpeechRecognizer) + TTS voice (edge-tts) ───

enum class VoiceLanguage(
    val label: String,
    val chip: String,
    val speechLocale: String,
    val ttsVoice: String
) {
    ENGLISH("English", "EN", "en-IN", "en-IN-NeerjaNeural"),
    HINDI("Hindi", "हिं", "hi-IN", "hi-IN-SwaraNeural"),
    MARATHI("Marathi", "मरा", "mr-IN", "mr-IN-AarohiNeural"),
    GUJARATI("Gujarati", "ગુ", "gu-IN", "gu-IN-DhwaniNeural"),
    BENGALI("Bengali", "বাং", "bn-IN", "bn-IN-TanishaaNeural"),
    TAMIL("Tamil", "தமிழ்", "ta-IN", "ta-IN-PallaviNeural"),
    TELUGU("Telugu", "తెలు", "te-IN", "te-IN-ShrutiNeural"),
    KANNADA("Kannada", "ಕನ್ನಡ", "kn-IN", "kn-IN-SapnaNeural"),
    MALAYALAM("Malayalam", "മല", "ml-IN", "ml-IN-SobhanaNeural")
}

@Composable
fun JarvisSphere(
    state: SphereState,
    amplitude: Float = 0f,          // 0..1 mic level (LISTENING)
    ttsProgress: Float = 0f,        // 0..1 playback progress (SPEAKING)
    transcript: String = "",
    language: VoiceLanguage = VoiceLanguage.ENGLISH,
    onLanguageSelected: (VoiceLanguage) -> Unit = {},
    currentModel: String = "",
    onOpenModelPicker: () -> Unit = {},
    fullResponseMode: Boolean = false,
    onToggleFullResponse: () -> Unit = {},
    onTap: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalDarkTheme.current

    // Light/dark UI chrome
    val chipBg = if (isDark) Color(0xFF141B2D).copy(alpha = 0.85f)
                 else Color(0xFFFFFFFF).copy(alpha = 0.92f)
    val chipBorder = if (isDark) Color(0xFF2A3A4A) else Color(0xFFCBD5E1)
    val chipText = if (isDark) Color(0xFF8C9AAB) else Color(0xFF475569)
    val cardBg = if (isDark) Color(0xFF0C1016).copy(alpha = 0.92f)
                 else Color(0xFFFFFFFF).copy(alpha = 0.95f)
    val cardContent = if (isDark) Color.White else Color(0xFF1A2233)
    val exitBar = if (isDark) Color(0xFF2A3A4A) else Color(0xFFCBD5E1)
    val screenBg = if (isDark) Color(0xFF000000) else Color(0xFFF4F6FA)
    val transition = rememberInfiniteTransition(label = "sphere")
    val breathAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )
    val speakingWavePhase = remember { mutableStateOf(0f) }

    // Plasma palette — deep blue → purple → pink (matches reference).
    // State shifts subtly: pink-dominant listening, purple bright thinking,
    // blue-dominant speaking; intensity/swirl carry the main state signal.
    data class SphereColors(val core: Color, val ring: Color, val particle: Color, val bg: Color)
    val (coreColor, ringColor, particleColor, bgColor) = when (state) {
        SphereState.IDLE, SphereState.AWAITING ->
            SphereColors(Color(0xFF2A3FD6), Color(0xFF8A3BFF), Color(0xFFB44DFF), screenBg)
        SphereState.LISTENING ->
            SphereColors(Color(0xFFFF4DD2), Color(0xFF8A3BFF), Color(0xFF3D8BFF), screenBg)
        SphereState.THINKING ->
            SphereColors(Color(0xFF8A3BFF), Color(0xFFB44DFF), Color(0xFFFF4DD2), screenBg)
        SphereState.SPEAKING ->
            SphereColors(Color(0xFF3D8BFF), Color(0xFF2A3FD6), Color(0xFF8A3BFF), screenBg)
        SphereState.ERROR ->
            SphereColors(Color(0xFFFF4D4D), Color(0xFFFF4D4D), Color(0xFFFF4D4D), screenBg)
    }

    // Speaking waveform phase — keyed on STATE only (ttsProgress changes
    // ~10x/sec while speaking; re-keying there cancelled the wave loop
    // every ~100ms so the animation barely advanced). Also advances during
    // LISTENING so the FRIDAY-style waveform bars animate on mic input.
    LaunchedEffect(state) {
        if (state == SphereState.SPEAKING || state == SphereState.LISTENING) {
            while (isActive) {
                speakingWavePhase.value = (speakingWavePhase.value + 0.15f) % (2 * 3.14159f)
                delay(50)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center
    ) {
        // ── Faded Hermes watermark (same on every screen) ──
        HermesWatermark()
        // ── Vignette ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = center.x
            val cy = center.y
            val maxR = max(cx.toDouble(), cy.toDouble()) * 1.2
            for (i in 0..40) {
                val r = maxR * (i / 40.0)
                val alpha = (0.15 * (1 - i / 40.0)).toFloat().coerceIn(0f, 1f)
                drawCircle(
                    color = Color.Black.copy(alpha = alpha),
                    center = Offset(cx, cy),
                    radius = r.toFloat(),
                    style = Stroke(width = 2f)
                )
            }
        }

        // ── Outer soft halo (unclipped, behind the sphere) ──
        Canvas(
            modifier = Modifier
                .size(440.dp)
                .blur(26.dp)
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ringColor.copy(alpha = 0.20f),
                        coreColor.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.minDimension * 0.5f
                ),
                radius = size.minDimension * 0.5f
            )
        }

        // ── Sphere body (plasma energy sphere) ──
        Box(
            modifier = Modifier
                .size(300.dp)
                .graphicsLayer {
                    scaleX = 1f + 0.02f * breathAnim.value
                    scaleY = 1f + 0.02f * breathAnim.value
                }
                .clip(CircleShape)
                .background(Color.Black)
                .clickable { onTap() }
        ) {
            // ── Compose neon disc BEHIND the GL view: if the shader fails to
            // compile or the GL thread is dead, the surface clears
            // TRANSPARENT and this disc shows through — never a black hole.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ringColor.copy(alpha = 0.55f),
                            coreColor.copy(alpha = 0.25f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension * 0.5f
                    ),
                    radius = size.minDimension * 0.5f
                )
            }
            // ── GPU shader sphere (GLSurfaceView). RENDERMODE_WHEN_DIRTY +
            // capped frame driver: the sphere only renders when the driver
            // calls requestRender() (idle ~10fps, active ~30fps) — no more
            // full-FPS-forever battery drain. The driver also feeds the
            // state uniforms (uState/palette/volume/progress) each frame.
            val glRenderer = remember { SphereGLRenderer() }
            val glLifecycleOwner = LocalLifecycleOwner.current
            var glView by remember { mutableStateOf<GLSurfaceView?>(null) }

            // Frame-driver value providers — rememberUpdatedState so the
            // driver always reads the LATEST values without re-keying any
            // LaunchedEffect (ttsProgress/amplitude change many times/sec).
            val driverState by rememberUpdatedState(state)
            val driverAmp by rememberUpdatedState(amplitude)
            val driverProgress by rememberUpdatedState(ttsProgress)
            val driverPhase by rememberUpdatedState(speakingWavePhase.value)
            val frameDriver = remember {
                SphereFrameDriver(
                    renderer = glRenderer,
                    glViewProvider = { glView },
                    stateProvider = { driverState },
                    amplitudeProvider = { driverAmp },
                    progressProvider = { driverProgress },
                    phaseProvider = { driverPhase }
                )
            }
            // Belt & braces: unconditional start on composition. The
            // lifecycle effect below also starts it when RESUMED — start()
            // is idempotent, and this covers any path where no lifecycle
            // event arrives after the observer is attached.
            LaunchedEffect(Unit) { frameDriver.start() }

            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(2)
                        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                        holder.setFormat(PixelFormat.TRANSLUCENT)
                        setRenderer(glRenderer)
                        // Capped by the frame driver — renders only on
                        // requestRender() at per-state fps caps.
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        setOnClickListener { onTap() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { glView = it }
            )
            // Tie the frame driver to the existing lifecycle handling:
            // privacy-pause stops the driver + GLSurfaceView (zero frames),
            // resume restarts both.
            DisposableEffect(glLifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            frameDriver.stop()
                            glView?.onPause()
                        }
                        Lifecycle.Event.ON_RESUME -> {
                            glView?.onResume()
                            frameDriver.start()
                        }
                        else -> {}
                    }
                }
                glLifecycleOwner.lifecycle.addObserver(observer)
                // CRITICAL: the observer only receives FUTURE events — if the
                // lifecycle is ALREADY resumed (first entry into this screen,
                // or returning to the tab), ON_RESUME never fires and the
                // driver never starts -> the sphere renders ONE static frame
                // and never animates again. Start it explicitly here.
                if (glLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    frameDriver.start()
                }
                onDispose {
                    glLifecycleOwner.lifecycle.removeObserver(observer)
                    frameDriver.stop()
                    // Pause the GL thread too — repeated tab switches without
                    // onPause() leave GL threads accumulating.
                    try { glView?.onPause() } catch (_: Exception) {}
                }
            }
        }

        // ── Status pill REMOVED ──
        // An accent-dot + label pill used to float at TopCenter (top=52dp) —
        // the SAME band as the model chip row. With the chips spanning
        // wide, the pill rendered BEHIND the 'best-coding' chip (the
        // "overlapping component" the user kept seeing). The state is
        // already communicated twice: the sphere's color/animation and the
        // bottom hint text ("Listening… speak freely" etc.).

        // ── TTS progress arc hugging the sphere rim (SPEAKING only) ──
        if (state == SphereState.SPEAKING) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(306.dp)
            ) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2f
                val arcSize = size.width - stroke
                drawArc(
                    color = VoiceNeonBlue.copy(alpha = 0.15f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(arcSize, arcSize),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawArc(
                    color = VoiceNeonBlue,
                    startAngle = -90f,
                    sweepAngle = 360f * ttsProgress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(arcSize, arcSize),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }

        // ── FRIDAY-style 8-bar voice waveform (LISTENING/SPEAKING) ──
        if (state == SphereState.LISTENING || state == SphereState.SPEAKING) {
            val barColor = if (state == SphereState.LISTENING) VoiceNeonCyan else VoiceNeonBlue
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 168.dp)
                    .size(width = 150.dp, height = 46.dp)
            ) {
                val barW = 3.dp.toPx()
                val gap = 9.dp.toPx()
                val totalW = 8 * barW + 7 * gap
                val startX = (size.width - totalW) / 2f
                val phase = speakingWavePhase.value
                val amp = amplitude.coerceIn(0f, 1f)
                for (i in 0 until 8) {
                    // staggered delays 0 → 0.8s across the 8 bars
                    val delay = i * (0.8f / 7f)
                    val wave = 0.5f + 0.5f * sin(phase * 2f + delay * 4f)
                    // LISTENING: bars ride the mic level; SPEAKING: full swing
                    val ampFactor = if (state == SphereState.LISTENING) (0.25f + 0.75f * amp) else 1f
                    val h = ((10f + wave * 20f) * ampFactor).dp.toPx() // 10 → 30dp
                    drawRoundRect(
                        color = barColor.copy(alpha = 0.9f),
                        topLeft = Offset(startX + i * (barW + gap), size.height - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                    )
                }
            }
        }

        // ── Top controls: reply-mode toggle + model chip + transcript ──
        // One stacked Column (NOT absolute offsets): at larger system font
        // scales the old fixed-top transcript collided with the chips row.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
        ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Reply mode toggle: Stream (fast, per-sentence) vs Full reply
            // (whole response, smoother prosody)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (fullResponseMode) Color(0xFF8A3BFF).copy(alpha = if (isDark) 0.35f else 0.16f)
                        else chipBg
                    )
                    .border(
                        width = 1.dp,
                        color = if (fullResponseMode) Color(0xFFB44DFF) else chipBorder,
                        shape = RoundedCornerShape(50)
                    )
                    .clickable { onToggleFullResponse() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (fullResponseMode) "Full reply" else "Stream",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (fullResponseMode) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (fullResponseMode) Color.White else chipText
                )
            }

            // Model chip — opens the shared model picker
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(chipBg)
                    .border(1.dp, chipBorder, RoundedCornerShape(50))
                    .clickable { onOpenModelPicker() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = Color(0xFFB44DFF),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (currentModel.isNotBlank())
                        currentModel.substringAfterLast("/").take(18)
                    else "Model",
                    style = MaterialTheme.typography.labelMedium,
                color = cardContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Select model",
                tint = chipText,
                modifier = Modifier.size(16.dp)
            )
            } // Row (model chip content)
            } // Box (model chip)
        } // Row (top controls)

        // ── Top transcript bar — stacked INSIDE the same Column as the
        // chips (no fixed offset, so it can never overlap them) ──
        // Hidden during ERROR — the red error banner replaces it.
        // ALSO hidden when the transcript is just a stock status placeholder
        // ("Listening…" / "Thinking…") — the bottom hint already says that,
        // and an empty status card under the chips read as "overlap".
        val stockStatus = transcript.startsWith("Listening…") || transcript == "Thinking…"
        if (transcript.isNotBlank() && state != SphereState.ERROR && !stockStatus) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = cardBg,
                    contentColor = cardContent
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(all = 16.dp)) {
                    Text(
                        text = transcript.take(200),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state == SphereState.SPEAKING) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "▁▂▃▄▅▆▇  ${(ttsProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = particleColor
                            )
                            Text(
                                text = "Tap to interrupt",
                                style = MaterialTheme.typography.labelSmall,
                                color = chipText
                            )
                        }
                    }
                }
            }
        }
        } // Column (top controls)

        // ── Bottom hint ──
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp, start = 24.dp, end = 24.dp, top = 0.dp)
        ) {
            Text(
                text = when (state) {
                    SphereState.IDLE -> "Tap to start"
                    SphereState.LISTENING -> "Listening… speak freely"
                    SphereState.THINKING -> "Thinking…"
                    SphereState.SPEAKING -> "Speaking — tap to interrupt"
                    SphereState.AWAITING -> "Awaiting… speak or tap the handle to exit"
                    SphereState.ERROR -> "Something went wrong — exit and reopen"
                },
                style = MaterialTheme.typography.bodySmall,
                color = chipText
            )
        }

        // ── Language selector (chips) ──
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ── Reply mode toggle lives next to the model chip (top) ──
                VoiceLanguage.entries.forEach { lang ->
                    val selected = lang == language
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) Color(0xFF8A3BFF).copy(alpha = if (isDark) 0.35f else 0.16f)
                                else chipBg
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) Color(0xFFB44DFF) else chipBorder,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable { onLanguageSelected(lang) }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = lang.chip,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) Color.White else chipText
                        )
                    }
                }
            }
        }

        // ── ERROR: red banner + red vignette flash (transient, auto-reverts
        // in the ViewModel after ~2.5s; the state never lingers) ──
        if (state == SphereState.ERROR) {
            val errTransition = rememberInfiniteTransition(label = "errorFlash")
            val vignetteAlpha by errTransition.animateFloat(
                initialValue = 0.10f,
                targetValue = 0.30f,
                animationSpec = infiniteRepeatable(
                    animation = tween(420, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "vignette"
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            VoiceNeonRed.copy(alpha = vignetteAlpha)
                        ),
                        center = center,
                        radius = size.minDimension * 0.55f
                    )
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(VoiceNeonRed.copy(alpha = 0.16f))
                    .border(1.dp, VoiceNeonRed.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(VoiceNeonRed)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        // During ERROR the transcript param carries the VM's
                        // error message (VoiceScreen passes errorMessage ?: transcript).
                        text = transcript.take(120),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFD9D9),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ── Exit handle (tap to exit) ──
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .fillMaxWidth()
                .clickable { onExit() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(4.dp)
                    .background(exitBar)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}
