package com.hermes.mobile.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.VectorConverter
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.model.ConnectionStatus
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.components.HermesWatermark
import com.hermes.mobile.ui.theme.ErrorRed
import com.hermes.mobile.ui.theme.HermesAccent
import com.hermes.mobile.ui.theme.HermesPrimary
import com.hermes.mobile.ui.theme.HermesPrimaryDark
import com.hermes.mobile.ui.theme.HermesSecondary
import com.hermes.mobile.ui.theme.HermesSecondaryDark
import com.hermes.mobile.ui.theme.SuccessGreen
import com.hermes.mobile.ui.theme.WarningAmber
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ─── UI State ───

data class HomeUiState(
    val greeting: String = "Good Morning",
    val greetingEmoji: String = "🌅",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectionLatency: Long = 0L,
    val serverBaseUrl: String = "",
    val isLoading: Boolean = false,
    val sessions: List<Session> = emptyList()
)

// ─── ViewModel ───

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HermesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        observeSessions()
        updateGreeting()
        checkConnection()
        // Poll connection every 5s when not connected
        viewModelScope.launch {
            while (isActive) {
                delay(5000)
                val current = _uiState.value.connectionStatus
                if (current != ConnectionStatus.CONNECTED) {
                    checkConnection()
                }
            }
        }
    }

    private fun observeSessions() {
        viewModelScope.launch {
            repository.allSessions.collect { sessions ->
                _uiState.update { state -> state.copy(sessions = sessions.take(10)) }
            }
        }
    }

    private fun updateGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val (greeting, emoji) = when (hour) {
            in 0..4 -> "Late Night" to "🌙"
            in 5..11 -> "Good Morning" to "🌅"
            in 12..16 -> "Good Afternoon" to "☀️"
            in 17..20 -> "Good Evening" to "🌆"
            else -> "Good Night" to "🌙"
        }
        _uiState.update { state -> state.copy(greeting = greeting, greetingEmoji = emoji) }
    }

    fun checkConnection() {
        viewModelScope.launch {
            _uiState.update { state -> state.copy(connectionStatus = ConnectionStatus.CONNECTING) }
            // Use saved config (from Settings) instead of blank defaults
            val savedConfig = repository.getSavedConfig()
            if (savedConfig != null) {
                // Check connection with saved config (won't overwrite with empty values).
                // ONE call — RTT measured on the same request (was: two calls).
                val start = System.currentTimeMillis()
                val status = repository.checkConnection(savedConfig)
                val latency = if (status == ConnectionStatus.CONNECTED) {
                    System.currentTimeMillis() - start
                } else 0L
                _uiState.update { state ->
                    state.copy(
                        connectionStatus = status,
                        serverBaseUrl = savedConfig.baseUrl,
                        connectionLatency = latency
                    )
                }
            } else {
                // No config saved yet — show disconnected state
                _uiState.update { state ->
                    state.copy(
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        serverBaseUrl = "http://localhost:8080"
                    )
                }
            }
        }
    }

    fun createNewSession(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.update { state -> state.copy(isLoading = true) }
            val session = repository.createSession()
            _uiState.update { state -> state.copy(isLoading = false) }
            onCreated(session.id)
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
        }
    }

    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch {
            repository.renameSession(sessionId, title)
        }
    }

    fun refreshConnection() {
        checkConnection()
    }
}

// ─── HomeScreen Composable ───

@Composable
fun HomeScreen(
    onNavigateToChat: (String) -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToVoice: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Long-press target for the rename dialog
    var renameTarget by remember { mutableStateOf<Session?>(null) }

    // Re-check connection when returning from Settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshConnection()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        HermesWatermark()
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 24.dp,
                bottom = 24.dp
            )
        ) {
            // ── Compact header (Telegram-style: title + status line) ──
            item(key = "header") {
                HomeHeader(
                    greeting = uiState.greeting,
                    emoji = uiState.greetingEmoji,
                    status = uiState.connectionStatus,
                    serverUrl = uiState.serverBaseUrl,
                    latency = uiState.connectionLatency,
                    onRefresh = { viewModel.refreshConnection() }
                )
            }

            // ── Quick actions: compact pill row ──
            item(key = "quick_actions") {
                QuickActionsRow(
                    isLoading = uiState.isLoading,
                    onNewChat = { viewModel.createNewSession(onCreated = onNavigateToChat) },
                    onResumeSession = onNavigateToSessions,
                    onVoiceInput = onNavigateToVoice
                )
            }

            // ── Recent Sessions ──
            item(key = "recent_header") {
                RecentSessionsHeader(
                    sessionCount = uiState.sessions.size,
                    onSeeAll = onNavigateToSessions
                )
            }

            if (uiState.sessions.isEmpty()) {
                item(key = "empty_sessions") {
                    EmptySessionsPlaceholder()
                }
            } else {
                itemsIndexed(
                    items = uiState.sessions,
                    key = { _, it -> it.id }
                ) { index, session ->
                    Column {
                        SwipeableSessionItem(
                            session = session,
                            onClick = { onNavigateToChat(session.id) },
                            onDelete = { viewModel.deleteSession(session.id) },
                            onRename = { renameTarget = session }
                        )
                        // Telegram-style thin divider between rows
                        if (index < uiState.sessions.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                modifier = Modifier.padding(start = 64.dp)
                            )
                        }
                    }
                }
            }
        }
        }

        // Rename dialog (long-press a session row)
        renameTarget?.let { target ->
            com.hermes.mobile.ui.components.RenameSessionDialog(
                currentTitle = target.title ?: "",
                onDismiss = { renameTarget = null },
                onRename = { name ->
                    viewModel.renameSession(target.id, name)
                    renameTarget = null
                }
            )
        }
    }
}

