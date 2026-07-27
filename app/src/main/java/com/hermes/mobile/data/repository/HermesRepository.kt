package com.hermes.mobile.data.repository

import com.hermes.mobile.data.local.MessageDao
import com.hermes.mobile.data.local.SessionDao
import com.hermes.mobile.data.model.*
import com.hermes.mobile.network.HermesApiService
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

    suspend fun getSession(sessionId: String): Session? = sessionDao.getSession(sessionId)

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

    suspend fun sendMessage(
        sessionId: String,
        query: String,
        onChunk: (String) -> Unit
    ): String {
        // Save user message
        val userMsg = Message(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = query
        )
        messageDao.insertMessage(userMsg)
        sessionDao.incrementMessageCount(sessionId)

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
                }
            )
        } catch (e: Exception) {
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

    suspend fun restoreSession(session: Session) {
        sessionDao.upsertSession(session)
    }

    /** Local-only delete (no server call). Used as fallback. */
    suspend fun deleteSessionLocal(sessionId: String) {
        messageDao.deleteSessionMessages(sessionId)
        sessionDao.deleteSession(sessionId)
    }

    suspend fun clearSession(sessionId: String) {
        messageDao.deleteSessionMessages(sessionId)
    }

    // ─── Server Connection ───

    fun saveConfig(config: ServerConfig) {
        apiService.updateConfig(config)
    }

    fun getSavedConfig(): ServerConfig? = apiService.getConfig()

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

    // ─── Dark Theme ───

    fun saveDarkTheme(isDark: Boolean) {
        apiService.saveDarkTheme(isDark)
    }

    fun isDarkTheme(): Boolean = apiService.isDarkTheme()

    fun hasDarkThemePreference(): Boolean = apiService.hasDarkThemePreference()

    /** Expose SharedPreferences for reactive observation. */
    fun prefs(): android.content.SharedPreferences = apiService.prefs()
}
