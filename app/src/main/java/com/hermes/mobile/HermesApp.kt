package com.hermes.mobile

import android.app.Application
import android.os.Process
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hermes.mobile.network.AuthInterceptor
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Coil image loads (chat attachments under /uploads) must send the same
 * Bearer token as API calls — the server now requires auth on /uploads.
 */
@HiltAndroidApp
class HermesApp : Application(), ImageLoaderFactory {

    @Inject lateinit var authInterceptor: AuthInterceptor
    @Inject lateinit var runController: com.hermes.mobile.data.runs.RunController
    @Inject lateinit var repository: com.hermes.mobile.data.repository.HermesRepository

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        DiagLog.i("APP", "onCreate version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        installCrashHandler()
        // Re-adopt turns that were live when the process died: poll each
        // persisted run id once, finalize completed ones (notification!),
        // re-attach watchers for still-running ones.
        runController.recover()
        syncCrashDumps()
        // Foreground tracking for the response-notification (only ping when
        // the user is NOT looking at the app).
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) =
                com.hermes.mobile.notifications.AppForeground.onActivityStarted()
            override fun onActivityStopped(activity: android.app.Activity) =
                com.hermes.mobile.notifications.AppForeground.onActivityStopped()
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }

    /**
     * Crash dumps are useless sitting on a phone that just died. On every
     * launch, push any unsent crash_*.txt to the gateway diag endpoint
     * (renamed .sent afterwards so each trace ships exactly once, even if
     * the upload lands while offline-then-online).
     */
    private fun syncCrashDumps() {
        kotlinx.coroutines.MainScope().launch {
            try {
                val dir = File(filesDir, "crashes")
                val pending = dir.listFiles { f -> f.name.startsWith("crash_") && !f.name.endsWith(".sent") }
                    ?.sortedBy { it.lastModified() } ?: return@launch
                for (f in pending) {
                    val text = runCatching { f.readText() }.getOrNull() ?: continue
                    val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                    val version = runCatching {
                        packageManager.getPackageInfo(packageName, 0).versionName
                    }.getOrNull() ?: "?"
                    if (repository.uploadDiagLog(device, version, "AUTO-CRASH\n" + text.take(180_000))) {
                        runCatching { f.renameTo(File(f.parentFile, f.name + ".sent")) }
                    }
                }
            } catch (_: Exception) { /* diagnostics must never crash */ }
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                DiagLog.e("CRASH", "thread=${thread.name} ${throwable.javaClass.name}: ${throwable.message}")
                throwable.stackTrace.take(12).forEach { DiagLog.e("CRASH", "  at $it") }
                val crashDir = File(filesDir, "crashes")
                crashDir.mkdirs()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val crashFile = File(crashDir, "crash_$timestamp.txt")
                FileWriter(crashFile).use { writer ->
                    writer.write("=== CRASH REPORT ===\n")
                    writer.write("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
                    writer.write("Thread: ${thread.name}\n")
                    writer.write("Process: ${Process.myPid()}\n\n")
                    writer.write("Stack Trace:\n")
                    throwable.printStackTrace(java.io.PrintWriter(writer))
                }
            } catch (_: Exception) {
                // Best effort — crash handler must never throw
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}