package com.hermes.mobile.data.repository

import com.hermes.mobile.network.HermesApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepositoryAudio @Inject constructor(
    private val apiService: HermesApiService
) {
    suspend fun transcribeFromBase64(
        base64Audio: String,
        mimeType: String = "audio/mp4",
        model: String? = null
    ): String {
        var b64 = base64Audio
        if (b64.startsWith("data:") && "," in b64) {
            b64 = b64.split(",", limit = 2)[1]
        }
        return apiService.transcribeAudio(b64.trim(), mimeType, model)
    }

    suspend fun speakToDataUrl(
        text: String,
        model: String? = null
    ): String {
        return apiService.speakText(text, model)
    }
}