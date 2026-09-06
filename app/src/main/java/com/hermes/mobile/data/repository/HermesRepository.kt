package com.hermes.mobile.data.repository

import com.hermes.mobile.data.network.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HermesRepository @Inject constructor(
    private val apiClient: HermesApiClient
) {
    suspend fun pairWithToken(pairingToken: String): Result<PairResponse> {
        return try {
            val response = apiClient.pairWithToken(pairingToken)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getSessions(): Result<List<Session>> {
        return try {
            val sessions = apiClient.getSessions()
            Result.success(sessions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun createSession(title: String? = null, model: String? = null): Result<Session> {
        return try {
            val session = apiClient.createSession(title, model)
            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun chatStream(sessionId: String?, query: String): Flow<ChatEvent> {
        return flow {
            try {
                apiClient.chatStream(sessionId, query).collect { event ->
                    emit(event)
                }
            } catch (e: Exception) {
                emit(ChatEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }
    
    suspend fun getUsage(days: Int = 30): Result<UsageData> {
        return try {
            val usage = apiClient.getUsage(days)
            Result.success(usage)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun listModels(): Result<List<Model>> {
        return try {
            val models = apiClient.listModels()
            Result.success(models)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun switchModel(model: String, provider: String? = null): Result<Unit> {
        return try {
            apiClient.switchModel(model, provider)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
