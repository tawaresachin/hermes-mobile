package com.hermes.mobile.ui.screens.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.model.ModelInfo
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.components.ModelPickerSheet
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

    // ── Model selection (same live picker as chat — server-side active model) ──
    private val _currentModel = MutableStateFlow("")
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    private var voiceModeJob: Job? = null
    private var ttsPlaybackJob: Job? = null
    private var initJob: Job? = null
    @Volatile private var lastSpokenSentence: String = ""
    @Volatile private var lastSpokenAt: Long = 0L

    init {
        // Create a session for voice conversations on first open
        initJob = viewModelScope.launch {
            try {
                if (_sessionId.value == null) {
                    val session = repository.createSession()
                    _sessionId.value = session.id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "Failed to create session: ${e.message}"
            }
        }
    }

    fun startVoiceLoop() {
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
                        stripEchoPrefix(recognized, lastSpokenSentence)
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
                            }
                            playTtsSentenceBlocking(sentence)
                        }
                    }

                    // Send to server & stream response (60s cap — a stalled
                    // SSE stream must not freeze the voice loop for minutes).
                    val pending = StringBuilder()
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
                                    // Flush complete sentences to the TTS queue
                                    val (done, rest) = splitSentences(pending.toString())
                                    if (done.isNotEmpty()) {
                                        done.forEach { ttsQueue.trySend(it) }
                                        pending.setLength(0)
                                        pending.append(rest)
                                    }
                                }
                            )
                        }
                    } catch (e: TimeoutCancellationException) {
                        // Reply stream stalled — speak what we got and keep
                        // the loop alive instead of dying.
                        Log.w("VoiceScreen", "Reply stream timed out")
                    } finally {
                        // Always flush the trailing partial + close, even if
                        // sendMessage throws — otherwise the queue consumer
                        // would block on the open channel forever.
                        if (pending.isNotBlank()) {
                            ttsQueue.trySend(pending.toString())
                        }
                        ttsQueue.close()
                    }
                    ttsPlaybackJob?.join()

                    // ── AWAITING (brief pause before re-arming; skip if the
                    // user already interrupted playback to speak) ──
                    if (_voiceModeState.value != SphereState.LISTENING) {
                        setVoiceModeState(SphereState.AWAITING)
                        _voiceTranscript.value = "Awaiting…"
                        delay(500) // brief pause before re-arming
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _voiceTranscript.value = "Error: ${e.message}"
                setVoiceModeState(SphereState.AWAITING)
            }
        }
    }

    fun stopVoiceLoop() {
        voiceModeJob?.cancel()
        ttsPlaybackJob?.cancel()
        _voiceModeState.value = SphereState.IDLE
        _voiceAmplitude.value = 0f
        _ttsProgress.value = 0f
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
        _voiceModeState.value = state
    }

    private fun appendVoiceTranscript(text: String) {
        _voiceTranscript.value = if (_voiceTranscript.value.isBlank()) text else "${_voiceTranscript.value}\n$text"
    }

    fun interruptTts() {
        ttsPlaybackJob?.cancel()
        _ttsProgress.value = 0f
        // Immediately re-arm listening
        if (_voiceModeState.value == SphereState.SPEAKING) {
            setVoiceModeState(SphereState.LISTENING)
        }
    }

    private suspend fun listenOnce(): String? {
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

    /** Split text into (complete sentences, trailing partial). */
    private fun splitSentences(text: String): Pair<List<String>, String> {
        val done = mutableListOf<String>()
        var last = 0
        for (i in text.indices) {
            val c = text[i]
            // Don't split decimals ("3.5") or abbreviations ("Mr." → keep whole)
            val prevDigit = i > 0 && text[i - 1].isDigit()
            val nextDigit = i + 1 < text.length && text[i + 1].isDigit()
            val isDecimal = c == '.' && (prevDigit || nextDigit)
            if (!isDecimal && (c == '.' || c == '!' || c == '?' || c == '।' || c == '\n')) {
                val s = text.substring(last, i + 1).trim()
                if (s.isNotBlank()) done.add(s)
                last = i + 1
            }
        }
        return done to text.substring(last).trim()
    }

    /**
     * JARVIS-style echo rejection/salvage with FUZZY matching.
     * Echo survives STT mis-transcription (e.g. "explores"→"laws") because
     * words are matched by Levenshtein similarity, not exact equality.
     * - Pure echo (≥70% of reference words matched) → returns "" (skip).
     * - Echo prefix + user speech appended → returns the user's tail.
     * - No match → returns the input unchanged.
     */
    private fun stripEchoPrefix(text: String, ttsText: String): String {
        if (ttsText.isBlank() || text.isBlank()) return text
        val tWords = cleanWords(text)
        val rWords = cleanWords(ttsText)
        if (rWords.isEmpty() || tWords.isEmpty()) return text

        // Walk both lists; count reference words matched within tolerance.
        // Allow skipping reference words so a dropped/inserted word doesn't
        // kill the whole match.
        var matched = 0
        var i = 0          // index into tWords
        var j = 0          // index into rWords
        var consumed = 0   // transcript words eaten by the echo
        while (i < tWords.size && j < rWords.size) {
            if (wordSimilarity(tWords[i], rWords[j]) >= 0.8f) {
                matched++
                consumed = i + 1
                i++
                j++
            } else {
                j++   // skip one reference word (transcription drift)
            }
        }
        if (matched == 0) return text

        val overlap = matched.toFloat() / rWords.size
        if (overlap >= 0.7f) {
            val tail = tWords.drop(consumed).joinToString(" ").trim()
            return if (tail.length >= 3) tail else ""   // salvage or reject
        }
        return text
    }

    /** Lowercase + strip punctuation for comparison. */
    private fun cleanWords(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s\u0900-\u097F\u0C80-\u0CFF\u0B80-\u0BFF\u0D00-\u0DFF\u0A00-\u0A7F\u0B00-\u0B7F\u0C00-\u0C7F\u0D00-\u0D7F\u0B80-\u0BFF]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    /** Levenshtein-based similarity in [0,1]. */
    private fun wordSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val maxLen = maxOf(a.length, b.length)
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return 1f - prev[b.length].toFloat() / maxLen
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

            // Play via MediaPlayer
            val tempFile = java.io.File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            tempFile.writeBytes(audioBytes)

            val player = android.media.MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
            }

            val duration = player.duration.toFloat()
            val progressJob = viewModelScope.launch {
                try {
                    while (isActive && player.isPlaying) {
                        val current = player.currentPosition.toFloat()
                        _ttsProgress.value = (current / duration).coerceIn(0f, 1f)
                        delay(100)
                    }
                } finally {
                    _ttsProgress.value = 1f
                }
            }

            // Wait for completion (cancellation propagates via delay →
            // CancellationException caught below)
            try {
                while (player.isPlaying) {
                    delay(200)
                }
            } finally {
                progressJob.cancel()
                player.release()
                tempFile.delete()
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

    // Stop the loop when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
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
    AWAITING    // Response done, auto-re-arming
}

