package com.hermes.mobile

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-backed diagnostic log with a hard 24-hour retention window.
 *
 * - Appends tagged, timestamped lines to filesDir/diag.log
 * - Prunes lines older than 24h + deletes crash_*.txt older than 24h
 *   (on init and periodically while writing) — storage stays bounded,
 *   and the shared log always covers the last day of activity.
 * - Shared via Settings → About → "Share log".
 */
object DiagLog {
    private const val MAX_BYTES = 200_000L
    private const val RETENTION_MS = 24L * 60 * 60 * 1000
    private const val PRUNE_EVERY = 50 // writes between prunes
    private val TS_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var ctx: Context? = null
    @Volatile private var writesSincePrune = 0

    fun init(context: Context) {
        ctx = context.applicationContext
        prune()
        log("APP", "DiagLog initialized (24h retention)")
    }

    fun log(tag: String, msg: String) {
        val c = ctx ?: return
        try {
            val file = File(c.filesDir, "diag.log")
            val ts = TS_FORMAT.format(Date())
            file.appendText("$ts [$tag] $msg\n")
            writesSincePrune++
            if (file.length() > MAX_BYTES || writesSincePrune >= PRUNE_EVERY) {
                prune()
            }
        } catch (_: Exception) {
            // best effort — diagnostics must never crash
        }
    }

    /**
     * Keep only the last 24h of activity:
     * 1) diag.log lines older than 24h are dropped
     * 2) crash_*.txt files older than 24h are deleted
     */
    private fun prune() {
        writesSincePrune = 0
        val c = ctx ?: return
        try {
            val now = System.currentTimeMillis()
            val file = File(c.filesDir, "diag.log")
            if (file.exists()) {
                val cutoff = now - RETENTION_MS
                val kept = file.readLines().filter { line ->
                    // Lines start with "yyyy-MM-dd HH:mm:ss.SSS [TAG] msg"
                    if (line.length < 23) return@filter true // header/partial — keep
                    val ts = try {
                        TS_FORMAT.parse(line.substring(0, 23)).time
                    } catch (_: Exception) {
                        return@filter true // unparseable — keep (don't lose evidence)
                    }
                    ts >= cutoff
                }
                if (kept.size != file.readLines().size) {
                    file.writeText(kept.joinToString("\n") + if (kept.isNotEmpty()) "\n" else "")
                }
            }
            // Crash dumps older than 24h
            val crashDir = File(c.filesDir, "crashes")
            crashDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("crash_") && f.lastModified() < now - RETENTION_MS) {
                    f.delete()
                }
            }
        } catch (_: Exception) {
            // best effort
        }
    }
}
