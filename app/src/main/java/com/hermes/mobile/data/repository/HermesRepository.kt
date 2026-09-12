package com.hermes.mobile.data.repository

import android.content.Context
import com.hermes.mobile.data.local.MessageDao
import com.hermes.mobile.data.local.SessionDao
import com.hermes.mobile.data.model.*
import com.hermes.mobile.network.HermesApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HermesRepository @Inject constructor(
    private val apiService: HermesApiService,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val runControllerRef: com.hermes.mobile.data.runs.RunController,
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
        // Best-effort server-side delete (won't block local if offline).
        // The server knows the session by its OWN id (api_…) from the
        // continuity map — deleting by local UUID was a silent 404 no-op
        // that left the session alive on the gateway.
        val serverId = apiService.serverIdFor(sessionId)?.takeIf { it.isNotBlank() }
        if (serverId != null) apiService.deleteSession(serverId)
        // Always delete locally
        messageDao.deleteSessionMessages(sessionId)
        sessionDao.deleteSession(sessionId)
        purgeSessionKeys(sessionId)
    }

    /** Toggle archive on a session: local flag first (instant UI), then the
     * server PATCH best-effort — the desktop sidebar shares the same flag. */
    suspend fun setSessionArchived(sessionId: String, archived: Boolean) {
        sessionDao.setArchived(sessionId, archived)
        apiService.serverIdFor(sessionId)?.takeIf { it.isNotBlank() }?.let { sid ->
            try { apiService.setSessionArchived(sid, archived) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* offline: local flag stands; desktop syncs its own */ }
        }
    }

    /** Remove prefs state owned by a deleted session (continuity map,
     * model pin, draft) so deletes leave zero orphans behind. */
    private fun purgeSessionKeys(sessionId: String) {
        apiService.forgetSessionKeys(sessionId)
        com.hermes.mobile.data.local.DraftStore.clear(sessionId)
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

    /** Edit a user message (update content + editedAt timestamp). */
    suspend fun editMessage(messageId: Long, newContent: String) {
        messageDao.updateMessageEdit(messageId, newContent, System.currentTimeMillis())
    }

    /** Restore a message after an Undo — re-insert the exact row (same id
     * via REPLACE), keeping the conversation position intact. */
    suspend fun restoreMessage(message: Message) {
        messageDao.insertMessage(message)  // REPLACE on same primary key
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

    /** Strip session-upload URLs from displayed text — delegates to the
     * shared TurnText implementation (one rule, both turn paths). */
    private fun stripUploadUrls(sessionId: String, text: String): String =
        com.hermes.mobile.data.runs.TurnText.stripUploadUrls(sessionId, text)

    /** Insert the user's message locally (SENDING tick) WITHOUT starting a
     * stream. Used by the Telegram-style queue: when the agent is busy the
     * message shows immediately and its turn starts after the current
     * response completes. Returns the row id (passed back via
     * RunController.startTurn's userMsgId so the turn advances THIS row's
     * ticks). */
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
    /** Last server message_count per session (catch-up poll cheap check).
     * The full transcript is a 0.5-5 MB download; polling it every 5s on a
     * Tailscale link throttled the gateway and the UI ("goes in loop").
     * Poll /api/sessions/{id} (~1 KB) instead; refetch only on change. */
    private val lastSeenMsgCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    suspend fun pollServerResponse(sessionId: String): Boolean {
        try {
            val count = apiService.fetchSessionMessageCount(sessionId)
            if (count != null) {
                val prev = lastSeenMsgCount[sessionId]
                if (prev != null && count == prev) return false
                // First sight of a session: adopt the count WITHOUT fetching
                // the transcript (local rows already came via resumeSession)
                // unless a response is actually pending.
                lastSeenMsgCount[sessionId] = count
                if (prev == null) return false
            }
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

    /** Forget the cheap-check baseline (session deleted/forked). */
    fun forgetPollBaseline(sessionId: String) { lastSeenMsgCount.remove(sessionId) }

    /** Server-truth slash command list (same source as the Telegram menu). */
    suspend fun fetchServerCommands() = apiService.fetchServerCommands()

    /** Hermes update state on the host (plugin /api/mobile/update/check). */
    suspend fun updateCheck(fresh: Boolean = false) = apiService.updateCheck(fresh)
    suspend fun updateVersion() = apiService.updateVersion()
    suspend fun updateApply() = apiService.updateApply()

    /** Expand a skill slash command via the server (Telegram-parity
     * injection); null = not a skill command. */
    suspend fun resolveSkillCommand(command: String, args: String): String? =
        apiService.resolveSkillCommand(command, args)

    // ─── Durable runs (the /v1/runs engine the chat screen uses) ───

    val runController: com.hermes.mobile.data.runs.RunController
        get() = runControllerRef

    // ─── Session fork (branch this chat from here) ───

    /** Server-side fork: copies the transcript into a NEW session id, then
     * mirrors it locally (rows + model pin + continuity map). Returns the
     * new local session id, or null when the server rejected the fork. */
    suspend fun forkSession(sourceSessionId: String): String? {
        val serverId = apiService.serverIdFor(sourceSessionId)?.takeIf { it.isNotBlank() }
            ?: sourceSessionId
        val newLocalId = UUID.randomUUID().toString()
        val title = sessionDao.getSessionById(sourceSessionId)?.title
        val newServerId = apiService.forkSession(serverId, newLocalId,
            title = "${title ?: "chat"} fork") ?: return null
        val source = sessionDao.getSessionById(sourceSessionId) ?: return null
        sessionDao.upsertSession(source.copy(id = newLocalId, title = title?.plus(" (fork)"),
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
        // Copy local rows so the fork opens instantly with full history
        // (the server transcript is the source of truth going forward).
        messageDao.getMessagesOnce(sourceSessionId).forEach {
            messageDao.insertMessage(it.copy(id = 0, sessionId = newLocalId))
        }
        apiService.saveServerId(newLocalId, newServerId)
        apiService.savedModelForSession(sourceSessionId)?.let {
            apiService.saveModelForSession(newLocalId, it, apiService.savedModelSlugForSession(sourceSessionId))
        }
        return newLocalId
    }

    // ─── Cron jobs + skills (Settings screens, server truth) ───

    suspend fun listJobs(): JSONArray? = apiService.listJobs()
    suspend fun jobAction(jobId: String, action: String): Boolean = apiService.jobAction(jobId, action)
    suspend fun listSkills(): JSONArray? = apiService.listSkills()

    /** Auto-approve: when ON, a pending tool approval is answered
     * 'session' the moment it arrives (user opted in from Settings). */
    fun isAutoApprove(): Boolean = apiService.isAutoApprove()
    fun saveAutoApprove(on: Boolean) = apiService.saveAutoApprove(on)

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
        purgeSessionKeys(sessionId)
    }

    /** Rename a session (local-only metadata change). */
    suspend fun getSessionTitle(sessionId: String): String? =
        sessionDao.getSessionById(sessionId)?.title

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

    suspend fun uploadFile(
        file: java.io.File, fileName: String, mimeType: String, sessionId: String = ""
    ): Pair<String, String>? {
        return apiService.uploadFile(file, fileName, mimeType, sessionId)
    }

    /** Telegram-style: tap a media/file bubble → download the attachment
     * bytes (Bearer attached automatically) so the UI can save/open it. */
    suspend fun downloadAttachment(relUrl: String): ByteArray? {
        return apiService.downloadAttachment(relUrl)
    }

    /** Upload the on-device diag log to the gateway plugin (stored under ~/.hermes/mobile-logs/diag/). */
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

    /** Local SYSTEM reply (slash-command output). Rendered as an assistant
     * bubble; never reaches the server or the conversation history. */
    suspend fun insertLocalSystemMessage(sessionId: String, content: String) {
        messageDao.insertMessage(
            Message(
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = content
            )
        )
        sessionDao.incrementMessageCount(sessionId)
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

    /** Forget the saved server URL + API key (Settings → Log Out). */
    fun clearSavedConnection() {
        apiService.clearConfig()
    }

    fun getBaseUrl(): String = apiService.getBaseUrl()

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

    suspend fun isServerReachable(config: ServerConfig): Boolean =
        apiService.isReachable(config)

    suspend fun checkConnectionRaw(config: ServerConfig): Boolean {
        return apiService.healthCheck(config)
    }

    // ─── Model Management ───

    suspend fun listModels(sessionId: String = ""): ModelListResponse? {
        return apiService.listModels(sessionId)
    }

    // Grouped provider inventory — same source as the Hermes picker.
    suspend fun getJson(path: String): org.json.JSONObject? =
        apiService.getJson(path)

    suspend fun fetchModelOptions(): ModelListResponse? {
        return apiService.fetchModelOptions()
    }

    suspend fun switchModel(sessionId: String, modelName: String, global: Boolean = false): Boolean {
        return apiService.switchModel(sessionId, modelName, global)
    }

    fun savedModelForSession(sessionId: String): String? =
        apiService.savedModelForSession(sessionId)

    fun savedModelSlugForSession(sessionId: String): String =
        apiService.savedModelSlugForSession(sessionId)

    fun saveModelForSession(sessionId: String, modelId: String, providerSlug: String) =
        apiService.saveModelForSession(sessionId, modelId, providerSlug)
    fun isSwarmForSession(sessionId: String): Boolean = apiService.isSwarmForSession(sessionId)
    fun saveSwarmForSession(sessionId: String, on: Boolean) = apiService.saveSwarmForSession(sessionId, on)

    // ─── Dark Theme ───

    fun isCaveman(): Boolean = apiService.isCaveman()
    fun saveCaveman(on: Boolean) = apiService.saveCaveman(on)

    // Chat text size — ONE shared reactive source (@Singleton repo). The
    // Settings slider and every open chat read the same flow, so a change
    // lands mid-session with no restart.
    private val _chatFontSp = MutableStateFlow(
        apiService.prefs().getFloat("chat_font_sp",
            apiService.contextRes.resources.getInteger(com.hermes.mobile.R.integer.chat_font_sp).toFloat()))
    val chatFontSp: StateFlow<Float> = _chatFontSp
    fun setChatFont(sp: Float) {
        _chatFontSp.value = sp
        apiService.prefs().edit().putFloat("chat_font_sp", sp).apply()
    }

    /** Clear the override; flow re-seeds to the screen-class resource. */
    fun resetChatFontToAuto() {
        apiService.prefs().edit().remove("chat_font_sp").apply()
        _chatFontSp.value =
            apiService.contextRes.resources.getInteger(com.hermes.mobile.R.integer.chat_font_sp).toFloat()
    }

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

    // ─── Usage Stats ───

    data class UsageStats(
        val sessionsCount: Int,
        val messagesCount: Int,
        val tokensUsed: Long
    )

    suspend fun fetchContextWindow(modelId: String, providerSlug: String?): Long =
        apiService.fetchContextWindow(modelId, providerSlug)

    suspend fun fetchContextUsage(localSessionId: String): Long =
        apiService.fetchContextUsage(localSessionId)

    /** Server-truth usage (falls back to local counts when offline). */
    suspend fun getServerUsageStats(): HermesApiService.ServerUsage? =
        apiService.fetchServerUsage()

    suspend fun getUsageStats(): UsageStats {
        return try {
            val sessions = sessionDao.getAllSessions().first()
            val sessionsCount = sessions.size
            var messagesCount = 0
            var tokensUsed = 0L
            for (session in sessions) {
                val msgs = messageDao.getMessagesOnce(session.id)
                messagesCount += msgs.size
                for (msg in msgs) {
                    if (msg.tokens > 0) tokensUsed += msg.tokens
                }
            }
            UsageStats(sessionsCount, messagesCount, tokensUsed)
        } catch (_: Exception) {
            UsageStats(0, 0, 0)
        }
    }
}
