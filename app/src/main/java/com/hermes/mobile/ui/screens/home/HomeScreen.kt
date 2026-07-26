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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.hermes.mobile.ui.theme.DarkOnSurface
import com.hermes.mobile.ui.theme.DarkOnSurfaceVariant
import com.hermes.mobile.ui.theme.DarkSurfaceVariant
import com.hermes.mobile.ui.theme.ErrorRed
import com.hermes.mobile.ui.theme.GlassWhite
import com.hermes.mobile.ui.theme.GlassWhiteStrong
import com.hermes.mobile.ui.theme.HermesAccent
import com.hermes.mobile.ui.theme.HermesPrimary
import com.hermes.mobile.ui.theme.HermesPrimaryDark
import com.hermes.mobile.ui.theme.HermesSecondary
import com.hermes.mobile.ui.theme.HermesSecondaryDark
import com.hermes.mobile.ui.theme.SuccessGreen
import com.hermes.mobile.ui.theme.WarningAmber
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
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
            // Simulate a short delay for UX polish
            delay(400L)
            val config = com.hermes.mobile.data.model.ServerConfig()
            val status = repository.checkConnection(config)
            _uiState.update { state ->
                state.copy(
                    connectionStatus = status,
                    serverBaseUrl = config.baseUrl,
                    connectionLatency = if (status == ConnectionStatus.CONNECTED) 42L else 0L
                )
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
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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
                    onVoiceInput = { viewModel.createNewSession(onCreated = onNavigateToChat) }
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

// ─── Connection Hero Card (Glassmorphism) ───

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
            ConnectionStatus.DISCONNECTED -> DarkOnSurfaceVariant
            ConnectionStatus.ERROR -> ErrorRed
        },
        animationSpec = tween(600),
        label = "statusColor"
    )

    val statusText = when (status) {
        ConnectionStatus.CONNECTED -> "Connected to Hermes"
        ConnectionStatus.CONNECTING -> "Connecting…"
        ConnectionStatus.DISCONNECTED -> "Disconnected"
        ConnectionStatus.ERROR -> "Connection Error"
    }

    val statusIcon = when (status) {
        ConnectionStatus.CONNECTED -> Icons.Filled.Wifi
        ConnectionStatus.CONNECTING -> Icons.Filled.Wifi
        ConnectionStatus.DISCONNECTED -> Icons.Filled.WifiOff
        ConnectionStatus.ERROR -> Icons.Filled.WifiOff
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithCache {
                    // Glassmorphism base
                    val glassBrush = Brush.linearGradient(
                        colors = listOf(
                            GlassWhite,
                            GlassWhiteStrong.copy(alpha = 0.15f)
                        )
                    )
                    onDrawBehind {
                        // Outer glow
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    HermesPrimary.copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx())
                        )
                        // Glass background
                        drawRoundRect(
                            brush = glassBrush,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx())
                        )
                        // Border gradient
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    GlassWhiteStrong,
                                    HermesPrimary.copy(alpha = 0.3f),
                                    HermesSecondary.copy(alpha = 0.3f),
                                    GlassWhiteStrong
                                )
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
                .clickable { onRefresh() }
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Status row
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pulsing dot
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .alpha(
                                if (status == ConnectionStatus.CONNECTING) pulseAlpha else 1f
                            )
                            .clip(CircleShape)
                            .background(statusColor)
                            .shadow(
                                elevation = if (status == ConnectionStatus.CONNECTED) 4.dp else 0.dp,
                                shape = CircleShape,
                                ambientColor = statusColor,
                                spotColor = statusColor
                            )
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkOnSurface
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Latency badge (only when connected)
                    if (status == ConnectionStatus.CONNECTED) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
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

                Spacer(modifier = Modifier.height(12.dp))

                // Server URL
                Text(
                    text = serverUrl.ifBlank { "Not configured" },
                    style = MaterialTheme.typography.bodySmall,
                    color = DarkOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Action hint
                if (status != ConnectionStatus.CONNECTED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap to retry connection",
                        style = MaterialTheme.typography.labelSmall,
                        color = HermesSecondary.copy(alpha = 0.7f)
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
    val brush = remember(gradientColors) {
        Brush.verticalGradient(gradientColors)
    }

    Card(
        onClick = onClick,
        modifier = modifier.height(128.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        enabled = enabled
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    onDrawBehind {
                        // Gradient accent top border
                        drawRoundRect(
                            brush = brush,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Icon with gradient background
                Surface(
                    shape = CircleShape,
                    color = gradientColors[0].copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = gradientColors[0],
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkOnSurface
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = DarkOnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
            text = "Recent Sessions",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "$sessionCount total",
            style = MaterialTheme.typography.labelSmall,
            color = DarkOnSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp)
        )

        IconButton(onClick = onSeeAll) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "See all",
                    style = MaterialTheme.typography.labelMedium,
                    color = HermesPrimary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = "See all sessions",
                    tint = HermesPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ─── Empty Sessions Placeholder ───

@Composable
private fun EmptySessionsPlaceholder() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.5f))
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
                tint = DarkOnSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No recent sessions",
                style = MaterialTheme.typography.titleMedium,
                color = DarkOnSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Start a new chat or resume a previous one",
                style = MaterialTheme.typography.bodySmall,
                color = DarkOnSurfaceVariant.copy(alpha = 0.7f),
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
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
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
                    color = DarkOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${session.messageCount} message${if (session.messageCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = DarkOnSurfaceVariant
                    )
                    if (session.messageCount > 0) {
                        Text(
                            text = " · ",
                            style = MaterialTheme.typography.labelSmall,
                            color = DarkOnSurfaceVariant
                        )
                        Text(
                            text = formatRelativeTime(session.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = DarkOnSurfaceVariant
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "Open session",
                tint = DarkOnSurfaceVariant.copy(alpha = 0.5f),
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
