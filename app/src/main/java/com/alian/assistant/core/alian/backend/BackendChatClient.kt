package com.alian.assistant.core.alian.backend

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Backend Chat 客户端
 * 封装与Backend服务器的所有交互
 */
class BackendChatClient(
    private val context: Context,
    private val baseUrl: String = "http://39.98.113.244:5173/api/v1"
) {
    private val authManager = AuthManager.getInstance(context)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        explicitNulls = false
    }
    private val mediaType = "application/json".toMediaType()

    // OkHttp客户端，带认证拦截器
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)  // 设置为 30 秒，避免请求挂起
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)  // 添加心跳机制，每30秒发送一次ping
        .retryOnConnectionFailure(false)  // 禁用重试，避免重复发送请求
        .addInterceptor(AuthInterceptor(authManager))
        .build()

    init {
        // 设置Token刷新回调
        authManager.onTokenRefresh = { oldAccessToken, refreshToken ->
            refreshTokenToken(refreshToken)
        }
    }

    /**
     * 检查响应体中是否包含认证失败信息（code:401）
     * 如果是认证失败，清除认证信息并返回 true
     */
    private fun checkAndHandleAuthFailure(responseBody: String): Boolean {
        Log.d("BackendChatClient", "检查响应体中的认证失败信息: $responseBody")
        
        // 检查多种可能的格式
        val hasAuthFailed = responseBody.contains("\"code\":401") || 
                           responseBody.contains("\"code\" :401") ||
                           responseBody.contains("code=401") ||
                           responseBody.contains("code:401") ||
                           responseBody.contains("Authentication failed")
        
        if (hasAuthFailed) {
            Log.w("BackendChatClient", "检测到认证失败，清除认证信息。响应体: $responseBody")
            authManager.clearAuth()
            return true
        }
        
        Log.d("BackendChatClient", "未检测到认证失败信息")
        return false
    }

    /**
     * 刷新Token
     * @param refreshToken 刷新令牌
     * @return 新的Access Token，刷新失败返回null
     */
    private suspend fun refreshTokenToken(refreshToken: String): String? = withContext(Dispatchers.IO) {
        try {
            // 构建刷新Token请求
            val refreshTokenRequest = RefreshTokenRequest(refresh_token = refreshToken)
            val requestBody = json.encodeToString(refreshTokenRequest)
                .toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$baseUrl/auth/refresh")
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e("BackendChatClient", "Refresh token failed: ${response.code} - $errorBody")
                return@withContext null
            }

            val responseBody = response.body?.string()
                ?: return@withContext null

            // 解析响应
            val refreshResponse = json.decodeFromString<RefreshTokenResponse>(responseBody)

            if (refreshResponse.code != 0 || refreshResponse.data == null) {
                Log.e("BackendChatClient", "Refresh token failed: ${refreshResponse.msg}")
                return@withContext null
            }

            // 返回新的Access Token
            refreshResponse.data.access_token
        } catch (e: Exception) {
            Log.e("BackendChatClient", "Refresh token error", e)
            null
        }
    }

    /**
     * 登录
     */
    suspend fun login(email: String, password: String): Result<AuthData> = withContext(Dispatchers.IO) {
        Log.d("BackendChatClient", "开始登录")
        Log.d("BackendChatClient", "baseUrl: $baseUrl")
        Log.d("BackendChatClient", "email: $email")

        try {
            val loginRequest = LoginRequest(email, password)
            val requestBody = json.encodeToString(loginRequest).toRequestBody(mediaType)

            Log.d("BackendChatClient", "构建登录请求: $baseUrl/auth/login")

            val request = Request.Builder()
                .url("$baseUrl/auth/login")
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()

            Log.d("BackendChatClient", "发送登录请求")

            val response = client.newCall(request).execute()

            Log.d("BackendChatClient", "收到响应: ${response.code}")

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                val errorMsg = "Login failed: ${response.code} - $errorBody"
                Log.e("BackendChatClient", errorMsg)
                return@withContext Result.failure(Exception(errorMsg))
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            Log.d("BackendChatClient", "响应体: $responseBody")

            val loginResponse = json.decodeFromString<LoginResponse>(responseBody)

            Log.d("BackendChatClient", "解析响应: code=${loginResponse.code}, msg=${loginResponse.msg}")

            if (loginResponse.code != 0 || loginResponse.data == null) {
                val errorMsg = loginResponse.msg
                Log.e("BackendChatClient", "登录失败: $errorMsg")
                return@withContext Result.failure(Exception(errorMsg))
            }

            // 保存Token
            Log.d("BackendChatClient", "保存Token")
            Log.d("BackendChatClient", "Access Token 长度: ${loginResponse.data.access_token.length}")
            Log.d("BackendChatClient", "Refresh Token 长度: ${loginResponse.data.refresh_token.length}")
            
            authManager.saveTokens(
                accessToken = loginResponse.data.access_token,
                refreshToken = loginResponse.data.refresh_token,
                email = email
            )
            
            // 验证Token是否真的被保存了
            val savedAccessToken = authManager.getAccessToken()
            val savedRefreshToken = authManager.getRefreshToken()
            val savedEmail = authManager.getUserEmail()
            
            Log.d("BackendChatClient", "验证Token保存:")
            Log.d("BackendChatClient", "  Access Token: ${if (savedAccessToken != null) "已保存 (长度: ${savedAccessToken.length})" else "未保存"}")
            Log.d("BackendChatClient", "  Refresh Token: ${if (savedRefreshToken != null) "已保存 (长度: ${savedRefreshToken.length})" else "未保存"}")
            Log.d("BackendChatClient", "  Email: $savedEmail")

            Log.d("BackendChatClient", "登录成功")
            Result.success(loginResponse.data)
        } catch (e: Exception) {
            Log.e("BackendChatClient", "登录异常", e)
            Result.failure(e)
        }
    }

    /**
     * 登出
     */
    fun logout() {
        authManager.clearAuth()
    }

    /**
     * 检查是否已登录
     */
    fun isLoggedIn(): Boolean = authManager.isLoggedIn()

    /**
     * 检查认证状态
     * @return true 表示认证有效，false 表示认证无效或过期
     */
    suspend fun checkAuthStatus(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/auth/status")
                .get()
                .build()

            val response = client.newCall(request).execute()

            Log.d("BackendChatClient", "Check auth status response code: ${response.code}")

            if (!response.isSuccessful) {
                Log.e("BackendChatClient", "Check auth status failed: ${response.code}")
                return@withContext false
            }

            val responseBody = response.body?.string()
                ?: return@withContext false

            Log.d("BackendChatClient", "Check auth status response body: $responseBody")

            // 检查是否是认证失败（code:401）
            if (checkAndHandleAuthFailure(responseBody)) {
                Log.w("BackendChatClient", "Auth status check failed: 认证失败 (code:401)")
                return@withContext false
            }

            val statusResponse = json.decodeFromString<AuthStatusResponse>(responseBody)

            Log.d("BackendChatClient", "Auth status response: code=${statusResponse.code}, msg=${statusResponse.msg}, data=${statusResponse.data}")

            // 检查 code 是否为 0 且 data 不为 null
            if (statusResponse.code != 0) {
                Log.w("BackendChatClient", "Auth status check failed: code=${statusResponse.code}, msg=${statusResponse.msg}")
                return@withContext false
            }

            // 即使 code 为 0，如果 data 为 null，也认为是认证失败
            if (statusResponse.data == null) {
                Log.w("BackendChatClient", "Auth status check failed: data is null")
                authManager.clearAuth()
                return@withContext false
            }

            Log.d("BackendChatClient", "Auth status valid: ${statusResponse.data.auth_provider}")
            return@withContext true
        } catch (e: Exception) {
            Log.e("BackendChatClient", "Check auth status error", e)
            return@withContext false
        }
    }

    /**
     * 创建会话
     */
    suspend fun createSession(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/sessions")
                .put("".toRequestBody(mediaType))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext Result.failure(
                    Exception("Create session failed: ${response.code} - $errorBody")
                )
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            val sessionResponse = json.decodeFromString<CreateSessionResponse>(responseBody)

            if (sessionResponse.code != 0 || sessionResponse.data == null) {
                return@withContext Result.failure(
                    Exception(sessionResponse.msg)
                )
            }

            Result.success(sessionResponse.data.session_id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取会话列表
     */
    suspend fun getSessions(): Result<List<SessionData>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/sessions")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext Result.failure(
                    Exception("Get sessions failed: ${response.code} - $errorBody")
                )
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            val sessionsResponse = json.decodeFromString<SessionsResponse>(responseBody)

            if (sessionsResponse.code != 0 || sessionsResponse.data == null) {
                return@withContext Result.failure(
                    Exception(sessionsResponse.msg)
                )
            }

            Result.success(sessionsResponse.data.sessions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 删除会话
     */
    suspend fun deleteSession(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/sessions/$sessionId")
                .delete()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext Result.failure(
                    Exception("Delete session failed: ${response.code} - $errorBody")
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 发送聊天消息（SSE流式响应）
     * 返回Flow<SSEEvent>，可以实时接收流式事件
     */
    fun sendMessageStream(
        sessionId: String,
        message: String,
        eventId: String? = null
    ): Flow<SSEEvent> = flow {
        try {
            val chatRequest = ChatRequest(
                message = message,
                timestamp = System.currentTimeMillis() / 1000,
                event_id = eventId
            )

            val requestBody = json.encodeToString(chatRequest).toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$baseUrl/sessions/$sessionId/chat")
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Connection", "keep-alive")
                .header("X-Accel-Buffering", "no")
                .build()

            Log.d("BackendChatClient", "发送SSE请求到: $baseUrl/sessions/$sessionId/chat")

            // 使用回调方式执行请求
            suspendCancellableCoroutine { continuation ->
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("BackendChatClient", "请求失败", e)
                        continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        Log.d("BackendChatClient", "收到响应，响应码: ${response.code}")
                        continuation.resumeWith(Result.success(response))
                    }
                })
            }.let { response ->
                // 检查响应是否成功
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e("BackendChatClient", "请求失败: ${response.code} - $errorBody")
                    emit(SSEEvent(SSEEventType.ERROR, "Request failed: ${response.code} - $errorBody"))
                    response.close()
                    return@flow
                }

                // 检查是否是认证失败
                if (response.code == 401) {
                    Log.w("BackendChatClient", "认证失败，清除认证信息")
                    authManager.clearAuth()
                    emit(SSEEvent(SSEEventType.ERROR, "Authentication failed"))
                    response.close()
                    return@flow
                }

                // 获取响应体
                val responseBody = response.body
                if (responseBody == null) {
                    Log.e("BackendChatClient", "响应体为空")
                    emit(SSEEvent(SSEEventType.ERROR, "Empty response body"))
                    response.close()
                    return@flow
                }

                Log.d("BackendChatClient", "开始读取SSE流...")

                try {
                    // 使用 OkHttp 的 BufferedSource 来读取响应流
                    val source = responseBody.source()
                    var currentEventType: String? = null
                    var currentEventId: String? = null
                    var currentData = StringBuilder()

                    while (!source.exhausted()) {
                        // 读取一行，OkHttp 会自动处理缓冲
                        val line = source.readUtf8Line()
                        
                        if (line != null) {
                            Log.d("BackendChatClient", "收到SSE行: $line")
                            System.out.flush()

                            when {
                                line.startsWith("event:") -> {
                                    // 事件类型
                                    currentEventType = line.substring(6).trim()
                                }
                                line.startsWith("id:") -> {
                                    // 事件 ID
                                    currentEventId = line.substring(3).trim()
                                }
                                line.startsWith("data:") -> {
                                    // 事件数据
                                    val data = line.substring(5).trim()
                                    currentData.append(data)
                                }
                                line.isEmpty() -> {
                                    // 空行表示一个事件结束
                                    if (currentData.isNotEmpty() && currentEventType != null) {
                                        Log.d("BackendChatClient", "解析SSE事件: type=$currentEventType, id=$currentEventId, data长度=${currentData.length}")
                                        System.out.flush()

                                        // 解析事件类型
                                        val eventType = when (currentEventType?.lowercase()) {
                                            // 旧接口事件类型
                                            "message" -> SSEEventType.MESSAGE
                                            "message_chunk" -> SSEEventType.MESSAGE_CHUNK
                                            "title" -> SSEEventType.TITLE
                                            "plan" -> SSEEventType.PLAN
                                            "step" -> SSEEventType.STEP
                                            "tool" -> SSEEventType.TOOL
                                            "deep_thinking_chunk" -> SSEEventType.DEEP_THINKING_CHUNK
                                            "error" -> SSEEventType.ERROR
                                            "done" -> SSEEventType.DONE
                                            "wait" -> SSEEventType.WAIT
                                            // 新接口事件类型
                                            "text_message_start" -> SSEEventType.TEXT_MESSAGE_START
                                            "text_message_chunk" -> SSEEventType.TEXT_MESSAGE_CHUNK
                                            "text_message_end" -> SSEEventType.TEXT_MESSAGE_END
                                            "user_message" -> SSEEventType.USER_MESSAGE
                                            "tool_call_start" -> SSEEventType.TOOL_CALL_START
                                            "tool_call_chunk" -> SSEEventType.TOOL_CALL_CHUNK
                                            "tool_call_result" -> SSEEventType.TOOL_CALL_RESULT
                                            "tool_call_end" -> SSEEventType.TOOL_CALL_END
                                            "plan_started" -> SSEEventType.PLAN_STARTED
                                            "plan_finished" -> SSEEventType.PLAN_FINISHED
                                            "phase_started" -> SSEEventType.PHASE_STARTED
                                            "phase_finished" -> SSEEventType.PHASE_FINISHED
                                            else -> SSEEventType.COMMON
                                        }

                                        // 发送事件
                                        emit(SSEEvent(eventType, currentData.toString()))

                                        // 重置当前事件
                                        currentEventType = null
                                        currentEventId = null
                                        currentData = StringBuilder()
                                    }
                                }
                            }
                        } else {
                            break
                        }
                    }

                    Log.d("BackendChatClient", "SSE流读取完成")
                } finally {
                    response.close()
                }
            }

        } catch (e: Exception) {
            Log.e("BackendChatClient", "SSE流异常", e)
            emit(SSEEvent(SSEEventType.ERROR, e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 发送聊天消息（简化版，返回完整响应）
     */
    suspend fun sendMessage(
        sessionId: String,
        message: String,
        eventId: String? = null,
        onPartialResponse: (String) -> Unit = {},
        onEvent: ((UIEvent) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fullResponse = StringBuilder()
            var hasError = false
            var errorInfo: String? = null
            var sessionTitle: String? = null

            // 收集流式响应
            sendMessageStream(sessionId, message, eventId).collect { event ->
                when (event.type) {
                    SSEEventType.MESSAGE -> {
                        Log.d("BackendChatClient", "收到MESSAGE事件")
                        System.out.flush()
                        try {
                            // 解析消息数据
                            val messageData = json.decodeFromString<MessageData>(event.data)
                            Log.d("BackendChatClient", "解析消息: role=${messageData.role}, content=${messageData.content.take(50)}...")
                            System.out.flush()
                            // 添加内容到聊天框，但需要过滤掉工具执行内容
                            // 工具执行内容通常包含特定的标记或格式，这里暂时不过滤
                            // 如果需要过滤，可以根据 content 的特征来判断
                            fullResponse.append(messageData.content)
                            Log.d("BackendChatClient", "追加内容，当前响应长度: ${fullResponse.length}")
                            System.out.flush()
                            onPartialResponse(messageData.content)
                            // 发送UI事件，role统一设置为 "assistant" 以便作为AI消息显示
                            onEvent?.invoke(
                                UIMessageEvent(
                                    eventId = messageData.event_id,
                                    timestamp = messageData.timestamp,
                                    role = "assistant",  // 统一作为 assistant 消息显示
                                    content = messageData.content,
                                    attachments = messageData.attachments
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析MESSAGE事件失败", e)
                            // 如果解析失败，直接使用原始数据
                            fullResponse.append(event.data)
                            onPartialResponse(event.data)
                        }
                    }
                    SSEEventType.MESSAGE_CHUNK -> {
                        Log.d("BackendChatClient", "收到MESSAGE_CHUNK事件")
                        System.out.flush()
                        try {
                            // 解析消息块数据
                            val chunkData = json.decodeFromString<MessageChunkData>(event.data)
                            Log.d("BackendChatClient", "解析消息块: messageId=${chunkData.message_id}, chunkIndex=${chunkData.chunk_index}, done=${chunkData.done}, chunk=${chunkData.chunk.take(50)}...")
                            System.out.flush()
                            
                            // 追加内容到响应，但需要过滤掉工具执行内容
                            fullResponse.append(chunkData.chunk)
                            Log.d("BackendChatClient", "追加内容，当前响应长度: ${fullResponse.length}")
                            System.out.flush()
                            onPartialResponse(chunkData.chunk)
                            
                            // 发送UI事件
                            onEvent?.invoke(
                                UIMessageChunkEvent(
                                    eventId = chunkData.event_id,
                                    timestamp = chunkData.timestamp,
                                    messageId = chunkData.message_id,
                                    chunk = chunkData.chunk,
                                    chunkIndex = chunkData.chunk_index,
                                    chunkTotal = chunkData.chunk_total,
                                    done = chunkData.done,
                                    attachments = chunkData.attachments
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析MESSAGE_CHUNK事件失败", e)
                        }
                    }
                    SSEEventType.TITLE -> {
                        Log.d("BackendChatClient", "收到TITLE事件")
                        System.out.flush()
                        try {
                            val titleData = json.decodeFromString<TitleData>(event.data)
                            sessionTitle = titleData.title
                            Log.d("BackendChatClient", "会话标题: $sessionTitle")
                            System.out.flush()
                            // 发送UI事件
                            onEvent?.invoke(
                                UITitleEvent(
                                    eventId = titleData.event_id,
                                    timestamp = titleData.timestamp,
                                    title = titleData.title
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析TITLE事件失败", e)
                        }
                    }
                    SSEEventType.PLAN -> {
                        Log.d("BackendChatClient", "收到PLAN事件: ${event.data}")
                        System.out.flush()
                        try {
                            val planData = json.decodeFromString<PlanData>(event.data)
                            // 转换为UI事件
                            val uiSteps = planData.steps.map { step ->
                                UIStep(
                                    id = step.id,
                                    description = step.description,
                                    status = step.status
                                )
                            }
                            onEvent?.invoke(
                                UIPlanEvent(
                                    eventId = planData.event_id,
                                    timestamp = planData.timestamp,
                                    steps = uiSteps
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析PLAN事件失败", e)
                        }
                    }
                    SSEEventType.STEP -> {
                        Log.d("BackendChatClient", "收到STEP事件: ${event.data}")
                        System.out.flush()
                        try {
                            val stepData = json.decodeFromString<StepData>(event.data)
                            // 转换为UI事件
                            val uiStep = UIStep(
                                id = stepData.id,
                                description = stepData.description,
                                status = stepData.status
                            )
                            onEvent?.invoke(
                                UIStepEvent(
                                    eventId = stepData.event_id,
                                    timestamp = stepData.timestamp,
                                    step = uiStep
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析STEP事件失败", e)
                        }
                    }
                    SSEEventType.TOOL -> {
                        Log.d("BackendChatClient", "收到TOOL事件: ${event.data}")
                        System.out.flush()
                        try {
                            val toolData = json.decodeFromString<ToolData>(event.data)
                            // 转换为UI事件
                            val uiToolCall = UIToolCall(
                                toolCallId = toolData.tool_call_id,
                                name = toolData.name,
                                status = toolData.status,
                                function = toolData.function,
                                args = toolData.args,
                                content = toolData.content
                            )
                            onEvent?.invoke(
                                UIToolEvent(
                                    eventId = toolData.event_id,
                                    timestamp = toolData.timestamp,
                                    toolCall = uiToolCall
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析TOOL事件失败", e)
                        }
                    }
                    SSEEventType.DEEP_THINKING_CHUNK -> {
                        Log.d("BackendChatClient", "收到DEEP_THINKING_CHUNK事件: ${event.data}")
                        System.out.flush()
                        try {
                            val deepThinkingChunkData = json.decodeFromString<DeepThinkingChunkData>(event.data)
                            // 发送UI事件
                            onEvent?.invoke(
                                UIDeepThinkingChunkEvent(
                                    eventId = deepThinkingChunkData.event_id,
                                    timestamp = deepThinkingChunkData.timestamp,
                                    chunkType = deepThinkingChunkData.chunk_type,
                                    sectionIndex = deepThinkingChunkData.section_index,
                                    chunkIndex = deepThinkingChunkData.chunk_index,
                                    chunk = deepThinkingChunkData.chunk,
                                    done = deepThinkingChunkData.done
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析DEEP_THINKING_CHUNK事件失败", e)
                        }
                    }
                    SSEEventType.ERROR -> {
                        Log.e("BackendChatClient", "收到ERROR事件: ${event.data}")
                        System.out.flush()
                        hasError = true
                        try {
                            val errorData = json.decodeFromString<ErrorData>(event.data)
                            errorInfo = errorData.error
                            // 检查是否是认证失败
                            if (checkAndHandleAuthFailure(event.data)) {
                                Log.w("BackendChatClient", "SSE事件中检测到认证失败")
                            }
                            // 发送UI事件
                            onEvent?.invoke(
                                UIErrorEvent(
                                    eventId = errorData.event_id,
                                    timestamp = errorData.timestamp,
                                    error = errorData.error
                                )
                            )
                        } catch (e: Exception) {
                            errorInfo = event.data
                            // 检查是否是认证失败
                            if (checkAndHandleAuthFailure(event.data)) {
                                Log.w("BackendChatClient", "SSE事件中检测到认证失败")
                            }
                        }
                    }
                    SSEEventType.DONE -> {
                        Log.d("BackendChatClient", "收到DONE事件，流结束")
                        System.out.flush()
                        // 只有当data是有效的JSON格式时才解析
                        if (event.data.trim().startsWith("{")) {
                            try {
                                val doneData = json.decodeFromString<DoneData>(event.data)
                                // 发送UI事件
                                onEvent?.invoke(
                                    UIDoneEvent(
                                        eventId = doneData.event_id,
                                        timestamp = doneData.timestamp
                                    )
                                )
                            } catch (e: Exception) {
                                Log.e("BackendChatClient", "解析DONE事件失败", e)
                            }
                        } else {
                            // 非JSON格式的DONE事件（如"Connection closed"），只记录日志
                            Log.d("BackendChatClient", "收到非JSON格式的DONE事件: ${event.data}")
                        }
                        // 流结束，collect会自动结束
                    }
                    SSEEventType.WAIT -> {
                        Log.d("BackendChatClient", "收到WAIT事件")
                        System.out.flush()
                        try {
                            val waitData = json.decodeFromString<WaitData>(event.data)
                            // 发送UI事件
                            onEvent?.invoke(
                                UIWaitEvent(
                                    eventId = waitData.event_id,
                                    timestamp = waitData.timestamp
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析WAIT事件失败", e)
                        }
                    }
                    // ==================== 新接口事件处理 ====================
                    SSEEventType.TEXT_MESSAGE_START -> {
                        Log.d("BackendChatClient", "收到TEXT_MESSAGE_START事件")
                        System.out.flush()
                        try {
                            val startData = json.decodeFromString<TextMessageStartData>(event.data)
                            Log.d("BackendChatClient", "文本消息开始: message_id=${startData.message_id}, message_type=${startData.message_type}")
                            System.out.flush()
                            // 发送UI事件
                            onEvent?.invoke(
                                UIMessageChunkEvent(
                                    eventId = startData.id,
                                    timestamp = startData.timestamp,
                                    messageId = startData.message_id,
                                    chunk = "",
                                    chunkIndex = 0,
                                    chunkTotal = null,
                                    done = false,
                                    attachments = null
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析TEXT_MESSAGE_START事件失败", e)
                        }
                    }
                    SSEEventType.TEXT_MESSAGE_CHUNK -> {
                        Log.d("BackendChatClient", "收到TEXT_MESSAGE_CHUNK事件")
                        System.out.flush()
                        try {
                            val chunkData = json.decodeFromString<TextMessageChunkData>(event.data)
                            Log.d("BackendChatClient", "文本消息块: message_id=${chunkData.message_id}, delta=${chunkData.delta.take(50)}...")
                            System.out.flush()
                            
                            // 追加内容到响应
                            fullResponse.append(chunkData.delta)
                            onPartialResponse(chunkData.delta)
                            
                            // 发送UI事件
                            onEvent?.invoke(
                                UIMessageChunkEvent(
                                    eventId = chunkData.id,
                                    timestamp = chunkData.timestamp,
                                    messageId = chunkData.message_id,
                                    chunk = chunkData.delta,
                                    chunkIndex = 0,
                                    chunkTotal = null,
                                    done = false,
                                    attachments = null
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析TEXT_MESSAGE_CHUNK事件失败", e)
                        }
                    }
                    SSEEventType.TEXT_MESSAGE_END -> {
                        Log.d("BackendChatClient", "收到TEXT_MESSAGE_END事件")
                        System.out.flush()
                        try {
                            val endData = json.decodeFromString<TextMessageEndData>(event.data)
                            Log.d("BackendChatClient", "文本消息结束: message_id=${endData.message_id}")
                            System.out.flush()
                            // 发送UI事件，标记消息完成
                            onEvent?.invoke(
                                UIMessageChunkEvent(
                                    eventId = endData.id,
                                    timestamp = endData.timestamp,
                                    messageId = endData.message_id,
                                    chunk = "",
                                    chunkIndex = 0,
                                    chunkTotal = null,
                                    done = true,
                                    attachments = null
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析TEXT_MESSAGE_END事件失败", e)
                        }
                    }
                    SSEEventType.USER_MESSAGE -> {
                        Log.d("BackendChatClient", "收到USER_MESSAGE事件")
                        System.out.flush()
                        try {
                            val userData = json.decodeFromString<UserMessageData>(event.data)
                            Log.d("BackendChatClient", "用户消息: message_id=${userData.message_id}, message=${userData.message.take(50)}...")
                            System.out.flush()
                            // 发送UI事件
                            onEvent?.invoke(
                                UIMessageEvent(
                                    eventId = userData.id,
                                    timestamp = userData.timestamp,
                                    role = "user",
                                    content = userData.message,
                                    attachments = userData.attachments
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析USER_MESSAGE事件失败", e)
                        }
                    }
                    SSEEventType.TOOL_CALL_START -> {
                        Log.d("BackendChatClient", "收到TOOL_CALL_START事件")
                        System.out.flush()
                        try {
                            val startData = json.decodeFromString<ToolCallStartData>(event.data)
                            Log.d("BackendChatClient", "工具调用开始: tool_call_id=${startData.tool_call_id}, tool_call_name=${startData.tool_call_name}")
                            System.out.flush()
                            // 发送UI事件
                            onEvent?.invoke(
                                UIToolEvent(
                                    eventId = startData.id,
                                    timestamp = startData.timestamp,
                                    toolCall = UIToolCall(
                                        toolCallId = startData.tool_call_id,
                                        name = startData.tool_call_name,
                                        status = "pending",
                                        function = "",
                                        args = emptyMap(),
                                        content = null
                                    )
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析TOOL_CALL_START事件失败", e)
                        }
                    }
                    SSEEventType.TOOL_CALL_CHUNK -> {
                        // 暂时忽略 tool_call_chunk
                        Log.d("BackendChatClient", "收到TOOL_CALL_CHUNK事件，暂时忽略")
                        System.out.flush()
                    }
                    SSEEventType.TOOL_CALL_RESULT -> {
                        Log.d("BackendChatClient", "收到TOOL_CALL_RESULT事件")
                        System.out.flush()
                        try {
                            val resultData = json.decodeFromString<ToolCallResultData>(event.data)
                            Log.d("BackendChatClient", "工具调用结果: tool_call_id=${resultData.tool_call_id}")
                            System.out.flush()
                            // 发送UI事件，更新工具状态为 completed
                            onEvent?.invoke(
                                UIToolEvent(
                                    eventId = resultData.id,
                                    timestamp = resultData.timestamp,
                                    toolCall = UIToolCall(
                                        toolCallId = resultData.tool_call_id,
                                        name = resultData.tool_call_name,
                                        status = "completed",
                                        function = "",
                                        args = emptyMap(),
                                        content = resultData.content
                                    )
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析TOOL_CALL_RESULT事件失败", e)
                        }
                    }
                    SSEEventType.TOOL_CALL_END -> {
                        Log.d("BackendChatClient", "收到TOOL_CALL_END事件")
                        System.out.flush()
                        try {
                            val endData = json.decodeFromString<ToolCallEndData>(event.data)
                            Log.d("BackendChatClient", "工具调用结束: tool_call_id=${endData.tool_call_id}")
                            System.out.flush()
                            // 发送UI事件，标记工具调用完成
                            onEvent?.invoke(
                                UIToolEvent(
                                    eventId = endData.id,
                                    timestamp = endData.timestamp,
                                    toolCall = UIToolCall(
                                        toolCallId = endData.tool_call_id,
                                        name = endData.tool_call_name,
                                        status = "completed",
                                        function = "",
                                        args = emptyMap(),
                                        content = null
                                    )
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析TOOL_CALL_END事件失败", e)
                        }
                    }
                    SSEEventType.PLAN_STARTED -> {
                        Log.d("BackendChatClient", "收到PLAN_STARTED事件")
                        System.out.flush()
                        try {
                            val planData = json.decodeFromString<PlanStartedData>(event.data)
                            Log.d("BackendChatClient", "计划开始: goal=${planData.plan.goal}")
                            System.out.flush()
                            // 转换为UI事件
                            val uiSteps = planData.plan.phases.mapIndexed { index, phase ->
                                UIStep(
                                    id = phase.id,
                                    description = phase.title,
                                    status = phase.status
                                )
                            }
                            onEvent?.invoke(
                                UIPlanEvent(
                                    eventId = planData.id,
                                    timestamp = planData.timestamp,
                                    steps = uiSteps
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析PLAN_STARTED事件失败", e)
                        }
                    }
                    SSEEventType.PLAN_FINISHED -> {
                        Log.d("BackendChatClient", "收到PLAN_FINISHED事件")
                        System.out.flush()
                        try {
                            val planData = json.decodeFromString<PlanFinishedData>(event.data)
                            Log.d("BackendChatClient", "计划完成: goal=${planData.plan.goal}")
                            System.out.flush()
                            // 更新计划状态
                            val uiSteps = planData.plan.phases.mapIndexed { index, phase ->
                                UIStep(
                                    id = phase.id,
                                    description = phase.title,
                                    status = phase.status
                                )
                            }
                            onEvent?.invoke(
                                UIPlanEvent(
                                    eventId = planData.id,
                                    timestamp = planData.timestamp,
                                    steps = uiSteps
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析PLAN_FINISHED事件失败", e)
                        }
                    }
                    SSEEventType.PHASE_STARTED -> {
                        Log.d("BackendChatClient", "收到PHASE_STARTED事件")
                        System.out.flush()
                        try {
                            val phaseData = json.decodeFromString<PhaseStartedData>(event.data)
                            Log.d("BackendChatClient", "阶段开始: phase_id=${phaseData.phase_id}, title=${phaseData.title}")
                            System.out.flush()
                            // 发送UI事件
                            onEvent?.invoke(
                                UIStepEvent(
                                    eventId = phaseData.id,
                                    timestamp = phaseData.timestamp,
                                    step = UIStep(
                                        id = phaseData.phase_id,
                                        description = phaseData.title,
                                        status = "running"
                                    )
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析PHASE_STARTED事件失败", e)
                        }
                    }
                    SSEEventType.PHASE_FINISHED -> {
                        Log.d("BackendChatClient", "收到PHASE_FINISHED事件")
                        System.out.flush()
                        try {
                            val phaseData = json.decodeFromString<PhaseFinishedData>(event.data)
                            Log.d("BackendChatClient", "阶段完成: phase_id=${phaseData.phase_id}, title=${phaseData.title}")
                            System.out.flush()
                            // 发送UI事件
                            onEvent?.invoke(
                                UIStepEvent(
                                    eventId = phaseData.id,
                                    timestamp = phaseData.timestamp,
                                    step = UIStep(
                                        id = phaseData.phase_id,
                                        description = phaseData.title,
                                        status = "completed"
                                    )
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("BackendChatClient", "解析PHASE_FINISHED事件失败", e)
                        }
                    }
                    // =======================================================
                    SSEEventType.COMMON -> {
                        Log.d("BackendChatClient", "收到COMMON事件: ${event.data}")
                        System.out.flush()
                        // 通用事件，可以记录或忽略
                    }
                }
            }

            // 检查是否有错误
            if (hasError) {
                Log.e("BackendChatClient", "消息发送失败: $errorInfo")
                return@withContext Result.failure(Exception(errorInfo ?: "Unknown error"))
            }

            if (fullResponse.isEmpty()) {
                Log.w("BackendChatClient", "响应为空")
                return@withContext Result.failure(Exception("Empty response"))
            }

            Log.d("BackendChatClient", "消息发送成功，响应长度: ${fullResponse.length}")
            Log.d("BackendChatClient", "完整响应: ${fullResponse.toString()}")
            Result.success(fullResponse.toString())
        } catch (e: Exception) {
            Log.e("BackendChatClient", "发送消息异常", e)
            Result.failure(e)
        }
    }

    /**
     * 获取会话文件列表
     */
    suspend fun getSessionFiles(sessionId: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/sessions/$sessionId/files")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext Result.failure(
                    Exception("Get files failed: ${response.code} - $errorBody")
                )
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            val fileListResponse = json.decodeFromString<FileListResponse>(responseBody)

            if (fileListResponse.code != 0 || fileListResponse.data == null) {
                return@withContext Result.failure(
                    Exception(fileListResponse.msg)
                )
            }

            Result.success(fileListResponse.data.files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取会话消息历史
     */
    suspend fun getSessionMessages(sessionId: String): Result<List<SessionMessage>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/sessions/$sessionId/messages")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                // 检查是否是认证失败
                if (checkAndHandleAuthFailure(errorBody)) {
                    return@withContext Result.failure(Exception("Authentication failed"))
                }
                return@withContext Result.failure(
                    Exception("Get session messages failed: ${response.code} - $errorBody")
                )
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            // 检查是否是认证失败
            if (checkAndHandleAuthFailure(responseBody)) {
                return@withContext Result.failure(Exception("Authentication failed"))
            }

            val messagesResponse = json.decodeFromString<SessionMessagesResponse>(responseBody)

            if (messagesResponse.code != 0 || messagesResponse.data == null) {
                return@withContext Result.failure(
                    Exception(messagesResponse.msg)
                )
            }

            Result.success(messagesResponse.data.messages)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取会话详细信息（包含事件列表）
     */
    suspend fun getSessionDetail(sessionId: String): Result<SessionDetailData> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/sessions/$sessionId")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                // 检查是否是认证失败
                if (checkAndHandleAuthFailure(errorBody)) {
                    return@withContext Result.failure(Exception("Authentication failed"))
                }
                return@withContext Result.failure(
                    Exception("Get session detail failed: ${response.code} - $errorBody")
                )
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))

            // 检查是否是认证失败
            if (checkAndHandleAuthFailure(responseBody)) {
                return@withContext Result.failure(Exception("Authentication failed"))
            }

            val detailResponse = json.decodeFromString<SessionDetailResponse>(responseBody)

            if (detailResponse.code != 0 || detailResponse.data == null) {
                return@withContext Result.failure(
                    Exception(detailResponse.msg)
                )
            }

            Result.success(detailResponse.data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 停止会话任务
     */
    suspend fun stopSession(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/sessions/$sessionId/stop")
                .post("".toRequestBody(mediaType))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e("BackendChatClient", "Stop session failed: ${response.code} - $errorBody")
                // 检查是否是认证失败
                if (checkAndHandleAuthFailure(errorBody)) {
                    return@withContext Result.failure(Exception("Authentication failed"))
                }
                return@withContext Result.failure(
                    Exception("Stop session failed: ${response.code} - $errorBody")
                )
            }

            Log.d("BackendChatClient", "Stop session success: $sessionId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("BackendChatClient", "Stop session error", e)
            Result.failure(e)
        }
    }
}