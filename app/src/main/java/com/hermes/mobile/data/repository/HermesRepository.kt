package com.hermes.mobile.data.repository

import com.hermes.mobile.data.local.MessageDao
import com.hermes.mobile.data.local.SessionDao
import com.hermes.mobile.data.model.*
import com.hermes.mobile.network.HermesApiService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
        if (attempt == 1) {
            val userMsg = Message(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = query,
                attachmentUrl = attachmentUrl.ifBlank { null },
                attachmentType = attachType.ifBlank { null },
                replyToText = replyTo?.take(300)
            )
            messageDao.insertMessage(userMsg)
            sessionDao.incrementMessageCount(sessionId)
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
                onChunk = { chunk ->
                    fullResponse.append(chunk)
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
            fullResponse.append("⚠️ Connection error: ${e.message}")
        }

        // Finalize message
        messageDao.updateMessage(msgId, fullResponse.toString(), false)
        sessionDao.incrementMessageCount(sessionId)

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
