package com.hermes.mobile.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.hermes.mobile.network.ServerCommand
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ═══════════════════════════════════════════════════════════════
// Input bar
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    onEmoji: (String) -> Unit,
    onAttach: () -> Unit,
    pendingAttachments: List<PendingAttachment>,
    onRemoveAttachment: (PendingAttachment) -> Unit,
    onMarkup: (() -> Unit)? = null,
    isStreaming: Boolean,
    showEmojiPicker: Boolean,
    onToggleEmojiPicker: () -> Unit,
    enabled: Boolean,
    serverCommands: List<ServerCommand> = emptyList()
) {
    // ── Slash command state ──
    var commandsSheetOpen by remember { mutableStateOf(false) }
    var commandSearch by remember { mutableStateOf("") }
    val showSlashCommands = inputText.startsWith("/") && inputText.length <= 30

    val onCommandSelected: (String) -> Unit = { cmd ->
        onInputChange(cmd)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp
    ) {
        Column {
            // Slash command list (above the input, like Telegram)
            if (showSlashCommands) {
                SlashCommandList(
                    query = inputText,
                    onCommandSelected = onCommandSelected,
                    onDismiss = {},
                    serverCommands = serverCommands
                )
            }

            // Attachment preview (like Telegram: thumbnail + name above text field)
            for (att in pendingAttachments) {
                AttachmentPreview(
                    attachment = att,
                    onRemove = { onRemoveAttachment(att) },
                    onMarkup = onMarkup
                )
            }

            // Emoji picker popup (above the bar, like Telegram)
            if (showEmojiPicker) {
                EmojiPickerGrid(onEmojiSelected = onEmoji)
            }

            // Telegram composer: ONE unified rounded pill holds
            // [Menu][emoji][field][attach][mic/send] — no separate
            // inner field pill, no emoji glyph, flat outline icons.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (LocalDarkTheme.current) InputBarDark else InputBarLight
                    )
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                    val hasContent = inputText.isNotBlank() || pendingAttachments.isNotEmpty()

                    // ── 0. Bot command pill (opens the full command sheet;
                    // typing replaces it with the emoji button) ──
                    if (!hasContent && serverCommands.isNotEmpty()) {
                        Surface(
                            onClick = {
                                commandsSheetOpen = true
                                commandSearch = ""
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = HermesPrimary,
                            enabled = enabled
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                // Icon-only pill — label removed by user request.
                                Icon(
                                    imageVector = Icons.Outlined.Menu,
                                    contentDescription = "Commands",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(2.dp))
                    }

                    // ── 1. Emoji button (flat outline smiley, Telegram tint) ──
                    IconButton(
                        onClick = onToggleEmojiPicker,
                        modifier = Modifier.size(40.dp),
                        enabled = enabled
                    ) {
                        Icon(
                            imageVector = if (showEmojiPicker) Icons.Outlined.Keyboard
                                          else Icons.Outlined.Mood,
                            contentDescription = "Emoji",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // ── 2. Text field (transparent inside the unified pill) ──
                    TextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp, max = 120.dp),
                        placeholder = {
                            Text(
                                text = "Message",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = HermesPrimary
                        ),
                        shape = RoundedCornerShape(24.dp),
                        singleLine = false,
                        maxLines = 4,
                        enabled = enabled
                    )

                    // ── 3. Attach button ──
                    IconButton(
                        onClick = onAttach,
                        modifier = Modifier.size(40.dp),
                        enabled = enabled
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = "Attach",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // ── 4. Mic / Send (alternate in the same slot — like Telegram) ──
                    // Empty input → mic; text/attachment present → send replaces it.
                    if (hasContent) {
                        FilledIconButton(
                            onClick = onSend,
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = HermesPrimary,
                                contentColor = Color.White
                            ),
                            enabled = enabled
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        FilledIconButton(
                            onClick = onVoice,
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = HermesPrimary,
                                contentColor = Color.White
                            ),
                            enabled = enabled
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Voice input",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
            }

            // Bot commands sheet (icon-only pill opens it; search field filters)
            if (commandsSheetOpen) {
                ModalBottomSheet(
                    onDismissRequest = { commandsSheetOpen = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                ) {
                    val query = commandSearch.trim().lowercase()
                    val matched = remember(serverCommands, query) {
                        if (query.isEmpty()) serverCommands
                        else serverCommands.filter {
                            it.name.lowercase().startsWith(query.removePrefix("/")) ||
                                it.description.lowercase().contains(query)
                        }
                    }
                    Column(Modifier.padding(bottom = 16.dp)) {
                        Text(
                            "Commands",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                        // Find field — the registry is ~200 strong.
                        OutlinedTextField(
                            value = commandSearch,
                            onValueChange = { commandSearch = it },
                            placeholder = { Text("Search commands", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (commandSearch.isNotEmpty()) {
                                    IconButton(onClick = { commandSearch = "" }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = "Clear",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(22.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .padding(bottom = 8.dp)
                        )
                        if (matched.isEmpty()) {
                            Text(
                                "No command matches \u201C${commandSearch.trim()}\u201D",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                            )
                        }
                        LazyColumn(Modifier.heightIn(max = 460.dp)) {
                            items(matched, key = { it.name }) { cmd ->
                                Surface(
                                    onClick = {
                                        commandsSheetOpen = false
                                        commandSearch = ""
                                        onInputChange("/" + cmd.name.lowercase() + " ")
                                    },
                                    color = Color.Transparent,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "/" + cmd.name.lowercase(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = HermesPrimary,
                                            modifier = Modifier.width(140.dp)
                                        )
                                        Text(
                                            cmd.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
