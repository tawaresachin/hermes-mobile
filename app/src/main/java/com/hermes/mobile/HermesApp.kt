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
import javax.inject.Inject

/**
 * Coil image loads (chat attachments under /uploads) must send the same
 * Bearer token as API calls — the server now requires auth on /uploads.
 */
@HiltAndroidApp
class HermesApp : Application(), ImageLoaderFactory {

    @Inject lateinit var authInterceptor: AuthInterceptor

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        DiagLog.i("APP", "onCreate version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        installCrashHandler()
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