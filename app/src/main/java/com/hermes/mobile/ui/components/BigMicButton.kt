package com.hermes.mobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BigMicButton(
    isRecording: Boolean,
    isCancelled: Boolean = false,
    onRecordingStart: () -> Unit,
    onRecordingStop: () -> Unit,
    onTranscription: (String) -> Unit = {},
    onCancelRecording: () -> Unit = {},
    onError: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    iconSize: Dp = 36.dp,
    buttonElevation: Dp = 8.dp,
    ringColor: Color = Color(0xFF1976D2).copy(alpha = 0.55f),
    pulseColor: Color = Color(0xFF1976D2).copy(alpha = 0.35f),
    glowColor: Color = Color(0xFF1976D2).copy(alpha = 0.25f),
    waveformColor: Color = Color(0xFF1976D2).copy(alpha = 0.7f)
) {
    val density = LocalDensity.current
    val btnPx = with(density) { size.toPx() }
    val pulsePx = with(density) { 46.dp.toPx() }
    val glowPx = with(density) { 56.dp.toPx() }
    val outerPx = with(density) { 66.dp.toPx() }
    val midPx  = with(density) { 52.dp.toPx() }
    val innerPx = with(density) { 42.dp.toPx() }

    var pulsePhase by remember { mutableFloatStateOf(0f) }
    var ringProgress by remember { mutableFloatStateOf(0f) }
    var glowAlpha by remember { mutableFloatStateOf(0f) }
    var waveformPhase by remember { mutableFloatStateOf(0f) }
    var isPressed by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            elapsedSeconds = 0
            while (true) { delay(1000); elapsedSeconds++ }
        }
    }

    LaunchedEffect(isRecording) {
        if (!isRecording) {
            while (true) { pulsePhase = (pulsePhase + 0.02f).coerceAtMost(1f); delay(16); if (pulsePhase >= 1f) pulsePhase = 0f }
        }
    }
    LaunchedEffect(isRecording) {
        if (isRecording) { ringProgress = 0f; while (ringProgress < 1f) { ringProgress = (ringProgress + 0.005f).coerceAtMost(1f); delay(16) } } else { ringProgress = 0f }
    }
    LaunchedEffect(isRecording) {
        if (isRecording) { glowAlpha = 0f; while (glowAlpha < 0.4f) { glowAlpha = (glowAlpha + 0.01f).coerceAtMost(0.4f); delay(16) } } else { glowAlpha = 0f }
    }
    LaunchedEffect(isRecording) {
        if (isRecording) { while (true) { waveformPhase = (waveformPhase + 0.05f).coerceAtMost(1f); delay(32) } } else { waveformPhase = 0f }
    }

    val s by animateFloatAsState(targetValue = if (isPressed && !isRecording) 0.92f else 1f, label = "mic_scale")

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size)) {
        if (!isRecording) {
            Box(modifier = Modifier.size(size + 24.dp).align(Alignment.Center).background(brush = Brush.radialGradient(colors = listOf(pulseColor, pulseColor.copy(alpha = 0f)), center = Offset(pulsePx + 12f, pulsePx + 12f), radius = pulsePx + 12f), shape = CircleShape))
            Box(modifier = Modifier.size(size + 12.dp).align(Alignment.Center).background(brush = Brush.radialGradient(colors = listOf(pulseColor.copy(alpha = 0.5f), pulseColor.copy(alpha = 0f)), center = Offset(pulsePx + 6f, pulsePx + 6f), radius = pulsePx + 6f), shape = CircleShape))
        }
        if (isRecording) {
            Box(modifier = Modifier.size(size + 36.dp).align(Alignment.Center).background(brush = Brush.radialGradient(colors = listOf(ringColor, ringColor.copy(alpha = 0f)), center = Offset(outerPx + 18f, outerPx + 18f), radius = outerPx + 18f), shape = CircleShape))
            Box(modifier = Modifier.size(size + 24.dp).align(Alignment.Center).background(brush = Brush.radialGradient(colors = listOf(ringColor.copy(alpha = 0.7f), ringColor.copy(alpha = 0f)), center = Offset(midPx + 12f, midPx + 12f), radius = midPx + 12f), shape = CircleShape))
            Box(modifier = Modifier.size(size + 12.dp).align(Alignment.Center).background(brush = Brush.radialGradient(colors = listOf(ringColor.copy(alpha = 0.5f), ringColor.copy(alpha = 0f)), center = Offset(innerPx + 6f, innerPx + 6f), radius = innerPx + 6f), shape = CircleShape))
            Box(modifier = Modifier.size(size + 20.dp).align(Alignment.Center).background(brush = Brush.radialGradient(colors = listOf(glowColor, glowColor.copy(alpha = 0f)), center = Offset(glowPx + 10f, glowPx + 10f), radius = glowPx + 10f), shape = CircleShape))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .scale(s)
                .shadow(elevation = if (isRecording) 16.dp else buttonElevation, shape = CircleShape, clip = true)
                .clip(CircleShape)
                .background(
                    brush = if (isRecording) {
                        Brush.radialGradient(colors = listOf(Color(0xFF1565C0).copy(alpha = 0.9f), Color(0xFF1565C0).copy(alpha = 0.7f)), center = Offset(btnPx / 2f, btnPx / 2f), radius = btnPx / 2f)
                    } else {
                        Brush.horizontalGradient(colors = listOf(Color(0xFF1976D2), Color(0xFF1976D2).copy(alpha = 0.8f)))
                    },
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    // Telegram voice-note interaction: press to capture,
                    // release to send. A tap (quick press+release) still
                    // records + sends whatever was captured.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        onRecordingStart()
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                        }
                        isPressed = false
                        onRecordingStop()
                    }
                }
        ) {
            if (isRecording) {
                Box(modifier = Modifier.fillMaxSize().background(color = Color(0xFF1565C0).copy(alpha = 0.7f), shape = CircleShape))
            }
            Icon(imageVector = if (isRecording) Icons.Filled.MicOff else Icons.Filled.Mic, contentDescription = if (isRecording) "Recording — release to stop" else "Hold to record", tint = Color.White, modifier = Modifier.size(iconSize).scale(if (isRecording) 0.8f else 1f))
        }
        if (isRecording) {
            Text(text = String.format(Locale.getDefault(), "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        }
        if (isCancelled) {
            Text(text = "Recording cancelled", color = Color.Red, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
    }
}