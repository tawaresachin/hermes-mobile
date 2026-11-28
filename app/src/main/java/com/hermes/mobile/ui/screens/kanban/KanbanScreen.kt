package com.hermes.mobile.ui.screens.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.KanbanNav
import com.hermes.mobile.ui.theme.ErrorRed
import com.hermes.mobile.ui.theme.HermesPrimary
import com.hermes.mobile.ui.theme.LocalDarkTheme
import com.hermes.mobile.ui.theme.SuccessGreen
import com.hermes.mobile.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanScreen(
    paddingValues: PaddingValues,
    viewModel: KanbanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var boardMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.startPolling() }
    // A notification tap while this screen is already open re-delivers the id
    // through KanbanNav (onNewIntent path), so observe it instead of reading
    // it once at first composition.
    val pendingTask by remember { derivedStateOf { KanbanNav.pendingTaskId } }
    LaunchedEffect(pendingTask) {
        pendingTask?.let { id ->
            KanbanNav.pendingTaskId = null
            viewModel.openTask(id)
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPolling() }
    }
    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(state.creating, state.actionMessage) {
        if (showCreate && !state.creating && state.actionMessage != null) {
            showCreate = false
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = Modifier.padding(paddingValues).imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Kanban", fontWeight = FontWeight.SemiBold)
                        if (state.mode.enabled) {
                            Spacer(Modifier.width(8.dp))
                            Box {
                                TextButton(onClick = { boardMenu = true }) {
                                    Text(
                                        state.board?.board ?: state.selectedBoard ?: "default",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                DropdownMenu(expanded = boardMenu, onDismissRequest = { boardMenu = false }) {
                                    (state.board?.boards ?: state.mode.boards).forEach { slug ->
                                        DropdownMenuItem(
                                            text = { Text(slug) },
                                            onClick = {
                                                boardMenu = false
                                                viewModel.selectBoard(slug)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh board")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.mode.enabled && state.mode.available) {
                ExtendedFloatingActionButton(
                    onClick = { showCreate = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New task") },
                )
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                state.modeLoading && state.board == null ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                !state.mode.available ->
                    KanbanMessage(
                        title = "Kanban isn't available",
                        body = state.mode.error
                            ?: "This Hermes Agent doesn't expose the Kanban kernel yet. Update the agent and the mobile plugin.",
                        action = "Retry",
                        onAction = { viewModel.refreshMode(showLoading = true) },
                    )
                !state.mode.enabled ->
                    KanbanEnableCard(
                        state = state,
                        onEnable = { viewModel.setMode(true) },
                    )
                state.board == null && state.boardLoading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.board == null ->
                    KanbanMessage(
                        title = "Board unavailable",
                        body = "The agent did not return a Kanban board.",
                        action = "Retry",
                        onAction = { viewModel.loadBoard() },
                    )
                else -> KanbanBoardView(
                    board = state.board!!,
                    refreshing = state.refreshing,
                    onTaskClick = { viewModel.openTask(it) },
                )
            }
            if (state.mode.enabled && state.mode.restartRequired) {
                Surface(
                    color = WarningAmber.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                ) {
                    Text(
                        "Restart the Hermes gateway to apply the dispatcher change.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningAmber,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateTaskDialog(
            assignees = state.board?.assignees?.ifEmpty { listOf("default") } ?: listOf("default"),
            busy = state.creating,
            error = state.createError,
            onDismiss = { showCreate = false; viewModel.clearMessage() },
            onCreate = { title, body, assignee, triage ->
                viewModel.createTask(title, body, assignee, triage)
            },
        )
    }

    state.detail?.let { detail ->
        TaskDetailSheet(
            detail = detail,
            loading = state.detailLoading,
            busy = state.busyTaskId != null,
            sendingComment = state.sendingComment,
            onDismiss = { viewModel.closeTask() },
            onComment = { viewModel.postComment(it) },
            onAction = { action, extra -> viewModel.act(action, extra) },
        )
    }
}

@Composable
private fun KanbanMessage(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Dashboard, contentDescription = null, tint = HermesPrimary,
            modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun KanbanEnableCard(state: KanbanUiState, onEnable: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Dashboard, contentDescription = null, tint = HermesPrimary,
            modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        Text("Turn on Kanban mode", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Kanban mode gives the agent a task board and starts the gateway " +
                "dispatcher, so ready tasks run in parallel workers. You can " +
                "create tasks with a description, watch every stage, and get " +
                "notified when one needs you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onEnable,
            enabled = !state.modeLoading && !state.modeUpdating,
            colors = ButtonDefaults.buttonColors(containerColor = HermesPrimary),
        ) {
            if (state.modeLoading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (state.modeUpdating) "Enabling…" else "Enable Kanban mode")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "This changes the agent's api_server toolset and kanban.dispatch_in_gateway.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Explicit three-level palette. The theme's surface/surfaceVariant tokens
 * are only a few RGB units apart in light mode, which made the stage/task
 * nesting invisible. These values give a clearly readable hierarchy in both
 * themes (stage darkest gray → task white → child mid-gray). */
private data class KanbanPalette(
    val stageBg: Color,
    val taskBg: Color,
    val childBg: Color,
    val border: Color,
    val mutedText: Color,
)

@Composable
private fun kanbanPalette(): KanbanPalette = if (LocalDarkTheme.current) {
    KanbanPalette(
        stageBg = Color(0xFF243342),
        taskBg = Color(0xFF3B4E62),
        childBg = Color(0xFF1C2833),
        border = Color(0xFF5C748B),
        mutedText = Color(0xFFB9C6D3),
    )
} else {
    KanbanPalette(
        stageBg = Color(0xFFD8D8DE),
        taskBg = Color.White,
        childBg = Color(0xFFC7C7CE),
        border = Color(0xFFA6A6B0),
        mutedText = Color(0xFF54585D),
    )
}

@Composable
private fun KanbanBoardView(
    board: KanbanBoard,
    refreshing: Boolean,
    onTaskClick: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (refreshing) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        // Each stage is ONE gray card: the stage name is the card title and
        // its tasks are sub-cards nested underneath. No per-task status badge
        // is needed (and therefore no truncated status text) — the stage
        // title is the single source of truth for the status.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // Extra bottom inset keeps the last card clear of the FAB.
            contentPadding = PaddingValues(
                start = 10.dp, end = 10.dp, top = 10.dp, bottom = 88.dp,
            ),
        ) {
            items(board.columns, key = { it.key }) { column ->
                KanbanStageCard(column, onTaskClick)
            }
        }
    }
}

@Composable
private fun KanbanStageCard(
    column: KanbanColumn,
    onTaskClick: (String) -> Unit,
) {
    val stageColor = statusColor(column.key)
    val pal = kanbanPalette()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            // Full-opacity gray stage container — clearly darker than the
            // white task sub-cards nested inside it.
            containerColor = pal.stageBg,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, pal.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            // ── Stage title (full name, never trimmed) ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(stageColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    column.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = stageColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        column.tasks.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        // Dark on light tint: readable at 11sp for every stage.
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── Task sub-cards ──
            if (column.tasks.isEmpty()) {
                Text(
                    "No tasks in this stage",
                    style = MaterialTheme.typography.bodySmall,
                    color = pal.mutedText,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                column.tasks.forEach { task ->
                    KanbanTaskCardView(
                        task,
                        onClick = { onTaskClick(task.id) },
                        onSubtaskClick = onTaskClick,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun KanbanTaskCardView(
    task: KanbanTaskCard,
    onClick: () -> Unit,
    onSubtaskClick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val pal = kanbanPalette()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = pal.taskBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, pal.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (expanded) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse task" else "Expand task",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (task.bodyPreview.isNotBlank()) {
                    Text(
                        task.bodyPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (task.commentCount > 0) {
                    Text(
                        "${task.commentCount} ${if (task.commentCount == 1) "comment" else "comments"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // Sub-tasks keep their own stage badge — a sub-task can be
                // in a different stage than its parent task.
                if (task.subtasks.isNotEmpty()) {
                    Text(
                        "Sub-tasks (${task.progressDone}/${task.progressTotal} done)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    task.subtasks.forEach { subtask ->
                        // Open the sub-task's own detail so it can be acted on
                        // (comment/unblock/delete) independently of the parent.
                        KanbanSubtaskView(subtask, onClick = { onSubtaskClick(subtask.id) })
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Person, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(
                        task.assignee.ifBlank { "unassigned" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        shortAge(task.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                if (task.blockKind == "needs_input") {
                    Spacer(Modifier.height(6.dp))
                    Surface(color = ErrorRed.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            "Needs your input",
                            style = MaterialTheme.typography.labelSmall,
                            color = ErrorRed,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClick) { Text("Open details") }
            }
        }
    }
}

@Composable
private fun KanbanSubtaskView(subtask: KanbanSubtask, onClick: () -> Unit) {
    val stageColor = statusColor(subtask.status)
    val pal = kanbanPalette()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
        shape = RoundedCornerShape(8.dp),
        color = pal.childBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, pal.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = stageColor.copy(alpha = 0.18f), shape = RoundedCornerShape(4.dp)) {
                Text(
                    kanbanStatusLabel(subtask.status),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = stageColor,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                subtask.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                subtask.assignee.ifBlank { "—" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CreateTaskDialog(
    assignees: List<String>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (title: String, body: String, assignee: String, triage: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf(assignees.firstOrNull() ?: "default") }
    var triage by remember { mutableStateOf(false) }
    var assigneeMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New Kanban task") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Description") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { assigneeMenu = true }) {
                        Text("Assignee: ${assignee.ifBlank { "default" }}")
                    }
                    DropdownMenu(expanded = assigneeMenu, onDismissRequest = { assigneeMenu = false }) {
                        assignees.forEach { a ->
                            DropdownMenuItem(text = { Text(a) }, onClick = {
                                assignee = a; assigneeMenu = false
                            })
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = triage, onCheckedChange = { triage = it })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Plan first (triage)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Lets the agent split the work before dispatching",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = ErrorRed)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title, body, assignee, triage) },
                enabled = !busy && title.isNotBlank(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailSheet(
    detail: KanbanTaskDetail,
    loading: Boolean,
    busy: Boolean,
    sendingComment: Boolean,
    onDismiss: () -> Unit,
    onComment: (String) -> Unit,
    onAction: (String, org.json.JSONObject?) -> Unit,
) {
    var comment by remember(detail.task.id) { mutableStateOf("") }
    var confirmDelete by remember(detail.task.id) { mutableStateOf(false) }
    val task = detail.task
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        "${kanbanStatusLabel(task.status)} · ${task.assignee.ifBlank { "unassigned" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (task.status == "blocked" || task.blockKind == "needs_input") {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = ErrorRed.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "This task needs a decision. Unblock it to let the worker continue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                when (task.status) {
                    "blocked" -> {
                        // Answering with a comment loosens dependency blocks too
                        // (the agent resumes from it); Unblock forces the retry.
                        ActionButton("Unblock", Icons.Filled.PlayArrow, busy) {
                            onAction("unblock", null)
                        }
                    }
                    "review" -> ActionButton("Approve", Icons.Filled.CheckCircle, busy) {
                        onAction("approve", null)
                    }
                    "running" -> ActionButton("Retry", Icons.Filled.Refresh, busy) {
                        onAction("retry", null)
                    }
                    "triage", "todo", "scheduled", "ready" ->
                        ActionButton("Start now", Icons.Filled.PlayArrow, busy) {
                            onAction("unblock", null)
                        }
                }
                ActionButton("Archive", Icons.Filled.Archive, busy) {
                    onAction("archive", null)
                }
                if (task.status != "running") {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = !busy,
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = ErrorRed,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, ErrorRed.copy(alpha = 0.5f)),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (detail.body.isNotBlank()) {
                Text("Description", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold)
                Text(detail.body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
            }
            val summary = detail.task.latestSummary.ifBlank {
                detail.runs.lastOrNull { it.summary.isNotBlank() }?.summary.orEmpty()
            }
            if (summary.isNotBlank()) {
                Text("Latest handoff", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold)
                Text(summary, style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(12.dp))
            }
            if (detail.runs.isNotEmpty()) {
                Text("Runs", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold)
                detail.runs.takeLast(4).forEach { run ->
                    Text(
                        "${shortAge(run.startedAt)} · ${run.status}${if (run.outcome.isNotBlank()) " / ${run.outcome}" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            Text("Comments", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold)
            if (detail.comments.isEmpty()) {
                Text("No comments yet", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                detail.comments.takeLast(8).forEach { c ->
                    Text(
                        "${c.author}: ${c.body}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    placeholder = { Text("Post a comment…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
                IconButton(
                    onClick = {
                        if (comment.isNotBlank()) {
                            onComment(comment)
                            comment = ""
                        }
                    },
                    enabled = !sendingComment && comment.isNotBlank(),
                ) {
                    if (sendingComment) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Send, contentDescription = "Post comment")
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete task?") },
            text = { Text("\"${task.title}\" and all its comments, events and child links will be permanently removed. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onAction("delete", null)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp),
                ) { Text("Delete", color = ErrorRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    busy: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = !busy) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    "triage", "todo", "scheduled" -> MaterialTheme.colorScheme.onSurfaceVariant
    "ready" -> HermesPrimary
    "running" -> WarningAmber
    "blocked" -> ErrorRed
    "review" -> Color(0xFF6366F1)
    "done" -> SuccessGreen
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun shortAge(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val diff = (System.currentTimeMillis() / 1000L - epochSeconds).coerceAtLeast(0)
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86_400 -> "${diff / 3600}h"
        diff < 604_800 -> "${diff / 86_400}d"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochSeconds * 1000L))
    }
}
