package com.hermes.mobile

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-backed diagnostic log with LEVELS + a hard 24-hour retention window.
 *
 * - Levels: ERROR always persisted; WARN/INFO persist when at/above
 *   [MIN_PERSIST_LEVEL]; DEBUG is transient (dropped by default) — the
 *   on-disk log stays small and high-signal.
 * - Prunes lines older than 24h + deletes crash_*.txt older than 24h
 *   (on init and periodically while writing) — storage stays bounded.
 * - Shared via Settings → About → "Share log".
 */
object DiagLog {
    const val DEBUG = 0
    const val INFO = 1
    const val WARN = 2
    const val ERROR = 3

    private const val MAX_BYTES = 200_000L
    private const val RETENTION_MS = 24L * 60 * 60 * 1000
    private const val PRUNE_EVERY = 50 // writes between prunes

    // Persist ERROR always; WARN/INFO only when at/above this threshold.
    // DEBUG lines are dropped entirely (kept out of the file).
    private const val MIN_PERSIST_LEVEL = INFO
    private val TS_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var ctx: Context? = null
    @Volatile private var writesSincePrune = 0

    fun init(context: Context) {
        ctx = context.applicationContext
        prune()
        log(INFO, "APP", "DiagLog initialized (levels: ERROR+always, WARN/INFO by threshold, 24h retention)")
    }

    fun d(tag: String, msg: String) = log(DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(INFO, tag, msg)
    fun w(tag: String, msg: String) = log(WARN, tag, msg)
    fun e(tag: String, msg: String) = log(ERROR, tag, msg)

    private fun log(level: Int, tag: String, msg: String) {
        if (level < MIN_PERSIST_LEVEL) return // DEBUG dropped by default
        val c = ctx ?: return
        try {
            val file = File(c.filesDir, "diag.log")
            val lvl = when (level) {
                ERROR -> "E"
                WARN -> "W"
                else -> "I"
            }
            val ts = TS_FORMAT.format(Date())
            file.appendText("$ts [$lvl][$tag] $msg\n")
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
                val lines = file.readLines()
                val kept = lines.filter { line ->
                    // Lines start with "yyyy-MM-dd HH:mm:ss.SSS [L][TAG] msg"
                    if (line.length < 23) return@filter true // header/partial — keep
                    val ts = try {
                        TS_FORMAT.parse(line.substring(0, 23)).time
                    } catch (_: Exception) {
                        return@filter true // unparseable — keep (don't lose evidence)
                    }
                    ts >= cutoff
                }
                if (kept.size != lines.size) {
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
