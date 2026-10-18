package com.hermes.mobile.ui.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.model.ModelInfo
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.data.repository.RepositoryAudio
import com.hermes.mobile.data.runs.RunController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Conversation mode: one enum drives the hands-free loop. `Paused` is not a
 * failure — the mode stays on and resumes when the cause clears. */
enum class ConversationPhase { Off, Listening, Transcribing, Thinking, Speaking, Paused }

/** One exchange in the voice transcript (both voice paths share the list). */
data class VoiceTurn(
    val id: Int,
    val userText: String,
    val assistantText: String = "",
    val done: Boolean = false,
)

/** Why the loop is paused — it resumes only when THAT cause clears. */
private enum class PauseReason { Approval, Busy, Connection }

/** Beat before re-opening the mic: the TTS tail and room echo land in the
 * first frames after playback stops, and a 0 ms restart transcribes them. */
private const val CONVERSATION_RESTART_MS = 300L

/** Connection probe cadence while the loop runs. */
private const val CONNECTION_POLL_MS = 3_000L

/** Loop tick — also how fast an approval/busy pause clears. */
private const val CONVERSATION_TICK_MS = 250L

/** Spoken replies stay snappy; the bubble still shows the full text. */
private const val SPOKEN_REPLY_MAX_CHARS = 2000