// ── Plasma filament descriptors (module-level: allocated once, not per frame) ──

private val TOOL_CALL_BLOCK = Regex(
    "```tool_call\\s*\\n?\\{.*?\\}\\n?```",
    RegexOption.DOT_MATCHES_ALL
)

private data class Rosette(
    val k: Double, val speed: Double, val phase0: Double,
    val cA: Color, val cB: Color, val w: Float
)

private data class Ribbon(
    val k: Double, val speed: Double, val phase0: Double,
    val cA: Color, val cB: Color, val w: Float, val a: Float
)

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
    onTap: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
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
    val rotationAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val speakingWavePhase = remember { mutableStateOf(0f) }

    // Plasma palette — deep blue → purple → pink (matches reference).
    // State shifts subtly: pink-dominant listening, purple bright thinking,
    // blue-dominant speaking; intensity/swirl carry the main state signal.
    data class SphereColors(val core: Color, val ring: Color, val particle: Color, val bg: Color)
    val (coreColor, ringColor, particleColor, bgColor) = when (state) {
        SphereState.IDLE, SphereState.AWAITING ->
            SphereColors(Color(0xFF2A3FD6), Color(0xFF8A3BFF), Color(0xFFB44DFF), Color(0xFF000000))
        SphereState.LISTENING ->
            SphereColors(Color(0xFFFF4DD2), Color(0xFF8A3BFF), Color(0xFF3D8BFF), Color(0xFF000000))
        SphereState.THINKING ->
            SphereColors(Color(0xFF8A3BFF), Color(0xFFB44DFF), Color(0xFFFF4DD2), Color(0xFF000000))
        SphereState.SPEAKING ->
            SphereColors(Color(0xFF3D8BFF), Color(0xFF2A3FD6), Color(0xFF8A3BFF), Color(0xFF000000))
    }

    // Speaking waveform phase
    LaunchedEffect(state, ttsProgress) {
        if (state == SphereState.SPEAKING) {
            val job = scope.launch {
                while (state == SphereState.SPEAKING) {
                    speakingWavePhase.value = (speakingWavePhase.value + 0.15f) % (2 * 3.14159f)
                    delay(50)
                }
            }
            job.invokeOnCompletion { } // simplified - just cancel on recomposition
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
            // ── Soft plasma filaments (blurred → nebula glow) ──
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(10.dp)
            ) {
                val cx = center.x
                val cy = center.y
                val d = size.minDimension
                val baseR = d * 0.40f

                // Rim light — brightest at bottom-right (offset gradient center up-left)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            coreColor.copy(alpha = 0.14f),
                            ringColor.copy(alpha = 0.30f),
                            particleColor.copy(alpha = 0.42f),
                            Color.Transparent
                        ),
                        center = Offset(cx - d * 0.12f, cy - d * 0.12f),
                        radius = d * 0.72f
                    ),
                    radius = d * 0.5f
                )

                // State-driven intensity & swirl speed
                val intensity = when (state) {
                    SphereState.LISTENING -> 0.65f + 0.45f * amplitude
                    SphereState.SPEAKING -> 0.75f + 0.30f * abs(sin(speakingWavePhase.value)).toFloat()
                    SphereState.THINKING -> 1.0f
                    else -> 0.55f + 0.15f * breathAnim.value
                }
                val swirl = when (state) {
                    SphereState.THINKING -> 1.5f
                    SphereState.SPEAKING -> 1.15f
                    else -> 0.75f
                }

                // Three rosette filaments — blue → purple → pink, swirling
                val rosettes = listOf(
                    Rosette(3.0, 0.50, 0.0, Color(0xFF2A3FD6), Color(0xFF8A3BFF), 7f),
                    Rosette(5.0, -0.42, 2.1, Color(0xFF8A3BFF), Color(0xFFFF4DD2), 6f),
                    Rosette(4.0, 0.46, 4.2, Color(0xFFFF4DD2), Color(0xFF3D8BFF), 8f)
                )
                val segs = 72
                rosettes.forEach { ros ->
                    val phase = rotationAnim.value * ros.speed * swirl + ros.phase0
                    for (i in 0 until segs) {
                        val th1 = i * 6.28318 / segs
                        val th2 = (i + 1) * 6.28318 / segs
                        val m1 = cos(ros.k * th1 + phase).toFloat()
                        val m2 = cos(ros.k * th2 + phase).toFloat()
                        val r1 = baseR * (0.62f + 0.42f * m1)
                        val r2 = baseR * (0.62f + 0.42f * m2)
                        val p1 = Offset(cx + r1 * cos(th1).toFloat(), cy + r1 * sin(th1).toFloat())
                        val p2 = Offset(cx + r2 * cos(th2).toFloat(), cy + r2 * sin(th2).toFloat())
                        val t = 0.5f + 0.5f * m1
                        val depth = 1f - abs(m1)   // brightest/thickest at lobe peaks
                        val col = lerp(ros.cA, ros.cB, t)
                        drawLine(
                            color = col.copy(alpha = (0.30f + 0.40f * depth) * intensity),
                            start = p1,
                            end = p2,
                            strokeWidth = (ros.w * (0.35f + 0.9f * depth)).coerceIn(1f, 16f),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            // ── Volumetric shadow (dark upper-left → depth) ──
            Canvas(modifier = Modifier.fillMaxSize()) {
                val d = size.minDimension
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.32f)
                        ),
                        center = Offset(center.x + d * 0.10f, center.y + d * 0.10f),
                        radius = d * 0.5f
                    ),
                    radius = d * 0.5f
                )
            }

            // ── Bright teardrop swirl (upper-left, slowly orbiting) ──
            Canvas(modifier = Modifier.fillMaxSize()) {
                val d = size.minDimension
                val cx = center.x
                val cy = center.y
                val bx = cx - d * 0.17f
                val by = cy - d * 0.19f
                rotate(
                    degrees = rotationAnim.value * 0.30f,
                    pivot = center
                ) {
                    val tear = Path().apply {
                        moveTo(bx, by - d * 0.055f)
                        cubicTo(bx - d * 0.10f, by - d * 0.095f, bx - d * 0.115f, by + d * 0.02f, bx - d * 0.045f, by + d * 0.055f)
                        cubicTo(bx + d * 0.015f, by + d * 0.09f, bx + d * 0.095f, by + d * 0.035f, bx + d * 0.075f, by - d * 0.02f)
                        cubicTo(bx + d * 0.055f, by - d * 0.055f, bx + d * 0.02f, by - d * 0.065f, bx, by - d * 0.055f)
                        close()
                    }
                    drawPath(
                        tear,
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.95f),
                                Color(0xFF3D8BFF).copy(alpha = 0.85f),
                                Color(0xFF2A3FD6).copy(alpha = 0.0f)
                            ),
                            center = Offset(bx, by),
                            radius = d * 0.13f
                        )
                    )
                }
            }

            // ── Crisp highlight ribbons (sharp layer, no blur) ──
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = center.x
                val cy = center.y
                val d = size.minDimension
                val baseR = d * 0.40f
                val ribbons = listOf(
                    Ribbon(3.0, 0.50, 0.0, Color(0xFF6A5CFF), Color(0xFFFF6BD6), 2.2f, 0.80f),
                    Ribbon(5.0, -0.42, 2.1, Color(0xFF4DA3FF), Color(0xFFB44DFF), 1.8f, 0.65f)
                )
                val segs = 60
                ribbons.forEach { rb ->
                    val phase = rotationAnim.value * rb.speed * 1.15 + rb.phase0
                    for (i in 0 until segs) {
                        val th1 = i * 6.28318 / segs
                        val th2 = (i + 1) * 6.28318 / segs
                        val m1 = cos(rb.k * th1 + phase).toFloat()
                        val m2 = cos(rb.k * th2 + phase).toFloat()
                        val r1 = baseR * (0.62f + 0.42f * m1)
                        val r2 = baseR * (0.62f + 0.42f * m2)
                        val p1 = Offset(cx + r1 * cos(th1).toFloat(), cy + r1 * sin(th1).toFloat())
                        val p2 = Offset(cx + r2 * cos(th2).toFloat(), cy + r2 * sin(th2).toFloat())
                        val t = 0.5f + 0.5f * m1
                        val depth = 1f - abs(m1)
                        drawLine(
                            color = lerp(rb.cA, rb.cB, t).copy(alpha = rb.a * (0.35f + 0.65f * depth)),
                            start = p1,
                            end = p2,
                            strokeWidth = rb.w * (0.5f + 0.8f * depth),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            // ── Bright rim outline (white → light purple, crisp + soft glow) ──
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(5.dp)
            ) {
                drawCircle(
                    color = Color(0xFFE8D8FF).copy(alpha = 0.55f),
                    radius = size.minDimension * 0.5f - 1f,
                    style = Stroke(width = 6f)
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f),
                            Color(0xFFD9B8FF).copy(alpha = 0.75f),
                            Color(0xFFB44DFF).copy(alpha = 0.55f),
                            Color(0xFF8A3BFF).copy(alpha = 0.60f),
                            Color.White.copy(alpha = 0.95f)
                        ),
                        center = center
                    ),
                    radius = size.minDimension * 0.5f - 1.5f,
                    style = Stroke(width = 2.5f)
                )
            }

            // ── Lens flare streaks (right edge, radiating outward) ──
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = center.x
                val cy = center.y
                val r = size.minDimension * 0.5f
                val streakOffsets = listOf(-0.055f, -0.02f, 0.012f, 0.045f)
                streakOffsets.forEachIndexed { i, off ->
                    val y = cy + off * size.minDimension
                    val start = Offset(cx + r - 3f, y)
                    val end = Offset(cx + r + (30f + i * 16f), y + off * size.minDimension * 0.6f)
                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.55f),
                                Color(0xFFB44DFF).copy(alpha = 0.15f),
                                Color.Transparent
                            ),
                            start = start,
                            end = end
                        ),
                        start = start,
                        end = end,
                        strokeWidth = 1.4f
                    )
                }
            }

            // ── Bright glowing core ──
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.85f),
                            coreColor.copy(alpha = 0.45f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension * 0.15f
                    ),
                    radius = size.minDimension * 0.15f
                )
            }
        }

        // ── Model chip (top-left — opens the shared model picker) ──
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 48.dp, start = 16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF141B2D).copy(alpha = 0.85f))
                .border(1.dp, Color(0xFF2A3A4A), RoundedCornerShape(50))
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
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Select model",
                    tint = Color(0xFF8C9AAB),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // ── Top transcript bar ──
        if (transcript.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp, start = 24.dp, end = 24.dp, bottom = 0.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF0C1016).copy(alpha = 0.92f),
                        contentColor = Color.White
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
                                    color = Color(0xFF8C9AAB)
                                )
                            }
                        }
                    }
                }
            }
        }

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
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8C9AAB)
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
                VoiceLanguage.entries.forEach { lang ->
                    val selected = lang == language
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) Color(0xFF8A3BFF).copy(alpha = 0.35f)
                                else Color(0xFF141B2D).copy(alpha = 0.85f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) Color(0xFFB44DFF) else Color(0xFF2A3A4A),
                                shape = RoundedCornerShape(50)
                            )
                            .clickable { onLanguageSelected(lang) }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = lang.chip,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) Color.White else Color(0xFF8C9AAB)
                        )
                    }
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
                    .background(Color(0xFF2A3A4A))
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}
