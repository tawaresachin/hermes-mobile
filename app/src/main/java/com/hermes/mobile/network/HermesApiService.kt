package com.hermes.mobile.network

import com.hermes.mobile.data.model.ServerConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HermesApiService @Inject constructor() {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val authHeader = config?.let { cfg ->
                    if (cfg.apiKey.isNotBlank()) "Bearer ${cfg.apiKey}" else null
                }
                val newRequest = if (authHeader != null) {
                    request.newBuilder()
                        .header("Authorization", authHeader)
                        .header("Content-Type", "application/json")
                        .build()
                } else {
                    request.newBuilder()
                        .header("Content-Type", "application/json")
                        .build()
                }
                chain.proceed(newRequest)
            }
            .build()
    }

    private var config: ServerConfig? = null

    fun updateConfig(cfg: ServerConfig) {
        config = cfg
    }

    fun getBaseUrl(): String = config?.baseUrl ?: "http://localhost:8080"

    // ─── Health Check ───

    suspend fun healthCheck(cfg: ServerConfig? = null): Boolean {
        val baseUrl = cfg?.baseUrl ?: config?.baseUrl ?: return false
        return try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ─── Streaming Chat via SSE ───

    fun streamChat(
        query: String,
        sessionId: String,
        onChunk: (String) -> Unit,
        onComplete: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): EventSource {
        val baseUrl = config?.baseUrl ?: "http://localhost:8080"
        val payload = JSONObject().apply {
            put("query", query)
            put("session_id", sessionId)
            put("stream", true)
        }

        val request = Request.Builder()
            .url("$baseUrl/api/chat/stream")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("Accept", "text/event-stream")
            .build()

        val factory = EventSources.createFactory(client)

        return factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    onComplete?.invoke()
                    return
                }
                try {
                    val json = JSONObject(data)
                    if (json.has("content")) {
                        onChunk(json.getString("content"))
                    }
                    if (json.optBoolean("done", false)) {
                        onComplete?.invoke()
                    }
                } catch (e: Exception) {
                    onChunk(data)
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val msg = t?.message ?: "Connection failed"
                onError?.invoke(msg)
            }

            override fun onClosed(eventSource: EventSource) {
                onComplete?.invoke()
            }
        })
    }

    // ─── List Sessions ───

    suspend fun listSessions(): List<Map<String, Any>> {
        val baseUrl = config?.baseUrl ?: "http://localhost:8080"
        val request = Request.Builder()
            .url("$baseUrl/api/sessions")
            .get()
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: "[]"
        // Parse as JSON array
        val arr = org.json.JSONArray(body)
        return (0 until arr.length()).map { i ->
            arr.getJSONObject(i).toMap()
        }
    }

    // ─── Simple Chat (non-streaming) ───

    suspend fun sendChat(
        query: String,
        sessionId: String? = null
    ): String {
        val baseUrl = config?.baseUrl ?: "http://localhost:8080"
        val payload = JSONObject().apply {
            put("query", query)
            put("stream", false)
            sessionId?.let { put("session_id", it) }
        }
        val request = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        return response.body?.string() ?: "{}"
    }
}

private fun org.json.JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    keys().forEach { key ->
        val value = get(key)
        map[key] = value
    }
    return map
}
