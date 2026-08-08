package com.hermes.mobile.ui.screens.sessions

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PushPin
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
import com.hermes.mobile.ui.components.HermesWatermark
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
    private val repository: HermesRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
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

    /** Its messages — Undo restores the conversation, not just the row. */
    private var lastDeletedMessages: List<com.hermes.mobile.data.model.Message> = emptyList()

    // ── Filtered sessions ────────────────────────────────────────

    // ── Pinned sessions (Telegram-style, persisted in prefs) ──
    private val _pinned = MutableStateFlow(loadPinnedIds())

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredSessions: StateFlow<List<Session>> = combine(
        repository.allSessions,
        _searchQuery.debounce(300),
        _refreshTrigger.onStart { emit(Unit) },
        _pinned
    ) { sessions, query, _, pinned ->
        val base = if (query.isBlank()) {
            sessions.filter { it.isActive }
        } else {
            sessions.filter { session ->
                session.isActive
                    && (session.title?.contains(query, ignoreCase = true) == true)
            }
        }
        // Telegram-style: pinned sessions float to the top.
        base.sortedByDescending { it.id in pinned }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Pinned sessions (Telegram-style, persisted in prefs) ──
    private fun loadPinnedIds(): Set<String> {
        return try {
            appContext.getSharedPreferences(
                "hermes_sessions", android.content.Context.MODE_PRIVATE
            )?.getStringSet("pinned_sessions", emptySet()) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun savePinnedIds(ids: Set<String>) {
        try {
            appContext.getSharedPreferences(
                "hermes_sessions", android.content.Context.MODE_PRIVATE
            )?.edit()?.putStringSet("pinned_sessions", ids)?.apply()
        } catch (e: Exception) {
            // ignore — pinning is best-effort
        }
    }

    val pinnedSessions: StateFlow<Set<String>> = _pinned.asStateFlow()

    fun togglePin(sessionId: String) {
        val next = _pinned.value.toMutableSet().apply {
            if (!add(sessionId)) remove(sessionId)
        }
        _pinned.value = next
        savePinnedIds(next)
    }

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
            // Snapshot BEFORE delete — Undo restores the conversation.
            lastDeletedMessages = repository.getMessagesOnce(session.id)
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

    /** Rename a session (local-only metadata change). */
    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch {
            repository.renameSession(sessionId, title)
        }
    }

    /**
     * Restore the most recently deleted session.
     * Called when the user taps "Undo" on the snackbar.
     */
    fun restoreLastDeleted() {
        val session = lastDeletedSession ?: return
        val messages = lastDeletedMessages
        viewModelScope.launch {
            try {
                repository.restoreSession(session, messages)
                lastDeletedSession = null
                lastDeletedMessages = emptyList()
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
    val context = androidx.compose.ui.platform.LocalContext.current

    // Long-press target for the rename dialog
    var renameTarget by remember { mutableStateOf<Session?>(null) }

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
        HermesWatermark()
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
                val pinnedIds by viewModel.pinnedSessions.collectAsState()
                // Draft map: read once per composition (drafts are tiny).
                val drafts = remember(sessions) {
                    com.hermes.mobile.data.local.DraftStore.init(context)
                    sessions.associate { it.id to com.hermes.mobile.data.local.DraftStore.get(it.id) }
                }
                SessionsList(
                    sessions = sessions,
                    pinnedIds = pinnedIds,
                    onSessionSelected = onSessionSelected,
                    onDeleteSession = viewModel::deleteSession,
                    onTogglePin = viewModel::togglePin,
                    onRenameSession = { renameTarget = it },
                    drafts = drafts
                )
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
    pinnedIds: Set<String>,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (Session) -> Unit,
    onTogglePin: (String) -> Unit,
    onRenameSession: (Session) -> Unit,
    drafts: Map<String, String>
) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(
            items = sessions,
            key = { _, it -> it.id }
        ) { index, session ->
            SwipeToDismissBox(
                state = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        // Allow every transition — returning false for Settled
                        // leaves the row stuck half-swiped (delete icon covering
                        // the trailing timestamp).
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            onDeleteSession(session)
                        }
                        true
                    }
                ),
                backgroundContent = { SwipeDeleteBackground() },
                enableDismissFromStartToEnd = false,
                enableDismissFromEndToStart = true
            ) {
                Column {
                    SessionCard(
                        session = session,
                        onClick = { onSessionSelected(session.id) },
                        onLongClick = { onRenameSession(session) },
                        isPinned = session.id in pinnedIds,
                        onTogglePin = { onTogglePin(session.id) },
                        draftText = drafts[session.id] ?: ""
                    )
                    // Telegram-style thin divider between rows
                    if (index < sessions.lastIndex) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(start = 72.dp)
                        )
                    }
                }
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: Session,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    draftText: String = ""
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
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // ── Telegram-style circular avatar (colored bg + initial) ──
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

                    // Telegram-style draft indicator: red italic "Draft: …"
                    // shown instead of the message preview when a draft exists.
                    if (draftText.isNotBlank()) {
                        Text(
                            text = "Draft: $draftText",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ErrorRed,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

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

                // Telegram-style pin toggle (filled = pinned, floats to top)
                if (onTogglePin != null) {
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isPinned) Icons.Filled.PushPin
                            else Icons.Outlined.PushPin,
                            contentDescription = if (isPinned) "Unpin" else "Pin",
                            tint = if (isPinned) HermesPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Telegram rows have no chevron — tap the whole row
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
// Cached formatters — SimpleDateFormat is expensive to construct per row.
private val tsWeekdayFmt = SimpleDateFormat("EEEE", Locale.getDefault())
private val tsDateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "Unknown"

    // Telegram-style relative time: now / 5m / 3h / Yesterday / weekday / date
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60_000L -> "now"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        diff < 86_400_000L -> "${diff / 3_600_000L}h"
        diff < 172_800_000L -> "Yesterday"
        diff < 7L * 86_400_000L -> tsWeekdayFmt.format(Date(millis))
        else -> tsDateFmt.format(Date(millis))
    }
}
