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
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hermes.mobile.MainActivity
import com.hermes.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** Foreground microphone service that owns the on-device wake detector. */
class WakeWordService : Service() {

    private val controller = WakeWordRunController()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var recorder: AudioRecord? = null
    private var detector: WakeWordDetector? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusGranted = false
    private var receiverRegistered = false
    private var deviceCallbackRegistered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> resume(WakePauseReason.AudioFocus)
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                if (controller.currentState() == WakeRunState.Listening) pause(WakePauseReason.AudioFocus)
        }
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        }
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
                stopSelf()
            }
            ACTION_PAUSE -> pause(reasonFrom(intent))
            ACTION_RESUME -> resume(reasonFrom(intent))
            ACTION_REARM -> rearm()
            ACTION_VOICE_ACTIVE -> setVoiceActive(intent.getBooleanExtra(EXTRA_ACTIVE, false))
            ACTION_APP_FOREGROUND -> setAppForeground(intent.getBooleanExtra(EXTRA_ACTIVE, true))
            ACTION_UPDATE_SENSITIVITY -> restartCapture()
            ACTION_SIMULATE_WAKE -> onWakeDetected()
        }
        // A killed app must not leave a resurrected microphone notification.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller.stop()
        stopCaptureLocked()
        abandonFocus()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(deviceReceiver) }
            receiverRegistered = false
        }
        if (deviceCallbackRegistered) {
            runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
            deviceCallbackRegistered = false
        }
        WakeWordBus.publishEnabled(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    private fun startListening() {
        if (!hasMicrophone() || !hasNotifications()) {
            WakeWordPrefs.setEnabled(this, false)
            WakeWordBus.publishEnabled(false)
            stopSelf()
            return
        }
        if (!WakeWordPrefs.isEnabled(this)) {
            stopSelf()
            return
        }
        postForeground(notification("Listening"))
        if (controller.currentState() == WakeRunState.Idle) controller.start()
        if (controller.currentState() == WakeRunState.Listening) {
            requestFocus()
            startCaptureLocked()
        }
        updateNotification()
    }

    private fun pause(reason: WakePauseReason) {
        if (controller.currentState() == WakeRunState.Idle) return
        controller.pause(reason)
        stopCaptureLocked()
        updateNotification()
    }

    private fun resume(reason: WakePauseReason) {
        val state = controller.resume(reason)
        if (state == WakeRunState.Listening) {
            requestFocus()
            if (focusGranted) startCaptureLocked() else controller.pause(WakePauseReason.AudioFocus)
        }
        updateNotification()
    }

    private fun rearm() {
        if (controller.turnFinished() == WakeRunState.Listening) {
            requestFocus()
            if (focusGranted) startCaptureLocked() else controller.pause(WakePauseReason.AudioFocus)
        }
        updateNotification()
    }

    private fun setVoiceActive(active: Boolean) {
        if (active) pause(WakePauseReason.VoiceScreen) else resume(WakePauseReason.VoiceScreen)
    }

    private fun setAppForeground(foreground: Boolean) {
        if (foreground || WakeWordPrefs.backgroundListening(this)) {
            resume(WakePauseReason.AppBackground)
        } else {
            // Background listening is opt-in. Stop entirely so no mic or
            // notification survives the app leaving the foreground.
            stopSelf()
        }
    }

    private fun onScreenOn(on: Boolean) {
        if (WakeWordPrefs.backgroundListening(this) || on) {
            resume(WakePauseReason.ScreenOff)
        } else {
            pause(WakePauseReason.ScreenOff)
        }
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
        stopCaptureLocked()
        startCaptureLocked()
    }

    @Synchronized
    private fun startCaptureLocked() {
        if (recorder != null || detector != null) return
        if (controller.currentState() != WakeRunState.Listening) return
        if (!hasMicrophone()) {
            controller.pause(WakePauseReason.MicrophoneUnavailable)
            WakeWordPrefs.setEnabled(this, false)
            WakeWordBus.publishEnabled(false)
            stopSelf()
            return
        }
        val min = AudioRecord.getMinBufferSize(
            WakeWordDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) {
            controller.pause(WakePauseReason.MicrophoneUnavailable)
            updateNotification()
            return
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WakeWordDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(min, WakeWordDetector.SAMPLES_PER_CHUNK * 2),
            )
        } catch (_: SecurityException) {
            controller.pause(WakePauseReason.MicrophoneUnavailable)
            stopSelf()
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            controller.pause(WakePauseReason.MicrophoneUnavailable)
            updateNotification()
            return
        }
        val det = try {
            WakeWordDetector(
                assets,
                onFire = { handler.post { onWakeDetected() } },
                sensitivity = WakeWordPrefs.sensitivity(this),
            )
        } catch (_: Exception) {
            rec.release()
            controller.pause(WakePauseReason.MicrophoneUnavailable)
            updateNotification()
            return
        }
        recorder = rec
        detector = det
        try {
            rec.startRecording()
        } catch (_: Exception) {
            stopCaptureLocked()
            controller.pause(WakePauseReason.MicrophoneUnavailable)
            updateNotification()
            return
        }
        captureJob = scope.launch {
            val frame = ShortArray(WakeWordDetector.SAMPLES_PER_CHUNK)
            var filled = 0
            while (isActive && controller.currentState() == WakeRunState.Listening) {
                val read = rec.read(frame, filled, frame.size - filled)
                if (read < 0) break
                if (read > 0) {
                    filled += read
                    if (filled == frame.size) {
                        filled = 0
                        runCatching { det.processFrame(frame) }
                    }
                }
            }
        }
    }

    @Synchronized
    private fun stopCaptureLocked() {
        captureJob?.cancel()
        captureJob = null
        recorder?.let { rec ->
            runCatching {
                if (rec.state == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            }
            runCatching { rec.release() }
        }
        recorder = null
        detector?.let { runCatching { it.close() } }
        detector = null
    }

    private fun onWakeDetected() {
        if (!WakeWordPrefs.isEnabled(this)) {
            stopSelf()
            return
        }
        if (!controller.onDetected()) return
        stopCaptureLocked()
        playChime()
        WakeWordBus.emitWake()
        updateNotification()
    }

    private fun playChime() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            handler.postDelayed({ runCatching { tone.release() } }, 250L)
        }
    }

    private fun requestFocus() {
        if (focusGranted) return
        val am = audioManager ?: return
        focusGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focusRequest ?: return
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (!focusGranted) return
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focusRequest ?: return
            am.abandonAudioFocusRequest(req)
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
        focusGranted = false
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
            .setContentTitle("Listening for Hey Hermes")
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
        val status = when (controller.currentState()) {
            WakeRunState.Listening -> "Tap Stop to turn off wake word"
            WakeRunState.Paused -> "Paused"
            WakeRunState.WaitingForTurn -> "Heard you — listening for your command"
            WakeRunState.Idle -> "Stopping…"
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
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_ACTIVE = "active"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 1101

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
            if (WakeWordPrefs.isEnabled(context)) {
                context.startService(actionIntent(context, ACTION_REARM))
            }
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
    }
}