/**
 * ViewModel for [VoiceScreen].
 *
 * Voice is a *frontend* for the same sessions the chat screen uses:
 * pick (or create) a session, hold the mic, release = transcribe →
 * send → streamed answer → always spoken back via TTS.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val audioRepo: RepositoryAudio,
    private val repository: HermesRepository
) : ViewModel() {

    private val _currentModel = MutableStateFlow("")
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    // Same Room-backed session list the chat/sessions screens use.
    val sessions: StateFlow<List<Session>> =
        repository.allSessions.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
        )

    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

    /** Shared transcript: hold-to-talk and conversation mode both append
     * here, so the screen renders one list. */
    private val _turns = MutableStateFlow<List<VoiceTurn>>(emptyList())
    val turns: StateFlow<List<VoiceTurn>> = _turns.asStateFlow()

    private val _conversationPhase = MutableStateFlow(ConversationPhase.Off)
    val conversationPhase: StateFlow<ConversationPhase> = _conversationPhase.asStateFlow()

    /** Pause/stop explanation shown under the mic (null = all good). */
    private val _conversationStatus = MutableStateFlow<String?>(null)
    val conversationStatus: StateFlow<String?> = _conversationStatus.asStateFlow()

    /** Tool call on the mode's run waiting for approve/deny. Rendered inline
     * here so the loop never has to send the user to the Chat tab. */
    private val _pendingApproval = MutableStateFlow<RunController.ApprovalRequest?>(null)
    val pendingApproval: StateFlow<RunController.ApprovalRequest?> = _pendingApproval.asStateFlow()

    /** Mic loudness (0..1) for the orb's live swell; Listening only. */
    private val _conversationLevel = MutableStateFlow(0f)
    val conversationLevel: StateFlow<Float> = _conversationLevel.asStateFlow()

    /** TTS payload for [AutoPlayAudio]; null = nothing to play. Barge-in and
     * playback-end both clear it. */
    private val _ttsDataUrl = MutableStateFlow<String?>(null)
    val ttsDataUrl: StateFlow<String?> = _ttsDataUrl.asStateFlow()

    private var turnSeq = 0
    private var ttsRequest = 0
    private var pauseReason: PauseReason? = null
    private var relistenJob: Job? = null
    private var loopJob: Job? = null
    private var probeBusy = false

    init {
        viewModelScope.launch {
            loadModels()
            if (_selectedSessionId.value == null) {
                _selectedSessionId.value = repository.getLastSession()?.id
            }
        }
    }

    fun loadModels() {
        viewModelScope.launch {
            _modelsLoading.value = true
            try {
                val response = repository.fetchModelOptions() ?: repository.listModels()
                if (response != null) {
                    val models = response.models.filter { it.id.isNotBlank() }
                    val serverDefault = response.current.takeIf { c -> models.any { it.id == c } }
                    val keepCurrent = _currentModel.value.takeIf { c -> models.any { it.id == c } }
                    _availableModels.value = models
                    if (keepCurrent == null && serverDefault != null) {
                        _currentModel.value = serverDefault
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
            } finally {
                _modelsLoading.value = false
            }
        }
    }

    /** Switching target session = switch to THAT session's model pick.
     * Sessions never borrow each other's selection; unset sessions fall
     * back to the server default on next send. */
    fun selectSession(id: String) {
        _selectedSessionId.value = id
        repository.savedModelForSession(id)?.let { _currentModel.value = it }
    }

    /** Create a fresh session and make it the voice target. */
    fun startNewSession(onReady: (String) -> Unit = {}) {
        viewModelScope.launch {
            val s = repository.createSession()
            _selectedSessionId.value = s.id
            repository.savedModelForSession(s.id)?.let { _currentModel.value = it }
            onReady(s.id)
        }
    }

    /**
     * Send transcribed text into the selected session (created on first
     * use) and stream the answer into [turns]; onComplete gets the final
     * reply text. Both voice paths go through here, so the transcript and
     * the wire payload are identical either way.
     */
    fun sendTranscribed(
        text: String,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val turnId = ++turnSeq
        _turns.update { it + VoiceTurn(id = turnId, userText = text) }
        viewModelScope.launch {
            try {
                val sid = _selectedSessionId.value
                    ?: repository.createSession().id.also { _selectedSessionId.value = it }
                val model = _currentModel.value.ifBlank { null }
                model?.let {
                    val slug = _availableModels.value
                        .firstOrNull { m -> m.id == it }?.providerSlug.orEmpty()
                    repository.saveModelForSession(sid, it, slug)
                }
                // Provider SLUG, never the display label (server contract).
                val provider = _availableModels.value
                    .firstOrNull { it.id == model }
                    ?.providerSlug?.takeIf { it.isNotBlank() }
                // Durable run path: the turn survives screen changes and the
                // voice loop just awaits its final text (streaming partials
                // ride the live-turn flow). No synchronous SSE to hold open.
                val startedAt = System.currentTimeMillis()
                repository.runController.startTurn(
                    sessionId = sid,
                    query = text,
                    model = model.orEmpty(),
                    provider = provider,
                    // Admit failure already reports; awaitTurn returns null
                    // right after, so the drop must be id-keyed (below) or
                    // the previous turn would be deleted too.
                    onAdmitError = { onError(it) },
                )
                // Partial captions while the run streams: the flow carries the
                // full accumulated text, so assign it. No per-delta substring
                // and no re-concatenation of the whole reply on every tick.
                var shown = 0
                val chunkJob = launch {
                    repository.runController.streamingTextFlow(sid).collect { full ->
                        if (full.length > shown) {
                            shown = full.length
                            updateTurn(turnId) { it.copy(assistantText = full) }
                        }
                    }
                }
                val reply = repository.runController.awaitTurn(sid, startedAt)
                chunkJob.cancel()
                when {
                    reply == null -> {
                        dropTurn(turnId)
                        onError("Assistant did not answer. Check the model/connection and try again.")
                    }
                    reply.startsWith("\u26a0\ufe0f") -> {
                        dropTurn(turnId)
                        onError("Assistant did not answer. Check the model/connection and try again.")
                    }
                    else -> {
                        // A blank final reply may still have streamed text on
                        // screen — keep and speak that instead of nothing.
                        val finalText = reply.ifBlank {
                            _turns.value.lastOrNull()?.assistantText.orEmpty()
                        }
                        updateTurn(turnId) { it.copy(assistantText = finalText, done = true) }
                        onComplete(finalText)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                dropTurn(turnId)
                onError("Could not send to chat. Check the connection and try again.")
            }
        }
    }

    /** Drop the transcript rows (session switch / new conversation). */
    fun clearTurns() {
        _turns.value = emptyList()
    }

    private fun updateTurn(id: Int, transform: (VoiceTurn) -> VoiceTurn) {
        _turns.update { list ->
            val last = list.lastOrNull() ?: return@update list
            if (last.id != id) list else list.dropLast(1) + transform(last)
        }
    }

    private fun dropTurn(id: Int) {
        _turns.update { list -> if (list.lastOrNull()?.id == id) list.dropLast(1) else list }
    }

    /**
     * Send base64 audio to the STT endpoint using the current model.
     * Callbacks run on the ViewModel scope — the old Result-return shape
     * always reported success because the coroutine finished later.
     */
    fun transcribe(
        base64Audio: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        // NOTE: never forward the chat model here — STT model is a server
        // setting (whisper). Sending "agnes-2.5-flash" as the whisper model
        // made the adapter download ggml-agnes-2.5-flash.bin -> fail ->
        // "Transcription failed" on every press.
        viewModelScope.launch {
            try {
                val text = audioRepo.transcribeFromBase64(base64Audio)
                onSuccess(text)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                onError("Transcription failed. Check the connection and try again.")
            }
        }
    }

    /**
     * Synthesise text to audio via the TTS endpoint using the current model.
     */
    fun speak(
        text: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        val model = _currentModel.value
        viewModelScope.launch {
            try {
                val dataUrl = audioRepo.speakToDataUrl(text, model = model)
                onSuccess(dataUrl)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                onError("Voice reply failed. Try again.")
            }
        }
    }

    // ─── Conversation mode (hands-free loop) ─────────────────────────────
    //
    // off → listening → transcribing → thinking → speaking → listening …
    // Measured in one place on purpose: the capture engine (VoiceCapture)
    // only reports utterance boundaries, every transition lives here.

    /** Start the loop. Guards mirror the reference app's: a start that cannot
     * work says why instead of opening the mic into a dead loop. */
    fun startConversation() {
        if (_conversationPhase.value != ConversationPhase.Off) return
        val cfg = repository.getSavedConfig()
        if (cfg == null || cfg.baseUrl.isBlank()) {
            _conversationStatus.value = "Connect to Hermes before starting conversation mode."
            return
        }
        val sid = _selectedSessionId.value
        if (sid != null && repository.runController.isBusy(sid)) {
            _conversationStatus.value = "Wait for the current reply to finish before starting conversation mode."
            return
        }
        _conversationStatus.value = null
        pauseReason = null
        cancelSpeech() // never listen over a reply that is playing
        _conversationPhase.value = ConversationPhase.Listening
        watchLoop()
    }

    /** Screen-level note (e.g. a denied mic permission) while the loop is off. */
    fun reportConversationStatus(message: String) {
        _conversationStatus.value = message
    }

    /** Also the failure exit: [reason] becomes the status line. */
    fun stopConversation(reason: String? = null) {        if (_conversationPhase.value == ConversationPhase.Off) return
        relistenJob?.cancel()
        loopJob?.cancel()
        loopJob = null
        pauseReason = null
        _pendingApproval.value = null
        _conversationLevel.value = 0f
        _conversationPhase.value = ConversationPhase.Off
        cancelSpeech()
        _conversationStatus.value = reason
    }

    /** The screen is leaving (tab switch, rotation, background): the mic goes
     * with it, so the mode ends. Keep a pause explanation — it is the more
     * useful line to come back to. */
    fun stopOnScreenExit() {
        val phase = _conversationPhase.value
        if (phase == ConversationPhase.Off) return
        stopConversation(
            if (phase == ConversationPhase.Paused) _conversationStatus.value
            else "Conversation mode ended — it runs only while this screen is open."
        )
    }

    /** Smoothed mic level from [VoiceCapture] — a late frame after a phase
     * change must not move the orb. */
    fun onLevel(level: Float) {
        if (_conversationPhase.value == ConversationPhase.Listening) {
            _conversationLevel.value = level
        }
    }

    /** One finished utterance from [VoiceCapture] (base64 WAV). Audio that
     * arrives after the phase moved on is stale — dropped, not queued. */
    fun onUtterance(base64Wav: String) {
        if (_conversationPhase.value != ConversationPhase.Listening) return
        _conversationPhase.value = ConversationPhase.Transcribing
        transcribe(
            base64Wav,
            onSuccess = { text ->
                // Stopped (or paused) while Whisper was running: the audio is
                // stale, sending it would submit a turn nobody asked for.
                if (_conversationPhase.value != ConversationPhase.Transcribing) return@transcribe
                val trimmed = text.trim()
                if (trimmed.isBlank()) {
                    // Whisper heard nothing — just listen again.
                    relisten()
                } else {
                    _conversationStatus.value = null
                    _conversationPhase.value = ConversationPhase.Thinking
                    sendTranscribed(
                        trimmed,
                        onComplete = { reply -> speakTurn(reply) },
                        onError = { msg -> stopConversation(msg) },
                    )
                }
            },
            onError = { msg -> stopConversation(msg) },
        )
    }

    /** Playback ended (or the player was cleared). */
    fun onPlaybackFinished() {
        val wasSpeaking = _conversationPhase.value == ConversationPhase.Speaking
        _ttsDataUrl.value = null
        if (wasSpeaking) relisten()
    }

    /** Answer the run's pending approval (once | session | always | deny). */
    fun resolveApproval(choice: String) {
        val sid = _selectedSessionId.value ?: return
        repository.runController.resolveApproval(sid, choice)
    }

    /** Barge-in: tapping the orb mid-reply abandons the playback. Clears the
     * payload for the hold-to-talk path too. */
    fun interruptPlayback() {
        cancelSpeech()
        if (_conversationPhase.value == ConversationPhase.Speaking) relisten()
    }

    /** Synthesise + hand the payload to [AutoPlayAudio] (both voice paths). */
    fun speakReply(text: String, onError: (String) -> Unit = {}) {
        if (text.isBlank()) {
            onError("Nothing to speak")
            return
        }
        val request = ++ttsRequest
        speak(
            text.take(SPOKEN_REPLY_MAX_CHARS),
            onSuccess = { url ->
                // A stop/barge-in while the synthesis was running must not
                // resurrect the audio later (it would play on the next visit).
                if (request == ttsRequest) _ttsDataUrl.value = url
            },
            onError = onError,
        )
    }

    /** Drop whatever is playing or being synthesised. */
    private fun cancelSpeech() {
        ttsRequest++
        _ttsDataUrl.value = null
    }

    private fun speakTurn(reply: String) {
        // Stopped while the run was finishing: don't restart the loop by
        // speaking (the reply is still on screen).
        if (_conversationPhase.value == ConversationPhase.Off) return
        if (reply.isBlank()) {
            relisten()
            return
        }
        _conversationPhase.value = ConversationPhase.Speaking
        speakReply(reply) { msg -> stopConversation(msg) }
    }

    /** Re-open the mic after a beat, unless the phase already moved on. */
    private fun relisten() {
        val from = _conversationPhase.value
        if (from == ConversationPhase.Off) return
        relistenJob?.cancel()
        relistenJob = viewModelScope.launch {
            delay(CONVERSATION_RESTART_MS)
            // Gate on the phase alone: `pause()` cancels this job, so a stale
            // pause reason must NOT block it — that stranded the mode in
            // Speaking after the reply it paused for had already been spoken.
            if (_conversationPhase.value == from) {
                _conversationPhase.value = ConversationPhase.Listening
            }
        }
    }

    /**
     * Single watcher for all three pause causes, so the loop cannot get stuck
     * paused: an approval/question on the live turn, a turn still running, and
     * an unreachable gateway. Each clears itself.
     */
    private fun watchLoop() {
        if (loopJob?.isActive == true) return
        loopJob = viewModelScope.launch {
            val cfg = repository.getSavedConfig() ?: return@launch
            var tick = 0L
            while (_conversationPhase.value != ConversationPhase.Off) {
                val sid = _selectedSessionId.value
                val turn = sid?.let { repository.runController.liveTurn(it) }
                _pendingApproval.value = turn?.pendingApproval
                when {
                    turn?.pendingApproval != null -> pause(
                        PauseReason.Approval,
                        "Conversation mode paused — approve or deny the request below."
                    )
                    turn != null -> when {
                        // Answered: the run is working again, so stop showing a
                        // stale "waiting for you" line (the reply still speaks
                        // through awaitTurn's callback).
                        pauseReason == PauseReason.Approval -> {
                            pauseReason = null
                            _conversationStatus.value = null
                            _conversationPhase.value = ConversationPhase.Thinking
                        }
                        // A turn is in flight: never stack a second run on it.
                        _conversationPhase.value == ConversationPhase.Listening ->
                            pause(PauseReason.Busy, "Wait for the current reply to finish…")
                    }
                    else -> {
                        resume(PauseReason.Approval)
                        resume(PauseReason.Busy)
                    }
                }

                if (tick % (CONNECTION_POLL_MS / CONVERSATION_TICK_MS) == 0L && !probeBusy) {
                    // OFF the tick loop on purpose: the shared HTTP client has
                    // a 300 s read timeout (it is built for SSE), so awaiting a
                    // probe inline would stall approval/busy detection with the
                    // mic still open. `probeBusy` clears only when the call
                    // really returns, so a wedged gateway costs one thread, not
                    // one per tick.
                    probeBusy = true
                    launch {
                        try {
                            val reachable = try {
                                repository.isServerReachable(cfg)
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                                false
                            }
                            if (reachable) {
                                resume(PauseReason.Connection)
                            } else if (_conversationPhase.value == ConversationPhase.Listening) {
                                // Only a silent listener needs this pause: an
                                // upload/reply in flight reports its own
                                // failure, and pausing would cut its playback.
                                pause(
                                    PauseReason.Connection,
                                    "Hermes is unreachable. Conversation mode resumes when it returns."
                                )
                            }
                        } finally {
                            probeBusy = false
                        }
                    }
                }
                tick++
                delay(CONVERSATION_TICK_MS)
            }
        }
    }

    private fun pause(reason: PauseReason, message: String) {
        if (_conversationPhase.value == ConversationPhase.Off) return
        if (pauseReason == reason) return
        pauseReason = reason
        relistenJob?.cancel()
        _conversationLevel.value = 0f
        _conversationPhase.value = ConversationPhase.Paused
        _conversationStatus.value = message
    }

    /** Clear [reason] and listen again — but never on top of a spoken reply
     * (playback-end owns that transition). */
    private fun resume(reason: PauseReason) {
        if (pauseReason != reason) return
        pauseReason = null
        if (_conversationPhase.value != ConversationPhase.Paused) return
        _conversationStatus.value = null
        relisten()
    }

    fun switchModel(modelId: String, providerSlug: String = "", global: Boolean = false) {
        val sid = _selectedSessionId.value ?: run {
            _currentModel.value = modelId
            return
        }
        viewModelScope.launch {
            val success = repository.switchModel(sid, modelId, global)
            if (success) {
                _currentModel.value = modelId
                repository.saveModelForSession(sid, modelId, providerSlug)
                if (global) loadModels()
            }
        }
    }
}
