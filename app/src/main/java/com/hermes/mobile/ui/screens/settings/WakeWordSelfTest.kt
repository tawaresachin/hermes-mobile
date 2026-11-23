package com.hermes.mobile.ui.screens.settings

import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.mobile.wake.WakeWordDetector
import com.hermes.mobile.wake.WavFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays
import kotlin.math.min

private data class FixtureResult(val name: String, val maxScore: Float, val fired: Boolean, val frames: Int, val error: String?)

/** DEBUG-only: feeds both bundled WAVs through the real detector, no mic needed. */
@Composable
fun WakeWordSelfTestRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !running) {
            running = true
            scope.launch(Dispatchers.IO) {
                val lines = listOf("hey_hermes.wav", "hello_there.wav").map { runFixture(context, it) }
                val text = buildString {
                    append("threshold 0.6 · 3 windows · 2s cooldown\n\n")
                    lines.forEach { r ->
                        append(r.name).append('\n')
                        if (r.error != null) {
                            append("  ERROR: ").append(r.error)
                        } else {
                            append("  max score ").append("%.4f".format(r.maxScore))
                            append("  ·  ").append(if (r.fired) "FIRED" else "not fired")
                            append("  ·  ").append(r.frames).append(" frames")
                        }
                        append('\n')
                    }
                }
                withContext(Dispatchers.Main) {
                    report = text
                    running = false
                    show = true
                }
            }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Wake word self-test",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                text = if (running) "running…" else "run fixtures",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Wake word self-test") },
            text = {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    modifier = Modifier.padding(top = 4.dp),
                )
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("Close") } },
        )
    }
}

private fun runFixture(context: Context, name: String): FixtureResult {
    var detector: WakeWordDetector? = null
    return try {
        detector = WakeWordDetector(context.assets)
        val samples = WavFixtures.readPcm16Mono(context.assets.open("wakeword/fixtures/$name"))
        val frame = ShortArray(WakeWordDetector.SAMPLES_PER_CHUNK)
        var fired = false
        var frames = 0
        var offset = 0
        while (offset < samples.size) {
            val n = min(WakeWordDetector.SAMPLES_PER_CHUNK, samples.size - offset)
            Arrays.fill(frame, 0)
            System.arraycopy(samples, offset, frame, 0, n)
            if (detector.processFrame(frame)) fired = true
            offset += n
            frames++
        }
        FixtureResult(name, detector.maxScore, fired, frames, null)
    } catch (t: Throwable) {
        val msg = buildString {
            append(t.toString())
            var cause = t.cause
            while (cause != null) {
                append(" <- ").append(cause.toString())
                cause = cause.cause
            }
        }
        Log.e("WakeWordSelfTest", "$name: $msg", t)
        FixtureResult(name, 0f, false, 0, msg)
    } finally {
        runCatching { detector?.close() }
    }
}
