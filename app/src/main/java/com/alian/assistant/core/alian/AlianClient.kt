package com.alian.assistant.core.alian

import android.content.Context
import android.util.Log
import com.alian.assistant.core.alian.backend.AuthManager
import com.alian.assistant.core.alian.backend.BackendChatClient
import com.alian.assistant.core.alian.backend.SessionData
import com.alian.assistant.core.alian.backend.SessionDetailData
import com.alian.assistant.core.alian.backend.SessionMessage
import com.alian.assistant.core.alian.backend.UIEvent
import com.alian.assistant.presentation.viewmodel.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * API 聊天消息（用于 OpenAI 兼容 API）
 */
@Serializable
data class ApiChatMessage(
    val role: String,  // "user" or "assistant"
    val content: String
)

/**
 * API 请求体
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ApiChatMessage>,
    val temperature: Double = 0.7,
    val max_tokens: Int = 2000
)

/**
 * API 响应
 */
@Serializable
data class ChatResponse(
    val id: String,
    val choices: List<Choice>
)

@Serializable
data class Choice(
    val message: ApiChatMessage,
    val finish_reason: String
)

/**
 * Alian 聊天客户端
 * 支持两种模式：
 * 1. OpenAI 兼容 API 模式（原有）
 * 2. Backend API 模式（新增）
 */
