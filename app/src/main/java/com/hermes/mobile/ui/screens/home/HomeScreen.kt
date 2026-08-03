package com.hermes.mobile.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.VectorConverter
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.hermes.mobile.ui.theme.DarkSurfaceVariant
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 48.dp,
                bottom = 24.dp
            )
        ) {
            // ── Greeting ──
            item(key = "greeting") {
                GreetingSection(
                    emoji = uiState.greetingEmoji,
                    greeting = uiState.greeting
                )
            }

            // ── Connection Hero Card ──
            item(key = "connection_hero") {
                ConnectionHeroCard(
                    status = uiState.connectionStatus,
                    serverUrl = uiState.serverBaseUrl,
                    latency = uiState.connectionLatency,
                    onRefresh = { viewModel.refreshConnection() }
                )
            }

            // ── Quick Actions ──
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
                items(
                    items = uiState.sessions,
                    key = { it.id }
                ) { session ->
                    SwipeableSessionItem(
                        session = session,
                        onClick = { onNavigateToChat(session.id) },
                        onDelete = { viewModel.deleteSession(session.id) }
                    )
                }
            }
        }
    }
}

// ─── Greeting Section ───

@Composable
private fun GreetingSection(
    emoji: String,
    greeting: String
) {
    AnimatedContent(
        targetState = greeting,
        transitionSpec = {
            fadeIn(animationSpec = tween(400)) togetherWith
                fadeOut(animationSpec = tween(400))
        },
        label = "greeting_animation"
    ) { currentGreeting ->
        val targetEmoji = when (currentGreeting) {
            "Good Morning" -> "🌅"
            "Good Afternoon" -> "☀️"
            "Good Evening" -> "🌆"
            "Good Night" -> "🌙"
            "Late Night" -> "🌙"
            else -> "👋"
        }
        Text(
            text = "$targetEmoji $currentGreeting",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Text(
        text = "Welcome to Hermes Mobile",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    )
}

// ─── Connection Hero Card ───

@Composable
private fun ConnectionHeroCard(
    status: ConnectionStatus,
    serverUrl: String,
    latency: Long,
    onRefresh: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val statusColor by animateColorAsState(
        targetValue = when (status) {
            ConnectionStatus.CONNECTED -> SuccessGreen
            ConnectionStatus.CONNECTING -> WarningAmber
            ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
            ConnectionStatus.ERROR -> ErrorRed
        },
        animationSpec = tween(600),
        label = "statusColor"
    )

    val statusText = when (status) {
        ConnectionStatus.CONNECTED -> "Connected"
        ConnectionStatus.CONNECTING -> "Connecting…"
        ConnectionStatus.DISCONNECTED -> "Disconnected"
        ConnectionStatus.ERROR -> "Connection Error"
    }

    val statusIcon = when (status) {
        ConnectionStatus.CONNECTED -> Icons.Filled.CheckCircle
        ConnectionStatus.CONNECTING -> Icons.Filled.Sync
        ConnectionStatus.DISCONNECTED -> Icons.Filled.CloudOff
        ConnectionStatus.ERROR -> Icons.Filled.Error
    }

    Card(
        onClick = onRefresh,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon with pulse
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .alpha(
                            if (status == ConnectionStatus.CONNECTING) pulseAlpha else 1f
                        )
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = serverUrl.ifBlank { "Not configured" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (status == ConnectionStatus.CONNECTED) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SuccessGreen.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "${latency}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = SuccessGreen,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ─── Quick Actions Row ───

@Composable
private fun QuickActionsRow(
    isLoading: Boolean,
    onNewChat: () -> Unit,
    onResumeSession: () -> Unit,
    onVoiceInput: () -> Unit
) {
    Column {
        Text(
            text = "Quick Actions",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // New Chat
            QuickActionButton(
                icon = Icons.Filled.Add,
                label = "New Chat",
                description = "Start a fresh conversation",
                gradientColors = listOf(HermesPrimary, HermesPrimaryDark),
                onClick = onNewChat,
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            )

            // Resume
            QuickActionButton(
                icon = Icons.Filled.MenuBook,
                label = "Resume",
                description = "Pick up where you left off",
                gradientColors = listOf(HermesSecondary, HermesSecondaryDark),
                onClick = onResumeSession,
                modifier = Modifier.weight(1f)
            )

            // Voice
            QuickActionButton(
                icon = Icons.Filled.Mic,
                label = "Voice",
                description = "Speak your query",
                gradientColors = listOf(HermesAccent, WarningAmber),
                onClick = onVoiceInput,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    gradientColors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        enabled = enabled
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            // Icon with solid background
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(gradientColors[0].copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = gradientColors[0],
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
            text = "Recent Sessions",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
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
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
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
            onClick = onClick
        )
    }
}

// ─── Session Item ───

@Composable
private fun SessionItem(
    session: Session,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Session icon
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = HermesPrimary.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = HermesPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title ?: "Untitled Session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${session.messageCount} message${if (session.messageCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (session.messageCount > 0) {
                        Text(
                            text = " · ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatRelativeTime(session.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "Open session",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─── Relative Time Formatting ───

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000L -> "just now"
        diff < 3600_000L -> {
            val mins = diff / 60_000L
            "${mins}m ago"
        }
        diff < 86_400_000L -> {
            val hours = diff / 3600_000L
            "${hours}h ago"
        }
        diff < 604_800_000L -> {
            val days = diff / 86_400_000L
            "${days}d ago"
        }
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}
