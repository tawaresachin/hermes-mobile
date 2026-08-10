package com.hermes.mobile.data.repository

import android.content.Context
import com.hermes.mobile.data.local.MessageDao
import com.hermes.mobile.data.local.SessionDao
import com.hermes.mobile.data.model.*
import com.hermes.mobile.network.HermesApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HermesRepository @Inject constructor(
    private val apiService: HermesApiService,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    @ApplicationContext
    private val context: Context
) {
    // ─── Sessions ───

    val allSessions: Flow<List<Session>> = sessionDao.getAllSessions()

    suspend fun createSession(): Session {
        val session = Session(id = UUID.randomUUID().toString())
        sessionDao.upsertSession(session)
        return session
    }

    suspend fun deleteSession(sessionId: String) {
        // Best-effort server-side delete (won't block local if offline)
        apiService.deleteSession(sessionId)
        // Always delete locally
        messageDao.deleteSessionMessages(sessionId)
        sessionDao.deleteSession(sessionId)
    }

    // ─── Messages ───

    fun getMessages(sessionId: String): Flow<List<Message>> = messageDao.getMessages(sessionId)

    /** Latest active session (bottom tabs resume it). */
    suspend fun getLastSession(): Session? = sessionDao.getLastSession()

    /** One-shot snapshot (for delete-undo: keep the messages in memory). */
    suspend fun getMessagesOnce(sessionId: String): List<Message> =
        messageDao.getMessagesOnce(sessionId)

    /** Telegram-style per-message delete (local history only). */
    suspend fun deleteMessage(sessionId: String, msgId: Long) {
        messageDao.deleteMessage(msgId)
    }

    /** Tick a single local message FAILED (a queued message cancelled via
     * its Stop square — the server never saw it). */
    suspend fun markMessageFailed(msgId: Long) {
        messageDao.updateMessageStatus(msgId, MessageStatus.FAILED)
    }

    /** Telegram-style reaction (👍) — stored locally per message. */
    suspend fun setReaction(messageId: Long, reaction: String?) {
        messageDao.updateReaction(messageId, reaction)
    }

    /** Telegram-style forward: send the text as a user message in the
     *  target session (saves it + hands it to the AI in one go). */
    suspend fun forwardMessage(sessionId: String, content: String) {
        sendMessage(sessionId = sessionId, query = content, onChunk = {})
    }

    /** Strip session-upload URLs from displayed text. The attachment bubble
     * (image preview / file row) replaces the URL — Telegram never shows raw
     * media links. Applied at FINALIZE time (full text available) because
     * streamed chunks split the URL across events, defeating per-chunk
     * stripping on the server. */
    private fun stripUploadUrls(sessionId: String, text: String): String {
        if (text.isBlank()) return text
        val ext = "(?:\\.png|\\.jpe?g|\\.gif|\\.webp|\\.bmp|\\.svg|\\.mp4|\\.webm|\\.mov|\\.mkv" +
            "|\\.mp3|\\.wav|\\.ogg|\\.m4a|\\.opus|\\.flac|\\.pdf|\\.zip|\\.docx?|\\.xlsx?" +
            "|\\.pptx?|\\.txt|\\.md|\\.csv|\\.json|\\.log|\\.bin)"
        val re = Regex("/uploads/" + java.util.regex.Pattern.quote(sessionId) + "/[^\\s)\\]]*?" + ext)
        return text.replace(re, "").replace(Regex("\\s+"), " ").trim()
    }

    /** Finalize a streamed turn: strip upload URLs before persisting. */
    private suspend fun finalizeMessage(msgId: Long, sessionId: String, content: String) {
        messageDao.updateMessage(msgId, stripUploadUrls(sessionId, content), false)
    }

    /** Insert the user's message locally (SENDING tick) WITHOUT starting a
     * stream. Used by the Telegram-style queue: when the agent is busy the
     * message shows immediately and its turn starts after the current
     * response completes. Returns the row id (passed back via
     * [sendMessage]'s userMsgId so the turn advances THIS row's ticks). */
    suspend fun insertLocalUserMessage(
        sessionId: String,
        content: String,
        attachmentUrl: String? = null,
        attachType: String? = null,
        replyTo: String? = null
    ): Long {
        val userMsg = Message(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = content,
            attachmentUrl = attachmentUrl?.ifBlank { null },
            attachmentType = attachType?.ifBlank { null },
            replyToText = replyTo?.take(300),
            status = MessageStatus.SENDING
        )
        val id = messageDao.insertMessage(userMsg)
        sessionDao.incrementMessageCount(sessionId)
        return id
    }

    suspend fun sendMessage(
        sessionId: String,
        query: String,
        onChunk: (String) -> Unit,
        onToolCall: (String, String, String) -> Unit = { _, _, _ -> },
        onToolResult: (String, String) -> Unit = { _, _ -> },
        onModelReverted: (String) -> Unit = {},
        onAttachment: (String, String) -> Unit = { _, _ -> },
        onTurnEnd: () -> Unit = {},
        attempt: Int = 1,
        attachmentUrl: String = "",
        attachType: String = "",
        multiAgent: Boolean = false,
        replyTo: String? = null,
        // Pre-inserted row (queued messages) — reuse it for the tick chain
        // instead of creating a duplicate user message.
        userMsgId: Long? = null,
    ): String {
        // Save user message ONLY on first attempt (retries must not duplicate it)
        var userMsgIdFinal: Long? = userMsgId
        if (attempt == 1 && userMsgId == null) {
            userMsgIdFinal = insertLocalUserMessage(
                sessionId, query, attachmentUrl, attachType, replyTo
            )
        } else if (attempt > 1 && userMsgId == null) {
            // Retry: the user row already exists — reuse it so the tick
            // chain (SENT → READ) still advances on the retried attempt.
            userMsgIdFinal = messageDao.getMessagesOnce(sessionId)
                .lastOrNull { it.role == MessageRole.USER }?.id
        }

        // Create placeholder for assistant response
        val assistantMsg = Message(
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true
        )
        val msgId = messageDao.insertMessage(assistantMsg)

        val fullResponse = StringBuilder()
        // Foreground watcher: keeps the process alive while generating and
        // notifies when the answer lands while the app is backgrounded.
        // Start on EVERY attempt (idempotent), stop in the finally below.
        // The query is passed so the notification shows the message stack
        // (Telegram-style: user bubble + Hermes reply under the logo).
        com.hermes.mobile.notifications.ResponseWatcherService.start(context, sessionId, query)
        try {
            try {
                apiService.streamChat(
                query = query,
                sessionId = sessionId,
                onOpen = {
                    // Server accepted + opened the stream → SENT.
                    if (userMsgIdFinal != null) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                messageDao.updateMessageStatus(userMsgIdFinal!!, MessageStatus.SENT)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) { }
                        }
                    }
                },
                onChunk = { chunk ->
                    fullResponse.append(chunk)
                    // First content → the agent is answering → READ.
                    if (fullResponse.length == chunk.length && userMsgIdFinal != null) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                messageDao.updateMessageStatus(userMsgIdFinal!!, MessageStatus.READ)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) { }
                        }
                    }
                    onChunk(chunk)
                },
                onToolCall = onToolCall,
                onToolResult = onToolResult,
                onModelReverted = onModelReverted,
                onAttachment = { url, type ->
                    // In-stream attachment (Telegram: media+caption arrive
                    // together) — apply the image/file to THIS bubble now.
                    // NOTE: store the URL AS-IS (relative /uploads/... path).
                    // stripUploadUrls strips upload paths from TEXT content —
                    // applying it to the URL itself would blank it out and
                    // the bubble would never render (zero /uploads GETs).
                    if (url.isNotBlank() && msgId != null) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                messageDao.updateMessageWithAttachment(
                                    msgId, fullResponse.toString(), true, url, type,
                                    url.substringAfterLast('/').takeIf { it.isNotBlank() }
                                )
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) { }
                        }
                    }
                    onAttachment(url, type)
                },
                onTurnEnd = {
                    // Follow-up turn boundary: persist the accumulated text
                    // into the CURRENT placeholder, open a fresh placeholder
                    // for the next turn, reset the builder (so [DONE] writes
                    // only THIS turn), and tell the VM to clear its live
                    // preview (the finalized bubble now renders from Room).
                    val text = fullResponse.toString()
                    fullResponse.setLength(0)
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            messageDao.updateLastStreamingMessage(sessionId, text)
                            messageDao.insertMessage(
                                Message(
                                    sessionId = sessionId,
                                    role = MessageRole.ASSISTANT,
                                    content = "",
                                    isStreaming = true
                                )
                            )
                            onTurnEnd()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) { }
                    }
                },
                attachmentUrl = attachmentUrl,
                attachType = attachType,
                multiAgent = multiAgent,
                replyTo = replyTo,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Interrupted (Stop button / interrupt mode): the server saved
            // the partial via its CancelledError path + push channel, but
            // THIS placeholder row is still isStreaming=1 in Room. Finalize
            // it with whatever text arrived (or drop it if nothing did) so
            // no blank bubble survives. Then rethrow — cancellation must
            // never write a ghost error message.
            val partial = fullResponse.toString()
            try {
                if (partial.isBlank()) {
                    messageDao.deleteMessage(msgId)
                } else {
                    finalizeMessage(msgId, sessionId, partial)
                }
            } catch (e2: kotlinx.coroutines.CancellationException) {
                throw e2
            } catch (_: Exception) { }
            throw e
        } catch (e: Exception) {
            val errorMsg = e.message ?: ""
            // Transparent retry on 401 — don't save error, don't show in UI
            if (errorMsg.contains("401") && attempt < 2) {
                // Delete the placeholder message we just created
                messageDao.deleteMessage(msgId)
                // Retry silently — user message already saved, so don't re-insert.
                // Named params: a 401 retry must NOT drop the attachment,
                // reply quote or multi-agent flag (positional call lost them).
                return sendMessage(
                    sessionId = sessionId,
                    query = query,
                    onChunk = onChunk,
                    onToolCall = onToolCall,
                    onToolResult = onToolResult,
                    attempt = attempt + 1,
                    attachmentUrl = attachmentUrl,
                    attachType = attachType,
                    multiAgent = multiAgent,
                    replyTo = replyTo,
                )
            }
            // Transient network failure BEFORE any content arrived (drop,
            // idle timeout, connection reset): retry with backoff instead
            // of writing an error bubble. No retry once content started —
            // the resume-repair polls the server for the completed answer.
            val noContentYet = fullResponse.isEmpty()
            val transient = noContentYet && (
                e is java.io.IOException ||
                    errorMsg.contains("timeout", ignoreCase = true) ||
                    errorMsg.contains("idle", ignoreCase = true) ||
                    errorMsg.contains("Connection failed", ignoreCase = true)
                )
            if (transient && attempt < 3) {
                messageDao.deleteMessage(msgId)
                kotlinx.coroutines.delay(1_000L * attempt) // 1s, 2s backoff
                return sendMessage(
                    sessionId = sessionId,
                    query = query,
                    onChunk = onChunk,
                    onToolCall = onToolCall,
                    onToolResult = onToolResult,
                    attempt = attempt + 1,
                    attachmentUrl = attachmentUrl,
                    attachType = attachType,
                    multiAgent = multiAgent,
                    replyTo = replyTo,
                )
            }
            fullResponse.append("⚠️ Connection error: ${e.message}")
            // Tick → FAILED: the send did not complete after retries.
            if (userMsgIdFinal != null) {
                try {
                    messageDao.updateMessageStatus(userMsgIdFinal!!, MessageStatus.FAILED)
                } catch (e2: kotlinx.coroutines.CancellationException) {
                    throw e2
                } catch (_: Exception) { }
            }
        }

        // Finalize message (strip upload URLs — the attachment bubble
        // replaces them, Telegram never shows raw media links)
        finalizeMessage(msgId, sessionId, fullResponse.toString())
        sessionDao.incrementMessageCount(sessionId)

        // Tick → READ once the response COMPLETED. The first-text-chunk
        // hook misses tool-only / reasoning-only / resume-repair responses
        // (no text chunk ever fires), leaving those stuck on the single
        // tick. Any completed turn with real content = read. (The FAILED
        // path already marked FAILED above and starts with the error
        // marker — never override it.)
        if (userMsgIdFinal != null && !fullResponse.startsWith("⚠️ Connection error")) {
            try {
                messageDao.updateMessageStatus(userMsgIdFinal!!, MessageStatus.READ)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { }
        }

            // Success — ping the user if the app is backgrounded.
            if (!fullResponse.startsWith("⚠️ Connection error")) {
                com.hermes.mobile.notifications.ResponseWatcherService.notifyReady(
                    context, sessionId, fullResponse.toString(), query
                )
            }
        } finally {
            // Always drop the watcher: success, failure, retry (the retried
            // attempt restarts it) and cancellation.
            com.hermes.mobile.notifications.ResponseWatcherService.stop(context)
        }

        return fullResponse.toString()
    }

    suspend fun resumeSession(sessionId: String): List<Message> {
        return messageDao.getMessagesOnce(sessionId)
    }

    /** Backfill attachment fields from the server onto local assistant rows
     * that are MISSING them (rows created before in-stream attachments, or
     * after a client update). Telegram keeps media on every bubble forever —
     * a reopen must show it. Matches by content; only patches rows whose
     * attachment fields are empty. Best-effort; never throws. */
    suspend fun backfillAttachments(sessionId: String) {
        try {
            val serverMsgs = apiService.fetchSessionMessages(sessionId) ?: return
            val local = messageDao.getMessagesOnce(sessionId)
            if (local.isEmpty()) return
            for (sm in serverMsgs) {
                if (sm.optString("role") != "assistant") continue
                val url = sm.optString("attachment_url", "")
                if (url.isBlank()) continue
                val type = sm.optString("attachment_type", "")
                val content = sm.optString("content", "")
                // Find the matching local row: same content, no attachment yet.
                val target = local.lastOrNull {
                    it.role == MessageRole.ASSISTANT &&
                        it.attachmentUrl.isNullOrBlank() &&
                        it.content == content
                } ?: continue
                val name = url.substringAfterLast('/').takeIf { it.isNotBlank() }
                messageDao.updateMessageWithAttachment(
                    target.id, content, false, url, type, name
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort — reopening must never fail because of this.
        }
    }

    /** Apply a server response to the local chat (shared by the push
     * subscription and the catch-up poll). Fills a streaming placeholder
     * or inserts the missing assistant response. Idempotent: skips when
     * the local tail already matches. [ts] is the server save time — a
     * STALE response (superseded stream finishing late) is rejected so it
     * can't clobber a newer turn. Returns true when the chat changed. */
    suspend fun applyServerResponse(
        sessionId: String,
        content: String,
        ts: Long = 0,
        attachmentUrl: String = "",
        attachmentType: String = ""
    ): Boolean {
        try {
            if (content.isBlank() && attachmentUrl.isBlank()) return false
            val local = messageDao.getMessagesOnce(sessionId)
            if (local.isEmpty()) return false
            // Anchor to the newest ASSISTANT row — the active turn's bubble.
            // (NOT the newest row overall: a queued user message may sit
            // above it locally while this response was still in flight —
            // inserting after THAT would misalign the conversation, e.g.
            // the answer to "how are you?" landing after "model?"/"date?".)
            val newestAssistant = local.lastOrNull { it.role == MessageRole.ASSISTANT }
            // Stale-guard: the response is older than the turn bubble it
            // would patch (a superseded/late stream) — never overwrite.
            if (newestAssistant != null && ts > 0 && ts < newestAssistant.timestamp) return false
            val attachName = attachmentUrl.substringAfterLast('/').takeIf { it.isNotBlank() }
            if (newestAssistant != null) {
                // Same turn (streaming placeholder, finalized partial, or a
                // completed bubble that the server re-delivered) — patch in
                // place. Inserting would duplicate or misalign the bubble.
                // Attachment fields ride along when the server attached a
                // session upload (image preview / file row instead of a
                // bare /uploads/... link in the text).
                val cleanContent = stripUploadUrls(sessionId, content)
                if (attachmentUrl.isNotBlank()) {
                    messageDao.updateMessageWithAttachment(
                        newestAssistant.id, cleanContent, false, attachmentUrl, attachmentType, attachName
                    )
                } else {
                    messageDao.updateMessage(newestAssistant.id, cleanContent, false)
                }
                // Tick → READ. The STREAM path advances the request's tick
                // on the first chunk; the PUSH/poll path (stream died,
                // response delivered later) must do the same — otherwise
                // the request stays on single ✓ forever even though its
                // answer landed. Tick the user row that owns this turn
                // (the newest user message NOT above the patched bubble).
                local.lastOrNull {
                    it.role == MessageRole.USER && it.timestamp <= newestAssistant.timestamp
                }?.let { user ->
                    messageDao.updateMessageStatus(user.id, MessageStatus.READ)
                }
            } else {
                // No assistant row at all — genuinely new server-side
                // response (detached run answered after the client
                // disconnected) — append it.
                messageDao.insertMessage(
                    Message(
                        sessionId = sessionId,
                        role = MessageRole.ASSISTANT,
                        content = stripUploadUrls(sessionId, content),
                        attachmentUrl = attachmentUrl.ifBlank { null },
                        attachmentType = attachmentType.ifBlank { null },
                        attachmentName = attachName
                    )
                )
            }
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
    }

    /** Poll the server for a response the local stream may have missed
     * (dead SSE connection, detached/backgrounded run). Used as the
     * catch-up + fallback channel; the push subscription is primary. */
    suspend fun pollServerResponse(sessionId: String): Boolean {
        try {
            val serverMsgs = apiService.fetchSessionMessages(sessionId) ?: return false
            val tail = serverMsgs.lastOrNull() ?: return false
            if (tail.optString("role") != "assistant") return false
            return applyServerResponse(
                sessionId,
                tail.optString("content"),
                tail.optLong("timestamp", 0L),
                tail.optString("attachment_url", ""),
                tail.optString("attachment_type", "")
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
    }

    /** Subscribe to the session's push channel (primary response delivery). */
    fun subscribeSessionEvents(
        sessionId: String,
        onResponseReady: (String, Long, String, String) -> Unit,
        onFailure: (Throwable?) -> Unit
    ): okhttp3.sse.EventSource? {
        return apiService.subscribeSessionEvents(sessionId, onResponseReady, onFailure)
    }

    /** INTERRUPT the running agent (Telegram interrupt mode — Stop button). */
    suspend fun cancelChat(sessionId: String): Boolean {
        return apiService.cancelChat(sessionId)
    }

    /**
     * Repair a lost last response. If the session's newest local row is a
     * BLANK assistant message (placeholder left by a stream that died when
     * the user left the chat / the process was killed), the server is still
     * generating it in a detached background task — poll until it lands,
     * then patch the real content in. Best-effort; never throws.
     */
    suspend fun repairBlankAssistantResponse(sessionId: String) {
        try {
            val msgs = messageDao.getMessagesOnce(sessionId)
            val last = msgs.lastOrNull() ?: return
            if (last.role != MessageRole.ASSISTANT || last.content.isNotBlank()) return
            // Poll up to ~3 min: the server's detached generation may still
            // be running (it saves the response when finished).
            repeat(90) { attempt ->
                val serverMsgs = apiService.fetchSessionMessages(sessionId) ?: return
                val tail = serverMsgs.lastOrNull()
                if (tail != null && tail.optString("role") == "assistant") {
                    // ONLY the server's NEWEST message counts. If it's a
                    // non-blank response, THIS turn's answer has landed —
                    // patch it in. Taking any older non-blank assistant row
                    // would patch STALE content (duplicate/wrong bubble).
                    if (tail.optString("content").isNotBlank()) {
                        messageDao.updateMessage(last.id, tail.optString("content"), false)
                        // The recovered response means the turn completed —
                        // the user's tick must advance to READ (it was stuck
                        // on SENT because the stream died before any chunk).
                        messageDao.getMessagesOnce(sessionId)
                            .lastOrNull { it.role == MessageRole.USER }
                            ?.let { user ->
                                messageDao.updateMessageStatus(user.id, MessageStatus.READ)
                            }
                    } else {
                        // Genuinely empty turn — drop the dead bubble.
                        messageDao.deleteMessage(last.id)
                    }
                    return
                }
                // Server's newest is still the user's query — generation in
                // progress, wait and retry.
                if (attempt < 89) delay(2000)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort repair — never crash the resume path.
        }
    }

    /** Mark stale isStreaming=1 rows as finalized (process died mid-stream). */
    suspend fun finalizeStaleStreaming(sessionId: String) {
        messageDao.finalizeStaleStreaming(sessionId)
    }

    suspend fun restoreSession(session: Session, messages: List<Message> = emptyList()) {
        sessionDao.upsertSession(session)
        // Undo must bring the CONVERSATION back too — re-inserting only the
        // session row resurrects an empty chat with all history gone.
        messages.forEach { messageDao.insertMessage(it) }
    }

    /** Local-only delete (no server call). Used as fallback. */
    suspend fun deleteSessionLocal(sessionId: String) {
        messageDao.deleteSessionMessages(sessionId)
        sessionDao.deleteSession(sessionId)
    }

    /** Rename a session (local-only metadata change). */
    suspend fun renameSession(sessionId: String, title: String) {
        sessionDao.renameSession(sessionId, title.trim().ifBlank { "Untitled Session" })
    }

    suspend fun clearSession(sessionId: String) {
        messageDao.deleteSessionMessages(sessionId)
    }

    suspend fun finalizePendingMessage(sessionId: String, content: String) {
        // Update the last streaming assistant message with final content
        messageDao.updateLastStreamingMessage(sessionId, content)
    }

    /** Drop stale isStreaming placeholders (no text produced before cancel). */
    suspend fun deletePendingMessage(sessionId: String) {
        messageDao.deleteStreamingPlaceholders(sessionId)
    }

    // ─── File Upload ───

    suspend fun uploadFile(file: java.io.File, fileName: String, mimeType: String): String? {
        return apiService.uploadFile(file, fileName, mimeType)
    }

    /** Upload the on-device diag log to the bridge (stored under STORE_PATH/logs/). */
    suspend fun uploadDiagLog(device: String, version: String, log: String): Boolean {
        return apiService.uploadDiagLog(device, version, log)
    }

    // ─── Keep Computer Awake (platform-generic) ───

    suspend fun fetchSystemStatus(): HermesApiService.SystemStatus? {
        return apiService.getSystemStatus()
    }

    suspend fun setKeepAwake(awake: Boolean): String? {
        return apiService.setSystemAwake(awake)
    }

    // ─── Follow-ups to a running agent (Cursor-style) ───

    suspend fun sendFollowUp(sessionId: String, query: String): Boolean {
        return apiService.sendFollowUp(sessionId, query)
    }

    /** Local user bubble for a queued follow-up (the server persists its
     * own copy as the backup; the local row drives the UI). */
    suspend fun insertLocalUserMessage(sessionId: String, content: String) {
        messageDao.insertMessage(
            Message(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = content,
                status = MessageStatus.SENT
            )
        )
        sessionDao.incrementMessageCount(sessionId)
    }

    // ─── Session status/source badges (server truth) ───

    suspend fun fetchServerSessionStatus(): Map<String, Pair<String, String>> {
        return apiService.fetchServerSessionStatus()
    }

    // ─── Server Connection ───

    fun saveConfig(config: ServerConfig) {
        apiService.updateConfig(config)
    }

    fun getSavedConfig(): ServerConfig? = apiService.getConfig()

    fun getBaseUrl(): String = apiService.getBaseUrl()

    // ─── Device account (auto-registered on QR pairing) ───
    fun saveDeviceCredentials(email: String, password: String) {
        apiService.saveDeviceCredentials(email, password)
    }

    fun getDeviceCredentials(): Pair<String, String>? = apiService.getDeviceCredentials()

    suspend fun checkConnection(config: ServerConfig): ConnectionStatus {
        return try {
            if (apiService.healthCheck(config)) ConnectionStatus.CONNECTED
            else ConnectionStatus.ERROR
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionStatus.ERROR
        }
    }

    suspend fun checkConnectionRaw(config: ServerConfig): Boolean {
        return apiService.healthCheck(config)
    }

    // ─── Model Management ───

    suspend fun listModels(sessionId: String): ModelListResponse? {
        return apiService.listModels(sessionId)
    }

    suspend fun switchModel(sessionId: String, modelName: String, global: Boolean = false): Boolean {
        return apiService.switchModel(sessionId, modelName, global)
    }

    // ─── Dark Theme ───

    fun saveDarkTheme(isDark: Boolean) {
        apiService.saveDarkTheme(isDark)
    }

    fun isDarkTheme(): Boolean = apiService.isDarkTheme()

    fun hasDarkThemePreference(): Boolean = apiService.hasDarkThemePreference()

    /** Expose SharedPreferences for reactive observation. */
    fun prefs(): android.content.SharedPreferences = apiService.prefs()

    // ─── Text-to-Speech ───

    suspend fun textToSpeech(text: String, voice: String = "en-IN-NeerjaNeural"): ByteArray? {
        return apiService.textToSpeech(text, voice)
    }

    /** Whisper STT via the bridge (null → caller falls back to system). */
    suspend fun transcribeAudio(wav: ByteArray, lang: String? = null): String? {
        return apiService.transcribeAudio(wav, lang)
    }
}
