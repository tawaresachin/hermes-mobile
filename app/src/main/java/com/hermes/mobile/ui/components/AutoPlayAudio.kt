package com.hermes.mobile.ui.components

import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * Headless one-shot audio playback for a `data:audio/...;base64,` URL.
 * Starts as soon as the composable enters composition, releases on leave.
 * Used by the voice screen to speak every assistant reply (no UI at all —
 * the reply text on screen IS the visual).
 */
@Composable
fun AutoPlayAudio(
    dataUrl: String,
    onFinished: () -> Unit = {},
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var done by remember(dataUrl) { mutableStateOf(false) }
    DisposableEffect(dataUrl) {
        var mp: MediaPlayer? = null
        try {
            val parts = dataUrl.split(",", limit = 2)
            if (parts.size == 2) {
                val bytes = Base64.decode(parts[1], Base64.DEFAULT)
                val tmp = File.createTempFile("hermes_tts_", ".mp3", context.cacheDir)
                tmp.writeBytes(bytes)
                mp = MediaPlayer().apply {
                    setDataSource(tmp.absolutePath)
                    setOnPreparedListener { start() }
                    setOnCompletionListener {
                        if (!done) { done = true; onFinished() }
                    }
                    setOnErrorListener { _, _, _ ->
                        if (!done) { done = true; onError("Playback error"); onFinished() }
                        true
                    }
                    prepareAsync()
                }
            } else {
                done = true
                onError("Invalid audio")
                onFinished()
            }
        } catch (e: Exception) {
            done = true
            onError(e.message ?: "Playback error")
            onFinished()
        }
        onDispose {
            try { mp?.release() } catch (_: Exception) { }
        }
    }
}
