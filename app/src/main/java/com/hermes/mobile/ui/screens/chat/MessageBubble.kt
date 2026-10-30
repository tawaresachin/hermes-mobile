package com.hermes.mobile.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hermes.mobile.data.model.*
import com.hermes.mobile.ui.markdown.TableParse
import com.hermes.mobile.R
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs

/** Format timestamp as HH:MM for display under bubbles. */
private fun formatTime(timestamp: Long): String {
    return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
}

// ═══════════════════════════════════════════════════════════════
// Message bubble
// ═══════════════════════════════════════════════════════════════

/**
 * Telegram-style delivery tick: clock (sending) → ✓ (sent) →
 * blue ✓✓ (read) → red ! (failed).
 */
@Composable
private fun StatusTick(status: MessageStatus, tint: Color) {
    when (status) {
        MessageStatus.SENDING -> CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = tint
        )
        MessageStatus.SENT -> Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "Sent",
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        MessageStatus.READ -> Icon(
            imageVector = Icons.Filled.DoneAll,
            contentDescription = "Read",
            tint = ReadReceiptBlue,
            modifier = Modifier.size(14.dp)
        )
        MessageStatus.FAILED -> Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp)
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

@Composable
fun MessageBubble(
    message: Message,
    displayContent: String,
    isStreaming: Boolean,
    baseUrl: String = "",
    onEdit: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    onReact: (() -> Unit)? = null,
    highlighted: Boolean = false,
    fontSizeSp: Float = 15f,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    // Telegram: tap an attachment bubble to open/save the file
    onAttachmentTap: ((Message) -> Unit)? = null,
    // Download icon: save to Downloads + open with system viewer
    onDownloadAttachment: ((Message) -> Unit)? = null,
    // Telegram: tap image to open full-screen viewer
    onImageTap: ((String) -> Unit)? = null
) {
    val isUser = message.role == MessageRole.USER
    val isDark = LocalDarkTheme.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val hapticCtx = androidx.compose.ui.platform.LocalContext.current
    val bubbleColor = if (isUser) {
        if (isDark) UserBubbleDark else UserBubbleLight
    } else {
        if (isDark) OtherBubbleDark else OtherBubbleLight
    }
    // Search-jump highlight: wash the bubble with the accent tint
    val effectiveBubbleColor = if (highlighted) {
        HermesPrimary.copy(alpha = 0.30f)
    } else bubbleColor
    val alignment = if (isUser) Arrangement.End else Arrangement.Start
    // Telegram-style grouping: within a group the inner corners square up
    // (6dp); the group's exposed ends stay rounded (18dp). Standalone
    // bubbles keep the exact same shape as before.
    val bubbleShape = RoundedCornerShape(
        topStart = if (isUser) 17.dp else (if (isFirstInGroup && !isLastInGroup) 17.dp else 3.dp),
        topEnd = if (isUser) (if (isFirstInGroup && !isLastInGroup) 17.dp else 3.dp) else 17.dp,
        bottomStart = if (isUser) 17.dp else (if (isLastInGroup) 17.dp else 3.dp),
        bottomEnd = if (isUser) (if (isLastInGroup) 17.dp else 3.dp) else 17.dp
    )
    val textColor = if (isUser) {
        if (isDark) Color.White else LightFg
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    // Build absolute URL for images served by the gateway plugin
    // Response media (Telegram parity): an assistant reply can CARRY media —
    // an inlined `![img](data:...)` from api_server or a trailing
    // `MEDIA:/abs/file.ext` tag (any file; fetched via the plugin's
    // /api/mobile/file route). Resolved at render time only (stored text is
    // never rewritten); a persisted attachment row always wins.
    val responseMedia = remember(displayContent) {
        if (message.role == MessageRole.USER) null
        else com.hermes.mobile.ui.chat.ResponseMedia.extract(displayContent)
    }
    val effAttachmentUrl = message.attachmentUrl ?: responseMedia?.url
    val effAttachmentType = if (message.attachmentUrl != null) message.attachmentType
        else responseMedia?.type
    val effAttachmentName = if (message.attachmentUrl != null) message.attachmentName
        else responseMedia?.name
    val bubbleContent = responseMedia?.cleanText ?: displayContent
    val absoluteImageUrl = remember(effAttachmentUrl, baseUrl) {
        val rel = effAttachmentUrl ?: return@remember null
        if (rel.startsWith("http") || rel.startsWith("data:")) rel
        else baseUrl.trimEnd('/') + rel
    }

    // Telegram-style bubble with the user's full-width preference: wide
    // bubbles (up to ~94% incl. tail/avatar lanes); short texts still hug.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bubbleMax = this.maxWidth * 0.94f
        // Swipe: drag MOVES the bubble (draw-phase only — graphicsLayer below),
        // release springs it back or commits. 90px commit kept from the old
        // detector; past 135px movement is damped (0.35x) for the sluggish feel.
        val shift = remember { Animatable(0f) }
        val swipeScope = rememberCoroutineScope()
        val density = LocalDensity.current
        val maxPx = with(density) { maxWidth.toPx() }
        // Backdrop: delete icon on the left (revealed swiping right),
        // reply on the right (revealed swiping left).
        if (onReply != null || onDelete != null) {
            Row(
                modifier = Modifier.matchParentSize().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.graphicsLayer {
                        alpha = (if (shift.value > 0f) shift.value / 135f else 0f).coerceIn(0f, 1f)
                    }
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply",
                    tint = HermesPrimary,
                    modifier = Modifier.graphicsLayer {
                        alpha = (if (shift.value < 0f) -shift.value / 135f else 0f).coerceIn(0f, 1f)
                    }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // In selection mode: tap toggles selection; long-press and
                // double-tap actions are disabled.
                .combinedClickable(
                    enabled = !isStreaming,
                    onClick = { if (selectionMode) onToggleSelect?.invoke() },
                    onLongClick = if (selectionMode) null else { {
                        com.hermes.mobile.ui.haptics.Haptics.press(haptics)
                        onLongPress?.invoke()
                    } },
                    onDoubleClick = if (selectionMode) null else { {
                        com.hermes.mobile.ui.haptics.Haptics.tick(haptics)
                        onReact?.invoke()
                    } }
                )
                .pointerInput(onReply, onDelete, isStreaming, selectionMode) {
                    if (isStreaming || selectionMode) return@pointerInput
                    if (onReply == null && onDelete == null) return@pointerInput
                    // Telegram gestures: swipe LEFT = reply (hint_swipe_reply),
                    // swipe RIGHT = delete. Deltas feed the shift Animatable;
                    // commit at 90px (same as the old detector), otherwise the
                    // release spring returns the bubble. Vertical scroll is
                    // untouched: horizontal deltas only.
                    var armed = false
                    detectHorizontalDragGestures(
                        onDragStart = { armed = false },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val raw = shift.value + dragAmount
                            val clamped = if (abs(raw) <= 135f) raw
                                else if (raw > 0f) 135f + (raw - 135f) * 0.35f
                                else -135f + (raw + 135f) * 0.35f
                            swipeScope.launch { shift.snapTo(clamped) }
                            // One haptic tick per crossing (re-arm near centre).
                            if (raw in -40f..40f) armed = false
                            else if (!armed && raw < -90f && onReply != null) {
                                armed = true
                                com.hermes.mobile.ui.haptics.Haptics.tick(haptics)
                            } else if (!armed && raw > 90f && onDelete != null) {
                                armed = true
                                com.hermes.mobile.ui.haptics.Haptics.press(haptics)
                            }
                        },
                        onDragEnd = {
                            when {
                                shift.value < -90f && onReply != null -> {
                                    swipeScope.launch {
                                        shift.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                                    }
                                    onReply()
                                }
                                shift.value > 90f && onDelete != null -> {
                                    swipeScope.launch {
                                        shift.animateTo(maxPx * 1.1f, tween(140))
                                        shift.snapTo(0f)
                                    }
                                    onDelete()
                                }
                                else -> swipeScope.launch {
                                    shift.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
                                }
                            }
                        },
                        onDragCancel = { swipeScope.launch { shift.snapTo(0f) } }
                    )
                }
                .graphicsLayer { translationX = shift.value },
            horizontalArrangement = alignment
        ) {
            // ── Selection-mode checkbox (Telegram's animated leading
            //    checkbox; dims the bubble while selected) ──
            if (selectionMode) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInHorizontally { -it / 2 },
                    exit = fadeOut() + slideOutHorizontally { -it / 2 }
                ) {
                    val checkColor = if (selected) HermesPrimary
                    else MaterialTheme.colorScheme.outlineVariant
                    Icon(
                        imageVector = if (selected) Icons.Filled.CheckCircleOutline
                        else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (selected) "Selected" else "Not selected",
                        tint = checkColor,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(22.dp)
                            .alpha(if (selected) 1f else 0.55f)
                    )
                }
            }
            // Box so the tail can OVERLAY the bubble's top corner (a Column
            // child would render below the bubble instead).
            // Telegram-style: Hermes avatar on the LAST message of an
            // assistant group (left side, next to the bubble).
            if (!isUser && isLastInGroup && !isStreaming) {
                AsyncImage(
                    model = com.hermes.mobile.R.drawable.hermes_girl,
                    contentDescription = "Hermes",
                    modifier = Modifier
                        .padding(end = 6.dp, bottom = 2.dp)
                        .size(32.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
            Box {
            // ⋮ menu — TOP-RIGHT corner overlay (Telegram-style trailing
            // affordance). Opens the full sheet: select/copy/reply/edit/
            // forward/regenerate/delete. Long-press on the bubble does the
            // same, for muscle memory.
            if (!isStreaming && (onMenu != null || onLongPress != null)) {
                IconButton(
                    onClick = onMenu ?: onLongPress!!,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 3.dp, end = 3.dp)
                        .size(18.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Box(contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Message options",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .widthIn(max = bubbleMax)
                    // Telegram selection: dim the bubble while selected
                    // (checkbox stays bright).
                    .alpha(if (selected) 0.55f else 1f)
            ) {
            // Telegram-style: sender name above the FIRST message of an
            // assistant group.
            if (!isUser && isFirstInGroup && !isStreaming) {
                Text(
                    text = "Hermes",
                    style = MaterialTheme.typography.labelSmall,
                    color = HermesPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 14.dp, bottom = 2.dp)
                )
            }
            Surface(
                shape = bubbleShape,
                color = effectiveBubbleColor,
                // No shadowElevation: per-bubble shadows are the classic
                // Compose list-scroll jank source (Telegram bubbles are flat).
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)) {
                    // ── Telegram-style quote chip (reply preview) ──
                    // Rendered at the top of the replying bubble: accent-tinted
                    // box with the quoted text, max 2 lines.
                    if (message.replyToText?.isNotBlank() == true) {
                        Surface(
                            color = HermesPrimary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                        ) {
                            Text(
                                text = message.replyToText!!.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isUser) textColor.copy(alpha = 0.85f)
                                else MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    // ── Image attachment (persisted OR carried in the reply text) ──
                    if (absoluteImageUrl != null && effAttachmentType?.startsWith("image") == true) {
                        val isGif = effAttachmentType == "image/gif"
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = if (bubbleContent.isNotBlank()) 8.dp else 0.dp)
                        ) {
                            if (isGif) {
                                // GIF: use Coil with animation enabled
                                AsyncImage(
                                    model = absoluteImageUrl,
                                    contentDescription = effAttachmentName ?: "GIF",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(300.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = !isStreaming) {
                                            onImageTap?.invoke(absoluteImageUrl)
                                        },
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                // Static image
                                AsyncImage(
                                    model = absoluteImageUrl,
                                    contentDescription = effAttachmentName ?: "Image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 100.dp, max = 400.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = !isStreaming) {
                                            onImageTap?.invoke(absoluteImageUrl)
                                        },
                                    contentScale = ContentScale.FillWidth
                                )
                            }
                        }
                    }
                    // ── File attachment (non-image; persisted OR from a MEDIA tag) ──
                    if (effAttachmentUrl != null && (effAttachmentType == null || !effAttachmentType!!.startsWith("image"))) {
                        // Video support
                        if (effAttachmentType?.startsWith("video") == true) {
                            VideoAttachmentRow(
                                name = effAttachmentName ?: "Video",
                                url = effAttachmentUrl,
                                onClick = { onImageTap?.invoke(effAttachmentUrl) }
                            )
                        } else {
                            FileAttachmentRow(
                                name = effAttachmentName ?: "File",
                                modifier = Modifier.padding(bottom = if (bubbleContent.isNotBlank()) 8.dp else 0.dp),
                                onDownload = if (effAttachmentUrl != null) {
                                    {
                                        val target = if (message.attachmentUrl != null) message
                                        else message.copy(
                                            attachmentUrl = effAttachmentUrl,
                                            attachmentType = effAttachmentType,
                                            attachmentName = effAttachmentName)
                                        onDownloadAttachment?.invoke(target)
                                    }
                                } else null,
                                onClick = {
                                    // Persisted row: full Message flow (save/open).
                                    // Response-carried MEDIA tag: build a transient
                                    // Message so the same download+viewer path runs.
                                    if (message.attachmentUrl != null) onAttachmentTap?.invoke(message)
                                    else onAttachmentTap?.invoke(
                                        message.copy(
                                            attachmentUrl = effAttachmentUrl,
                                            attachmentType = effAttachmentType,
                                            attachmentName = effAttachmentName,
                                        )
                                    )
                                }
                            )
                        }
                    }
                    // ── Grouped tool activity (Telegram: one compact line
                    //    per call, persisted with the bubble) ──
                    if (!isStreaming && !message.toolActivity.isNullOrBlank()) {
                        ToolActivityGroup(jsonLines = message.toolActivity!!)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    // ── Text content ──
                    if (bubbleContent.isNotBlank()) {
                        // A markdown table gets lifted into the full-width
                        // overlay (wide grids don't fit bubble width) — but
                        // the surrounding prose must STAY in the bubble
                        // (the old code hid all text when a table existed,
                        // so "Here's the comparison:" and the closing note
                        // silently vanished).
                        // Memoized: streaming emits ~20Hz and this re-parse is
                        // the only unremembered work ahead of StreamingText.
                        val table = remember(bubbleContent) { parseTablesOf(bubbleContent) }
                        val bubbleText = table?.prose ?: bubbleContent
                        if (bubbleText.isNotBlank()) {
                            if (isStreaming) {
                                StreamingText(
                                    text = bubbleText,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = fontSizeSp.sp,
                                        lineHeight = (fontSizeSp + 6).sp),
                                    color = textColor,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                MarkdownText(
                                    text = bubbleText,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = fontSizeSp.sp,
                                        lineHeight = (fontSizeSp + 6).sp),
                                    color = textColor,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    // The old blinking cursor bar is REMOVED — the header's
                    // "thinking…" subtitle is the generating indicator now
                    // (Telegram's top-bar placement; no blinking in the list).
                    // Telegram-style delivery tick — always visible on
                    // finished user messages (clock → ✓ → blue ✓✓ / red !).
                    // Legacy rows (pre-status column) default to READ —
                    // past messages were all read, never single-tick.
                    if (isUser && !isStreaming && displayContent.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusTick(
                                status = message.status ?: MessageStatus.READ,
                                tint = textColor.copy(alpha = 0.5f)
                            )
                        }
                    }
                    // Stop stays bottom-right (live action); the ⋮ menu moved
                    // to the bubble's TOP-RIGHT corner overlay.
                    val showStopAction = onStop != null && isUser
                    if (showStopAction) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                                IconButton(
                                    onClick = onStop,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Stop,
                                        contentDescription = "Stop generating",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                        }
                    }
                    // Telegram-style reaction badge (double-tap to toggle 👍)
                    if (message.reaction != null && !isStreaming) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isUser) Color.White.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = message.reaction!!,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    // ── Telegram-style: timestamp + edit indicator + delivery ticks ──
                    if (!isFirstInGroup || !isLastInGroup) {
                        // Show timestamp for non-grouped or mid-group messages
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (message.editedAt > 0) {
                                Text(
                                    text = "edited",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isUser) Color.White.copy(alpha = 0.45f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = formatTime(message.timestamp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp)),
                                color = if (isUser) Color.White.copy(alpha = 0.65f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            if (message.isStreaming) {
                                // Tiny streaming indicator
                                Spacer(modifier = Modifier.width(2.dp))
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .background(HermesPrimary, CircleShape)
                                )
                            }
                            if (isUser && !isStreaming) {
                                // Delivery status ticks (Telegram style)
                                Spacer(modifier = Modifier.width(2.dp))
                                StatusTick(
                                    status = message.status ?: MessageStatus.READ,
                                    tint = if (isUser) Color.White.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
            } // Column (bubble)
            // ── Telegram-style tail (small arrow at the top corner) — a
            // BOX child so it overlays the bubble corner (BoxScope.align
            // accepts full alignments; ColumnScope only horizontal ones).
            // One tail per GROUP: only the first (top) message carries it.
            if (isFirstInGroup) {
            Canvas(
                modifier = Modifier
                    .align(if (isUser) Alignment.TopEnd else Alignment.TopStart)
                    .size(9.dp)
            ) {
                val tailPath = Path().apply {
                    if (isUser) {
                        // Right-side bubble: tail at the top-right, pointing out
                        moveTo(0f, 9.dp.toPx())
                        lineTo(9.dp.toPx(), 0f)
                        lineTo(9.dp.toPx(), 9.dp.toPx())
                    } else {
                        // Left-side bubble: tail at the top-left, pointing out
                        moveTo(0f, 0f)
                        lineTo(9.dp.toPx(), 9.dp.toPx())
                        lineTo(0f, 9.dp.toPx())
                    }
                    close()
                }
                drawPath(tailPath, bubbleColor)
            }
            }
            } // Box (tail overlay)
        }
    }
}

// ── Full-width table overlay for assistant messages ──

@Composable
fun FullWidthTableOverlay(
    message: Message,
    displayContent: String,
    isStreaming: Boolean,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    if (isStreaming || message.role == MessageRole.USER) return
    val table = parseTablesOf(displayContent) ?: return
    // EVERY table in the message renders (the first parser silently left a
    // second table as raw pipe text); prose is handled by the bubble itself.
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        table.tables.forEach { rows -> MarkdownTable(rows, isDark) }
    }
}


/** Render a markdown table as formatted text. */


/**
 * Table detection lives in ui/markdown/MarkdownTableParser.kt (JVM-tested).
 * All tables in a message render as full-width overlays below the bubble;
 * the bubble keeps the prose between/around them.
 */
private fun parseTablesOf(text: String): TableParse? =
    com.hermes.mobile.ui.markdown.parseTables(text)



/** Video attachment row (plays inline or opens player). */
@Composable
private fun VideoAttachmentRow(name: String, url: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = HermesPrimary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Video",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
