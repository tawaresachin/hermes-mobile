package com.hermes.mobile.data.repository

import com.hermes.mobile.data.local.MessageDao
import com.hermes.mobile.data.local.SessionDao
import com.hermes.mobile.data.model.*
import com.hermes.mobile.network.HermesApiService
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
    private val messageDao: MessageDao
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

    /** Telegram-style reaction (👍) — stored locally per message. */
    suspend fun setReaction(messageId: Long, reaction: String?) {
        messageDao.updateReaction(messageId, reaction)
    }

    /** Telegram-style forward: send the text as a user message in the
     *  target session (saves it + hands it to the AI in one go). */
    suspend fun forwardMessage(sessionId: String, content: String) {
        sendMessage(sessionId = sessionId, query = content, onChunk = {})
    }

    suspend fun sendMessage(
        sessionId: String,
        query: String,
        onChunk: (String) -> Unit,
        onToolCall: (String, String, String) -> Unit = { _, _, _ -> },
        onToolResult: (String, String) -> Unit = { _, _ -> },
        attempt: Int = 1,
        attachmentUrl: String = "",
        attachType: String = "",
        multiAgent: Boolean = false,
        replyTo: String? = null,
    ): String {
        // Save user message ONLY on first attempt (retries must not duplicate it)
        var userMsgId: Long? = null
        if (attempt == 1) {
            val userMsg = Message(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = query,
                attachmentUrl = attachmentUrl.ifBlank { null },
                attachmentType = attachType.ifBlank { null },
                replyToText = replyTo?.take(300),
                // Telegram-style tick: SENDING until the server acknowledges.
                status = MessageStatus.SENDING
            )
            userMsgId = messageDao.insertMessage(userMsg)
            sessionDao.incrementMessageCount(sessionId)
        } else {
            // Retry: the user row already exists — reuse it so the tick
            // chain (SENT → READ) still advances on the retried attempt.
            userMsgId = messageDao.getMessagesOnce(sessionId)
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
        try {
            apiService.streamChat(
                query = query,
                sessionId = sessionId,
                onOpen = {
                    // Server accepted + opened the stream → SENT.
                    if (userMsgId != null) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                messageDao.updateMessageStatus(userMsgId!!, MessageStatus.SENT)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) { }
                        }
                    }
                },
                onChunk = { chunk ->
                    fullResponse.append(chunk)
                    // First content → the agent is answering → READ.
                    if (fullResponse.length == chunk.length && userMsgId != null) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                messageDao.updateMessageStatus(userMsgId!!, MessageStatus.READ)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Exception) { }
                        }
                    }
                    onChunk(chunk)
                },
                onToolCall = onToolCall,
                onToolResult = onToolResult,
                attachmentUrl = attachmentUrl,
                attachType = attachType,
                multiAgent = multiAgent,
                replyTo = replyTo,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // cancelled send must not write a ghost error message
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
            if (userMsgId != null) {
                try {
                    messageDao.updateMessageStatus(userMsgId!!, MessageStatus.FAILED)
                } catch (e2: kotlinx.coroutines.CancellationException) {
                    throw e2
                } catch (_: Exception) { }
            }
        }

        // Finalize message
        messageDao.updateMessage(msgId, fullResponse.toString(), false)
        sessionDao.incrementMessageCount(sessionId)

        // Tick → READ once the response COMPLETED. The first-text-chunk
        // hook misses tool-only / reasoning-only / resume-repair responses
        // (no text chunk ever fires), leaving those stuck on the single
        // tick. Any completed turn with real content = read. (The FAILED
        // path already marked FAILED above and starts with the error
        // marker — never override it.)
        if (userMsgId != null && !fullResponse.startsWith("⚠️ Connection error")) {
            try {
                messageDao.updateMessageStatus(userMsgId!!, MessageStatus.READ)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { }
        }

        return fullResponse.toString()
    }

    suspend fun resumeSession(sessionId: String): List<Message> {
        return messageDao.getMessagesOnce(sessionId)
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
