package com.hermes.mobile

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal file-backed diagnostic log for on-device debugging.
 * Appends tagged, timestamped lines to filesDir/diag.log (capped at
 * ~200KB, keeping the tail). Shared via Settings → About → Share logs.
 */
object DiagLog {
    private const val MAX_BYTES = 200_000L
    @Volatile private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
        log("APP", "DiagLog initialized")
    }

    fun log(tag: String, msg: String) {
        val c = ctx ?: return
        try {
            val file = File(c.filesDir, "diag.log")
            if (file.length() > MAX_BYTES) {
                val tail = file.readText().takeLast((MAX_BYTES / 2).toInt())
                file.writeText(tail)
            }
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$ts [$tag] $msg\n")
        } catch (_: Exception) {
            // best effort — diagnostics must never crash
        }
    }
}
