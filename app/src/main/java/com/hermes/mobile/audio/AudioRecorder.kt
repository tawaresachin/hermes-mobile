package com.hermes.mobile.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayOutputStream

/**
 * Captures PCM audio via Android AudioRecord and encodes to base64 WAV.
 */
class AudioRecorder {
    private var recorder: AudioRecord? = null
    private var isRecording = false

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    suspend fun capture(durationMs: Int = 30000): String? = withContext(Dispatchers.IO) {
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )
            val audioData = ByteArrayOutputStream()
            val buffer = ByteArray(bufferSize)

            recorder!!.startRecording()
            isRecording = true

            val endTime = System.currentTimeMillis() + durationMs
            while (isRecording && System.currentTimeMillis() < endTime) {
                val read = recorder!!.read(buffer, 0, buffer.size)
                if (read > 0) {
                    audioData.write(buffer, 0, read)
                }
            }

            recorder!!.stop()
            recorder!!.release()
            recorder = null
            isRecording = false

            val pcmData = audioData.toByteArray()
            val wavData = encodeWav(pcmData, sampleRate, 1, 16)
            Base64.encodeToString(wavData, Base64.NO_WRAP)
        } catch (e: Exception) {
            isRecording = false
            recorder?.release()
            recorder = null
            null
        }
    }

    fun stop() {
        isRecording = false
        recorder?.stop()
        recorder?.release()
        recorder = null
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
}