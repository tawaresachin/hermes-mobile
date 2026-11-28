package com.hermes.mobile.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hermes.mobile.MainActivity
import com.hermes.mobile.R
import com.hermes.mobile.data.repository.HermesRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Background watcher for the agent-side Kanban board.
 *
 * The gateway's dispatcher moves tasks through triage → ready → running →
 * blocked/review → done on its own. While the app is away the user still needs
 * to know when a task needs a decision (blocked / review requested / failed)
 * or when it completes. The agent's dashboard plugin offers a WebSocket, but
 * the mobile plugin exposes a cursor-based events route, so this service polls
 * it every 20 s with a persisted high-water mark and posts local notifications.
 *
 * Started only while Kanban mode is enabled, and only from a foreground
 * context (Settings toggle / Kanban screen). A background-start denial is
 * swallowed; the next foreground sync retries.
 */
@AndroidEntryPoint
class KanbanWatcherService : Service() {

    @Inject lateinit var repository: HermesRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels()
        startForeground(NOTIF_ID_WATCH, watchNotification())
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        stopForeground(true)
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        // First run baselines the cursor so a fresh install never replays the
        // whole board history as notifications.
        var cursor = prefs.getLong(KEY_CURSOR, -1L)
        while (scope.isActive) {
            try {
                val res = repository.kanbanEvents(
                    since = cursor.coerceAtLeast(0L),
                    board = prefs.getString(KEY_BOARD, null),
                )
                val body = res.body
                if (body != null && body.optBoolean("ok", false)) {
                    val events = body.optJSONArray("events") ?: JSONArray()
                    if (cursor < 0) {
                        // Baseline: latest is the high-water mark; notify nothing.
                        cursor = body.optLong("latest", 0L)
                        prefs.edit().putLong(KEY_CURSOR, cursor).apply()
                    } else {
                        var latestSeen = cursor
                        for (i in 0 until events.length()) {
                            val e = events.optJSONObject(i) ?: continue
                            val id = e.optLong("id", 0L)
                            if (id <= cursor) continue
                            latestSeen = maxOf(latestSeen, id)
                            notifyForEvent(e)
                        }
                        cursor = maxOf(latestSeen, body.optLong("cursor", latestSeen))
                        prefs.edit().putLong(KEY_CURSOR, cursor).apply()
                    }
                }
            } catch (_: Exception) {
                // Offline / gateway restart: retry on the next tick.
            }
            delay(POLL_MS)
        }
    }

    private fun notifyForEvent(event: JSONObject) {
        val kind = event.optString("kind")
        val (title, body, action) = when (kind) {
            "blocked" -> Triple(
                "Kanban task needs you",
                event.optString("task_title").ifBlank { "A task is blocked" } +
                    reasonSuffix(event),
                true,
            )
            "review_requested" -> Triple(
                "Kanban review requested",
                event.optString("task_title").ifBlank { "A task is ready for review" },
                true,
            )
            "block_loop_detected" -> Triple(
                "Kanban task is stuck",
                event.optString("task_title").ifBlank { "A task keeps re-blocking" } +
                    reasonSuffix(event),
                true,
            )
            "gave_up", "crashed", "timed_out" -> Triple(
                "Kanban task failed",
                event.optString("task_title").ifBlank { "A task stopped" } +
                    reasonSuffix(event),
                true,
            )
            "completed" -> Triple(
                "Kanban task completed",
                event.optString("task_title").ifBlank { "A task finished" },
                false,
            )
            else -> return
        }
        val taskId = event.optString("task_id")
        if (taskId.isBlank()) return
        val id = (event.optLong("id", 0L) % 100_000L).toInt()
        val notif = NotificationCompat.Builder(this, CHANNEL_ACTIVITY)
            .setSmallIcon(R.drawable.hermes_logo_circle)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(taskIntent(taskId))
            .setPriority(
                if (action) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_EVENT_BASE + id, notif)
        }
    }

    private fun reasonSuffix(event: JSONObject): String {
        val payload = event.optJSONObject("payload")
        val reason = payload?.optString("reason")?.trim().orEmpty()
            .ifBlank { payload?.optString("error")?.trim().orEmpty() }
        return if (reason.isNotBlank()) "\n$reason" else ""
    }

    private fun taskIntent(taskId: String): PendingIntent {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_KANBAN_TASK, taskId)
        }
        return PendingIntent.getActivity(
            this,
            taskId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun watchNotification() = NotificationCompat.Builder(this, CHANNEL_WATCH)
        .setSmallIcon(R.drawable.hermes_logo_circle)
        .setContentTitle("Hermes Kanban")
        .setContentText("Watching for task updates")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(taskIntent(""))
        .build()

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_WATCH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WATCH, "Kanban watcher",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Keeps the Kanban task watcher alive while backgrounded" }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ACTIVITY) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ACTIVITY, "Kanban task updates",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Tasks that need action or have completed" }
            )
        }
    }

    companion object {
        private const val PREFS = "kanban_watch"
        private const val KEY_CURSOR = "cursor"
        private const val KEY_BOARD = "board"
        private const val ENABLED = "enabled"
        private const val CHANNEL_WATCH = "kanban_watch"
        private const val CHANNEL_ACTIVITY = "kanban_updates"
        private const val NOTIF_ID_WATCH = 2001
        private const val NOTIF_ID_EVENT_BASE = 2100
        private const val POLL_MS = 20_000L

        /** Start or stop the watcher to match Kanban mode + login state. */
        fun sync(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(ENABLED, enabled).apply()
            if (enabled) start(context) else stop(context)
        }

        fun start(context: Context) {
            val intent = Intent(context, KanbanWatcherService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KanbanWatcherService::class.java)) }
        }

        /** Called from HermesApp when the app comes to the foreground. */
        fun reconcile(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(ENABLED, false)) start(context)
        }

        /** Remember which board the watcher should follow. */
        fun setBoard(context: Context, board: String?) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_BOARD, board).putLong(KEY_CURSOR, -1L).apply()
        }

        fun resetCursor(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_CURSOR, -1L).apply()
        }
    }
}
