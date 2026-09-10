package com.hermes.mobile.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ─── Chat / Message Models ───

enum class MessageRole { USER, ASSISTANT }

/** Telegram-style delivery status for OUTGOING (user) messages. */
enum class MessageStatus { SENDING, SENT, READ, FAILED }

@Entity(
    tableName = "messages",
    indices = [Index(value = ["sessionId"])]
)
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val attachmentUrl: String? = null,
    val attachmentType: String? = null,
    val attachmentName: String? = null,
    // Telegram-style reply: text of the quoted message (rendered as a
    // quote chip at the top of the bubble).
    val replyToText: String? = null,
    // Telegram-style reaction (👍) — stored locally per message.
    val reaction: String? = null,
    // Telegram-style delivery tick (user messages only; null = SENT).
    val status: MessageStatus? = null,
    // When this message was last edited (0 = never).
    val editedAt: Long = 0,
    // Token count for this message (for usage stats).
    val tokens: Long = 0,
    // JSON array of tool activity lines [{n,e,l,s}] captured during the
    // turn (Telegram-style grouped display; persisted with the bubble).
    val toolActivity: String? = null,
    // prompt_tokens of this turn = the session's context fill right after
    // it (seeds the context meter when the session is reopened).
    val contextTokens: Long = 0
)

// ─── Session Models ───

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey
    val id: String,
    val title: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val isActive: Boolean = true
)

// ─── Model Info ───

data class ModelInfo(
    val id: String,
    val name: String,
    val isVision: Boolean = false,
    val isFree: Boolean = false,
    val provider: String = "",
    val baseUrl: String = "",
    // Provider SLUG (e.g. "custom:freellm") — what the server expects in the
    // request "provider" field. `provider` above is the human label.
    val providerSlug: String = ""
)

data class ModelListResponse(
    val models: List<ModelInfo>,
    val current: String,
    val default: String,
    val provider: String
)

// ─── Server Config ───

data class ServerConfig(
    val baseUrl: String = "http://localhost:8080",
    val isConnected: Boolean = false,
    val apiKey: String = "",
    val setupToken: String = "",
)

// ─── Connection Status ───

enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
