package com.hermes.mobile.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Cross-platform sync service.
 * 
 * Design:
 * - Messages are encrypted client-side before upload (E2E)
 * - Server stores encrypted blobs, cannot read content
 * - When syncing to another device, share public keys via QR/insecure channel
 * - Conflict resolution: newest timestamp wins
 */
class SyncService(
    private val baseUrl: String
) {
    /** Upload encrypted session data to server */
    suspend fun uploadSession(session: EncryptedSession): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/sync/session"
            val body = JSONObject().apply {
                put("session_id", session.sessionId)
                put("updated_at", session.updatedAt)
                put("encrypted_data", session.encryptedData)
                put("device_id", session.deviceId)
            }
            
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(Exception("Upload failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Download sessions from server */
    suspend fun downloadSessions(deviceId: String): Result<List<EncryptedSession>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/sync/sessions?device_id=${deviceId}"
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder()
                .url(url)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("Download failed: ${response.code}"))
                
                val json = JSONObject(response.body?.string() ?: "{}")
                val sessions = mutableListOf<EncryptedSession>()
                val arr = json.optJSONArray("sessions") ?: org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    sessions.add(
                        EncryptedSession(
                            sessionId = obj.getString("session_id"),
                            updatedAt = obj.getLong("updated_at"),
                            encryptedData = obj.getString("encrypted_data"),
                            deviceId = obj.getString("device_id")
                        )
                    )
                }
                Result.success(sessions)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Resolve conflicts between local and remote sessions */
    fun resolveConflict(local: EncryptedSession, remote: EncryptedSession): EncryptedSession {
        // Simple strategy: newest timestamp wins
        return if (local.updatedAt >= remote.updatedAt) local else remote
    }

    data class EncryptedSession(
        val sessionId: String,
        val updatedAt: Long,
        val encryptedData: String,
        val deviceId: String
    )
}
