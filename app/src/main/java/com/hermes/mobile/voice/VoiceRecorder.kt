package com.hermes.mobile.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    var isRecording: Boolean = false
        private set

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startRecording(cacheDir: File): File? {
        if (!hasPermission()) return null

        val file = File(cacheDir, "hermes_voice_${System.currentTimeMillis()}.ogg")
        outputFile = file

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioSamplingRate(16000)
            setAudioChannels(1)
            setAudioEncodingBitRate(32000)
            setOutputFile(file.absolutePath)
            try {
                prepare()
                start()
                isRecording = true
            } catch (e: Exception) {
                release()
                return null
            }
        }
        return file
    }

    fun stopRecording(): File? {
        try {
            recorder?.apply {
                if (isRecording) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            // Ignore release errors
        }
        recorder = null
        isRecording = false
        return outputFile
    }

    fun release() {
        stopRecording()
    }
}
