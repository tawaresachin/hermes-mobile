package com.hermes.mobile.ui.chat

import java.net.URLEncoder
import java.util.Locale

/**
 * Response-side media extraction — the read twin of Telegram's MEDIA:
 * delivery. The agent ends turns with `MEDIA:/abs/path.ext` (any file) and
 * the api_server inlines `![alt](data:image/...;base64,...)` for small
 * images. The chat wire carries that text verbatim, so without this the app
 * shows raw paths or a base64 wall instead of a tappable attachment.
 *
 * - data: image   -> attachment rendered straight from the URL (Coil loads it)
 * - MEDIA: file   -> attachment downloaded through the plugin's
 *                     /api/mobile/file route (guarded host-side by the same
 *                     validate_media_delivery_path denylist Telegram uses)
 * Tokens inside ``` fences are left as literal text (they are examples).
 */
data class ExtractedMedia(val cleanText: String, val url: String?, val type: String?, val name: String?)

object ResponseMedia {
    // MEDIA:/abs/path.ext — path stops at whitespace/backtick/closing bracket.
    private val MEDIA_RE = Regex("""MEDIA:(/[^\s)`\]]+\.[A-Za-z0-9]{1,6})""")
    // ![alt](data:mime;base64,....) — the inlined-image form api_server emits.
    private val DATA_IMG_RE = Regex("""!\[[^\]]*\]\((data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+)\)""")

    private val MIME_BY_EXT: Map<String, String> = mapOf(
        "pdf" to "application/pdf", "zip" to "application/zip",
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "json" to "application/json", "log" to "text/plain",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp",
        "mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "m4a" to "audio/mp4",
    )

    private fun mimeFor(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MIME_BY_EXT[ext] ?: "application/octet-stream"
    }

    /** Strip media tokens (outside code fences) from [text], line-based —
     * fences only ever start/end a line, and so do MEDIA tags. */
    private fun scrub(text: String): String =
        text.lines().let { lines ->
            var fenced = false
            val kept = lines.filterNot { line ->
                val t = line.trim()
                if (t.startsWith("```")) { fenced = !fenced; return@filterNot false }
                !fenced && (MEDIA_RE.containsMatchIn(line) || DATA_IMG_RE.containsMatchIn(line))
            }
            kept.joinToString("\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }

    /** Pull the media out of an assistant reply: returns display text with
     * every token removed plus one attachment ref (data image preferred —
     * self-contained; else the first MEDIA file). */
    fun extract(text: String): ExtractedMedia {
        if (text.isBlank()) return ExtractedMedia(text, null, null, null)
        DATA_IMG_RE.find(text)?.let { m ->
            val url = m.groupValues[1]
            return ExtractedMedia(
                cleanText = scrub(text),
                url = url,
                type = url.substringAfter("data:").substringBefore(";"),
                name = "image",
            )
        }
        val media = MEDIA_RE.findAll(text).map { it.groupValues[1] }.toList()
        if (media.isEmpty()) return ExtractedMedia(text, null, null, null)
        val first = media.first()
        val url = "/api/mobile/file?path=" + URLEncoder.encode(first, "UTF-8")
        return ExtractedMedia(
            cleanText = scrub(text),
            url = url,
            type = mimeFor(first),
            name = first.substringAfterLast('/'),
        )
    }
}
