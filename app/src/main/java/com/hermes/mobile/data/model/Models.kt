package com.hermes.mobile.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ─── Chat / Message Models ───

enum class MessageRole { USER, ASSISTANT }

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
    val reaction: String? = null
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
    val baseUrl: String = ""
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
