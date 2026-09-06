package com.hermes.mobile.data.network

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class HermesApiClient(
    private val baseUrl: String,
    private val mobileToken: String? = null
) {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        
        install(Auth) {
            if (mobileToken != null) {
                bearer {
                    loadTokens {
                        BearerTokens(
                            accessToken = mobileToken,
                            refreshToken = ""
                        )
                    }
                }
            }
        }
        
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
        
        expectSuccess = true
    }
    
    suspend fun pairWithToken(pairingToken: String): PairResponse {
        val response = client.post("$baseUrl/api/plugins/hermes-mobile/pair/verify") {
            body = PairVerifyRequest(
                pairing_token = pairingToken,
                device_name = "Hermes Mobile"
            )
        }
        return response
    }
    
    suspend fun getSessions(limit: Int = 20, offset: Int = 0): List<Session> {
        return client.get("$baseUrl/api/plugins/hermes-mobile/sessions") {
            url {
                parameters.append("limit", limit.toString())
                parameters.append("offset", offset.toString())
            }
        }
    }
    
    suspend fun createSession(title: String? = null, model: String? = null): Session {
        return client.post("$baseUrl/api/plugins/hermes-mobile/sessions") {
            body = CreateSessionRequest(title = title, model = model)
        }
    }
    
    suspend fun chatStream(sessionId: String?, query: String): Flow<ChatEvent> {
        return client.eventFlow("$baseUrl/api/plugins/hermes-mobile/chat/stream") {
            setBody(ChatRequest(
                query = query,
                session_id = sessionId
            ))
        }
    }
    
    suspend fun getUsage(days: Int = 30): UsageData {
        return client.get("$baseUrl/api/plugins/hermes-mobile/usage") {
            url {
                parameters.append("days", days.toString())
            }
        }
    }
    
    suspend fun listModels(): List<Model> {
        return client.get("$baseUrl/api/plugins/hermes-mobile/models")
    }
    
    suspend fun switchModel(model: String, provider: String? = null) {
        client.post("$baseUrl/api/plugins/hermes-mobile/models/switch") {
            body = ModelSwitchRequest(model = model, provider = provider)
        }
    }
}

// Request/Response Models

data class PairVerifyRequest(
    val pairing_token: String,
    val device_name: String,
    val device_id: String? = null
)

data class PairResponse(
    val access_token: String,
    val token_type: String = "bearer",
    val expires_in: Int,
    val desktop_info: Map<String, Any>
)

data class ChatRequest(
    val query: String,
    val session_id: String? = null,
    val model: String? = null,
    val multi_agent: Boolean = false,
    val attachment_url: String = "",
    val attachment_type: String = "",
    val reply_to: String? = null
)

data class CreateSessionRequest(
    val title: String? = null,
    val model: String? = null,
    val parent_session_id: String? = null
)

data class ModelSwitchRequest(
    val model: String,
    val provider: String? = null
)

// Session Data Class
data class Session(
    val id: String,
    val title: String,
    val model: String?,
    val message_count: Int,
    val updated_at: Double,
    val input_tokens: Int = 0,
    val output_tokens: Int = 0
)

// Chat Event for SSE
sealed class ChatEvent {
    data class TextChunk(val content: String) : ChatEvent()
    data class ToolCall(val name: String, val args: String) : ChatEvent()
    data class ToolResult(val tool_call_id: String, val result: String) : ChatEvent()
    data class Reasoning(val content: String) : ChatEvent()
    object TurnEnd : ChatEvent()
    data class Error(val message: String) : ChatEvent()
}

// Usage Data
data class UsageData(
    val daily: List<Map<String, Any>>,
    val by_model: List<Map<String, Any>>,
    val totals: Map<String, Any>,
    val period_days: Int,
    val skills: Map<String, Any>
)

// Model Data
data class Model(
    val id: String,
    val name: String,
    val provider: String
)
