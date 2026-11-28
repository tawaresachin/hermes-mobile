package com.hermes.mobile.ui.screens.kanban

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.notifications.KanbanWatcherService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@Immutable
data class KanbanUiState(
    val mode: KanbanMode = KanbanMode(),
    val modeLoading: Boolean = true,
    val modeUpdating: Boolean = false,
    val board: KanbanBoard? = null,
    val boardLoading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val selectedBoard: String? = null,
    val creating: Boolean = false,
    val createError: String? = null,
    val detail: KanbanTaskDetail? = null,
    val detailLoading: Boolean = false,
    val actionMessage: String? = null,
    val busyTaskId: String? = null,
    val sendingComment: Boolean = false,
)

@HiltViewModel
class KanbanViewModel @Inject constructor(
    private val repository: HermesRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(KanbanUiState())
    val state: kotlinx.coroutines.flow.StateFlow<KanbanUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var modeToggleInFlight = false
    private var watcherEnabled: Boolean? = null

    init {
        refreshMode(showLoading = true)
    }

    fun refreshMode(showLoading: Boolean = false) {
        viewModelScope.launch {
            if (showLoading) _state.value = _state.value.copy(modeLoading = true)
            val res = repository.kanbanMode()
            val mode = parseKanbanMode(res.body).let { parsed ->
                if (res.status == 501 || res.status == 404) {
                    parsed.copy(available = false, error = "This Hermes Agent does not expose Kanban yet.")
                } else if (res.body == null && res.status == 0) {
                    parsed.copy(available = false, error = null)
                } else parsed
            }
            _state.value = _state.value.copy(mode = mode, modeLoading = false,
                selectedBoard = _state.value.selectedBoard ?: mode.boards.firstOrNull())
            if (mode.enabled) loadBoard(silent = true) else pollJob?.cancel()
            syncWatcher(mode.available && mode.enabled)
        }
    }

    fun setMode(enabled: Boolean) {
        if (modeToggleInFlight) return
        modeToggleInFlight = true
        viewModelScope.launch {
            _state.value = _state.value.copy(modeUpdating = true)
            try {
                val res = repository.setKanbanMode(enabled)
                if (res.body == null) {
                    _state.value = _state.value.copy(
                        error = "Could not update Kanban mode (HTTP ${res.status}).")
                    return@launch
                }
                val mode = parseKanbanMode(res.body)
                _state.value = _state.value.copy(mode = mode, modeUpdating = false, error = null)
                syncWatcher(mode.available && mode.enabled)
                if (mode.enabled) loadBoard() else _state.value =
                    _state.value.copy(board = null, detail = null)
            } finally {
                modeToggleInFlight = false
                _state.value = _state.value.copy(modeUpdating = false)
            }
        }
    }

    /** Start/stop the background watcher only when the effective state flips. */
    private fun syncWatcher(enabled: Boolean) {
        if (watcherEnabled == enabled) return
        watcherEnabled = enabled
        KanbanWatcherService.sync(context, enabled)
    }

    fun selectBoard(slug: String) {
        if (slug == _state.value.selectedBoard) return
        _state.value = _state.value.copy(selectedBoard = slug, board = null, error = null)
        KanbanWatcherService.setBoard(context, slug)
        loadBoard()
    }

    fun loadBoard(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.value = _state.value.copy(boardLoading = true, error = null)
            val board = _state.value.selectedBoard
            val res = repository.kanbanBoard(board)
            val parsed = parseKanbanBoard(res.body)
            if (parsed != null) {
                _state.value = _state.value.copy(
                    board = parsed,
                    boardLoading = false,
                    refreshing = false,
                    error = null,
                    selectedBoard = parsed.board,
                )
                // Keep an open detail sheet live as the worker advances.
                _state.value.detail?.task?.id?.let { id ->
                    if (!silent) refreshDetail(id)
                }
            } else {
                _state.value = _state.value.copy(
                    boardLoading = false,
                    refreshing = false,
                    error = "Could not load the Kanban board (HTTP ${res.status}).",
                )
            }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(refreshing = true)
        refreshMode()
        loadBoard()
    }

    fun createTask(title: String, body: String, assignee: String, triage: Boolean) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) {
            _state.value = _state.value.copy(createError = "Title is required")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(creating = true, createError = null)
            val payload = JSONObject()
                .put("title", cleanTitle)
                .put("body", body.trim())
                .put("assignee", assignee.trim())
                .put("triage", triage)
                .put("priority", 0)
                .put("goal_mode", false)
            _state.value.selectedBoard?.let { payload.put("board", it) }
            val res = repository.kanbanCreateTask(payload)
            val ok = res.body?.optBoolean("ok", false) == true
            if (ok) {
                _state.value = _state.value.copy(
                    creating = false, createError = null,
                    actionMessage = if (triage) "Task added to Triage" else "Task queued for the agent",
                )
                loadBoard(silent = true)
            } else {
                val err = res.body?.optString("error")?.ifBlank { null }
                    ?: "Could not create the task (HTTP ${res.status})."
                _state.value = _state.value.copy(creating = false, createError = err)
            }
        }
    }

    fun openTask(taskId: String) {
        _state.value = _state.value.copy(detailLoading = true, actionMessage = null)
        viewModelScope.launch { refreshDetail(taskId) }
    }

    private fun refreshDetail(taskId: String) {
        viewModelScope.launch {
            val res = repository.kanbanTask(taskId, _state.value.selectedBoard)
            val detail = parseKanbanTaskDetail(res.body)
            _state.value = _state.value.copy(
                detail = detail ?: _state.value.detail,
                detailLoading = false,
                error = if (detail == null && res.status != 0)
                    "Could not load the task (HTTP ${res.status})." else _state.value.error,
            )
        }
    }

    fun closeTask() {
        _state.value = _state.value.copy(detail = null, detailLoading = false)
    }

    fun postComment(text: String) {
        val id = _state.value.detail?.task?.id ?: return
        val clean = text.trim()
        if (clean.isEmpty() || _state.value.sendingComment) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sendingComment = true)
            val res = repository.kanbanComment(id, clean, _state.value.selectedBoard)
            val ok = res.body?.optBoolean("ok", false) == true
            _state.value = _state.value.copy(
                sendingComment = false,
                actionMessage = if (ok) "Comment posted" else "Could not post the comment",
            )
            if (ok) refreshDetail(id)
        }
    }

    fun act(action: String, extra: JSONObject? = null) {
        val task = _state.value.detail?.task ?: return
        val id = task.id
        if (_state.value.busyTaskId != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busyTaskId = id, actionMessage = null)
            val res = repository.kanbanAction(id, action, extra)
            val body = res.body
            val ok = body?.optBoolean("ok", false) == true && body.optBoolean("applied", true)
            _state.value = _state.value.copy(
                busyTaskId = null,
                actionMessage = if (ok) {
                    when (action) {
                        "unblock" -> "Task unblocked"
                        "retry" -> "Task queued for retry"
                        "approve" -> "Task approved"
                        "archive" -> "Task archived"
                        "delete" -> "Deleted \"${task.title}\""
                        else -> "Task updated"
                    }
                } else body?.optString("error")?.ifBlank { null } ?: "Action failed",
            )
            if (ok) {
                if (action == "delete") {
                    // The task no longer exists — close the sheet before the
                    // detail refresh can fail with a 404.
                    closeTask()
                } else {
                    refreshDetail(id)
                }
                loadBoard(silent = true)
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(actionMessage = null, error = null, createError = null)
    }

    /** Board + mode polling while the screen is visible. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(8_000)
                if (!_state.value.modeLoading && _state.value.mode.enabled) {
                    loadBoard(silent = true)
                    refreshMode()
                } else {
                    refreshMode()
                }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
