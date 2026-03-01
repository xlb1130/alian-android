package com.alian.assistant.core.alian.backend

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 认证管理器
 * 负责Token的存储、获取和刷新
 * 使用单例模式，确保整个应用使用同一个实例
 */
class AuthManager private constructor(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    // Token刷新互斥锁，防止并发刷新
    private val refreshMutex = Mutex()

    // Token刷新回调
    var onTokenRefresh: (suspend (String, String) -> String?)? = null

    // 认证信息清除回调（用于通知 UI 层更新登录状态）
    var onAuthCleared: (() -> Unit)? = null

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_TOKEN_EXPIRE_TIME = "token_expire_time"

        @Volatile
        private var instance: AuthManager? = null

        /**
         * 获取 AuthManager 单例实例
         */
        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * 清除单例实例（用于测试或重置）
         */
        fun clearInstance() {
            instance = null
        }
    }

    /**
     * 保存Token
     * @param accessToken 访问令牌
     * @param refreshToken 刷新令牌
     * @param email 用户邮箱
     * @param expiresIn 过期时间（秒），默认7天
     */
    fun saveTokens(
        accessToken: String,
        refreshToken: String,
        email: String? = null,
        expiresIn: Long = 7 * 24 * 60 * 60  // 默认7天
    ) {
        val expireTime = System.currentTimeMillis() + (expiresIn * 1000)
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_TOKEN_EXPIRE_TIME, expireTime)
            .apply()
        email?.let {
            prefs.edit().putString(KEY_USER_EMAIL, it).apply()
        }
    }

    /**
     * 获取Access Token
     */
    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    /**
     * 获取Refresh Token
     */
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    /**
     * 获取用户邮箱
     */
    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    /**
     * 获取Token过期时间戳
     */
    fun getTokenExpireTime(): Long = prefs.getLong(KEY_TOKEN_EXPIRE_TIME, 0)

    /**
     * 检查Token是否即将过期（5分钟内）
     */
    fun isTokenExpiringSoon(): Boolean {
        val expireTime = getTokenExpireTime()
        if (expireTime == 0L) return false
        val fiveMinutesInMillis = 5 * 60 * 1000
        return System.currentTimeMillis() >= (expireTime - fiveMinutesInMillis)
    }

    /**
     * 检查Token是否已过期
     */
    fun isTokenExpired(): Boolean {
        val expireTime = getTokenExpireTime()
        if (expireTime == 0L) return false
        return System.currentTimeMillis() >= expireTime
    }

    /**
     * 清除所有认证信息
     */
    fun clearAuth() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_TOKEN_EXPIRE_TIME)
            .apply()
        // 触发认证清除回调，通知 UI 层更新登录状态
        onAuthCleared?.invoke()
    }

    /**
     * 检查是否已登录
     */
    fun isLoggedIn(): Boolean = getAccessToken() != null

    /**
     * 刷新Access Token（带锁，防止并发刷新）
     * @param expiresIn 过期时间（秒），默认2小时
     * @return 新的Access Token，刷新失败返回null
     */
    suspend fun refreshAccessToken(expiresIn: Long = 2 * 60 * 60): String? = refreshMutex.withLock {
        // 双重检查：在获取锁后再次检查Token是否需要刷新
        if (!isTokenExpiringSoon()) {
            return@withLock getAccessToken()
        }

        return withContext(Dispatchers.IO) {
            val refreshToken = getRefreshToken()
                ?: return@withContext null

            try {
                // 调用刷新回调
                val newAccessToken = onTokenRefresh?.invoke(refreshToken, refreshToken)
                if (newAccessToken != null) {
                    // 刷新成功，更新过期时间（默认2小时）
                    saveTokens(
                        accessToken = newAccessToken,
                        refreshToken = refreshToken,
                        email = getUserEmail(),
                        expiresIn = expiresIn
                    )
                    return@withContext newAccessToken
                } else {
                    // 刷新失败，清除认证信息
                    clearAuth()
                    return@withContext null
                }
            } catch (e: Exception) {
                Log.e("AuthManager", "Token refresh failed", e)
                // 刷新失败，清除认证信息
                clearAuth()
                return@withContext null
            }
        }
    }
}