class AlianClient(
    private val context: Context? = null,
    private val apiKey: String = "",
    private val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    private val model: String = "qwen-plus",
    private val useBackend: Boolean = false
) {
    // Backend API 客户端
    private var backendClient: BackendChatClient? = null
    private var currentSessionId: String? = null

    // OpenAI 兼容 API 客户端
    private val openAIClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mediaType = "application/json".toMediaType()

    init {
        if (useBackend && context != null) {
            backendClient = BackendChatClient(context, baseUrl)
        }
    }

    /**
     * 使用 Backend API 登录
     */
    suspend fun login(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        Log.d("AlianClient", "开始登录")
        Log.d("AlianClient", "useBackend: $useBackend")
        Log.d("AlianClient", "backendClient: ${backendClient != null}")

        if (!useBackend || backendClient == null) {
            val errorMsg = "Backend mode is not enabled"
            Log.e("AlianClient", errorMsg)
            return@withContext Result.failure(Exception(errorMsg))
        }

        try {
            Log.d("AlianClient", "调用BackendChatClient.login")
            val result = backendClient!!.login(email, password)
            Log.d("AlianClient", "BackendChatClient.login返回: $result")

            if (result.isSuccess) {
                Log.d("AlianClient", "登录成功")
                Result.success("登录成功")
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "登录失败"
                Log.e("AlianClient", "登录失败: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("AlianClient", "登录异常", e)
            Result.failure(e)
        }
    }

    /**
     * 登出
     */
    fun logout() {
        backendClient?.logout()
        currentSessionId = null
    }

    /**
     * 检查是否已登录
     */
    fun isLoggedIn(): Boolean {
        return if (useBackend) {
            // 使用AuthManager检查登录状态
            context?.let {
                val authManager = AuthManager.getInstance(it)
                authManager.getAccessToken() != null
            } ?: false
        } else {
            apiKey.isNotBlank()
        }
    }

    /**
     * 发送聊天消息
     */
    suspend fun sendMessage(
        userMessage: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        onEvent: ((UIEvent) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (useBackend) {
            sendMessageWithBackend(userMessage, onEvent)
        } else {
            sendMessageWithOpenAI(userMessage, conversationHistory)
        }
    }

    /**
     * 使用 Backend API 发送消息
     */
    private suspend fun sendMessageWithBackend(
        userMessage: String,
        onEvent: ((UIEvent) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        Log.d("AlianClient", "sendMessageWithBackend开始")
        Log.d("AlianClient", "backendClient: ${backendClient != null}")
        Log.d("AlianClient", "currentSessionId: $currentSessionId")

        if (backendClient == null) {
            val errorMsg = "Backend client not initialized"
            Log.e("AlianClient", errorMsg)
            return@withContext Result.failure(Exception(errorMsg))
        }

        try {
            // 如果没有会话，创建一个新会话
            if (currentSessionId == null) {
                Log.d("AlianClient", "创建新会话")
                val sessionResult = backendClient!!.createSession()
                Log.d("AlianClient", "会话创建结果: $sessionResult")

                if (sessionResult.isFailure) {
                    val errorMsg = sessionResult.exceptionOrNull()?.message ?: "Failed to create session"
                    Log.e("AlianClient", "创建会话失败: $errorMsg")
                    return@withContext Result.failure(Exception(errorMsg))
                }
                currentSessionId = sessionResult.getOrNull()
                Log.d("AlianClient", "会话创建成功: $currentSessionId")
            }

            // 发送消息
            Log.d("AlianClient", "发送消息到会话: $currentSessionId, 消息: $userMessage")
            val result = backendClient!!.sendMessage(
                sessionId = currentSessionId!!,
                message = userMessage,
                onEvent = onEvent
            )

            Log.d("AlianClient", "消息发送结果: isSuccess=${result.isSuccess}")
            if (result.isSuccess) {
                Log.d("AlianClient", "响应内容: ${result.getOrNull()?.take(100)}...")
            } else {
                Log.e("AlianClient", "错误信息: ${result.exceptionOrNull()?.message}")
            }
            result
        } catch (e: Exception) {
            Log.e("AlianClient", "发送消息异常", e)
            Result.failure(e)
        }
    }

    /**
     * 使用 OpenAI 兼容 API 发送消息
     */
    private suspend fun sendMessageWithOpenAI(
        userMessage: String,
        conversationHistory: List<ChatMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 构建消息列表
            val messages = mutableListOf<ApiChatMessage>()

            // 添加系统提示
            messages.add(ApiChatMessage(
                role = "system",
                content = """你是 Alian，一个通用 AI 助手。你是一个友好、专业且乐于助人的助手，可以回答各种问题，提供建议和帮助用户解决问题。你的回答应该简洁、准确、有用。"""
            ))

            // 添加历史对话
            conversationHistory.forEach { msg ->
                messages.add(ApiChatMessage(
                    role = if (msg.isUser) "user" else "assistant",
                    content = msg.content
                ))
            }

            // 添加当前用户消息
            messages.add(ApiChatMessage(
                role = "user",
                content = userMessage
            ))

            // 构建请求
            val request = ChatRequest(
                model = model,
                messages = messages
            )

            val requestBody = json.encodeToString(request)
                .toRequestBody(mediaType)

            // 发送请求
            val httpRequest = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = openAIClient.newCall(httpRequest).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext Result.failure(
                    Exception("API request failed: ${response.code} - $errorBody")
                )
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            // 解析响应
            val chatResponse = json.decodeFromString<ChatResponse>(responseBody)

            if (chatResponse.choices.isEmpty()) {
                return@withContext Result.failure(Exception("No choices in response"))
            }

            val assistantMessage = chatResponse.choices[0].message.content
            Result.success(assistantMessage)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 流式发送聊天消息
     */
    suspend fun sendMessageStream(
        userMessage: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        onPartialResponse: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (useBackend) {
            sendMessageStreamWithBackend(userMessage, onPartialResponse)
        } else {
            // OpenAI 模式暂时使用非流式
            sendMessageWithOpenAI(userMessage, conversationHistory)
        }
    }

    /**
     * 使用 Backend API 流式发送消息
     */
    private suspend fun sendMessageStreamWithBackend(
        userMessage: String,
        onPartialResponse: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (backendClient == null) {
            return@withContext Result.failure(Exception("Backend client not initialized"))
        }

        try {
            // 如果没有会话，创建一个新会话
            if (currentSessionId == null) {
                val sessionResult = backendClient!!.createSession()
                if (sessionResult.isFailure) {
                    return@withContext Result.failure(
                        sessionResult.exceptionOrNull() ?: Exception("Failed to create session")
                    )
                }
                currentSessionId = sessionResult.getOrNull()
            }

            // 发送消息（流式）
            val result = backendClient!!.sendMessage(
                sessionId = currentSessionId!!,
                message = userMessage,
                onPartialResponse = onPartialResponse
            )

            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取会话列表（仅 Backend 模式）
     */
    suspend fun getSessions(): Result<List<SessionData>> = withContext(Dispatchers.IO) {
        if (!useBackend || backendClient == null) {
            return@withContext Result.failure(Exception("Backend mode is not enabled"))
        }

        backendClient!!.getSessions()
    }

    /**
     * 删除会话（仅 Backend 模式）
     */
    suspend fun deleteSession(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!useBackend || backendClient == null) {
            return@withContext Result.failure(Exception("Backend mode is not enabled"))
        }

        backendClient!!.deleteSession(sessionId)
    }

    /**
     * 获取会话消息历史（仅 Backend 模式）
     */
    suspend fun getSessionMessages(sessionId: String): Result<List<SessionMessage>> = withContext(Dispatchers.IO) {
        if (!useBackend || backendClient == null) {
            return@withContext Result.failure(Exception("Backend mode is not enabled"))
        }

        backendClient!!.getSessionMessages(sessionId)
    }

    /**
     * 获取会话详细信息（包含事件列表）（仅 Backend 模式）
     */
    suspend fun getSessionDetail(sessionId: String): Result<SessionDetailData> = withContext(Dispatchers.IO) {
        if (!useBackend || backendClient == null) {
            return@withContext Result.failure(Exception("Backend mode is not enabled"))
        }

        backendClient!!.getSessionDetail(sessionId)
    }

    /**
     * 清除当前会话
     */
    fun clearCurrentSession() {
        currentSessionId = null
    }

    /**
     * 设置当前会话ID
     */
    fun setCurrentSessionId(sessionId: String) {
        currentSessionId = sessionId
    }

    /**
     * 获取当前会话ID
     */
    fun getCurrentSessionId(): String? {
        return currentSessionId
    }

    /**
     * 获取 BackendChatClient
     */
    fun getBackendClient(): BackendChatClient? {
        return backendClient
    }
}