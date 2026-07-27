package com.hermes.mobile

import android.app.Application
import android.os.Process
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class HermesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
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
