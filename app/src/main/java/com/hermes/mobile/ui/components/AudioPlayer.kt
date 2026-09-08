package com.hermes.mobile.ui.components

import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hermes.mobile.R
import java.io.File

@Composable
fun AudioPlayer(
    dataUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var preparing by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(dataUrl) {
        var mp: MediaPlayer? = null
        try {
            val parts = dataUrl.split(",", limit = 2)
            if (parts.size != 2) throw IllegalArgumentException("Invalid data URL")
            val base64 = parts[1]
            val audioBytes = Base64.decode(base64, Base64.DEFAULT)
            val tempFile = File.createTempFile("hermes_audio_", ".mp3", context.cacheDir)
            tempFile.writeBytes(audioBytes)
            mp = MediaPlayer()
            mp.setDataSource(tempFile.absolutePath)
            mp.setOnPreparedListener {
                preparing = false
                it.start()
            }
            mp.setOnErrorListener { _, _, _ ->
                errorMsg = "Playback error"
                preparing = false
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            errorMsg = e.message
            preparing = false
        }
        player = mp
        onDispose {
            mp?.release()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audio playback") },
        text = {
            if (preparing) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            } else if (errorMsg != null) {
                Text(errorMsg ?: "Error", color = Color.Red)
            } else {
                Text("Playing...")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                player?.stop()
                onDismiss()
            }) {
                Icon(painter = painterResource(android.R.drawable.ic_menu_close_clear_cancel), contentDescription = "Close")
                Text("Close")
            }
        },
        dismissButton = {}
    )
}