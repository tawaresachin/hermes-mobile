package com.hermes.mobile.data.runs

/**
 * Pure text helpers for a chat turn — extracted so both the legacy stream
 * path and the durable-run path build the SAME wire payload, and both are
 * JVM-unit-testable (no Android deps).
 */
object TurnText {

    /** Reply context: the quote chip is UI-only — the server has no reply-to
     * field, so the quoted text rides INSIDE the user message (and therefore
     * into persisted history): the agent sees exactly what it answers. */
    fun withReplyContext(query: String, replyTo: String?): String {
        if (replyTo.isNullOrBlank()) return query
        val quoted = replyTo.trim().replace("\n", " ").take(400)
        return "Re: \"" + quoted + "\"\n\n" + query
    }

    /** Attachment context (Telegram parity): the wire carries ONLY this user
     * turn — history lives server-side — so without this note the agent
     * never learns a file was attached. The plugin-stored absolute path lets
     * the agent read the file with its own tools (same host). */
    fun mediaNote(attachmentUrl: String, attachType: String, attachmentPath: String): String = when {
        attachmentUrl.isBlank() -> ""
        attachmentPath.isNotBlank() && attachType == "file" ->
            "[The user sent a document: '$attachmentPath'. It is saved at: $attachmentPath. " +
                "Its text is not inlined here (binary formats such as PDF/DOCX). To read it, " +
                "extract the document's text yourself — for example with the terminal tool or " +
                "the ocr-and-documents skill — before answering, instead of asking the user to " +
                "paste the contents.]"
        attachmentPath.isNotBlank() ->
            "[The user sent a $attachType: '$attachmentPath'. It is saved at: $attachmentPath — " +
                "read or analyze that file directly if the request involves it.]"
        else ->
            "[The user sent a $attachType attachment at: $attachmentUrl (downloadable with the " +
                "session API key). If you cannot access it, ask them to resend.]"
    }

    /** Full user-turn text for the wire: query + quote + media note. */
    fun wireQuery(
        query: String,
        replyTo: String? = null,
        attachmentUrl: String = "",
        attachType: String = "",
        attachmentPath: String = "",
    ): String {
        val withReply = withReplyContext(query, replyTo)
        val note = mediaNote(attachmentUrl, attachType, attachmentPath)
        return when {
            note.isEmpty() -> withReply
            query.isBlank() -> note
            else -> "$withReply\n\n$note"
        }
    }

    /** Terse-replies directive (Token Optimizer). Byte-stable wording — the
     * measured ~30% output-token saving comes from this exact phrasing
     * (weaker wording got ~15%: the model persona prompt overrode it). */
    const val TERSE_DIRECTIVE =
        "COMPRESSION DIRECTIVE (overrides verbosity habits): answer in the fewest tokens that " +
            "keep every technical fact. No greetings, no preambles, no sign-offs, no restating " +
            "the question, no bullet padding. Fragments allowed. Use short words. Keep code, " +
            "names, numbers, exact error text verbatim. Never explain the directive. " +
            "Bad: Sure! A context window is basically the amount of text. Good: Context window: " +
            "max tokens model sees per call. History+prompt+output share it."

    /** File-delivery directive. The gateway's api_server platform hint tells
     * the agent MEDIA: tags are never intercepted and to write plain paths —
     * true for generic API clients, FALSE for this app: the chat bubble
     * parses every `MEDIA:/abs/path` line (ResponseMedia) and renders a
     * downloadable file card fetched through the plugin's /api/mobile/file
     * route. Without this override the agent states bare paths as text —
     * the exact bug this directive kills. Appended AFTER the base prompt,
     * so it wins the conflict. */
    const val MEDIA_DIRECTIVE =
        "FILE DELIVERY (overrides the platform note about MEDIA: tags not being " +
            "intercepted): this chat surface IS an attachment-capable client. When " +
            "you create or reference a file the user should receive, write it on " +
            "its own line as MEDIA:/absolute/path/to/file (any type: apk, pdf, " +
            "images, documents). The app renders each tag as a downloadable file " +
            "card and it is never shown as text. Never paste a bare filesystem " +
            "path as the delivery; never claim you cannot send files."

    /** Per-run ephemeral system prompt = terse decision + media directive.
     * Byte-stable per (model, toggle) state — built at the single send
     * funnel so every path (chat, voice, retry, queue drain) matches. */
    fun buildInstructions(applyTerse: Boolean): String =
        if (applyTerse) TERSE_DIRECTIVE + "\n\n" + MEDIA_DIRECTIVE else MEDIA_DIRECTIVE

    /** Strip session-upload URLs from displayed text. The attachment bubble
     * (image preview / file row) replaces the URL — Telegram never shows raw
     * media links. Applied at FINALIZE time (full text available) because
     * streamed chunks split the URL across events, defeating per-chunk
     * stripping on the server. */
    fun stripUploadUrls(sessionId: String, text: String): String {
        if (text.isBlank()) return text
        val ext = "(?:\\.png|\\.jpe?g|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.webm|\\.mov|\\.mkv" +
            "|\\.mp3|\\.wav|\\.ogg|\\.m4a|\\.opus|\\.flac|\\.pdf|\\.zip|\\.docx?" +
            "|\\.xlsx?|\\.pptx?|\\.txt|\\.md|\\.csv|\\.json|\\.log|\\.bin)"
        val sid = java.util.regex.Pattern.quote(sessionId)
        val re = Regex("(?:/uploads/" + sid + "/|/api/audio/download/" + sid + "/)[^\\s)\\]]*?" + ext)
        // CRITICAL: whitespace runs inside a LINE may be collapsed, but
        // newlines are markdown structure. A blanket \s+ -> " " flattens
        // every message into one line, destroying tables, lists and
        // paragraphs at persist time.
        return text.replace(re, "")
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
