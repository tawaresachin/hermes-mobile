package com.hermes.mobile.ui.screens.sessions

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.model.Session
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════
//  SessionsViewModel  —  Hilt-injected, drives the sessions list
//  with real-time search, pull-to-refresh, and swipe-delete+undo.
// ═══════════════════════════════════════════════════════════════

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: HermesRepository
) : ViewModel() {

    /** Mutable search query — debounced before filtering. */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Trigger a re-combine of the sessions flow (for pull-to-refresh). */
    private val _refreshTrigger = MutableSharedFlow<Unit>(replay = 0)

    /** One-shot snackbar events for delete + undo. */
    private val _snackbarEvent = MutableSharedFlow<SnackbarMessage>()
    val snackbarEvent: SharedFlow<SnackbarMessage> = _snackbarEvent.asSharedFlow()

    /** Whether a manual refresh is in progress. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** The most recently deleted session — held for potential undo. */
    private var lastDeletedSession: Session? = null

    // ── Filtered sessions ────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredSessions: StateFlow<List<Session>> = combine(
        repository.allSessions,
        _searchQuery.debounce(300),
        _refreshTrigger.onStart { emit(Unit) }
    ) { sessions, query, _ ->
        if (query.isBlank()) {
            sessions.filter { it.isActive }
        } else {
            sessions.filter { session ->
                session.isActive
                    && (session.title?.contains(query, ignoreCase = true) == true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Actions ──────────────────────────────────────────────────

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshTrigger.emit(Unit)
            // Brief delay so the spinner is always visible even on fast DB reads
            kotlinx.coroutines.delay(300)
            _isRefreshing.value = false
        }
    }

    /**
     * Delete a session and emit a snackbar event with an Undo action.
     * The deleted [Session] is kept in memory so it can be restored.
     */
    fun deleteSession(session: Session) {
        viewModelScope.launch {
            try {
                repository.deleteSession(session.id)
                lastDeletedSession = session
                _snackbarEvent.emit(
                    SnackbarMessage(
                        text = "\"${session.title ?: "Untitled"}\" deleted",
                        actionLabel = "Undo"
                    )
                )
            } catch (e: Exception) {
                // If delete fails, try a direct local-only delete
                try {
                    repository.deleteSessionLocal(session.id)
                    lastDeletedSession = session
                    _snackbarEvent.emit(
                        SnackbarMessage(
                            text = "\"${session.title ?: "Untitled"}\" deleted",
                            actionLabel = "Undo"
                        )
                    )
                } catch (e2: Exception) {
                    _snackbarEvent.emit(
                        SnackbarMessage(
                            text = "Delete failed: ${e2.message ?: "unknown"}",
                            actionLabel = null
                        )
                    )
                }
            }
        }
    }

    /**
     * Restore the most recently deleted session.
     * Called when the user taps "Undo" on the snackbar.
     */
    fun restoreLastDeleted() {
        val session = lastDeletedSession ?: return
        viewModelScope.launch {
            try {
                repository.restoreSession(session)
                lastDeletedSession = null
            } catch (_: Exception) {
                // If restore fails (e.g. database error), fall back to
                // creating a fresh session so the user isn't left stranded.
                repository.createSession()
            }
        }
    }
}

// ── Snackbar event data ──────────────────────────────────────────

data class SnackbarMessage(
    val text: String,
    val actionLabel: String? = null
)

// ═══════════════════════════════════════════════════════════════
//  SessionsScreen  composable
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    paddingValues: PaddingValues,
    onSessionSelected: (String) -> Unit,
    onBack: () -> Unit,
    onNewChat: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel()
) {
    val sessions by viewModel.filteredSessions.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Snackbar event collector ───────────────────────────────
    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { msg ->
            val result = snackbarHostState.showSnackbar(
                message = msg.text,
                actionLabel = msg.actionLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreLastDeleted()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        topBar = {
            SessionsTopBar(
                searchQuery = searchQuery,
                onSearchQueryChanged = viewModel::onSearchQueryChanged,
                onBack = onBack
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (sessions.isEmpty()) {
                SessionsEmptyState(
                    hasSearchFilter = searchQuery.isNotBlank(),
                    onNewChat = onNewChat,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                SessionsList(
                    sessions = sessions,
                    onSessionSelected = onSessionSelected,
                    onDeleteSession = viewModel::deleteSession
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Top bar  —  collapses into a SearchBar when the search icon
//  is tapped
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsTopBar(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onBack: () -> Unit
) {
    var isSearchActive by remember { mutableStateOf(false) }

    if (isSearchActive) {
        // ── Active search bar ──
        SearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChanged,
            onSearch = { /* results are real-time via StateFlow */ },
            active = true,
            onActiveChange = { active ->
                if (!active) {
                    isSearchActive = false
                    onSearchQueryChanged("")
                }
            },
            placeholder = { Text("Search sessions\u2026") },
            leadingIcon = {
                IconButton(onClick = {
                    if (searchQuery.isNotBlank()) {
                        onSearchQueryChanged("")
                    } else {
                        isSearchActive = false
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Close search"
                    )
                }
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = {
                        isSearchActive = false
                        onSearchQueryChanged("")
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear and close")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            shape = RoundedCornerShape(16.dp),
            colors = SearchBarDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            // Empty — search results are shown in the main sessions list below
        }
    } else {
        // ── Default top app bar ──
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Sessions",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { isSearchActive = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Search sessions")
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = Color.Transparent
            )
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  Sessions list  —  LazyColumn with swipe-to-delete
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsList(
    sessions: List<Session>,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (Session) -> Unit
) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(
            items = sessions,
            key = { it.id }
        ) { session ->
            SwipeToDismissBox(
                state = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            onDeleteSession(session)
                            true
                        } else {
                            false
                        }
                    }
                ),
                backgroundContent = { SwipeDeleteBackground() },
                enableDismissFromStartToEnd = false,
                enableDismissFromEndToStart = true
            ) {
                SessionCard(
                    session = session,
                    onClick = { onSessionSelected(session.id) }
                )
            }
        }

        // Bottom spacer so the last card isn't flush with the nav bar
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Swipe-delete background  —  revealed when the user swipes
//  a session card to the left
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SwipeDeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        ErrorRed.copy(alpha = 0.7f)
                    )
                )
            ),
        contentAlignment = Alignment.CenterEnd
    ) {
        Row(
            modifier = Modifier.padding(end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = Color.White
            )
            Text(
                text = "Delete",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Session card  —  glassmorphism design with gradient overlay,
//  session icon, title, date, and message count
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SessionCard(
    session: Session,
    onClick: () -> Unit
) {
    val dateText = remember(session.updatedAt) {
        formatTimestamp(session.updatedAt)
    }

    val titleText = remember(session.title) {
        if (session.title.isNullOrBlank()) "Untitled Session" else session.title!!
    }

    val messagePreview = remember(session.messageCount) {
        when {
            session.messageCount <= 0 -> "No messages"
            session.messageCount == 1 -> "1 message"
            else -> "${session.messageCount} messages"
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // ── Session icon ──
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(HermesPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = HermesPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }

                Spacer(modifier = Modifier.width(14.dp))

                // ── Title + metadata ──
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Date
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Text(
                                text = dateText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        // Message count
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forum,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Text(
                                text = messagePreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // Chevron
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Open session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

// ═══════════════════════════════════════════════════════════════
//  Empty state  —  shown when there are no sessions or no search
//  results
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SessionsEmptyState(
    hasSearchFilter: Boolean,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (hasSearchFilter) {
            // ── No results for current search ──
            Icon(
                imageVector = Icons.Outlined.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No matching sessions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Try a different search term",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                textAlign = TextAlign.Center
            )
        } else {
            // ── No sessions at all ──
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "No sessions yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Start a conversation to see your\nsession history here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            FilledTonalButton(
                onClick = onNewChat,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = HermesPrimary.copy(alpha = 0.15f),
                    contentColor = HermesPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "New Chat",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Formatting utilities
// ═══════════════════════════════════════════════════════════════

/**
 * Formats a Unix-millis timestamp to a human-readable label.
 *
 * - **Today**        → "3:45 PM"
 * - **This week**    → "Mon 3:45 PM"
 * - **Older**        → "Jan 5, 2025"
 */
private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "Unknown"

    val now = System.currentTimeMillis()
    val todayStart = now - (now % 86_400_000L) // UTC day boundary

    return when {
        millis >= todayStart -> {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
        }
        millis >= todayStart - 6 * 86_400_000L -> {
            SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(millis))
        }
        else -> {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
        }
    }
}
