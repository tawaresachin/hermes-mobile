package com.hermes.mobile.voice

/**
 * JARVIS-style echo rejection/salvage with FUZZY matching.
 * Echo survives STT mis-transcription (e.g. "explores"→"laws") because
 * words are matched by Levenshtein similarity, not exact equality.
 * - Pure echo (≥70% of reference words matched) → returns "" (skip).
 * - Echo prefix + user speech appended → returns the user's tail.
 * - No match → returns the input unchanged.
 * (Transcript-level backstop — kept untouched on top of the new
 * low-latency echo-safety layers in the barge-in path.)
 */
object EchoRejection {

    fun stripEchoPrefix(text: String, ttsText: String): String {
        if (ttsText.isBlank() || text.isBlank()) return text
        val tWords = cleanWords(text)
        val rWords = cleanWords(ttsText)
        if (rWords.isEmpty() || tWords.isEmpty()) return text

        // Walk both lists; count reference words matched within tolerance.
        // Allow skipping reference words so a dropped/inserted word doesn't
        // kill the whole match.
        var matched = 0
        var i = 0          // index into tWords
        var j = 0          // index into rWords
        var consumed = 0   // transcript words eaten by the echo
        while (i < tWords.size && j < rWords.size) {
            if (wordSimilarity(tWords[i], rWords[j]) >= 0.8f) {
                matched++
                consumed = i + 1
                i++
                j++
            } else {
                j++   // skip one reference word (transcription drift)
            }
        }
        if (matched == 0) return text

        val overlap = matched.toFloat() / rWords.size
        if (overlap >= 0.7f) {
            val tail = tWords.drop(consumed).joinToString(" ").trim()
            return if (tail.length >= 3) tail else ""   // salvage or reject
        }
        return text
    }

    /** Lowercase + strip punctuation for comparison. */
    private fun cleanWords(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s\u0900-\u097F\u0C80-\u0CFF\u0B80-\u0BFF\u0D00-\u0DFF\u0A00-\u0A7F\u0B00-\u0B7F\u0C00-\u0C7F\u0D00-\u0D7F\u0B80-\u0BFF]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    /** Levenshtein-based similarity in [0,1]. */
    private fun wordSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val maxLen = maxOf(a.length, b.length)
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return 1f - prev[b.length].toFloat() / maxLen
    }
}
