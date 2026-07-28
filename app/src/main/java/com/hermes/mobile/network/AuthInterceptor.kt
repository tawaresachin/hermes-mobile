package com.hermes.mobile.network

import com.hermes.mobile.auth.AuthManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that attaches the current JWT access token
 * to every outgoing request as `Authorization: Bearer ***`.
 *
 * Skips adding the header for auth endpoints (/auth/register, /auth/login, /auth/refresh)
 * since those don't require an existing token.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val authManager: AuthManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val path = originalRequest.url.encodedPath

        // Don't add JWT to auth endpoints (login/register/refresh don't need it)
        if (path.startsWith("/auth/")) {
            return chain.proceed(originalRequest)
        }

        val token = authManager.getToken()
        return if (token != null) {
            val authenticatedRequest = originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            chain.proceed(authenticatedRequest)
        } else {
            chain.proceed(originalRequest)
        }
    }
}
