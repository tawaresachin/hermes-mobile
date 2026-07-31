package com.hermes.mobile.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

// ─── Chat / Message Models ───

data class ChatRequest(
    val query: String,
    val session_id: String? = null,
    val stream: Boolean = true
)

data class ChatResponse(
    val message: String,
    val session_id: String? = null,
    val done: Boolean = true
)

data class StreamChunk(
    val content: String? = null,
    val done: Boolean = false,
    @SerializedName("session_id")
    val sessionId: String? = null
)

enum class MessageRole { USER, ASSISTANT, SYSTEM }

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
    val attachmentName: String? = null
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

data class SessionSummary(
    val id: String,
    val title: String?,
    val created_at: String?,
    val updated_at: String?,
    val message_count: Int = 0
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
    val isConnected: Boolean = false
)

// ─── Connection Status ───

enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
