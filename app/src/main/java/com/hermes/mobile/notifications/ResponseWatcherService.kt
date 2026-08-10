package com.hermes.mobile.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.hermes.mobile.MainActivity
import com.hermes.mobile.R

/**
 * Foreground service that runs while a response is generating.
 *
 * Two jobs:
 * 1. Raise the process to foreground priority so MIUI/Android don't kill
 *    the app mid-generation when the user backgrounds it.
 * 2. When the stream completes while the app is NOT in the foreground,
 *    post a "Response ready" notification (tap → opens the session).
 *
 * TELEGRAM-STYLE: the notification shows the Hermes circle logo as the
 * notification avatar and uses MessagingStyle — the user's message and
 * Hermes's reply appear as stacked chat bubbles, exactly like a Telegram
 * notification. No progress bar, no "working…" chrome — just the logo
 * and the message stack.
 *
 * No FCM, no server changes — purely local, powered by the existing
 * stream lifecycle in HermesRepository.sendMessage.
 */
class ResponseWatcherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var currentSession = ""
    private var currentQuery = ""

    /** Live ticker: refreshes the ongoing notification. STOPS the moment a
     * ready notification replaces it — otherwise it would re-post the
     * "is typing…" bubble over the reply (the bug the user caught). */
    private val ticker = object : Runnable {
        override fun run() {
            if (readyPosted) return
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentSession = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        currentQuery = intent?.getStringExtra(EXTRA_QUERY).orEmpty()
        startedAt = SystemClock.elapsedRealtime()
        startForeground(NOTIF_ID_ONGOING, ongoingNotification())
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        // If the ready reply replaced the ongoing notification, keep it
        // visible after the service stops (DETACH = drop foreground status
        // but leave the notification). Otherwise remove it cleanly — a
        // plain stop must not leave a stale "is typing…" bubble.
        try {
            if (readyPosted) {
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    stopForeground(Service.STOP_FOREGROUND_DETACH)
                } else {
                    stopForeground(false)
                }
            } else {
                stopForeground(true)
            }
        } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun updateNotification() {
        if (currentSession.isBlank()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_ID_ONGOING, ongoingNotification())
        } catch (_: Exception) {
            // MIUI/restricted apps can throw on notify — never crash the watcher.
        }
    }

    private fun hermesPerson(): Person {
        val avatar: Bitmap? = try {
            BitmapFactory.decodeResource(resources, R.drawable.hermes_logo_circle)
        } catch (_: Exception) { null }
        return Person.Builder()
            .setName("Hermes")
            .setIcon(if (avatar != null) androidx.core.graphics.drawable.IconCompat.createWithBitmap(avatar) else null)
            .setBot(true)
            .build()
    }

    private fun ongoingNotification(): Notification {
        val pi = tapIntent(currentSession)
        val now = System.currentTimeMillis()
        // Telegram-style: the user's message bubble + "Hermes is typing…"
        // from the Hermes persona. No progress bar, no chrome — just the
        // logo and the message stack (exactly how Telegram renders a chat
        // notification: conversation title, timestamp, stacked bubbles).
        val style = NotificationCompat.MessagingStyle(hermesPerson())
            .setConversationTitle("Hermes")
            .addMessage(
                NotificationCompat.MessagingStyle.Message(
                    if (currentQuery.isNotBlank()) currentQuery else "…",
                    now,
                    "You"
                )
            )
            .addMessage(
                NotificationCompat.MessagingStyle.Message(
                    "is typing…",
                    now + 1,
                    hermesPerson()
                )
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.hermes_notif_icon)
            .setLargeIcon(hermesAvatarBitmap())
            // Telegram: the accent color tints the small icon + time.
            .setColor(0xFF0088CC.toInt())
            .setContentTitle("Hermes")
            // Collapsed summary MUST show the typing state (Telegram shows
            // "Hermes: is typing…" in the shade) — NOT the user's own
            // message, which made the thinking indicator disappear.
            .setContentText("is typing…")
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(now)
            .setShowWhen(true)
            .setContentIntent(pi)
            .build()
    }

    private fun hermesAvatarBitmap(): Bitmap? {
        return try {
            BitmapFactory.decodeResource(resources, R.drawable.hermes_logo_circle)
        } catch (_: Exception) { null }
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
        const val EXTRA_QUERY = "query"
        // ONE id for both states: the ready reply REPLACES the ongoing
        // "is typing…" notification in place. Two ids left the typing
        // notification visible on top of the reply (user bug report).
        private const val NOTIF_ID_ONGOING = 1001

        /** Set the moment a ready notification replaces the ongoing one —
         * the ticker stops re-posting "is typing…" over the reply. */
        @Volatile
        private var readyPosted = false

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

        fun start(context: Context, sessionId: String, query: String = "") {
            if (sessionId.isBlank()) return
            readyPosted = false
            ensureChannel(context)
            val intent = Intent(context, ResponseWatcherService::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_QUERY, query)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stream finished (or failed). Stop the watcher; if the app is
         *  backgrounded, REPLACE the ongoing notification with the reply
         *  (same id — no stale "is typing…" bubble left behind). */
        fun notifyReady(context: Context, sessionId: String, preview: String, query: String = "") {
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
            val avatar: Bitmap? = try {
                BitmapFactory.decodeResource(context.resources, R.drawable.hermes_logo_circle)
            } catch (_: Exception) { null }
            val hermes = Person.Builder()
                .setName("Hermes")
                .setIcon(if (avatar != null) androidx.core.graphics.drawable.IconCompat.createWithBitmap(avatar) else null)
                .setBot(true)
                .build()
            val text = preview.lineSequence().firstOrNull()?.take(120) ?: "Tap to open"
            val now = System.currentTimeMillis()
            // Telegram-style stacked bubbles: the user's message + Hermes's
            // reply, under the Hermes logo + conversation title + timestamp.
            val style = NotificationCompat.MessagingStyle(hermes)
                .setConversationTitle("Hermes")
                .addMessage(
                    NotificationCompat.MessagingStyle.Message(
                        if (query.isNotBlank()) query else "…",
                        now,
                        "You"
                    )
                )
                .addMessage(
                    NotificationCompat.MessagingStyle.Message(
                        text,
                        now + 1,
                        hermes
                    )
                )
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.hermes_notif_icon)
                .setLargeIcon(avatar)
                .setColor(0xFF0088CC.toInt())
                .setContentTitle("Hermes")
                .setContentText(text)
                .setStyle(style)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setWhen(now)
                .setShowWhen(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            // Stop the ticker BEFORE posting the reply so it can't overwrite
            // it, then replace the ongoing notification IN PLACE (same id).
            readyPosted = true
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
                NotificationManagerCompat.from(context).notify(NOTIF_ID_ONGOING, notif)
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