// ─── Compact Home Header (greeting + connection line) ───

@Composable
private fun HomeHeader(
    greeting: String,
    emoji: String,
    status: ConnectionStatus,
    serverUrl: String,
    latency: Long,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$emoji $greeting",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Hermes Mobile",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Slim connection line (tap to refresh) — no hero card.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onRefresh)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val dotColor = when (status) {
                ConnectionStatus.CONNECTED -> SuccessGreen
                ConnectionStatus.CONNECTING -> WarningAmber
                ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                ConnectionStatus.ERROR -> ErrorRed
            }
            val statusText = when (status) {
                ConnectionStatus.CONNECTED -> "Connected"
                ConnectionStatus.CONNECTING -> "Connecting…"
                ConnectionStatus.DISCONNECTED -> "Disconnected"
                ConnectionStatus.ERROR -> "Connection Error"
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                color = if (status == ConnectionStatus.CONNECTED) SuccessGreen
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (status == ConnectionStatus.CONNECTED) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "· ${latency}ms",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = serverUrl.ifBlank { "Not configured" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─── Quick Actions: compact pill row (Telegram-style) ───

@Composable
private fun QuickActionsRow(
    isLoading: Boolean,
    onNewChat: () -> Unit,
    onResumeSession: () -> Unit,
    onVoiceInput: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // New chat — filled primary pill
        Surface(
            onClick = onNewChat,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(50),
            color = HermesPrimary,
            enabled = !isLoading
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "New chat",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }
        // Voice — outline pill
        Surface(
            onClick = onVoiceInput,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(50),
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Voice",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }
        // Sessions — outline pill
        Surface(
            onClick = onResumeSession,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(50),
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Sessions",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }
    }
}


// ─── Recent Sessions Header ───

@Composable
private fun RecentSessionsHeader(
    sessionCount: Int,
    onSeeAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Recent sessions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 12.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "$sessionCount total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 2.dp)
        )

        // Clickable "See all" instead of IconButton to avoid min-size clipping
        Row(
            modifier = Modifier
                .clickable(onClick = onSeeAll)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "See all",
                style = MaterialTheme.typography.labelMedium,
                color = HermesPrimary,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "See all",
                tint = HermesPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ─── Empty Sessions Placeholder ───

@Composable
private fun EmptySessionsPlaceholder() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No recent sessions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Start a new chat or resume a previous one",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─── Swipeable Session Item ───

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSessionItem(
    session: Session,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            // Allow EVERY transition (Settled spring-back AND EndToStart)
            // — returning false for Settled is the classic bug that leaves
            // the row stuck half-swiped, with the delete icon covering the
            // row's trailing timestamp.
            if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
            }
            true
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // Delete background revealed on swipe
            val dismissValue = dismissState.currentValue
            val bgColor by animateColorAsState(
                targetValue = if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                    ErrorRed.copy(alpha = 0.2f)
                } else {
                    Color.Transparent
                },
                animationSpec = tween(300),
                label = "dismissBgColor"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                val scale by animateFloatAsState(
                    targetValue = if (dismissValue == SwipeToDismissBoxValue.EndToStart) 1.2f else 1f,
                    animationSpec = tween(300),
                    label = "deleteIconScale"
                )
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete session",
                    tint = ErrorRed,
                    modifier = Modifier.scale(scale)
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true
    ) {
        SessionItem(
            session = session,
            onClick = onClick,
            onLongClick = onRename
        )
    }
}

// ─── Session Item (Telegram-style row) ───

// Cached — SimpleDateFormat is expensive to construct per row per frame.
private val relTimeDateFmt = java.text.SimpleDateFormat(
    "d MMM", java.util.Locale.getDefault()
)

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "now"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        diff < 86_400_000L -> "${diff / 3_600_000L}h"
        diff < 7L * 86_400_000L -> "${diff / 86_400_000L}d"
        else -> relTimeDateFmt.format(java.util.Date(timestamp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionItem(
    session: Session,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val titleText = remember(session.title) {
        if (session.title.isNullOrBlank()) "Untitled Session" else session.title!!
    }
    val preview = when {
        session.messageCount <= 0 -> "No messages"
        session.messageCount == 1 -> "1 message"
        else -> "${session.messageCount} messages"
    }
    val timeText = remember(session.updatedAt) { formatRelativeTime(session.updatedAt) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Opaque surface: without this, the red swipe-delete layer +
            // trash icon show through the transparent row and sit on top of
            // the trailing timestamp even at rest.
            .background(MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Telegram-style circular avatar (colored bg + initial)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(HermesPrimary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = titleText.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Title + preview
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Relative time
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
