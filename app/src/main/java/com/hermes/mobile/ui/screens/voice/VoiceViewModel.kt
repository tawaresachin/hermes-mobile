package com.hermes.mobile.ui.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.model.ModelInfo
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.data.repository.RepositoryAudio
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
     * use) and stream the answer; onComplete gets the final reply text.
     */
    fun sendTranscribed(
        text: String,
        onChunk: (String) -> Unit = {},
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val sid = _selectedSessionId.value
                    ?: repository.createSession().id.also { _selectedSessionId.value = it }
                val model = _currentModel.value.ifBlank { null }
                model?.let { repository.saveModelForSession(sid, it) }
                // Provider SLUG, never the display label (server contract).
                val provider = _availableModels.value
                    .firstOrNull { it.id == model }
                    ?.providerSlug?.takeIf { it.isNotBlank() }
                val reply = repository.sendMessage(
                    sessionId = sid,
                    query = text,
                    onChunk = onChunk,
                    model = model,
                    provider = provider,
                )
                if (reply.startsWith("\u26a0\ufe0f")) {
                    onError("Assistant did not answer. Check the model/connection and try again.")
                } else {
                    onComplete(reply)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                onError("Could not send to chat. Check the connection and try again.")
            }
        }
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
        val model = _currentModel.value
        viewModelScope.launch {
            try {
                val text = audioRepo.transcribeFromBase64(base64Audio, model = model)
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

    fun switchModel(modelId: String) {
        viewModelScope.launch {
            _currentModel.value = modelId
            loadModels()
        }
    }
}
