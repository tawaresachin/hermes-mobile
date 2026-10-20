package com.hermes.mobile.ui.screens.chat

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.hermes.mobile.network.ServerCommand
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════
// Slash commands — matched from the server's /help output
// ═══════════════════════════════════════════════════════════════

data class SlashCommand(
    val command: String,
    val description: String
)

// Mirrors the app-side command executor (see ChatViewModel.handleSlashCommand).
internal val SLASH_COMMANDS = listOf(
    SlashCommand("/new", "Start a fresh conversation"),
    SlashCommand("/reset", "Same as /new"),
    SlashCommand("/clear", "Same as /new"),
    SlashCommand("/stop", "Stop the current response"),
    SlashCommand("/model", "Pick a model (or: /model <name>)"),
    SlashCommand("/status", "Session, model & connection info"),
    SlashCommand("/context", "Token window usage for this chat"),
    SlashCommand("/title", "Rename this session: /title <text>"),
    SlashCommand("/retry", "Resend the last message"),
    SlashCommand("/steer", "Inject into the running reply (else it queues)"),
    SlashCommand("/skills", "Installed Hermes skills (server)"),
    SlashCommand("/tools", "Enabled toolsets (server)"),
    SlashCommand("/usage", "Server token ledger totals"),
    SlashCommand("/queue", "Queue text for the next turn"),
    SlashCommand("/compress", "Context status (compression is automatic)"),
    SlashCommand("/whoami", "Device + server identity"),
    SlashCommand("/version", "App + server + plugin versions"),
    SlashCommand("/commands", "Every command handled client-side"),
    SlashCommand("/help", "List these commands"),
)

@Composable
fun SlashCommandList(
    query: String,
    onCommandSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    serverCommands: List<ServerCommand> = emptyList()
) {
    // Only show when text starts with "/"
    if (!query.startsWith("/") || query.length > 30) {
        onDismiss()
        return
    }

    val filter = query.substring(1).lowercase()
    // Server truth first (Telegram's own menu source); local list fills gaps
    // and provides handlers for commands the app executes itself.
    val localMatches = SLASH_COMMANDS.filter {
        it.command.removePrefix("/").contains(filter)
    }
    val serverMatches = serverCommands.filter {
        it.name.removePrefix("/").lowercase().contains(filter)
    }
    val seen = localMatches.map { it.command.lowercase() }.toSet()
    val matched: List<SlashCommand> = localMatches + serverMatches
        .filter { "/${it.name.lowercase()}" !in seen }
        .map { SlashCommand("/" + it.name.lowercase(), it.description) }

    val capped = matched.take(30)
    if (capped.isEmpty()) {
        onDismiss()
        return
    }

    // Distinct elevated sheet: surfaceContainer tone + solid divider so the
    // list never reads as command text floating over the chat (background and
    // surface were near-identical in light theme → "overlapping" look).
    // Capped at 180dp — more than that crushed the visible history.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        LazyColumn(
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            itemsIndexed(capped) { _, cmd ->
                Surface(
                    onClick = {
                        onCommandSelected(cmd.command + " ")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = cmd.command,
                            fontWeight = FontWeight.SemiBold,
                            color = HermesPrimary,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(
                            text = cmd.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
}
