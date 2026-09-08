package com.hermes.mobile.ui.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.model.ModelInfo
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.data.repository.RepositoryAudio
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for [VoiceScreen].
 *
 * Holds the transcription result, current model, and model list.
 * Loads the available models on init so the voice screen respects
 * whatever model the user selected in chat (or the global default).
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

    init {
        viewModelScope.launch {
            loadModels()
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
                    val currentModel = keepCurrent ?: serverDefault ?: models.firstOrNull()?.id ?: ""
                    _availableModels.value = models
                    if (keepCurrent == null && serverDefault != null) {
                        _currentModel.value = serverDefault
                    }
                }
            } catch (_: Exception) {
            } finally {
                _modelsLoading.value = false
            }
        }
    }

    /**
     * Send base64 audio to the STT endpoint using the current model.
     */
    fun transcribe(
        base64Audio: String,
        onSuccess: (String) -> Unit
    ): Result<Unit> {
        var result: Result<Unit> = Result.success(Unit)
        val model = _currentModel.value
        viewModelScope.launch {
            try {
                val text = audioRepo.transcribeFromBase64(base64Audio, model = model)
                onSuccess(text)
            } catch (e: Exception) {
                result = Result.failure(e)
            }
        }
        return result
    }

    /**
     * Synthesise text to audio via the TTS endpoint using the current model.
     */
    fun speak(
        text: String,
        onSuccess: (String) -> Unit
    ): Result<Unit> {
        var result: Result<Unit> = Result.success(Unit)
        val model = _currentModel.value
        viewModelScope.launch {
            try {
                val dataUrl = audioRepo.speakToDataUrl(text, model = model)
                onSuccess(dataUrl)
            } catch (e: Exception) {
                result = Result.failure(e)
            }
        }
        return result
    }

    fun switchModel(modelId: String) {
        viewModelScope.launch {
            _currentModel.value = modelId
            loadModels()
        }
    }
}