package com.hermes.mobile.wake

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hermes.mobile.MainActivity
import com.hermes.mobile.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Foreground microphone service that owns the on-device wake detector. */
class WakeWordService : Service() {

    private val controller = WakeWordRunController()
    private val handler = Handler(Looper.getMainLooper())
    // One thread owns the recorder and the TFLite interpreter. processFrame()
    // and close() must never overlap: the native interpreter is not thread-safe.
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hermes-wake-capture")
    }
    @Volatile private var captureSeq = 0
    @Volatile private var captureClosed = true
    @Volatile private var capturePending = false
    private var recorder: AudioRecord? = null
    private var detector: WakeWordDetector? = null
    private var audioManager: AudioManager? = null
    private var vibrator: Vibrator? = null
    private var receiverRegistered = false
    private var deviceCallbackRegistered = false
    // Turning wake off mid-turn must not cut off the spoken reply. The mic is
    // already paused for the turn; finish it, then tear the service down.
    private var stopAfterTurn = false

    // Passive listening never asks for audio focus. A phone playing music or
    // holding focus from another assistant must still hear "Hey Hermes".
    private var retryRunnable: Runnable? = null
    private var activeConfig: WakeCaptureConfig.CaptureConfig? = null
    private var rms = 0
    private var fires = 0
    private var lastError = "—"
    private var diagnosticsRevision = 0L
    private var heardYouUntil = 0L
    // Detector scores survive re-arm so the phrase that fired stays visible.
    private val scoreWindow = java.util.ArrayDeque<ScoreSample>()

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioManager.ACTION_HEADSET_PLUG -> onRouteChanged()
                Intent.ACTION_SCREEN_OFF -> onScreenOn(false)
                Intent.ACTION_SCREEN_ON -> onScreenOn(true)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onRouteChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onRouteChanged()
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(
            this, deviceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager?.registerAudioDeviceCallback(deviceCallback, handler)
            deviceCallbackRegistered = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP -> {
                WakeWordPrefs.setEnabled(this, false)
                WakeWordBus.publishEnabled(false)
                // The alert belongs to the master switch, not to the in-progress
                // turn — remove it even if teardown is deferred.
                cancelWakeAlert(this)
                if (controller.currentState() == WakeRunState.WaitingForTurn) {
                    stopAfterTurn = true
                    // Bounded fallback: a logout mid-turn can dispose ChatScreen
                    // before its re-arm callback lands. Never linger as an orphan.
                    handler.postDelayed({ if (stopAfterTurn) stopSelf() }, 30_000L)
                    updateNotification()
                } else {
                    stopSelf()
                }
            }
            ACTION_PAUSE -> pause(reasonFrom(intent))
            ACTION_RESUME -> resume(reasonFrom(intent))
            ACTION_REARM -> rearm()
            ACTION_VOICE_ACTIVE -> setVoiceActive(intent.getBooleanExtra(EXTRA_ACTIVE, false))
            ACTION_APP_FOREGROUND -> setAppForeground(intent.getBooleanExtra(EXTRA_ACTIVE, true))
            ACTION_UPDATE_SENSITIVITY -> restartCapture()
            ACTION_SIMULATE_WAKE -> onWakeDetected()
            ACTION_TEST_MIC -> testMic()
        }
        // A killed app must not leave a resurrected microphone notification.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancelRetry()
        controller.stop()
        stopCaptureLocked()
        // Lets the queued close finish before the thread is reclaimed.
        captureExecutor.shutdown()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(deviceReceiver) }
            receiverRegistered = false
        }
        if (deviceCallbackRegistered) {
            runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
            deviceCallbackRegistered = false
        }
        WakeWordBus.publishEnabled(false)
        publishDiagnostics()
        cancelWakeAlert(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startListening() {
        if (!hasMicrophone() || !hasNotifications()) {
            disableAndStop()
            return
        }
        if (!WakeWordPrefs.isEnabled(this)) {
            stopSelf()
            return
        }
        postForeground(notification("Listening"))
        if (controller.currentState() == WakeRunState.Idle) controller.start()
        if (controller.currentState() == WakeRunState.Listening) startCaptureLocked()
        updateNotification()
    }

    private fun pause(reason: WakePauseReason) {
        if (controller.currentState() == WakeRunState.Idle) return
        controller.pause(reason)
        cancelRetry()
        stopCaptureLocked()
        updateNotification()
    }

    private fun resume(reason: WakePauseReason) {
        val state = controller.resume(reason)
        if (state == WakeRunState.Listening) startCaptureLocked()
        updateNotification()
    }

    private fun rearm() {
        // Disable mid-turn defers teardown until here; disabling while idle
        // is handled by ACTION_STOP. Either way, never re-arm a disabled mic.
        if (stopAfterTurn || !WakeWordPrefs.isEnabled(this)) {
            stopSelf()
            return
        }
        if (controller.turnFinished() == WakeRunState.Listening) startCaptureLocked()
        updateNotification()
    }

    private fun setVoiceActive(active: Boolean) {
        if (active) pause(WakePauseReason.VoiceScreen) else resume(WakePauseReason.VoiceScreen)
    }

    private fun setAppForeground(foreground: Boolean) {
        // A permission revoked in Settings while the app was away takes
        // effect on return: stop cleanly instead of leaving a dead mic alert.
        if (foreground && (!hasMicrophone() || !hasNotifications())) {
            disableAndStop()
            return
        }
        // One toggle means always armed: the foreground service keeps the mic
        // when the app leaves the foreground. No background pref to honour.
        if (WakeWordPrefs.isEnabled(this)) resume(WakePauseReason.AppBackground)
    }

    private fun onScreenOn(@Suppress("UNUSED_PARAMETER") on: Boolean) {
        // Keep listening across screen-off too; the single toggle owns it.
        if (WakeWordPrefs.isEnabled(this)) resume(WakePauseReason.ScreenOff)
    }

    private fun onRouteChanged() {
        if (controller.currentState() == WakeRunState.Idle) return
        pause(WakePauseReason.RouteChange)
        handler.postDelayed({
            if (WakeWordPrefs.isEnabled(this)) resume(WakePauseReason.RouteChange)
        }, 500L)
    }

    private fun restartCapture() {
        if (controller.currentState() != WakeRunState.Listening) return
        // start() is queued behind close() on the capture thread, so no two
        // AudioRecords or interpreters can be live at once.
        stopCaptureLocked(restartAfterStop = true)
    }

    private data class Opened(
        val recorder: AudioRecord,
        val config: WakeCaptureConfig.CaptureConfig,
        val rms: Int,
    )

    private data class OpenResult(val opened: Opened?, val attempts: String)

    private data class ScoreSample(val at: Long, val score: Float)

    /**
     * Try every source/rate in order and keep the first that records real
     * samples. A phone that rejects 16 kHz or returns silence from MIC falls
     * through to the next configuration instead of pausing silently.
     */
    private fun openCapture(probeSamples: Int): OpenResult {
        val attempts = mutableListOf<String>()
        for (config in WakeCaptureConfig.ORDERED) {
            val min = AudioRecord.getMinBufferSize(
                config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (min <= 0) {
                attempts += "${config.label}: rate unavailable"
                continue
            }
            val frameSamples = WakeCaptureConfig.sourceFrameSize(config.sampleRate)
            val rec = try {
                AudioRecord(
                    config.source,
                    config.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(min, frameSamples * 2),
                )
            } catch (t: Throwable) {
                attempts += "${config.label}: ${t.javaClass.simpleName}"
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { rec.release() }
                attempts += "${config.label}: not initialized"
                continue
            }
            val started = runCatching { rec.startRecording(); true }.getOrDefault(false)
            if (!started) {
                runCatching { rec.release() }
                attempts += "${config.label}: start failed"
                continue
            }
            val probe = ShortArray(min(probeSamples, frameSamples))
            var read = 0
            var tries = 0
            while (read <= 0 && tries < 5) {
                read = runCatching { rec.read(probe, 0, probe.size) }.getOrDefault(-1)
                tries++
            }
            if (read <= 0) {
                runCatching { rec.release() }
                attempts += "${config.label}: no data"
                continue
            }
            if (!WakeCaptureConfig.hasSignal(probe, read)) {
                runCatching { rec.release() }
                attempts += "${config.label}: silent"
                continue
            }
            return OpenResult(Opened(rec, config, WakeCaptureConfig.rms(probe, read)), attempts.joinToString(" · "))
        }
        return OpenResult(
            null,
            attempts.joinToString(" · ").ifBlank { "no usable microphone configuration" },
        )
    }

    private fun isCurrentCapture(seq: Int) = !captureClosed && seq == captureSeq

    /** A stale run must not clear the pending flag of a newer run. */
    private fun clearPending(seq: Int) {
        if (captureSeq == seq) capturePending = false
    }

    @Synchronized
    private fun startCaptureLocked() {
        if (recorder != null || detector != null || capturePending) return
        if (controller.currentState() != WakeRunState.Listening) return
        cancelRetry()
        if (!hasMicrophone()) {
            disableAndStop()
            return
        }
        if (captureExecutor.isShutdown) return
        val seq = ++captureSeq
        captureClosed = false
        capturePending = true
        // Probe, detector init and the read loop all run on this one thread.
        captureExecutor.execute { openAndRun(seq) }
    }

    private fun openAndRun(seq: Int) {
        val result = openCapture(PROBE_SAMPLES)
        val opened = result.opened
        if (opened == null) {
            if (isCurrentCapture(seq)) {
                lastError = result.attempts
                publishDiagnostics()
                clearPending(seq)
                handler.post { if (isCurrentCapture(seq)) scheduleRetry() }
            }
            return
        }
        if (!isCurrentCapture(seq)) {
            // Stop arrived while probing: never keep an orphan AudioRecord.
            runCatching { opened.recorder.release() }
            return
        }
        val det = try {
            WakeWordDetector(
                assets,
                onFire = { handler.post { onWakeDetected() } },
                sensitivity = WakeWordPrefs.sensitivity(this@WakeWordService),
            )
        } catch (t: Throwable) {
            runCatching { opened.recorder.release() }
            if (isCurrentCapture(seq)) {
                lastError = "Wake detector failed: ${t.javaClass.simpleName}"
                publishDiagnostics()
                clearPending(seq)
                handler.post { if (isCurrentCapture(seq)) scheduleRetry() }
            }
            return
        }
        var accepted = false
        synchronized(this@WakeWordService) {
            if (isCurrentCapture(seq) && recorder == null && detector == null &&
                controller.currentState() == WakeRunState.Listening && hasMicrophone()
            ) {
                recorder = opened.recorder
                detector = det
                activeConfig = opened.config
                rms = opened.rms
                lastError = "—"
                accepted = true
                publishDiagnostics()
            }
        }
        if (!accepted) {
            runCatching { opened.recorder.release() }
            runCatching { det.close() }
            clearPending(seq)
            return
        }
        readLoop(seq, opened, det)
    }

    /** Runs on the capture thread; never call this from anywhere else. */
    private fun readLoop(seq: Int, opened: Opened, det: WakeWordDetector) {
        val source = ShortArray(WakeCaptureConfig.sourceFrameSize(opened.config.sampleRate))
        val frame = ShortArray(WakeCaptureConfig.FRAME_SIZE)
        val needsResample = opened.config.sampleRate != WakeCaptureConfig.TARGET_RATE
        var filled = 0
        var ticks = 0
        try {
            while (isCurrentCapture(seq) && controller.currentState() == WakeRunState.Listening &&
                recorder === opened.recorder
            ) {
                val read = runCatching {
                    opened.recorder.read(source, filled, source.size - filled)
                }.getOrDefault(-1)
                if (read < 0) {
                    if (isCurrentCapture(seq)) lastError = "Microphone read failed"
                    break
                }
                if (read > 0) {
                    filled += read
                    if (filled == source.size) {
                        filled = 0
                        rms = WakeCaptureConfig.rms(source, source.size)
                        if (needsResample) {
                            WakeCaptureConfig.resampleFrame(source, source.size, frame)
                        } else {
                            System.arraycopy(source, 0, frame, 0, frame.size)
                        }
                        // close() may have been requested while we were reading.
                        if (!isCurrentCapture(seq)) break
                        runCatching { det.processFrame(frame) }
                        recordScore(det.lastScore)
                        // ~4 updates a second (three 80 ms frames).
                        ticks++
                        if (ticks >= 3) {
                            ticks = 0
                            publishDiagnostics()
                        }
                    }
                }
            }
        } finally {
            clearPending(seq)
        }
        if (isCurrentCapture(seq) && controller.currentState() == WakeRunState.Listening &&
            recorder === opened.recorder
        ) {
            handler.post {
                if (isCurrentCapture(seq) && controller.currentState() == WakeRunState.Listening &&
                    recorder === opened.recorder
                ) {
                    stopCaptureLocked()
                    scheduleRetry()
                }
            }
        }
    }

    private fun scheduleRetry() {
        cancelRetry()
        if (!WakeWordPrefs.isEnabled(this) || controller.currentState() != WakeRunState.Listening) return
        val retry = Runnable {
            retryRunnable = null
            if (WakeWordPrefs.isEnabled(this) && controller.currentState() == WakeRunState.Listening) {
                startCaptureLocked()
            }
        }
        retryRunnable = retry
        handler.postDelayed(retry, RETRY_MS)
    }

    private fun cancelRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
    }

    /**
     * Signals the capture thread to stop and queues the native close behind it.
     * The queued task runs only after any in-flight readLoop/processFrame returns.
     */
    @Synchronized
    private fun stopCaptureLocked(restartAfterStop: Boolean = false) {
        captureSeq++
        captureClosed = true
        capturePending = false
        cancelRetry()
        // Unblock a waiting AudioRecord.read() without releasing it here; the
        // queued task owns release() and detector.close().
        recorder?.let { rec ->
            runCatching {
                if (rec.state == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            }
        }
        if (captureExecutor.isShutdown) return
        captureExecutor.execute {
            synchronized(this@WakeWordService) {
                recorder?.let { rec -> runCatching { rec.release() } }
                recorder = null
                detector?.let { runCatching { it.close() } }
                detector = null
                activeConfig = null
                rms = 0
            }
            publishDiagnostics()
            if (restartAfterStop && !stopAfterTurn && WakeWordPrefs.isEnabled(this) &&
                controller.currentState() == WakeRunState.Listening
            ) {
                startCaptureLocked()
            }
        }
    }

    @Synchronized
    private fun recordScore(score: Float) {
        if (score <= 0f) return
        val now = System.currentTimeMillis()
        scoreWindow.addLast(ScoreSample(now, score))
        trimScoreWindow(now)
    }

    @Synchronized
    private fun rollingMaxScore(): Float {
        val now = System.currentTimeMillis()
        trimScoreWindow(now)
        var maxScore = 0f
        for (sample in scoreWindow) {
            if (sample.score > maxScore) maxScore = sample.score
        }
        return maxScore
    }

    private fun trimScoreWindow(now: Long) {
        while (scoreWindow.isNotEmpty() && now - scoreWindow.first().at > SCORE_WINDOW_MS) {
            scoreWindow.removeFirst()
        }
    }

    private fun publishDiagnostics() {
        diagnosticsRevision++
        val state = when (controller.currentState()) {
            WakeRunState.Listening -> if (lastError == "—") "Listening" else "Error — retrying"
            WakeRunState.Paused -> "Paused"
            WakeRunState.WaitingForTurn -> "Heard you"
            WakeRunState.Idle -> "Off"
        }
        WakeWordBus.publishDiagnostics(
            WakeDiagnostics(
                state = state,
                source = activeConfig?.label ?: "—",
                rms = rms,
                maxScore = rollingMaxScore(),
                fires = fires,
                lastError = lastError,
                revision = diagnosticsRevision,
            )
        )
    }

    /** "Test mic" in diagnostics: reuse the live recorder, else probe on the capture thread. */
    private fun testMic() {
        val liveLabel = activeConfig?.label
        if (recorder != null && liveLabel != null) {
            lastError = "Mic test OK — $liveLabel, RMS $rms"
            publishDiagnostics()
            return
        }
        if (captureExecutor.isShutdown) return
        val seq = captureSeq
        captureExecutor.execute {
            // Never open a second AudioRecord while capture is starting/stopping.
            if (seq != captureSeq || capturePending) return@execute
            val result = openCapture(PROBE_SAMPLES)
            val opened = result.opened
            if (opened != null) {
                runCatching {
                    if (opened.recorder.state == AudioRecord.RECORDSTATE_RECORDING) opened.recorder.stop()
                    opened.recorder.release()
                }
                lastError = "Mic test OK — ${opened.config.label}, RMS ${opened.rms}"
            } else {
                lastError = "Mic test failed — ${result.attempts}"
            }
            publishDiagnostics()
        }
    }

    private fun onWakeDetected() {
        if (!WakeWordPrefs.isEnabled(this)) {
            stopSelf()
            return
        }
        if (!controller.onDetected()) return
        stopCaptureLocked()
        fires++
        playChime()
        vibrate()
        heardYouUntil = System.currentTimeMillis() + HEARD_YOU_MS
        updateNotification()
        handler.postDelayed({ updateNotification() }, HEARD_YOU_MS)
        WakeWordBus.emitWake()
        publishDiagnostics()
    }

    private fun playChime() {
        if (!WakeWordPrefs.wakeSoundEnabled(this)) return
        val ringer = audioManager?.ringerMode
        if (ringer == AudioManager.RINGER_MODE_SILENT || ringer == AudioManager.RINGER_MODE_VIBRATE) return
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            handler.postDelayed({ runCatching { tone.release() } }, 250L)
        }
    }

    private fun vibrate() {
        runCatching {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(VIBRATE_MS)
            }
        }
    }

    private fun disableAndStop() {
        WakeWordPrefs.setEnabled(this, false)
        WakeWordBus.publishEnabled(false)
        stopSelf()
    }

    private fun hasMicrophone() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications() =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Hey Hermes wake word",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Persistent control for on-device wake-word listening" }
            )
        }
        if (nm.getNotificationChannel(WAKE_ALERT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    WAKE_ALERT_CHANNEL_ID,
                    "Hey Hermes is listening",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Wake alert that opens Chat when Hermes hears you" }
            )
        }
    }

    private fun notification(status: String): android.app.Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, actionIntent(this, ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.hermes_logo_circle)
            .setContentTitle("Listening for \"Hey Hermes\"")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(R.drawable.hermes_logo_circle, "Stop", stopPi)
            .build()
    }

    private fun postForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val status = if (System.currentTimeMillis() < heardYouUntil) {
            "Heard you — listening…"
        } else {
            when (controller.currentState()) {
                WakeRunState.Listening -> "Tap Stop to turn off wake word"
                WakeRunState.Paused -> "Paused"
                WakeRunState.WaitingForTurn -> "Heard you — listening for your command"
                WakeRunState.Idle -> "Stopping…"
            }
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification(status))
    }

    private fun reasonFrom(intent: Intent): WakePauseReason =
        runCatching {
            WakePauseReason.valueOf(intent.getStringExtra(EXTRA_REASON) ?: WakePauseReason.VoiceScreen.name)
        }.getOrDefault(WakePauseReason.VoiceScreen)

    companion object {
        private const val ACTION_START = "com.hermes.mobile.wake.START"
        private const val ACTION_STOP = "com.hermes.mobile.wake.STOP"
        private const val ACTION_PAUSE = "com.hermes.mobile.wake.PAUSE"
        private const val ACTION_RESUME = "com.hermes.mobile.wake.RESUME"
        private const val ACTION_REARM = "com.hermes.mobile.wake.REARM"
        private const val ACTION_VOICE_ACTIVE = "com.hermes.mobile.wake.VOICE_ACTIVE"
        private const val ACTION_APP_FOREGROUND = "com.hermes.mobile.wake.APP_FOREGROUND"
        private const val ACTION_UPDATE_SENSITIVITY = "com.hermes.mobile.wake.SENSITIVITY"
        private const val ACTION_SIMULATE_WAKE = "com.hermes.mobile.wake.SIMULATE_WAKE"
        private const val ACTION_TEST_MIC = "com.hermes.mobile.wake.TEST_MIC"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_ACTIVE = "active"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 1101
        private const val WAKE_ALERT_CHANNEL_ID = "wake_alert"
        private const val WAKE_ALERT_ID = 1102
        private const val RETRY_MS = 5_000L
        private const val PROBE_SAMPLES = 512
        private const val VIBRATE_MS = 90L
        private const val HEARD_YOU_MS = 3_000L
        private const val SCORE_WINDOW_MS = 10_000L

        private fun actionIntent(context: Context, action: String) =
            Intent(context, WakeWordService::class.java).setAction(action)

        fun start(context: Context) {
            WakeWordPrefs.setEnabled(context, true)
            WakeWordBus.publishEnabled(true)
            ContextCompat.startForegroundService(context, actionIntent(context, ACTION_START))
        }

        fun stop(context: Context) {
            WakeWordPrefs.setEnabled(context, false)
            WakeWordBus.publishEnabled(false)
            context.startService(actionIntent(context, ACTION_STOP))
        }

        fun pause(context: Context, reason: WakePauseReason) {
            if (!WakeWordPrefs.isEnabled(context)) return
            context.startService(
                actionIntent(context, ACTION_PAUSE).putExtra(EXTRA_REASON, reason.name)
            )
        }

        fun resume(context: Context, reason: WakePauseReason) {
            if (!WakeWordPrefs.isEnabled(context)) return
            context.startService(
                actionIntent(context, ACTION_RESUME).putExtra(EXTRA_REASON, reason.name)
            )
        }

        fun rearm(context: Context) {
            // Always deliver: a disable issued mid-turn needs this to trigger
            // the deferred stop. The service decides whether to re-arm or stop.
            context.startService(actionIntent(context, ACTION_REARM))
        }

        fun updateSensitivity(context: Context) {
            if (WakeWordPrefs.isEnabled(context)) {
                context.startService(actionIntent(context, ACTION_UPDATE_SENSITIVITY))
            }
        }

        fun setVoiceActive(context: Context, active: Boolean) {
            if (WakeWordPrefs.isEnabled(context)) {
                context.startService(actionIntent(context, ACTION_VOICE_ACTIVE).putExtra(EXTRA_ACTIVE, active))
            }
        }

        fun setAppForeground(context: Context, foreground: Boolean) {
            if (WakeWordPrefs.isEnabled(context)) {
                context.startService(actionIntent(context, ACTION_APP_FOREGROUND).putExtra(EXTRA_ACTIVE, foreground))
            }
        }

        /** Restore an enabled setting on app start without starting from the background. */
        fun reconcile(context: Context) {
            if (!WakeWordPrefs.isEnabled(context)) return
            val mic = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!mic || !notifications) {
                WakeWordPrefs.setEnabled(context, false)
                WakeWordBus.publishEnabled(false)
                return
            }
            WakeWordBus.publishEnabled(true)
            ContextCompat.startForegroundService(context, actionIntent(context, ACTION_START))
        }

        fun simulateWake(context: Context) {
            if (WakeWordPrefs.isEnabled(context)) {
                context.startService(actionIntent(context, ACTION_SIMULATE_WAKE))
            }
        }

        fun testMic(context: Context) {
            context.startService(actionIntent(context, ACTION_TEST_MIC))
        }

        /**
         * Raise the wake alert directly from the coordinator — no service
         * start, so it works while the app is in the background. The wake
         * extra hands straight off to dictation; the plain tap is the
         * fallback when Android refuses the background activity start.
         */
        fun postWakeAlert(context: Context) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) return
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_WAKE, true)
            }
            val pi = PendingIntent.getActivity(
                context, 2, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alert = NotificationCompat.Builder(context, WAKE_ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.hermes_logo_circle)
                .setContentTitle("Hey Hermes — listening")
                .setContentText("Tap to speak your command")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory("assistant")
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Ensure the high-importance channel exists even if the service
            // was never created in this process (cold start, background).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(WAKE_ALERT_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        WAKE_ALERT_CHANNEL_ID,
                        "Hey Hermes is listening",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = "Wake alert that opens Chat when Hermes hears you" }
                )
            }
            nm.notify(WAKE_ALERT_ID, alert)
        }

        fun cancelWakeAlert(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(WAKE_ALERT_ID)
        }
    }
}
