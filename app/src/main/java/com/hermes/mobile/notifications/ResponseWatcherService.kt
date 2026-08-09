package com.hermes.mobile.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hermes.mobile.MainActivity

/**
 * Foreground service that runs while a response is generating.
 *
 * Two jobs:
 * 1. Raise the process to foreground priority so MIUI/Android don't kill
 *    the app mid-generation when the user backgrounds it.
 * 2. When the stream completes while the app is NOT in the foreground,
 *    post a "Response ready" notification (tap → opens the session).
 *
 * No FCM, no server changes — purely local, powered by the existing
 * stream lifecycle in HermesRepository.sendMessage.
 */
class ResponseWatcherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var currentSession = ""

    /** Live-progress ticker (Cursor-style Live Activities analog): updates
     * the ongoing notification with the elapsed time once per second. */
    private val ticker = object : Runnable {
        override fun run() {
            val secs = (SystemClock.elapsedRealtime() - startedAt) / 1000
            updateNotification(secs)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentSession = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        startedAt = SystemClock.elapsedRealtime()
        startForeground(NOTIF_ID_ONGOING, ongoingNotification(currentSession, 0))
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun updateNotification(elapsedSecs: Long) {
        if (currentSession.isBlank()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_ID_ONGOING, ongoingNotification(currentSession, elapsedSecs))
        } catch (_: Exception) {
            // MIUI/restricted apps can throw on notify — never crash the watcher.
        }
    }

    private fun ongoingNotification(sessionId: String, elapsedSecs: Long): Notification {
        val pi = tapIntent(sessionId)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Hermes is working…")
            .setContentText("Generating your response · ${elapsedSecs}s")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Indeterminate spinner — the user sees live progress.
            .setProgress(0, 0, true)
            .setContentIntent(pi)
            .build()
    }

    private fun tapIntent(sessionId: String): PendingIntent {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "agent_responses"
        const val EXTRA_SESSION_ID = "session_id"
        private const val NOTIF_ID_ONGOING = 1001
        const val NOTIF_ID_READY = 1002

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Agent responses",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = "Notifies when Hermes finishes a response in the background" }
                )
            }
        }

        fun start(context: Context, sessionId: String) {
            if (sessionId.isBlank()) return
            ensureChannel(context)
            val intent = Intent(context, ResponseWatcherService::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stream finished (or failed). Stop the watcher; if the app is
         *  backgrounded, post the "ready" notification for the user. */
        fun notifyReady(context: Context, sessionId: String, preview: String) {
            if (AppForeground.isForeground) return
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)
            val pi = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_SESSION_ID, sessionId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val text = preview.lineSequence().firstOrNull()?.take(120) ?: "Tap to open"
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Hermes response ready")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview.take(400)))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            // Wakes the screen briefly on phones that allow it; never
            // throws when the app is restricted (MIUI battery saver).
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            try {
                if (pm?.isInteractive == false) {
                    pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "hermes:ready")
                        .apply { acquire(1500) }
                }
            } catch (_: Exception) { }
            try {
                NotificationManagerCompat.from(context).notify(NOTIF_ID_READY, notif)
            } catch (_: SecurityException) { }
        }

        /** Drop the foreground service (success, failure, retry, cancel). */
        fun stop(context: Context) {
            context.stopService(Intent(context, ResponseWatcherService::class.java))
        }
    }
}

/** Tracks whether the app is in the foreground (started/stopped
 *  activities) — the notifier only pings when the user is away. */
object AppForeground {
    @Volatile var isForeground: Boolean = true
        private set
    private var started = 0

    fun onActivityStarted() {
        started++
        isForeground = true
    }

    fun onActivityStopped() {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) isForeground = false
    }
}
