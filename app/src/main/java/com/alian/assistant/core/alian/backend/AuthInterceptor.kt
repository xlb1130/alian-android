package com.alian.assistant.core.alian.backend

import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 认证拦截器
 * 自动在请求头中添加Authorization，并在Token过期或401错误时自动刷新
 */
class AuthInterceptor(private val authManager: AuthManager) : Interceptor {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // 检查是否是 SSE 请求
        val isSSERequest = originalRequest.header("Accept")?.contains("text/event-stream") == true
        
        var accessToken = authManager.getAccessToken()

        // 检查Token是否即将过期，如果是则主动刷新
        if (accessToken != null && authManager.isTokenExpiringSoon()) {
            runBlocking {
                accessToken = authManager.refreshAccessToken()
            }
        }

        // 如果有Token，添加到请求头
        val authenticatedRequest = if (accessToken != null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            originalRequest
        }

        // 执行请求
        var response = chain.proceed(authenticatedRequest)

        // 对于 SSE 请求，只检查 HTTP 状态码，不检查响应体
        if (isSSERequest) {
            if (response.code == 401) {
                Log.w("AuthInterceptor", "SSE 请求认证失败，清除认证信息")
                authManager.clearAuth()
                response.close()
                return response
            }
            return response
        }

        // 非 SSE 请求，检查是否是认证失败（HTTP 401 或响应体中 code:401）
        val isAuthFailed = response.code == 401 || checkAuthFailedInBody(response)

        if (isAuthFailed) {
            // 认证失败，清除所有认证信息
            Log.w("AuthInterceptor", "认证失败，清除认证信息")
            authManager.clearAuth()
            response.close()

            // 不再尝试刷新Token，直接返回原始响应
            return response
        }

        return response
    }

    /**
     * 检查响应体中是否包含 code:401 的认证失败信息
     */
    private fun checkAuthFailedInBody(response: Response): Boolean {
        try {
            val responseBody = response.peekBody(Long.MAX_VALUE).string()
            Log.d("AuthInterceptor", "检查响应体中的认证失败信息: $responseBody")
            
            // 检查多种可能的格式
            val hasAuthFailed = responseBody.contains("\"code\":401") || 
                               responseBody.contains("\"code\" :401") ||
                               responseBody.contains("code=401") ||
                               responseBody.contains("code:401") ||
                               responseBody.contains("Authentication failed")
            
            if (hasAuthFailed) {
                Log.w("AuthInterceptor", "检测到响应体中认证失败，清除认证信息。响应体: $responseBody")
                return true
            }
        } catch (e: Exception) {
            Log.e("AuthInterceptor", "检查响应体失败", e)
        }
        return false
    }
}