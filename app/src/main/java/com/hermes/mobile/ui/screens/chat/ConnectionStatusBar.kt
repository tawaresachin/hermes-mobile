package com.hermes.mobile.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
fun ConnectionStatusBar(connectionStatus: ConnectionStatus) {
    // Telegram-style debounced state machine: a connection blip (Tailscale
    // drop that recovers in <400ms) must NOT flash the red banner. The
    // transient state only shows once the status has been non-connected
    // for the debounce window (LaunchedEffect restarts on every change, so
    // a quick recovery cancels the pending show).
    var displayStatus by remember { mutableStateOf(connectionStatus) }
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            displayStatus = ConnectionStatus.CONNECTED
        } else {
            kotlinx.coroutines.delay(400)
            displayStatus = connectionStatus
        }
    }
    // Soft transient wording: a drop after a live connection is
    // "Reconnecting…" (amber, the VM polls every 5s to recover) — only a
    // hard error goes red. No alarm on recoverable blips.
    val (text, color, icon) = when (displayStatus) {
        ConnectionStatus.CONNECTED -> Triple("Connected", SuccessGreen, Icons.Filled.CheckCircle)
        ConnectionStatus.CONNECTING -> Triple("Connecting…", WarningAmber, Icons.Filled.Sync)
        ConnectionStatus.DISCONNECTED -> Triple("Reconnecting…", WarningAmber, Icons.Filled.Sync)
        ConnectionStatus.ERROR -> Triple("Connection lost — retrying", ErrorRed, Icons.Filled.Error)
    }

    AnimatedVisibility(
        visible = true,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = color.copy(alpha = 0.1f),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = text,
                    color = color,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
