package com.hermes.mobile.ui.screens.kanban

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/** A sub-task linked under a parent task, with its own stage (status). */
@Immutable
data class KanbanSubtask(
    val id: String,
    val title: String,
    val status: String,
    val assignee: String,
)

/** Immutable mobile view of one kanban card (board endpoint). */
@Immutable
data class KanbanTaskCard(
    val id: String,
    val title: String,
    val bodyPreview: String,
    val status: String,
    val assignee: String,
    val priority: Int,
    val createdAt: Long,
    val completedAt: Long,
    val latestSummary: String,
    val blockKind: String?,
    val commentCount: Int,
    val progressDone: Int,
    val progressTotal: Int,
    val currentRunStartedAt: Long,
    val subtasks: List<KanbanSubtask> = emptyList(),
)

@Immutable
data class KanbanColumn(
    val key: String,
    val label: String,
    val icon: String,
    val tasks: List<KanbanTaskCard>,
)

@Immutable
data class KanbanBoard(
    val board: String,
    val boards: List<String>,
    val columns: List<KanbanColumn>,
    val assignees: List<String>,
    val latestEventId: Long,
    val now: Long,
) {
    val totalTasks: Int get() = columns.sumOf { it.tasks.size }
}

@Immutable
data class KanbanMode(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val dispatchInGateway: Boolean = false,
    val restartRequired: Boolean = false,
    val explicit: Boolean = false,
    val boards: List<String> = emptyList(),
    val error: String? = null,
)

@Immutable
data class KanbanComment(
    val id: Long,
    val author: String,
    val body: String,
    val createdAt: Long,
)

@Immutable
data class KanbanRun(
    val id: Long,
    val status: String,
    val outcome: String,
    val summary: String,
    val error: String,
    val startedAt: Long,
    val endedAt: Long,
)

@Immutable
data class KanbanEventLine(
    val id: Long,
    val kind: String,
    val createdAt: Long,
)

@Immutable
data class KanbanTaskDetail(
    val task: KanbanTaskCard,
    val body: String,
    val result: String,
    val comments: List<KanbanComment>,
    val events: List<KanbanEventLine>,
    val runs: List<KanbanRun>,
)

private fun JSONObject.str(key: String): String {
    val v = opt(key) ?: return ""
    return if (v == JSONObject.NULL) "" else v.toString()
}

private fun JSONObject.long(key: String): Long = optLong(key, 0L).coerceAtLeast(0L)

internal fun parseKanbanMode(body: JSONObject?): KanbanMode {
    if (body == null) return KanbanMode(available = false, error = "No response from the server")
    val ok = body.optBoolean("ok", false)
    val boards = body.optJSONArray("boards")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val b = arr.optJSONObject(i)
            b?.optString("slug")?.takeIf { it.isNotBlank() }
        }
    } ?: emptyList()
    return KanbanMode(
        available = ok && body.optBoolean("available", true),
        enabled = body.optBoolean("enabled", false),
        dispatchInGateway = body.optBoolean("dispatch_in_gateway", false),
        restartRequired = body.optBoolean("restart_required", false),
        explicit = body.optBoolean("explicit", false),
        boards = boards,
        error = if (ok) null else body.str("error").ifBlank { "Kanban is not available" },
    )
}

private fun parseTaskCard(json: JSONObject): KanbanTaskCard? {
    val id = json.str("id").ifBlank { return null }
    val progress = json.optJSONObject("progress")
    return KanbanTaskCard(
        id = id,
        title = json.str("title").ifBlank { "Untitled task" },
        bodyPreview = json.str("body_preview").ifBlank { json.str("latest_summary") }.take(280),
        status = json.str("status").ifBlank { "todo" },
        assignee = json.str("assignee"),
        priority = json.optInt("priority", 0),
        createdAt = json.long("created_at"),
        completedAt = json.long("completed_at"),
        latestSummary = json.str("latest_summary"),
        blockKind = json.str("block_kind").ifBlank { null },
        commentCount = json.optInt("comment_count", 0),
        progressDone = progress?.optInt("done", 0) ?: 0,
        progressTotal = progress?.optInt("total", 0) ?: 0,
        currentRunStartedAt = json.long("current_run_started_at"),
        subtasks = json.optJSONArray("children")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i)?.str("id")?.ifBlank { null } ?: return@mapNotNull null
                val obj = arr.optJSONObject(i)!!
                KanbanSubtask(
                    id = c,
                    title = obj.str("title").ifBlank { "Untitled" },
                    status = obj.str("status").ifBlank { "todo" },
                    assignee = obj.str("assignee"),
                )
            }
        } ?: emptyList(),
    )
}

internal fun parseKanbanBoard(body: JSONObject?): KanbanBoard? {
    if (body == null || !body.optBoolean("ok", false)) return null
    val columns = body.optJSONArray("columns")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = c.str("key").ifBlank { return@mapNotNull null }
            val tasks = c.optJSONArray("tasks")?.let { list ->
                (0 until list.length()).mapNotNull { j ->
                    list.optJSONObject(j)?.let(::parseTaskCard)
                }
            } ?: emptyList()
            KanbanColumn(
                key = key,
                label = c.str("label").ifBlank { key.replaceFirstChar { it.uppercase() } },
                icon = c.str("icon"),
                tasks = tasks,
            )
        }
    } ?: emptyList()
    val boards = body.optJSONArray("boards")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
    } ?: emptyList()
    val assignees = body.optJSONArray("assignees")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
    } ?: emptyList()
    return KanbanBoard(
        board = body.str("board").ifBlank { "default" },
        boards = boards,
        columns = columns,
        assignees = assignees,
        latestEventId = body.long("latest_event_id"),
        now = body.long("now"),
    )
}

internal fun parseKanbanTaskDetail(body: JSONObject?): KanbanTaskDetail? {
    if (body == null || !body.optBoolean("ok", false)) return null
    val taskJson = body.optJSONObject("task") ?: return null
    val card = parseTaskCard(taskJson) ?: return null
    val comments = body.optJSONArray("comments")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            KanbanComment(
                id = c.long("id"),
                author = c.str("author"),
                body = c.str("body"),
                createdAt = c.long("created_at"),
            )
        }
    } ?: emptyList()
    val events = body.optJSONArray("events")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            KanbanEventLine(id = e.long("id"), kind = e.str("kind"), createdAt = e.long("created_at"))
        }
    } ?: emptyList()
    val runs = body.optJSONArray("runs")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            KanbanRun(
                id = r.long("id"),
                status = r.str("status"),
                outcome = r.str("outcome"),
                summary = r.str("summary"),
                error = r.str("error"),
                startedAt = r.long("started_at"),
                endedAt = r.long("ended_at"),
            )
        }
    } ?: emptyList()
    return KanbanTaskDetail(
        task = card,
        body = taskJson.str("body"),
        result = taskJson.str("result"),
        comments = comments,
        events = events,
        runs = runs,
    )
}

/** Human label for a status key, used where the server column isn't handy. */
internal fun kanbanStatusLabel(status: String): String = when (status) {
    "triage" -> "Triage"
    "todo" -> "Todo"
    "scheduled" -> "Scheduled"
    "ready" -> "Ready"
    "running" -> "Running"
    "blocked" -> "Blocked"
    "review" -> "Review"
    "done" -> "Done"
    "archived" -> "Archived"
    else -> status.replaceFirstChar { it.uppercase() }
}
