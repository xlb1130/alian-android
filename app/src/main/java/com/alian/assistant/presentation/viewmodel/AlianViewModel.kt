package com.alian.assistant.presentation.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alian.assistant.App
import com.alian.assistant.core.alian.backend.UIEvent
import com.alian.assistant.core.alian.AlianClient
import com.alian.assistant.core.alian.backend.Attachment
import com.alian.assistant.core.alian.backend.AuthManager
import com.alian.assistant.core.alian.backend.BackendChatClient
import com.alian.assistant.core.alian.backend.ChatAttachmentRef
import com.alian.assistant.core.alian.backend.DeepThinkingChunkData
import com.alian.assistant.core.alian.backend.MessageChunkData
import com.alian.assistant.core.alian.backend.MessageData
import com.alian.assistant.core.alian.backend.PlanData
import com.alian.assistant.core.alian.backend.SessionData
import com.alian.assistant.core.alian.backend.SessionEvent
import com.alian.assistant.core.alian.backend.StepData
import com.alian.assistant.core.alian.backend.ToolData
// 新接口数据模型导入
import com.alian.assistant.core.alian.backend.TextMessageStartData
import com.alian.assistant.core.alian.backend.TextMessageChunkData
import com.alian.assistant.core.alian.backend.TextMessageEndData
import com.alian.assistant.core.alian.backend.UserMessageData
import com.alian.assistant.core.alian.backend.ToolCallStartData
import com.alian.assistant.core.alian.backend.ToolCallChunkData
import com.alian.assistant.core.alian.backend.ToolCallResultData
import com.alian.assistant.core.alian.backend.ToolCallEndData
import com.alian.assistant.core.alian.backend.PlanStartedData
import com.alian.assistant.core.alian.backend.PlanFinishedData
import com.alian.assistant.core.alian.backend.PhaseStartedData
import com.alian.assistant.core.alian.backend.PhaseFinishedData
import com.alian.assistant.core.alian.backend.UIDeepThinkingChunkEvent
import com.alian.assistant.core.alian.backend.UIDoneEvent
import com.alian.assistant.core.alian.backend.UIMessageChunkEvent
import com.alian.assistant.core.alian.backend.UIMessageEvent
import com.alian.assistant.core.alian.backend.UIPlanEvent
import com.alian.assistant.core.alian.backend.UIStep
import com.alian.assistant.core.alian.backend.UIStepEvent
import com.alian.assistant.core.alian.backend.UIToolCall
import com.alian.assistant.core.alian.backend.UIToolEvent
import com.alian.assistant.infrastructure.ai.tts.HybridTtsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * 聊天消息
 */
data class ChatMessage(
    val id: String = "",
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList()
)

/**
 * 待发送附件（本地）
 */
data class PendingUploadAttachment(
    val id: String,
    val uriString: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long? = null
)

/**
 * 深度思考章节
 */
data class DeepThinkingSection(
    val eventId: String = "",
    val title: String = "",
    val paragraphs: SnapshotStateList<String> = mutableStateListOf(),
    val timestamp: Long = System.currentTimeMillis(),
    val isExpanded: MutableState<Boolean> = mutableStateOf(false)
)

/**
 * 统一的聊天项类型
 * 用于按时间顺序渲染所有聊天内容（消息、深度思考、计划）
 */
sealed class UnifiedChatItem {
    abstract val timestamp: Long
}

/**
 * 消息项
 */
data class MessageItem(
    val message: ChatMessage
) : UnifiedChatItem() {
    override val timestamp: Long = message.timestamp
}

/**
 * 深度思考项
 */
data class DeepThinkingItem(
    val section: DeepThinkingSection
) : UnifiedChatItem() {
    override val timestamp: Long = section.timestamp
}

/**
 * 计划项
 */
data class PlanItem(
    val planEvent: UIPlanEvent
) : UnifiedChatItem() {
    override val timestamp: Long = planEvent.timestamp
}

/**
 * Alian 对话状态
 */
sealed class AlianChatState {
    object Idle : AlianChatState()
    object Loading : AlianChatState()
    data class Success(val message: ChatMessage) : AlianChatState()
    data class Error(val message: String) : AlianChatState()
}

/**
 * 历史会话加载状态
 */
sealed class SessionLoadingState {
    object Idle : SessionLoadingState()
    data class Switching(val sessionId: String) : SessionLoadingState()
    data class Loaded(val sessionId: String) : SessionLoadingState()
    data class Failed(val sessionId: String, val message: String) : SessionLoadingState()
}

/**
 * Alian 对话 ViewModel
 */
class AlianViewModel(private val context: Context) : ViewModel() {
    // 配置 Json 实例，忽略未知键以兼容后端返回的额外字段
    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: SnapshotStateList<ChatMessage> = _messages

    private val _chatState = mutableStateOf<AlianChatState>(AlianChatState.Idle)
    val chatState: MutableState<AlianChatState> = _chatState

    // 登录状态
    private val _isLoggedIn = mutableStateOf(false)
    val isLoggedIn: State<Boolean> = _isLoggedIn

    // UI事件列表（用于显示工具调用、步骤等）
    private val _uiEvents = mutableStateListOf<UIEvent>()
    val uiEvents: SnapshotStateList<UIEvent> = _uiEvents

    // 深度思考章节列表
    private val _deepThinkingSections = mutableStateListOf<DeepThinkingSection>()
    val deepThinkingSections: SnapshotStateList<DeepThinkingSection> = _deepThinkingSections

    // 统一的聊天时间线（按时间顺序排列所有聊天内容）
    private val _unifiedChatTimeline = mutableStateListOf<UnifiedChatItem>()
    val unifiedChatTimeline: SnapshotStateList<UnifiedChatItem> = _unifiedChatTimeline

    // 工具调用列表
    private val _toolCalls = mutableStateListOf<UIToolCall>()
    val toolCalls: SnapshotStateList<UIToolCall> = _toolCalls

    // 当前消息的附件收集器
    private val _currentAttachments = mutableStateListOf<Attachment>()
    val currentAttachments: SnapshotStateList<Attachment> = _currentAttachments

    // 输入框待发送附件
    private val _pendingUploadAttachments = mutableStateListOf<PendingUploadAttachment>()
    val pendingUploadAttachments: SnapshotStateList<PendingUploadAttachment> = _pendingUploadAttachments

    // 处理状态（机器人是否正在输出）
    private val _isProcessing = mutableStateOf(false)
    val isProcessing: State<Boolean> = _isProcessing

    // 当前正在执行的 tool 名称（用于显示加载提示）
    private val _currentToolName = mutableStateOf<String?>(null)
    val currentToolName: State<String?> = _currentToolName

    // 当前正在执行的 step 名称（用于显示加载提示）
    private val _currentStepName = mutableStateOf<String?>(null)
    val currentStepName: State<String?> = _currentStepName

    // 会话列表
    private val _sessions = mutableStateListOf<SessionData>()
    val sessions: SnapshotStateList<SessionData> = _sessions

    // 加载会话列表状态
    private val _isLoadingSessions = mutableStateOf(false)
    val isLoadingSessions: State<Boolean> = _isLoadingSessions

    // 当前聊天区会话切换状态（用于异步加载历史）
    private val _sessionLoadingState = mutableStateOf<SessionLoadingState>(SessionLoadingState.Idle)
    val sessionLoadingState: State<SessionLoadingState> = _sessionLoadingState

    // 当前选中的会话（用于抽屉高亮）
    private val _currentSessionIdUi = mutableStateOf<String?>(null)
    val currentSessionIdUi: State<String?> = _currentSessionIdUi

    // API 模式
    var useBackend: Boolean = false

    // API 配置
    var apiKey: String = ""
    var baseUrl: String = ""
    var model: String = ""
    var backendBaseUrl: String = "http://39.98.113.244:5173/api/v1"

    // 登录凭据
    private val _email = mutableStateOf("xlb1130@vip.qq.com")
    val email: MutableState<String> = _email

    private val _password = mutableStateOf("")
    val password: MutableState<String> = _password

    // 登录加载状态
    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading

    // 登录错误信息
    private val _errorMessage = mutableStateOf<String?>(null)
    val errorMessage: State<String?> = _errorMessage

    // 更新邮箱和密码的方法
    fun updateEmail(newEmail: String) {
        _email.value = newEmail
    }

    fun updatePassword(newPassword: String) {
        _password.value = newPassword
    }

    // Alian 客户端（在初始化或配置更新时创建）
    private var alianClient: AlianClient? = null

    // 当前消息发送任务
    private var currentMessageJob: Job? = null

    // 当前会话历史加载任务（点击历史时异步加载）
    private var sessionLoadJob: Job? = null
    private var sessionLoadToken: Long = 0L
    private var lastRequestedSessionId: String? = null

    // 每轮用户输入对应一个 Plan 气泡：记录当前轮的 Plan 锚点
    private var activePlanBubbleEventId: String? = null
    private var activePlanBubbleTimestamp: Long? = null
    private var activePlanBoundaryUserMessageId: String? = null

    /**
     * 获取当前会话ID
     */
    fun getCurrentSessionId(): String? {
        return alianClient?.getCurrentSessionId()
    }

    /**
     * 获取 BackendChatClient
     */
    fun getBackendClient(): BackendChatClient? {
        return alianClient?.getBackendClient()
    }

    // TTS 配置
    var ttsEnabled: Boolean = false
    var ttsRealtime: Boolean = false
    var ttsVoice: String = "longyingmu_v3"
    var offlineTtsEnabled: Boolean = false
    var offlineTtsAutoFallbackToCloud: Boolean = true
    private var ttsClient: HybridTtsClient? = null

    // TTS 播放状态
    private val _isPlayingTTS = mutableStateOf(false)
    val isPlayingTTS: State<Boolean> = _isPlayingTTS

    // 当前正在播放的消息ID
    private val _currentPlayingMessageId = mutableStateOf<String?>(null)
    val currentPlayingMessageId: State<String?> = _currentPlayingMessageId

    // TTS 播放队列
    private val ttsQueue = mutableStateListOf<String>()
    private var isQueueProcessing = false

    // 流式消息块 TTS 缓冲区
    private var ttsChunkBuffer = StringBuilder()
    private var isTTSChunkPlaying = false
    private var currentMessageIdForTTS: String? = null
    private var streamingSessionStarted = false

    // 流式消息块队列（保证顺序）
    private val streamingChunkQueue = Channel<StreamingChunk>(capacity = Channel.UNLIMITED)
    private var isProcessingStreamingQueue = false

    // 流式消息块数据类
    private data class StreamingChunk(
        val messageId: String,
        val chunk: String,
        val done: Boolean
    )

    init {
        // 检查是否已登录
        checkLoginStatus()
        // 设置认证清除回调，当 AuthInterceptor 检测到认证失败时自动更新登录状态
        setupAuthClearedCallback()
    }

    /**
     * 设置认证清除回调
     * 当 AuthInterceptor 检测到认证失败并清除认证信息时，自动更新登录状态
     */
    private fun setupAuthClearedCallback() {
        val authManager = AuthManager.getInstance(context)
        authManager.onAuthCleared = {
            Log.d("AlianViewModel", "认证信息被清除，更新登录状态为 false")
            _isLoggedIn.value = false
        }
    }

    /**
     * 更新AlianClient配置
     */
    private fun updateAlianClient() {
        alianClient = if (useBackend) {
            AlianClient(
                context = context,
                useBackend = true,
                baseUrl = backendBaseUrl.ifBlank { "http://39.98.113.244:5173/api/v1" }
            )
        } else {
            AlianClient(
                apiKey = apiKey,
                baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
                model = model.ifBlank { "qwen-plus" }
            )
        }
        Log.d("AlianViewModel", "AlianClient已更新: useBackend=$useBackend")
    }

    /**
     * 更新TTS客户端配置
     */
    private fun updateTTSClient() {
        ttsClient?.release()
        ttsClient = null
        if (ttsEnabled) {
            ttsClient = HybridTtsClient(
                appContext = context,
                apiKey = apiKey,
                voice = ttsVoice,
                offlineTtsEnabled = offlineTtsEnabled,
                offlineTtsAutoFallbackToCloud = offlineTtsAutoFallbackToCloud
            )
            Log.d(
                "AlianViewModel",
                "TTS客户端已初始化: voice=$ttsVoice, offlineTtsEnabled=$offlineTtsEnabled"
            )
        } else {
            ttsClient = null
            Log.d("AlianViewModel", "TTS客户端已禁用")
        }
    }

    /**
     * 更新TTS配置
     */
    fun updateTTSConfig(
        enabled: Boolean,
        realtime: Boolean,
        voice: String,
        offlineEnabled: Boolean = false,
        offlineAutoFallbackToCloud: Boolean = true
    ) {
        ttsEnabled = enabled
        ttsRealtime = realtime
        ttsVoice = voice
        offlineTtsEnabled = offlineEnabled
        offlineTtsAutoFallbackToCloud = offlineAutoFallbackToCloud
        updateTTSClient()
    }

    /**
     * 更新Backend配置
     */
    fun updateBackendConfig(newUseBackend: Boolean, newBackendBaseUrl: String) {
        Log.d("AlianViewModel", "updateBackendConfig: useBackend=$newUseBackend, backendBaseUrl=$newBackendBaseUrl")
        
        val oldUseBackend = useBackend
        useBackend = newUseBackend
        backendBaseUrl = newBackendBaseUrl
        
        // 如果useBackend从false变为true，或者backendBaseUrl发生变化，需要重新检查登录状态
        if (!oldUseBackend && newUseBackend) {
            Log.d("AlianViewModel", "useBackend从false变为true，重新检查登录状态")
            checkLoginStatus()
        } else if (oldUseBackend && newUseBackend) {
            // useBackend保持为true，但backendBaseUrl可能变化，重新检查登录状态
            Log.d("AlianViewModel", "useBackend保持为true，backendBaseUrl可能变化，重新检查登录状态")
            checkLoginStatus()
        } else {
            // useBackend从true变为false，设置为未登录
            Log.d("AlianViewModel", "useBackend从true变为false，设置为未登录")
            _isLoggedIn.value = false
        }
    }

    /**
     * 检查登录状态
     */
    fun checkLoginStatus() {
        Log.d("AlianViewModel", "checkLoginStatus 被调用: useBackend=$useBackend")
        
        if (useBackend) {
            updateAlianClient()
            
            // 使用AuthManager检查登录状态
            val authManager = AuthManager.getInstance(context)
            val token = authManager.getAccessToken()
            val refreshToken = authManager.getRefreshToken()
            val userEmail = authManager.getUserEmail()
            
            Log.d("AlianViewModel", "Token 状态: ${if (token != null) "存在" else "不存在"}")
            Log.d("AlianViewModel", "RefreshToken 状态: ${if (refreshToken != null) "存在" else "不存在"}")
            Log.d("AlianViewModel", "UserEmail: $userEmail")
            
            if (token != null) {
                // Token 存在，尝试验证 token 是否有效
                Log.d("AlianViewModel", "Token 存在，验证有效性...")
                Log.d("AlianViewModel", "Token 长度: ${token.length}, 前10个字符: ${token.take(10)}...")
                
                // 检查 token 是否过期（本地检查）
                val isExpired = authManager.isTokenExpired()
                val isExpiringSoon = authManager.isTokenExpiringSoon()
                val expireTime = authManager.getTokenExpireTime()
                val currentTime = System.currentTimeMillis()
                
                Log.d("AlianViewModel", "Token 过期状态: isExpired=$isExpired, isExpiringSoon=$isExpiringSoon")
                Log.d("AlianViewModel", "Token 过期时间: $expireTime, 当前时间: $currentTime")
                Log.d("AlianViewModel", "Token 剩余时间: ${expireTime - currentTime}ms (${(expireTime - currentTime) / 1000 / 60}分钟)")
                
                if (isExpired) {
                    // Token 已过期，清除过期的 token（会通过 onAuthCleared 回调自动设置 _isLoggedIn.value = false）
                    authManager.clearAuth()
                    Log.d("AlianViewModel", "Token 已过期（本地检查），已清除")
                } else {
                    // Token 未过期，先暂时设置为已登录（乐观假设），然后异步验证
                    _isLoggedIn.value = true
                    Log.d("AlianViewModel", "Token 未过期（本地检查），暂时设置为已登录，调用后端接口验证...")
                    
                    viewModelScope.launch {
                        try {
                            val backendClient = getBackendClient()
                            if (backendClient != null) {
                                val isValid = backendClient.checkAuthStatus()
                                _isLoggedIn.value = isValid
                                Log.d("AlianViewModel", "后端认证状态检查: isValid=$isValid, isLoggedIn=${_isLoggedIn.value}")
                                
                                if (!isValid) {
                                    // 后端验证失败，清除认证信息（会通过 onAuthCleared 回调自动设置 _isLoggedIn.value = false）
                                    authManager.clearAuth()
                                    Log.d("AlianViewModel", "后端认证失败，已清除认证信息")
                                }
                            } else {
                                // 无法创建 BackendChatClient，但如果有 token，保持登录状态
                                Log.w("AlianViewModel", "无法创建 BackendChatClient，但 token 存在，保持登录状态")
                                _isLoggedIn.value = true
                            }
                        } catch (e: Exception) {
                            // 网络错误等，保持现有登录状态（不设置为 false）
                            Log.e("AlianViewModel", "后端认证状态检查失败（网络错误）: ${e.message}", e)
                            Log.d("AlianViewModel", "网络错误，保持现有登录状态: isLoggedIn=${_isLoggedIn.value}")
                            // 不修改 _isLoggedIn.value，保持现有状态
                        }
                    }
                }
            } else {
                _isLoggedIn.value = false
                Log.d("AlianViewModel", "Token 不存在，未登录")
            }
        } else {
            // 不使用 Backend 模式，设置为未登录
            _isLoggedIn.value = false
            Log.d("AlianViewModel", "不使用 Backend 模式，设置为未登录")
        }
    }

    fun addUserMessage(content: String, attachments: List<Attachment> = emptyList()) {
        if (content.isNotBlank() || attachments.isNotEmpty()) {
            // 新的一轮用户输入，重置 Plan 锚点
            activePlanBubbleEventId = null
            activePlanBubbleTimestamp = null
            val message = ChatMessage(
                id = generateMessageId(),
                content = content,
                isUser = true,
                attachments = attachments
            )
            activePlanBoundaryUserMessageId = message.id
            _messages.add(message)
            // 添加到统一时间线
            _unifiedChatTimeline.add(MessageItem(message))
            _unifiedChatTimeline.sortBy { it.timestamp }
            Log.d("AlianViewModel", "添加用户消息: timestamp=${message.timestamp}, content=$content.take(20)}..., 附件数量=${attachments.size}")
        }
    }

    fun addAssistantMessage(content: String) {
        addAssistantMessage(content, emptyList())
    }

    fun addAssistantMessage(content: String, attachments: List<Attachment>) {
        val message = ChatMessage(
            id = generateMessageId(),
            content = content,
            isUser = false,
            attachments = attachments
        )
        _messages.add(message)
        Log.d("AlianViewModel", "添加助手消息: id=${message.id}, timestamp=${message.timestamp}, content=$content.take(20)}..., 总消息数=${_messages.size}, 附件数量=${attachments.size}")

        // 添加到统一时间线
        _unifiedChatTimeline.add(MessageItem(message))
        _unifiedChatTimeline.sortBy { it.timestamp }

        // 如果启用了实时播放，则添加到播放队列
        if (ttsEnabled && ttsRealtime && content.isNotBlank()) {
            enqueueTTSMessage(message.id)
        }
    }

    /**
     * 更新最后一条AI消息的内容
     */
    fun updateLastAssistantMessage(content: String) {
        if (_messages.isNotEmpty()) {
            val lastIndex = _messages.size - 1
            val lastMessage = _messages[lastIndex]
            if (!lastMessage.isUser) {
                // 最后一条是 AI 消息，更新它
                _messages[lastIndex] = lastMessage.copy(content = content)
                Log.d("AlianViewModel", "更新最后一条AI消息: content=$content")
            } else {
                // 最后一条是用户消息，添加新的 AI 消息
                addAssistantMessage(content)
                Log.d("AlianViewModel", "添加新的AI消息（因为最后一条是用户消息）: content=$content")
            }
        } else {
            // 没有消息，添加新的 AI 消息
            addAssistantMessage(content)
            Log.d("AlianViewModel", "添加新的AI消息（因为没有消息）: content=$content")
        }
    }

    /**
     * 添加UI事件
     */
    fun addUIEvent(event: UIEvent) {
        _uiEvents.add(event)
        Log.d("AlianViewModel", "添加UI事件: ${event.javaClass.simpleName}")
        System.out.flush()
        
        // 处理不同类型的事件
        when (event) {
            is UIMessageChunkEvent -> {
                // 流式消息块事件
                Log.d("AlianViewModel", "收到消息块事件: messageId=${event.messageId}, chunkIndex=${event.chunkIndex}, done=${event.done}, chunk=${event.chunk.take(50)}...")
                System.out.flush()

                // 收集附件
                if (event.attachments != null && event.attachments.isNotEmpty()) {
                    Log.d("AlianViewModel", "收集到 ${event.attachments.size} 个附件")
                    _currentAttachments.addAll(event.attachments)
                }

                // 查找或创建对应的消息
                val existingIndex = _messages.indexOfFirst { it.id == event.messageId }

                if (existingIndex >= 0) {
                    // 更新现有消息 - 先移除再添加以确保 Compose 正确触发重组
                    val existingMessage = _messages[existingIndex]
                    val updatedContent = existingMessage.content + event.chunk
                    
                    // 更新附件列表（用于显示预览按钮）
                    val updatedAttachments = if (event.attachments != null && event.attachments.isNotEmpty()) {
                        // 合并现有附件和新附件
                        (existingMessage.attachments + event.attachments).distinctBy { it.file_url ?: it.url ?: it.path }
                    } else {
                        existingMessage.attachments
                    }
                    
                    val updatedMessage = existingMessage.copy(content = updatedContent, attachments = updatedAttachments)
                    _messages.removeAt(existingIndex)
                    _messages.add(existingIndex, updatedMessage)
                    Log.d("AlianViewModel", "更新消息内容: messageId=${event.messageId}, 新内容长度=${updatedContent.length}, 附件数量=${updatedAttachments.size}")
                    System.out.flush()

                    // 同步更新 _unifiedChatTimeline 中的 MessageItem
                    val existingTimelineIndex = _unifiedChatTimeline.indexOfFirst { 
                        it is MessageItem && it.message.id == event.messageId 
                    }
                    if (existingTimelineIndex >= 0) {
                        _unifiedChatTimeline.removeAt(existingTimelineIndex)
                        _unifiedChatTimeline.add(existingTimelineIndex, MessageItem(updatedMessage))
                        Log.d("AlianViewModel", "更新 _unifiedChatTimeline 中的 MessageItem: messageId=${event.messageId}")
                    } else {
                        // 如果时间线中没有找到，添加新的 MessageItem
                        _unifiedChatTimeline.add(MessageItem(updatedMessage))
                        _unifiedChatTimeline.sortBy { it.timestamp }
                        Log.d("AlianViewModel", "添加新的 MessageItem 到 _unifiedChatTimeline: messageId=${event.messageId}")
                    }
                    System.out.flush()

                    // 如果消息完成，清空附件收集器（附件已通过 attachments 字段存储）
                    if (event.done && _currentAttachments.isNotEmpty()) {
                        Log.d("AlianViewModel", "消息完成，清空附件收集器，已收集 ${_currentAttachments.size} 个附件")
                        _currentAttachments.clear()
                    }
                } else {
                    // 创建新消息
                    val initialAttachments = if (event.attachments != null && event.attachments.isNotEmpty()) {
                        event.attachments
                    } else {
                        emptyList()
                    }
                    val newMessage = ChatMessage(
                        id = event.messageId,
                        content = event.chunk,
                        isUser = false,
                        attachments = initialAttachments
                    )
                    _messages.add(newMessage)
                    Log.d("AlianViewModel", "创建新消息: messageId=${event.messageId}, 消息列表大小: ${_messages.size}, 附件数量=${initialAttachments.size}")
                    
                    // 同步添加到 _unifiedChatTimeline
                    _unifiedChatTimeline.add(MessageItem(newMessage))
                    _unifiedChatTimeline.sortBy { it.timestamp }
                    Log.d("AlianViewModel", "添加 MessageItem 到 _unifiedChatTimeline: messageId=${event.messageId}")
                    System.out.flush()
                }

                // 实时 TTS：
                // 1. 离线模式：消息气泡完成后整条播放（不按 chunk 播放）
                // 2. 在线模式：保持原有流式 chunk 播放逻辑
                if (ttsEnabled && ttsRealtime) {
                    if (offlineTtsEnabled) {
                        if (event.done) {
                            val finishedMessage = _messages.find { it.id == event.messageId }
                            if (finishedMessage != null && !finishedMessage.isUser && finishedMessage.content.isNotBlank()) {
                                enqueueTTSMessage(event.messageId)
                            } else {
                                Log.d(
                                    "AlianViewModel",
                                    "离线实时TTS跳过播放: messageId=${event.messageId}, hasMessage=${finishedMessage != null}, contentBlank=${finishedMessage?.content.isNullOrBlank()}"
                                )
                            }
                        }
                    } else if (event.chunk.isNotBlank()) {
                        accumulateTTSChunk(event.messageId, event.chunk, event.done)
                    }
                }
            }
            is UIMessageEvent -> {
                // 普通消息事件
                // 兼容 user/assistant 角色
                val attachments = event.attachments ?: emptyList()
                if (event.role == "user") {
                    if (event.content.isNotBlank()) {
                        val message = ChatMessage(
                            id = generateMessageId(),
                            content = event.content,
                            isUser = true,
                            timestamp = normalizeTimestamp(event.timestamp),
                            attachments = attachments
                        )
                        activePlanBubbleEventId = null
                        activePlanBubbleTimestamp = null
                        activePlanBoundaryUserMessageId = message.id
                        _messages.add(message)
                        _unifiedChatTimeline.add(MessageItem(message))
                        _unifiedChatTimeline.sortBy { it.timestamp }
                    } else {
                        Log.d("AlianViewModel", "忽略空的用户消息事件: eventId=${event.eventId}")
                    }
                } else {
                    // assistant 消息允许"空文本 + 附件"场景
                    if (event.content.isNotBlank() || attachments.isNotEmpty()) {
                        addAssistantMessage(
                            event.content,
                            attachments
                        )
                    } else {
                        Log.d("AlianViewModel", "忽略空的助手消息事件: eventId=${event.eventId}")
                    }
                }
            }
            is UIToolEvent -> {
                val toolCall = event.toolCall
                Log.d("AlianViewModel", "工具调用事件: name=${toolCall.name}, status=${toolCall.status}, toolCallId=${toolCall.toolCallId}")
                Log.d("AlianViewModel", "当前工具名称（更新前）: ${_currentToolName.value}")
                System.out.flush()

                // 清空测试工具名称（如果存在）
                if (_currentToolName.value == "测试工具") {
                    _currentToolName.value = null
                    Log.d("AlianViewModel", "清空测试工具名称")
                }

                // 查找是否已存在该工具调用（通过 toolCallId）
                val existingIndex = _toolCalls.indexOfFirst { it.toolCallId == toolCall.toolCallId }

                // 更新当前正在执行的 tool 名称
                // 只在工具状态为 calling 时更新，避免用 null 覆盖
                val status = toolCall.status.lowercase()
                Log.d("AlianViewModel", "工具状态（小写后）: $status")
                if (status == "calling") {
                    // 只有当工具名称不为 null 时才更新
                    if (toolCall.name.isNotBlank()) {
                        _currentToolName.value = toolCall.name
                        Log.d("AlianViewModel", "✓ 设置当前工具名称: ${toolCall.name}")
                    } else {
                        Log.d("AlianViewModel", "工具名称为空，不更新当前工具名称")
                    }
                } else if (status in listOf("called", "failed", "cancelled")) {
                    // 工具完成时，不立即清空 currentToolName
                    // 保持显示最后一个工具的名称，直到有新的工具开始执行或整个对话完成
                    Log.d("AlianViewModel", "工具完成，保持当前工具名称: ${_currentToolName.value}")
                } else {
                    Log.d("AlianViewModel", "工具状态 '$status' 不匹配任何已知状态，不更新当前工具名称")
                }
                Log.d("AlianViewModel", "当前工具名称（更新后）: ${_currentToolName.value}")
                System.out.flush()

                if (existingIndex >= 0) {
                    // 更新现有工具调用 - 先移除再添加以确保 UI 正确更新
                    _toolCalls.removeAt(existingIndex)
                    _toolCalls.add(existingIndex, toolCall)
                    Log.d("AlianViewModel", "更新工具调用: ${toolCall.name}, status=${toolCall.status}, toolCallId=${toolCall.toolCallId}")
                    System.out.flush()
                } else {
                    // 添加新工具调用
                    _toolCalls.add(toolCall)
                    Log.d("AlianViewModel", "添加工具调用: ${toolCall.name}, status=${toolCall.status}, toolCallId=${toolCall.toolCallId}")
                    System.out.flush()
                }

                // 将工具名称关联到正在进行中的 step
                Log.d("AlianViewModel", "尝试将工具名称 ${toolCall.name} 关联到 step，工具状态: ${toolCall.status}")
                System.out.flush()

                // 查找最后一个 UIPlanEvent
                val planEventIndex = _uiEvents.indexOfLast { it is UIPlanEvent }
                Log.d("AlianViewModel", "查找 UIPlanEvent，索引: $planEventIndex")
                System.out.flush()

                if (planEventIndex >= 0) {
                    val planEvent = _uiEvents[planEventIndex] as UIPlanEvent
                    Log.d("AlianViewModel", "找到 UIPlanEvent，步骤数量: ${planEvent.steps.size}")
                    System.out.flush()

                    // 查找最近一个状态为 in_progress、running 或 completed 的 step
                    // 按照步骤顺序查找，找到最后一个非pending的step
                    val targetStep = planEvent.steps.lastOrNull { 
                        it.status.lowercase() in listOf("in_progress", "running", "completed")
                    }
                    Log.d("AlianViewModel", "查找目标 step: ${targetStep?.id}, 所有steps状态: ${planEvent.steps.map { "id=${it.id}, status=${it.status}, toolCallIds=${it.toolCallIds}" }}")
                    System.out.flush()

                    if (targetStep != null) {
                        // 更新该 step 的工具调用ID列表（追加新工具的tool_call_id）
                        val updatedSteps = planEvent.steps.map { step ->
                            if (step.id == targetStep.id) {
                                // 检查tool_call_id是否已存在
                                if (!step.toolCallIds.contains(toolCall.toolCallId)) {
                                    val newToolCallIds = step.toolCallIds + toolCall.toolCallId
                                    Log.d("AlianViewModel", "追加 tool_call_id ${toolCall.toolCallId} 到 step ${step.id}")
                                    step.copy(toolCallIds = newToolCallIds)
                                } else {
                                    step
                                }
                            } else {
                                step
                            }
                        }

                        // 创建更新后的 UIPlanEvent
                        val updatedPlanEvent = planEvent.copy(steps = updatedSteps)

                        // 先移除再添加以确保 Compose 正确触发重组
                        _uiEvents.removeAt(planEventIndex)
                        _uiEvents.add(planEventIndex, updatedPlanEvent)
                        syncPlanItemInTimeline(updatedPlanEvent)

                        Log.d("AlianViewModel", "成功将工具 ${toolCall.name} (tool_call_id=${toolCall.toolCallId}) 关联到 step ${targetStep.id}")
                        System.out.flush()
                    } else {
                        Log.w("AlianViewModel", "没有找到目标 step")
                        System.out.flush()
                    }
                } else {
                    Log.w("AlianViewModel", "没有找到 UIPlanEvent")
                    System.out.flush()
                }
            }
            is UIDeepThinkingChunkEvent -> {
                Log.d("AlianViewModel", "收到深度思考块事件: chunkType=${event.chunkType}, sectionIndex=${event.sectionIndex}, chunkIndex=${event.chunkIndex}, done=${event.done}, chunk=${event.chunk.take(50)}...")
                System.out.flush()

                // 确保只有一个章节（index 0）
                while (_deepThinkingSections.size <= 0) {
                    _deepThinkingSections.add(DeepThinkingSection(
                        eventId = event.eventId,
                        timestamp = normalizeTimestamp(event.timestamp),
                        title = "深度思考",
                        paragraphs = mutableStateListOf("")  // 主段落
                    ))
                }

                val section = _deepThinkingSections[0]

                if (event.chunkType == "paragraph") {
                    // 将 chunk 追加到缓冲区（不添加引用标记）
                    if (section.paragraphs.isEmpty()) {
                        section.paragraphs.add("")
                        section.paragraphs.add("")  // 索引 1 用于缓冲区
                    } else if (section.paragraphs.size == 1) {
                        section.paragraphs.add("")  // 添加缓冲区
                    }
                    // 累积到缓冲区（索引 1）- 使用直接赋值方式
                    section.paragraphs[1] = section.paragraphs[1] + event.chunk
                    Log.d("AlianViewModel", "累积内容到缓冲区: ${event.chunk.take(50)}...")
                    System.out.flush()

                    // 如果 done=true，将缓冲区内容包裹在引用块中添加到主段落
                    if (event.done) {
                        val bufferContent = section.paragraphs[1]
                        if (bufferContent.isNotBlank()) {
                            // 将缓冲区内容包裹在引用块中
                            val quotedContent = bufferContent.split("\n").joinToString("\n") { line ->
                                if (line.isBlank()) {
                                    ">"
                                } else {
                                    "> $line"
                                }
                            }
                            section.paragraphs[0] = section.paragraphs[0] + quotedContent + "\n\n"
                            // 清空缓冲区
                            section.paragraphs[1] = ""
                            Log.d("AlianViewModel", "将缓冲区内容包裹在引用块中添加到主段落")
                        }
                    }
                } else if (event.chunkType == "title") {
                    // 如果缓冲区有内容，先将其包裹在引用块中添加到主段落
                    if (section.paragraphs.size > 1 && section.paragraphs[1].isNotBlank()) {
                        val bufferContent = section.paragraphs[1]
                        val quotedContent = bufferContent.split("\n").joinToString("\n") { line ->
                            if (line.isBlank()) {
                                ">"
                            } else {
                                "> $line"
                            }
                        }
                        section.paragraphs[0] = section.paragraphs[0] + quotedContent + "\n\n"
                        // 清空缓冲区
                        section.paragraphs[1] = ""
                        Log.d("AlianViewModel", "将缓冲区内容包裹在引用块中添加到主段落")
                    }

                    // 确保主段落存在
                    if (section.paragraphs.isEmpty()) {
                        section.paragraphs.add("")
                        section.paragraphs.add("")  // 缓冲区
                    }

                    // 添加 markdown 三级标题和分隔线到主段落 - 使用直接赋值方式
                    section.paragraphs[0] = section.paragraphs[0] + "\n\n---\n\n### ${event.chunk}\n\n"
                    Log.d("AlianViewModel", "添加子标题到深度思考内容: ${event.chunk}")
                    System.out.flush()

                    // 只在第一次收到 deep thinking 事件时添加到时间线
                    // 后续更新只修改现有章节内容，不重新添加到时间线
                    val existingItemIndex = _unifiedChatTimeline.indexOfFirst {
                        it is DeepThinkingItem && it.section.eventId == section.eventId
                    }
                    if (existingItemIndex < 0) {
                        // 第一次添加
                        val deepThinkingItem = DeepThinkingItem(section)
                        _unifiedChatTimeline.add(deepThinkingItem)
                        // 按时间排序
                        _unifiedChatTimeline.sortBy { it.timestamp }
                        Log.d("AlianViewModel", "首次添加 DeepThinkingItem 到统一时间线并排序，eventId=${section.eventId}")
                    }
                    // 如果已存在，不重新添加，因为 section 是引用，内容会自动更新
                }
            }
            is UIStepEvent -> {
                // 步骤事件 - 更新对应的 UIPlanEvent 中的步骤状态
                Log.d("AlianViewModel", "收到步骤事件: stepId=${event.step.id}, description=${event.step.description}, status=${event.step.status}")
                Log.d("AlianViewModel", "当前步骤名称（更新前）: ${_currentStepName.value}")
                System.out.flush()

                // 更新当前正在执行的 step 名称
                val stepStatus = event.step.status.lowercase()
                if (stepStatus in listOf("in_progress", "running")) {
                    // 步骤开始执行时，设置当前步骤名称
                    if (event.step.description.isNotBlank()) {
                        _currentStepName.value = event.step.description
                        Log.d("AlianViewModel", "✓ 设置当前步骤名称: ${event.step.description}")
                    } else {
                        Log.d("AlianViewModel", "步骤描述为空，不更新当前步骤名称")
                    }
                } else if (stepStatus in listOf("completed", "failed")) {
                    // 步骤完成时，不立即清空 currentStepName
                    // 保持显示最后一个步骤的名称，直到有新的步骤开始执行
                    Log.d("AlianViewModel", "步骤完成，保持当前步骤名称: ${_currentStepName.value}")
                }
                Log.d("AlianViewModel", "当前步骤名称（更新后）: ${_currentStepName.value}")
                System.out.flush()

                // 查找最后一个 UIPlanEvent
                val planEventIndex = _uiEvents.indexOfLast { it is UIPlanEvent }

                if (planEventIndex >= 0) {
                    val planEvent = _uiEvents[planEventIndex] as UIPlanEvent

                    // 查找并更新对应的步骤，保留工具调用ID列表
                    val updatedSteps = planEvent.steps.map { step ->
                        if (step.id == event.step.id) {
                            // 保留现有步骤的工具调用ID列表
                            val existingToolCallIds = step.toolCallIds
                            Log.d("AlianViewModel", "更新 step ${step.id}，保留 toolCallIds: $existingToolCallIds")
                            event.step.copy(toolCallIds = existingToolCallIds)
                        } else {
                            step
                        }
                    }

                    // 创建更新后的 UIPlanEvent
                    val updatedPlanEvent = planEvent.copy(steps = updatedSteps)

                    // 先移除再添加以确保 Compose 正确触发重组
                    _uiEvents.removeAt(planEventIndex)
                    _uiEvents.add(planEventIndex, updatedPlanEvent)
                    syncPlanItemInTimeline(updatedPlanEvent)

                    Log.d("AlianViewModel", "更新 UIPlanEvent 中的步骤: stepId=${event.step.id}, 新状态=${event.step.status}，保留工具列表")
                    System.out.flush()
                } else {
                    Log.w("AlianViewModel", "未找到 UIPlanEvent，无法更新步骤")
                }
            }
            is UIPlanEvent -> {
                // 计划事件：同一轮用户输入仅维护一个 Plan 气泡
                Log.d("AlianViewModel", "收到计划事件: timestamp=${event.timestamp}, eventId=${event.eventId}, steps=${event.steps.size}, steps详情: ${event.steps.map { "id=${it.id}, status=${it.status}, tools=${it.tools.size}" }}")
                System.out.flush()

                val normalizedEvent = event.copy(timestamp = normalizeTimestamp(event.timestamp))
                val latestUserMessageId = _messages.lastOrNull { it.isUser }?.id
                val isNewUserBoundary = latestUserMessageId != null && latestUserMessageId != activePlanBoundaryUserMessageId

                if (activePlanBubbleEventId == null || isNewUserBoundary) {
                    activePlanBubbleEventId = normalizedEvent.eventId
                    activePlanBubbleTimestamp = normalizedEvent.timestamp
                    activePlanBoundaryUserMessageId = latestUserMessageId
                }

                val anchorEventId = activePlanBubbleEventId ?: normalizedEvent.eventId
                val anchorTimestamp = activePlanBubbleTimestamp ?: normalizedEvent.timestamp

                val anchoredEvent = normalizedEvent.copy(
                    eventId = anchorEventId,
                    timestamp = anchorTimestamp
                )

                val existingPlanEventIndex = _uiEvents.indexOfLast {
                    it is UIPlanEvent && it.eventId == anchorEventId
                }

                val planEventForTimeline = if (existingPlanEventIndex >= 0) {
                    val existingPlanEvent = _uiEvents[existingPlanEventIndex] as UIPlanEvent
                    val updatedSteps = anchoredEvent.steps.map { newStep ->
                        val existingStep = existingPlanEvent.steps.find { it.id == newStep.id }
                        if (existingStep != null) {
                            newStep.copy(toolCallIds = existingStep.toolCallIds)
                        } else {
                            newStep
                        }
                    }
                    val updatedPlanEvent = anchoredEvent.copy(steps = updatedSteps)
                    _uiEvents[existingPlanEventIndex] = updatedPlanEvent
                    updatedPlanEvent
                } else {
                    _uiEvents.add(anchoredEvent)
                    anchoredEvent
                }

                syncPlanItemInTimeline(planEventForTimeline)
                Log.d("AlianViewModel", "同步 PlanItem 到统一时间线: eventId=${planEventForTimeline.eventId}, timestamp=${planEventForTimeline.timestamp}")

                // 当 plan 消息出现时，折叠所有的 deepthink
                _unifiedChatTimeline.forEach { item ->
                    if (item is DeepThinkingItem) {
                        item.section.isExpanded.value = false
                        Log.d("AlianViewModel", "折叠 DeepThinkingItem: ${item.section.title}")
                    }
                }
            }
            else -> {
                // 其他事件类型不处理消息更新
            }
        }
    }

    /**
     * 清除UI事件
     */
    fun clearUIEvents() {
        _uiEvents.clear()
        // 清除统一时间线中的计划项
        _unifiedChatTimeline.removeAll { it is PlanItem }
        Log.d("AlianViewModel", "清除UI事件和统一时间线中的计划项")
    }

    /**
     * 清除深度思考章节
     */
    fun clearDeepThinkingSections() {
        _deepThinkingSections.clear()
        // 清除统一时间线中的深度思考项
        _unifiedChatTimeline.removeAll { it is DeepThinkingItem }
        Log.d("AlianViewModel", "清除深度思考章节和统一时间线中的深度思考项")
    }

    /**
     * 清除工具调用
     */
    fun clearToolCalls() {
        _toolCalls.clear()
        _currentAttachments.clear()
        Log.d("AlianViewModel", "清除工具调用和附件收集器")
    }

    /**
     * 添加待发送附件
     */
    fun addPendingAttachment(uri: Uri) {
        try {
            val resolver = context.contentResolver
            var fileName: String? = null
            var sizeBytes: Long? = null

            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex)
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }

            val safeFileName = fileName
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "image_${System.currentTimeMillis()}.jpg"
            val mimeType = resolver.getType(uri) ?: "application/octet-stream"

            if (_pendingUploadAttachments.any { it.uriString == uri.toString() }) {
                Log.d("AlianViewModel", "附件已存在，跳过重复添加: $uri")
                return
            }

            val pending = PendingUploadAttachment(
                id = generatePendingAttachmentId(),
                uriString = uri.toString(),
                fileName = safeFileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes
            )
            _pendingUploadAttachments.add(pending)
            Log.d("AlianViewModel", "添加待发送附件: fileName=${pending.fileName}, mimeType=${pending.mimeType}")
        } catch (e: Exception) {
            Log.e("AlianViewModel", "添加待发送附件失败", e)
            Toast.makeText(context, "添加附件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        _pendingUploadAttachments.removeAll { it.id == attachmentId }
    }

    fun clearPendingUploadAttachments() {
        _pendingUploadAttachments.clear()
    }

    /**
     * 创建新Session
     */
    fun createNewSession() {
        Log.d("AlianViewModel", "创建新Session")
        sessionLoadJob?.cancel()
        sessionLoadJob = null
        activePlanBubbleEventId = null
        activePlanBubbleTimestamp = null
        activePlanBoundaryUserMessageId = null
        clearMessages()
        clearUIEvents()
        clearDeepThinkingSections()
        clearToolCalls()
        clearPendingUploadAttachments()
        alianClient?.clearCurrentSession()
        _currentSessionIdUi.value = null
        _sessionLoadingState.value = SessionLoadingState.Idle
        Log.d("AlianViewModel", "新Session已创建")
    }

    fun clearMessages() {
        _messages.clear()
        activePlanBubbleEventId = null
        activePlanBubbleTimestamp = null
        activePlanBoundaryUserMessageId = null
        // 清除统一时间线中的所有项
        _unifiedChatTimeline.clear()
        _pendingUploadAttachments.clear()
        // 注意：不清除SessionId，继续使用缓存的Session
        Log.d("AlianViewModel", "清除消息和统一时间线，保留Session，时间线大小: ${_unifiedChatTimeline.size}")
    }

    fun setLoading() {
        _chatState.value = AlianChatState.Loading
    }

    fun setSuccess(message: ChatMessage) {
        _chatState.value = AlianChatState.Success(message)
    }

    fun setError(message: String) {
        _chatState.value = AlianChatState.Error(message)
    }

    fun resetState() {
        _chatState.value = AlianChatState.Idle
    }

    private fun generatePendingAttachmentId(): String {
        return "pending_${System.currentTimeMillis()}_${_pendingUploadAttachments.size}"
    }

    private fun generateMessageId(): String {
        return "msg_${System.currentTimeMillis()}_${_messages.size}"
    }

    private fun toLocalAttachment(pending: PendingUploadAttachment): Attachment {
        return Attachment(
            filename = pending.fileName,
            name = pending.fileName,
            path = pending.uriString,
            size = pending.sizeBytes,
            content_type = pending.mimeType
        )
    }

    private suspend fun uploadAttachmentsForBackend(
        attachments: List<PendingUploadAttachment>
    ): Result<List<ChatAttachmentRef>> = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) {
            return@withContext Result.success(emptyList())
        }

        if (!useBackend) {
            return@withContext Result.failure(Exception("当前模式不支持附件上传"))
        }

        if (alianClient == null) {
            updateAlianClient()
        }

        val backendClient = alianClient?.getBackendClient()
            ?: return@withContext Result.failure(Exception("Backend 客户端未初始化"))

        val refs = mutableListOf<ChatAttachmentRef>()
        for (pending in attachments) {
            val uri = Uri.parse(pending.uriString)
            val uploadResult = backendClient.uploadFile(
                fileUri = uri,
                overrideFileName = pending.fileName,
                overrideMimeType = pending.mimeType
            )
            if (uploadResult.isFailure) {
                val error = uploadResult.exceptionOrNull()?.message ?: "未知错误"
                return@withContext Result.failure(Exception("上传 ${pending.fileName} 失败: $error"))
            }

            val uploaded = uploadResult.getOrNull()
                ?: return@withContext Result.failure(Exception("上传 ${pending.fileName} 失败: 无返回数据"))

            refs.add(
                ChatAttachmentRef(
                    file_id = uploaded.file_id,
                    filename = uploaded.filename ?: pending.fileName
                )
            )
        }

        Result.success(refs)
    }

    private fun normalizeTimestamp(timestamp: Long): Long {
        // 10^11 约为 1973 年毫秒时间戳，低于该值基本可判定为秒级时间戳
        return if (timestamp in 1 until 100_000_000_000L) timestamp * 1000 else timestamp
    }

    private fun syncPlanItemInTimeline(planEvent: UIPlanEvent) {
        val planItemIndex = _unifiedChatTimeline.indexOfFirst {
            it is PlanItem && it.planEvent.eventId == planEvent.eventId
        }
        if (planItemIndex >= 0) {
            _unifiedChatTimeline[planItemIndex] = PlanItem(planEvent)
        } else {
            _unifiedChatTimeline.add(PlanItem(planEvent))
            _unifiedChatTimeline.sortBy { it.timestamp }
        }
    }

    /**
     * 登录
     */
    suspend fun login(): Result<String> {
        Log.d("AlianViewModel", "开始登录流程")
        Log.d("AlianViewModel", "useBackend: $useBackend")
        Log.d("AlianViewModel", "email: $email")
        Log.d("AlianViewModel", "baseUrl: $baseUrl")

        if (!useBackend) {
            val errorMsg = "Backend mode is not enabled"
            Log.e("AlianViewModel", errorMsg)
            return Result.failure(Exception(errorMsg))
        }

        if (email.value.isBlank() || password.value.isBlank()) {
            val errorMsg = "请输入邮箱和密码"
            Log.e("AlianViewModel", errorMsg)
            _errorMessage.value = errorMsg
            return Result.failure(Exception(errorMsg))
        }

        _isLoading.value = true
        _errorMessage.value = null
        Log.d("AlianViewModel", "创建BackendChatClient")

        try {
            // 更新AlianClient配置
            updateAlianClient()

            Log.d("AlianViewModel", "调用login接口")
            val result = alianClient?.login(email.value, password.value)

            Log.d("AlianViewModel", "login result: $result")

            if (result != null && result.isSuccess) {
                Log.d("AlianViewModel", "登录成功")
                _isLoggedIn.value = true
                _isLoading.value = false
                _errorMessage.value = null

                // 启动定期刷新 Token 任务
                try {
                    App.getInstance().startTokenRefreshJobPublic()
                    Log.d("AlianViewModel", "定期刷新 Token 任务已启动")
                } catch (e: Exception) {
                    Log.e("AlianViewModel", "启动定期刷新 Token 任务失败", e)
                }

                return Result.success("登录成功")
            } else {
                val errorMsg = result?.exceptionOrNull()?.message ?: "登录失败"
                Log.e("AlianViewModel", "登录失败: $errorMsg")
                _errorMessage.value = errorMsg
                _isLoading.value = false
                return Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            val errorMsg = "登录失败: ${e.message}"
            Log.e("AlianViewModel", "登录异常", e)
            _errorMessage.value = errorMsg
            _isLoading.value = false
            return Result.failure(e)
        }
    }

    /**
     * 登出
     */
    fun logout() {
        alianClient?.logout()
        _isLoggedIn.value = false
        clearMessages()
    }

    /**
     * 加载会话列表
     */
    suspend fun loadSessions(): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d("AlianViewModel", "开始加载会话列表")
        _isLoadingSessions.value = true

        try {
            if (!useBackend || alianClient == null) {
                _isLoadingSessions.value = false
                return@withContext Result.failure(Exception("Backend mode is not enabled"))
            }

            val result = alianClient!!.getSessions()

            if (result.isSuccess) {
                val sessions = result.getOrNull() ?: emptyList()
                _sessions.clear()
                _sessions.addAll(sessions)
                Log.d("AlianViewModel", "会话列表加载成功，共 ${sessions.size} 个会话")
                _isLoadingSessions.value = false
                Result.success(Unit)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "加载会话列表失败"
                Log.e("AlianViewModel", "加载会话列表失败: $errorMsg")
                _isLoadingSessions.value = false
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("AlianViewModel", "加载会话列表异常", e)
            _isLoadingSessions.value = false
            Result.failure(e)
        }
    }

    /**
     * 删除会话
     */
    suspend fun deleteSession(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d("AlianViewModel", "删除会话: $sessionId")

        try {
            if (!useBackend) {
                return@withContext Result.failure(Exception("Backend mode is not enabled"))
            }

            if (alianClient == null) {
                updateAlianClient()
            }

            if (alianClient == null) {
                return@withContext Result.failure(Exception("Backend client is not initialized"))
            }

            val result = alianClient!!.deleteSession(sessionId)

            if (result.isSuccess) {
                // 从本地列表中移除
                _sessions.removeIf { it.session_id == sessionId }
                Log.d("AlianViewModel", "会话删除成功: $sessionId")
                Result.success(Unit)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "删除会话失败"
                Log.e("AlianViewModel", "删除会话失败: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("AlianViewModel", "删除会话异常", e)
            Result.failure(e)
        }
    }

    /**
     * 异步选择会话并加载历史消息
     * 点击历史后立即返回聊天框，数据在后台异步加载
     */
    fun selectSessionAsync(sessionId: String) {
        if (!useBackend) {
            _sessionLoadingState.value = SessionLoadingState.Failed(sessionId, "Backend mode is not enabled")
            return
        }

        // last-click-wins：取消旧任务，并使用 token 丢弃过时回包
        sessionLoadJob?.cancel()
        val token = ++sessionLoadToken
        lastRequestedSessionId = sessionId
        _currentSessionIdUi.value = sessionId

        // 切会话时先取消当前问答流，避免旧流事件污染新会话
        currentMessageJob?.cancel()
        currentMessageJob = null
        _isProcessing.value = false
        resetState()
        _currentToolName.value = null
        _currentStepName.value = null
        clearMessages()
        clearUIEvents()
        clearDeepThinkingSections()
        clearToolCalls()
        _sessionLoadingState.value = SessionLoadingState.Switching(sessionId)

        sessionLoadJob = viewModelScope.launch {
            val result = loadSessionInternal(sessionId, token)
            if (token != sessionLoadToken) {
                return@launch
            }

            _sessionLoadingState.value = if (result.isSuccess) {
                SessionLoadingState.Loaded(sessionId)
            } else {
                SessionLoadingState.Failed(
                    sessionId,
                    result.exceptionOrNull()?.message ?: "加载会话失败"
                )
            }
        }
    }

    fun retryCurrentSessionLoad() {
        val sessionId = lastRequestedSessionId ?: return
        selectSessionAsync(sessionId)
    }

    /**
     * 兼容旧调用链：保留 suspend 接口
     */
    suspend fun selectSession(sessionId: String): Result<Unit> {
        val token = ++sessionLoadToken
        lastRequestedSessionId = sessionId
        _currentSessionIdUi.value = sessionId
        _sessionLoadingState.value = SessionLoadingState.Switching(sessionId)
        val result = loadSessionInternal(sessionId, token)
        if (token == sessionLoadToken) {
            _sessionLoadingState.value = if (result.isSuccess) {
                SessionLoadingState.Loaded(sessionId)
            } else {
                SessionLoadingState.Failed(
                    sessionId,
                    result.exceptionOrNull()?.message ?: "加载会话失败"
                )
            }
        }
        return result
    }

    private suspend fun loadSessionInternal(sessionId: String, token: Long): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d("AlianViewModel", "异步加载会话: $sessionId, token=$token")

        try {
            if (!useBackend) {
                return@withContext Result.failure(Exception("Backend mode is not enabled"))
            }

            if (alianClient == null) {
                updateAlianClient()
            }

            if (alianClient == null) {
                return@withContext Result.failure(Exception("Backend client is not initialized"))
            }

            // 设置当前会话ID
            alianClient!!.setCurrentSessionId(sessionId)

            // 加载会话详细信息和事件列表
            val detailResult = alianClient!!.getSessionDetail(sessionId)

            if (detailResult.isSuccess) {
                if (token != sessionLoadToken) {
                    Log.d("AlianViewModel", "会话加载结果过期，丢弃: sessionId=$sessionId, token=$token")
                    return@withContext Result.success(Unit)
                }

                val detail = detailResult.getOrNull()!!
                Log.d("AlianViewModel", "会话详细信息加载成功，共 ${detail.events.size} 个事件")
                val backendClient = getBackendClient()
                // 首屏历史加载不再做附件签名预处理，避免 252k 事件场景被串行签名请求阻塞
                val normalizedEvents = detail.events

                withContext(Dispatchers.Main) mainContext@{
                    if (token != sessionLoadToken) {
                        return@mainContext
                    }
                    processSessionEvents(normalizedEvents, token, sessionId)
                }

                // 附件兜底放到后台异步，避免阻塞历史加载完成态
                if (backendClient != null) {
                    loadSessionAttachmentsFallbackAsync(sessionId, backendClient, token)
                }

                Log.d("AlianViewModel", "会话事件处理完成，统一时间线大小: ${_unifiedChatTimeline.size}")
                Result.success(Unit)
            } else {
                val errorMsg = detailResult.exceptionOrNull()?.message ?: "加载会话详细信息失败"
                Log.e("AlianViewModel", "加载会话详细信息失败: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: CancellationException) {
            Log.d("AlianViewModel", "会话加载取消: sessionId=$sessionId, token=$token")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e("AlianViewModel", "选择会话异常", e)
            Result.failure(e)
        }
    }

    private suspend fun normalizeSessionEventsAttachments(
        events: List<SessionEvent>,
        backendClient: BackendChatClient?
    ): List<SessionEvent> = withContext(Dispatchers.IO) {
        if (backendClient == null) {
            return@withContext events
        }

        val signedUrlCache = mutableMapOf<String, String>()

        events.map { event ->
            if (event.event != "plan_finished") {
                return@map event
            }

            try {
                val dataString = json.encodeToString(event.data)
                val planData = json.decodeFromString<PlanFinishedData>(dataString)
                val attachments = planData.attachments ?: emptyList()
                if (attachments.isEmpty()) {
                    return@map event
                }

                val resolvedAttachments = attachments.map { attachment ->
                    val hasUsableUrl = !attachment.file_url.isNullOrBlank() || !attachment.url.isNullOrBlank()
                    if (hasUsableUrl) {
                        attachment
                    } else {
                        val fileId = attachment.file_id
                        if (fileId.isNullOrBlank()) {
                            attachment
                        } else {
                            val signedUrl = signedUrlCache[fileId] ?: run {
                                val signedUrlResult = backendClient.getFileSignedUrl(fileId)
                                if (signedUrlResult.isSuccess) {
                                    val url = signedUrlResult.getOrNull()!!
                                    signedUrlCache[fileId] = url
                                    url
                                } else {
                                    Log.w("AlianViewModel", "file_id=$fileId 签名URL获取失败: ${signedUrlResult.exceptionOrNull()?.message}")
                                    ""
                                }
                            }

                            if (signedUrl.isNotBlank()) {
                                attachment.copy(
                                    file_url = signedUrl,
                                    url = signedUrl
                                )
                            } else {
                                attachment
                            }
                        }
                    }
                }

                val normalizedPlanData = planData.copy(attachments = resolvedAttachments)
                SessionEvent(
                    event = event.event,
                    data = json.encodeToJsonElement(normalizedPlanData)
                )
            } catch (e: Exception) {
                Log.e("AlianViewModel", "标准化 plan_finished 附件失败，保持原始事件", e)
                event
            }
        }
    }

    private fun attachSessionFilesAsFallback(attachments: List<Attachment>) {
        val hasAnyMessageAttachment = _messages.any { it.attachments.isNotEmpty() }
        if (hasAnyMessageAttachment) {
            return
        }

        val lastAssistantIndex = _messages.indexOfLast { !it.isUser }
        if (lastAssistantIndex < 0) {
            return
        }

        val lastMessage = _messages[lastAssistantIndex]
        val mergedAttachments = (lastMessage.attachments + attachments).distinctBy {
            it.file_id ?: it.file_url ?: it.url ?: it.path ?: it.filename ?: it.name
        }
        val updatedMessage = lastMessage.copy(attachments = mergedAttachments)
        _messages[lastAssistantIndex] = updatedMessage

        val timelineIndex = _unifiedChatTimeline.indexOfFirst {
            it is MessageItem && it.message.id == lastMessage.id
        }
        if (timelineIndex >= 0) {
            _unifiedChatTimeline[timelineIndex] = MessageItem(updatedMessage)
        }

        Log.d("AlianViewModel", "历史附件兜底挂载到最后一条 assistant 消息: ${mergedAttachments.size} 个")
    }

    private fun loadSessionAttachmentsFallbackAsync(
        sessionId: String,
        backendClient: BackendChatClient,
        token: Long
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (token != sessionLoadToken) {
                return@launch
            }
            val attachmentsResult = backendClient.getSessionAttachments(sessionId)
            if (attachmentsResult.isSuccess) {
                val attachments = attachmentsResult.getOrNull().orEmpty()
                if (attachments.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (token != sessionLoadToken) {
                            return@withContext
                        }
                        attachSessionFilesAsFallback(attachments)
                    }
                }
            } else {
                Log.w("AlianViewModel", "获取会话附件失败: ${attachmentsResult.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * 处理会话事件列表，转换为消息和UI事件
     */
    private suspend fun processSessionEvents(
        events: List<SessionEvent>,
        loadToken: Long,
        sessionId: String
    ) {
        // 辅助函数：将 JsonElement 正确序列化为 JSON 字符串
        fun JsonElement.toJsonString(): String = json.encodeToString(this)

        // 用于合并 message_chunk 的缓冲区
        val messageChunkBuffer = mutableMapOf<String, StringBuilder>()
        val messageTimestamps = mutableMapOf<String, Long>()
        // 用于收集每个消息的附件
        val messageAttachments = mutableMapOf<String, MutableList<Attachment>>()
        // plan_finished 附件先暂存，优先挂到下一条 assistant；若没有下一条再兜底挂到最后一条 assistant
        val pendingAssistantAttachments = mutableListOf<Attachment>()

        fun mergeAttachments(
            primary: List<Attachment>,
            secondary: List<Attachment>
        ): List<Attachment> {
            return (primary + secondary).distinctBy {
                it.file_id ?: it.file_url ?: it.url ?: it.path ?: it.filename ?: it.name
            }
        }

        var switchingMarkedLoaded = false
        fun markSwitchingLoadedOnce() {
            if (switchingMarkedLoaded) return
            if (loadToken != sessionLoadToken) return
            if (_sessionLoadingState.value is SessionLoadingState.Switching) {
                _sessionLoadingState.value = SessionLoadingState.Loaded(sessionId)
            }
            switchingMarkedLoaded = true
        }

        fun addHistoryMessage(message: ChatMessage) {
            markSwitchingLoadedOnce()
            _messages.add(message)
            _unifiedChatTimeline.add(MessageItem(message))
        }

        fun flushPendingAttachmentsToLastAssistant(reason: String) {
            if (pendingAssistantAttachments.isEmpty()) return
            val lastAssistantIndex = _messages.indexOfLast { !it.isUser }
            if (lastAssistantIndex >= 0) {
                val lastAssistant = _messages[lastAssistantIndex]
                val merged = mergeAttachments(lastAssistant.attachments, pendingAssistantAttachments)
                val updated = lastAssistant.copy(attachments = merged)
                _messages[lastAssistantIndex] = updated

                val timelineIndex = _unifiedChatTimeline.indexOfFirst {
                    it is MessageItem && it.message.id == lastAssistant.id
                }
                if (timelineIndex >= 0) {
                    _unifiedChatTimeline[timelineIndex] = MessageItem(updated)
                }
                Log.d(
                    "AlianViewModel",
                    "plan_finished 暂存附件兜底挂载到最后一条 assistant: ${pendingAssistantAttachments.size} 个, reason=$reason"
                )
                pendingAssistantAttachments.clear()
            }
        }

        fun queuePlanFinishedAttachments(attachments: List<Attachment>) {
            if (attachments.isEmpty()) return
            val merged = mergeAttachments(pendingAssistantAttachments, attachments)
            pendingAssistantAttachments.clear()
            pendingAssistantAttachments.addAll(merged)
            Log.d("AlianViewModel", "plan_finished 附件暂存，等待下一条 assistant 消息: ${attachments.size} 个")
        }

        var processedEventCount = 0
        events.forEach { event ->
            when (event.event) {
                "message" -> {
                    // 处理完整消息
                    try {
                        val dataString = event.data.toJsonString()
                        val messageData = json.decodeFromString<MessageData>(dataString)

                        if (messageData.role == "user" || messageData.role == "assistant") {
                            if (messageData.role == "user") {
                                flushPendingAttachmentsToLastAssistant("before user message event")
                                activePlanBubbleEventId = null
                                activePlanBubbleTimestamp = null
                                activePlanBoundaryUserMessageId = messageData.message_id
                            }
                            // 不再在 content 中显示附件链接，因为已经有预览按钮了
                            val contentWithAttachments = messageData.content
                            val rawAttachments = messageData.attachments ?: emptyList()
                            val mergedAttachments = if (messageData.role == "assistant" && pendingAssistantAttachments.isNotEmpty()) {
                                val merged = mergeAttachments(rawAttachments, pendingAssistantAttachments)
                                pendingAssistantAttachments.clear()
                                merged
                            } else {
                                rawAttachments
                            }

                            addHistoryMessage(ChatMessage(
                                id = messageData.message_id ?: generateMessageId(),
                                content = contentWithAttachments,
                                isUser = messageData.role == "user",
                                timestamp = normalizeTimestamp(messageData.timestamp),
                                attachments = mergedAttachments
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析消息事件失败", e)
                    }
                }
                "message_chunk" -> {
                    // 处理流式消息块
                    try {
                        val dataString = event.data.toJsonString()
                        val chunkData = json.decodeFromString<MessageChunkData>(dataString)

                        // 累积消息块
                        val buffer = messageChunkBuffer.getOrPut(chunkData.message_id) { StringBuilder() }
                        buffer.append(chunkData.chunk)
                        messageTimestamps[chunkData.message_id] = normalizeTimestamp(chunkData.timestamp)

                        // 收集附件
                        if (chunkData.attachments != null && chunkData.attachments.isNotEmpty()) {
                            val attachments = messageAttachments.getOrPut(chunkData.message_id) { mutableListOf() }
                            attachments.addAll(chunkData.attachments)
                        }

                        // 如果是最后一个块，创建完整消息
                        if (chunkData.done) {
                            val fullContent = buffer.toString()
                            // 不再在 content 中显示附件链接，因为已经有预览按钮了
                            val contentWithAttachments = fullContent
                            val rawAttachments = messageAttachments[chunkData.message_id] ?: emptyList()
                            val mergedAttachments = if (pendingAssistantAttachments.isNotEmpty()) {
                                val merged = mergeAttachments(rawAttachments, pendingAssistantAttachments)
                                pendingAssistantAttachments.clear()
                                merged
                            } else {
                                rawAttachments
                            }

                            addHistoryMessage(ChatMessage(
                                id = chunkData.message_id,
                                content = contentWithAttachments,
                                isUser = false,  // message_chunk 通常是 assistant 的消息
                                timestamp = normalizeTimestamp(chunkData.timestamp),
                                attachments = mergedAttachments
                            ))
                            // 清理缓冲区
                            messageChunkBuffer.remove(chunkData.message_id)
                            messageTimestamps.remove(chunkData.message_id)
                            messageAttachments.remove(chunkData.message_id)
                        }
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析消息块事件失败", e)
                    }
                }
                "deep_thinking_chunk" -> {
                    // 处理深度思考块
                    try {
                        val dataString = event.data.toJsonString()
                        val chunkData = json.decodeFromString<DeepThinkingChunkData>(dataString)
                        Log.d("AlianViewModel", "处理深度思考块事件: chunkType=${chunkData.chunk_type}, sectionIndex=${chunkData.section_index}, chunkIndex=${chunkData.chunk_index}, done=${chunkData.done}")

                        // 确保只有一个章节（index 0），标题固定为"深度思考"
                        while (_deepThinkingSections.size <= 0) {
                            _deepThinkingSections.add(DeepThinkingSection(
                                eventId = chunkData.event_id,
                                timestamp = normalizeTimestamp(chunkData.timestamp),
                                title = "深度思考",
                                paragraphs = mutableStateListOf("")  // 主段落
                            ))
                        }

                        val section = _deepThinkingSections[0]

                        if (chunkData.chunk_type == "paragraph") {
                            // 将 chunk 追加到缓冲区（不添加引用标记）
                            if (section.paragraphs.isEmpty()) {
                                section.paragraphs.add("")
                                section.paragraphs.add("")  // 索引 1 用于缓冲区
                            } else if (section.paragraphs.size == 1) {
                                section.paragraphs.add("")  // 添加缓冲区
                            }
                            // 累积到缓冲区（索引 1）
                            section.paragraphs[1] = section.paragraphs[1] + chunkData.chunk
                            Log.d("AlianViewModel", "累积内容到缓冲区: ${chunkData.chunk.take(50)}...")

                            // 如果 done=true，将缓冲区内容包裹在引用块中添加到主段落
                            if (chunkData.done) {
                                val bufferContent = section.paragraphs[1]
                                if (bufferContent.isNotBlank()) {
                                    // 将缓冲区内容包裹在引用块中
                                    val quotedContent = bufferContent.split("\n").joinToString("\n") { line ->
                                        if (line.isBlank()) {
                                            ">"
                                        } else {
                                            "> $line"
                                        }
                                    }
                                    section.paragraphs[0] = section.paragraphs[0] + quotedContent + "\n\n"
                                    // 清空缓冲区
                                    section.paragraphs[1] = ""
                                    Log.d("AlianViewModel", "将缓冲区内容包裹在引用块中添加到主段落")
                                }
                            }
                        } else if (chunkData.chunk_type == "title") {
                            // 如果缓冲区有内容，先将其包裹在引用块中添加到主段落
                            if (section.paragraphs.size > 1 && section.paragraphs[1].isNotBlank()) {
                                val bufferContent = section.paragraphs[1]
                                val quotedContent = bufferContent.split("\n").joinToString("\n") { line ->
                                    if (line.isBlank()) {
                                        ">"
                                    } else {
                                        "> $line"
                                    }
                                }
                                section.paragraphs[0] = section.paragraphs[0] + quotedContent + "\n\n"
                                // 清空缓冲区
                                section.paragraphs[1] = ""
                                Log.d("AlianViewModel", "将缓冲区内容包裹在引用块中添加到主段落")
                            }

                            // 确保主段落存在
                            if (section.paragraphs.isEmpty()) {
                                section.paragraphs.add("")
                                section.paragraphs.add("")  // 缓冲区
                            }

                            // 添加 markdown 三级标题和分隔线到主段落
                            section.paragraphs[0] = section.paragraphs[0] + "\n\n---\n\n### ${chunkData.chunk}\n\n"
                            Log.d("AlianViewModel", "添加子标题到深度思考内容: ${chunkData.chunk}, sectionIndex=${chunkData.section_index}")
                        }
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析深度思考块事件失败", e)
                    }
                }
                "tool" -> {
                    // 处理工具调用事件
                    try {
                        val dataString = event.data.toJsonString()
                        val toolData = json.decodeFromString<ToolData>(dataString)
                        Log.d("AlianViewModel", "处理工具调用事件: name=${toolData.name}, status=${toolData.status}, tool_call_id=${toolData.tool_call_id}")

                        val uiToolCall = UIToolCall(
                            toolCallId = toolData.tool_call_id,
                            name = toolData.name,
                            status = toolData.status,
                            function = toolData.function,
                            args = toolData.args,
                            content = toolData.content
                        )
                        _toolCalls.add(uiToolCall)

                        // 触发 UI 事件，将工具关联到步骤
                        addUIEvent(
                            UIToolEvent(
                                eventId = toolData.event_id,
                                timestamp = normalizeTimestamp(toolData.timestamp),
                                toolCall = uiToolCall
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析工具调用事件失败", e)
                    }
                }
                "step" -> {
                    // 处理步骤事件
                    try {
                        val dataString = event.data.toJsonString()
                        val stepData = json.decodeFromString<StepData>(dataString)
                        Log.d("AlianViewModel", "处理步骤事件: id=${stepData.id}, status=${stepData.status}")

                        val uiStep = UIStep(
                            id = stepData.id,
                            description = stepData.description,
                            status = stepData.status
                        )
                        addUIEvent(
                            UIStepEvent(
                                eventId = stepData.event_id,
                                timestamp = normalizeTimestamp(stepData.timestamp),
                                step = uiStep
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析步骤事件失败", e)
                    }
                }
                "plan" -> {
                    // 处理计划事件
                    try {
                        val dataString = event.data.toJsonString()
                        val planData = json.decodeFromString<PlanData>(dataString)
                        Log.d("AlianViewModel", "处理计划事件: steps=${planData.steps.size}")

                        val uiSteps = planData.steps.map { step ->
                            UIStep(
                                id = step.id,
                                description = step.description,
                                status = step.status
                            )
                        }

                        addUIEvent(
                            UIPlanEvent(
                                eventId = planData.event_id,
                                timestamp = normalizeTimestamp(planData.timestamp),
                                steps = uiSteps
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析计划事件失败", e)
                    }
                }
                // ==================== 新接口事件处理 ====================
                "text_message_start" -> {
                    // 处理文本消息开始事件
                    try {
                        val dataString = event.data.toJsonString()
                        val startData = json.decodeFromString<TextMessageStartData>(dataString)
                        Log.d("AlianViewModel", "处理文本消息开始事件: message_id=${startData.message_id}, message_type=${startData.message_type}")
                        // 初始化消息缓冲区
                        messageChunkBuffer.getOrPut(startData.message_id) { StringBuilder() }
                        messageTimestamps[startData.message_id] = normalizeTimestamp(startData.timestamp)
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析文本消息开始事件失败", e)
                    }
                }
                "text_message_chunk" -> {
                    // 处理文本消息块事件
                    try {
                        val dataString = event.data.toJsonString()
                        val chunkData = json.decodeFromString<TextMessageChunkData>(dataString)

                        // 累积消息块
                        val buffer = messageChunkBuffer.getOrPut(chunkData.message_id) { StringBuilder() }
                        buffer.append(chunkData.delta)
                        messageTimestamps[chunkData.message_id] = normalizeTimestamp(chunkData.timestamp)
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析文本消息块事件失败", e)
                    }
                }
                "text_message_end" -> {
                    // 处理文本消息结束事件
                    try {
                        val dataString = event.data.toJsonString()
                        val endData = json.decodeFromString<TextMessageEndData>(dataString)
                        Log.d("AlianViewModel", "处理文本消息结束事件: message_id=${endData.message_id}")

                        // 获取完整内容
                        val buffer = messageChunkBuffer[endData.message_id]
                        if (buffer != null) {
                            val fullContent = buffer.toString()
                            if (fullContent.isNotBlank()) {
                                val rawAttachments = messageAttachments[endData.message_id] ?: emptyList()
                                val mergedAttachments = if (pendingAssistantAttachments.isNotEmpty()) {
                                    val merged = mergeAttachments(rawAttachments, pendingAssistantAttachments)
                                    pendingAssistantAttachments.clear()
                                    merged
                                } else {
                                    rawAttachments
                                }
                                addHistoryMessage(ChatMessage(
                                    id = endData.message_id,
                                    content = fullContent,
                                    isUser = false,
                                    timestamp = normalizeTimestamp(messageTimestamps[endData.message_id] ?: endData.timestamp),
                                    attachments = mergedAttachments
                                ))
                            }
                            // 清理缓冲区
                            messageChunkBuffer.remove(endData.message_id)
                            messageTimestamps.remove(endData.message_id)
                            messageAttachments.remove(endData.message_id)
                        }
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析文本消息结束事件失败", e)
                    }
                }
                "user_message" -> {
                    // 处理用户消息事件
                    try {
                        val dataString = event.data.toJsonString()
                        val userData = json.decodeFromString<UserMessageData>(dataString)
                        flushPendingAttachmentsToLastAssistant("before user_message event")
                        activePlanBubbleEventId = null
                        activePlanBubbleTimestamp = null
                        activePlanBoundaryUserMessageId = userData.message_id

                        addHistoryMessage(ChatMessage(
                            id = userData.message_id,
                            content = userData.message,
                            isUser = true,
                            timestamp = normalizeTimestamp(userData.timestamp),
                            attachments = userData.attachments ?: emptyList()
                        ))
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析用户消息事件失败", e)
                    }
                }
                "tool_call_start" -> {
                    // 处理工具调用开始事件
                    try {
                        val dataString = event.data.toJsonString()
                        val startData = json.decodeFromString<ToolCallStartData>(dataString)
                        Log.d("AlianViewModel", "处理工具调用开始事件: tool_call_id=${startData.tool_call_id}, tool_call_name=${startData.tool_call_name}")

                        val uiToolCall = UIToolCall(
                            toolCallId = startData.tool_call_id,
                            name = startData.tool_call_name,
                            status = "pending",
                            function = "",
                            args = emptyMap(),
                            content = null
                        )
                        _toolCalls.add(uiToolCall)

                        _uiEvents.add(
                            UIToolEvent(
                                eventId = startData.id,
                                timestamp = startData.timestamp,
                                toolCall = uiToolCall
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析工具调用开始事件失败", e)
                    }
                }
                "tool_call_chunk" -> {
                    // 暂时忽略 tool_call_chunk
                    Log.d("AlianViewModel", "忽略 tool_call_chunk 事件")
                }
                "tool_call_result" -> {
                    // 处理工具调用结果事件
                    try {
                        val dataString = event.data.toJsonString()
                        val resultData = json.decodeFromString<ToolCallResultData>(dataString)
                        Log.d("AlianViewModel", "处理工具调用结果事件: tool_call_id=${resultData.tool_call_id}")

                        // 查找并更新工具调用
                        val existingIndex = _toolCalls.indexOfFirst { it.toolCallId == resultData.tool_call_id }
                        if (existingIndex >= 0) {
                            val updatedToolCall = _toolCalls[existingIndex].copy(
                                status = "completed",
                                content = resultData.content
                            )
                            _toolCalls.removeAt(existingIndex)
                            _toolCalls.add(existingIndex, updatedToolCall)

                            _uiEvents.add(
                                UIToolEvent(
                                    eventId = resultData.id,
                                    timestamp = resultData.timestamp,
                                    toolCall = updatedToolCall
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析工具调用结果事件失败", e)
                    }
                }
                "tool_call_end" -> {
                    // 处理工具调用结束事件
                    try {
                        val dataString = event.data.toJsonString()
                        val endData = json.decodeFromString<ToolCallEndData>(dataString)
                        Log.d("AlianViewModel", "处理工具调用结束事件: tool_call_id=${endData.tool_call_id}")

                        // 查找并更新工具调用
                        val existingIndex = _toolCalls.indexOfFirst { it.toolCallId == endData.tool_call_id }
                        if (existingIndex >= 0) {
                            val updatedToolCall = _toolCalls[existingIndex].copy(
                                status = "completed"
                            )
                            _toolCalls.removeAt(existingIndex)
                            _toolCalls.add(existingIndex, updatedToolCall)

                            _uiEvents.add(
                                UIToolEvent(
                                    eventId = endData.id,
                                    timestamp = endData.timestamp,
                                    toolCall = updatedToolCall
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析工具调用结束事件失败", e)
                    }
                }
                "plan_started" -> {
                    // 处理计划开始事件
                    try {
                        val dataString = event.data.toJsonString()
                        val planData = json.decodeFromString<PlanStartedData>(dataString)
                        Log.d("AlianViewModel", "处理计划开始事件: goal=${planData.plan.goal}")

                        val uiSteps = planData.plan.phases.mapIndexed { index, phase ->
                            UIStep(
                                id = phase.id,
                                description = phase.title,
                                status = phase.status
                            )
                        }
                        addUIEvent(
                            UIPlanEvent(
                                eventId = planData.id,
                                timestamp = normalizeTimestamp(planData.timestamp),
                                steps = uiSteps
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析计划开始事件失败", e)
                    }
                }
                "plan_finished" -> {
                    // 处理计划完成事件
                    try {
                        val dataString = event.data.toJsonString()
                        val planData = json.decodeFromString<PlanFinishedData>(dataString)
                        Log.d("AlianViewModel", "处理计划完成事件: goal=${planData.plan.goal}")

                        val uiSteps = planData.plan.phases.mapIndexed { index, phase ->
                            UIStep(
                                id = phase.id,
                                description = phase.title,
                                status = phase.status
                            )
                        }

                        addUIEvent(
                            UIPlanEvent(
                                eventId = planData.id,
                                timestamp = normalizeTimestamp(planData.timestamp),
                                steps = uiSteps
                            )
                        )
                        queuePlanFinishedAttachments(planData.attachments ?: emptyList())
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析计划完成事件失败", e)
                    }
                }
                "phase_started" -> {
                    // 处理阶段开始事件
                    try {
                        val dataString = event.data.toJsonString()
                        val phaseData = json.decodeFromString<PhaseStartedData>(dataString)
                        Log.d("AlianViewModel", "处理阶段开始事件: phase_id=${phaseData.phase_id}, title=${phaseData.title}")

                        addUIEvent(
                            UIStepEvent(
                                eventId = phaseData.id,
                                timestamp = normalizeTimestamp(phaseData.timestamp),
                                step = UIStep(
                                    id = phaseData.phase_id,
                                    description = phaseData.title,
                                    status = "running"
                                )
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析阶段开始事件失败", e)
                    }
                }
                "phase_finished" -> {
                    // 处理阶段完成事件
                    try {
                        val dataString = event.data.toJsonString()
                        val phaseData = json.decodeFromString<PhaseFinishedData>(dataString)
                        Log.d("AlianViewModel", "处理阶段完成事件: phase_id=${phaseData.phase_id}, title=${phaseData.title}")

                        addUIEvent(
                            UIStepEvent(
                                eventId = phaseData.id,
                                timestamp = normalizeTimestamp(phaseData.timestamp),
                                step = UIStep(
                                    id = phaseData.phase_id,
                                    description = phaseData.title,
                                    status = "completed"
                                )
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("AlianViewModel", "解析阶段完成事件失败", e)
                    }
                }
                // =======================================================
                else -> {
                    // 其他事件类型，暂时忽略
                    Log.d("AlianViewModel", "忽略事件类型: ${event.event}")
                }
            }

            processedEventCount++
            if (processedEventCount == 1) {
                markSwitchingLoadedOnce()
            }
            if (processedEventCount % 16 == 0) {
                if (loadToken != sessionLoadToken) {
                    Log.d("AlianViewModel", "事件处理中检测到会话切换，提前终止: token=$loadToken, current=$sessionLoadToken")
                    return
                }
                yield()
            }
        }

        markSwitchingLoadedOnce()

        // 处理剩余的未完成消息块（如果有）
        messageChunkBuffer.forEach { (messageId, buffer) ->
            val fullContent = buffer.toString()
            if (fullContent.isNotBlank()) {
                // 不再在 content 中显示附件链接，因为已经有预览按钮了
                val contentWithAttachments = fullContent
                val rawAttachments = messageAttachments[messageId] ?: emptyList()
                val mergedAttachments = if (pendingAssistantAttachments.isNotEmpty()) {
                    val merged = mergeAttachments(rawAttachments, pendingAssistantAttachments)
                    pendingAssistantAttachments.clear()
                    merged
                } else {
                    rawAttachments
                }
                addHistoryMessage(ChatMessage(
                    id = messageId,
                    content = contentWithAttachments,
                    isUser = false,
                    timestamp = normalizeTimestamp(messageTimestamps[messageId] ?: System.currentTimeMillis()),
                    attachments = mergedAttachments
                ))
            }
        }

        // 没有“下一条 assistant”可消费时，兜底挂到最后一条 assistant
        flushPendingAttachmentsToLastAssistant("after session events processed")

        // 处理深度思考章节的剩余缓冲区内容
        _deepThinkingSections.forEach { section ->
            if (section.paragraphs.size > 1 && section.paragraphs[1].isNotBlank()) {
                val bufferContent = section.paragraphs[1]
                val quotedContent = bufferContent.split("\n").joinToString("\n") { line ->
                    if (line.isBlank()) {
                        ">"
                    } else {
                        "> $line"
                    }
                }
                section.paragraphs[0] = section.paragraphs[0] + quotedContent + "\n\n"
                // 清空缓冲区
                section.paragraphs[1] = ""
                Log.d("AlianViewModel", "将深度思考章节剩余缓冲区内容包裹在引用块中添加到主段落")
            }
        }

        // 将所有深度思考章节添加到统一时间线
        _deepThinkingSections.forEach { section ->
            if (section.title.isNotBlank() || section.paragraphs.isNotEmpty()) {
                // 检查是否已存在对应 eventId 的 DeepThinkingItem
                val existingItem = _unifiedChatTimeline.firstOrNull {
                    it is DeepThinkingItem && it.section.eventId == section.eventId
                }
                if (existingItem == null) {
                    _unifiedChatTimeline.add(DeepThinkingItem(section))
                }
            }
        }

        // 将计划事件添加到统一时间线
        val planEvent = _uiEvents.filterIsInstance<UIPlanEvent>().lastOrNull()
        if (planEvent != null) {
            // 检查是否已存在对应 eventId 的 PlanItem
            val existingPlanItem = _unifiedChatTimeline.firstOrNull {
                it is PlanItem && it.planEvent.eventId == planEvent.eventId
            }
            if (existingPlanItem == null) {
                _unifiedChatTimeline.add(PlanItem(planEvent))
            }
        }

        // 按时间排序
        _unifiedChatTimeline.sortBy { it.timestamp }

        // 输出时间线中的所有项和时间戳
        _unifiedChatTimeline.forEach { item ->
            when (item) {
                is MessageItem -> Log.d("AlianViewModel", "时间线项: Message, timestamp=${item.timestamp}, isUser=${item.message.isUser}, content=${item.message.content.take(20)}...")
                is DeepThinkingItem -> Log.d("AlianViewModel", "时间线项: DeepThinking, timestamp=${item.timestamp}, title=${item.section.title}")
                is PlanItem -> Log.d("AlianViewModel", "时间线项: Plan, timestamp=${item.planEvent.timestamp}, steps=${item.planEvent.steps.size}")
            }
        }

        Log.d("AlianViewModel", "事件处理完成，共生成 ${_messages.size} 条消息，${_deepThinkingSections.size} 个深度思考章节，统一时间线共 ${_unifiedChatTimeline.size} 项")
    }

    /**
     * 取消当前消息发送
     */
    fun cancelMessage() {
        Log.d("AlianViewModel", "取消当前消息发送")
        currentMessageJob?.cancel()
        currentMessageJob = null
        _isProcessing.value = false
        resetState()
        // 也停止 TTS 播放
        stopTTS()
    }

    /**
     * 发送聊天消息
     */
    fun sendMessage(content: String) {
        val pendingAttachments = _pendingUploadAttachments.toList()
        if (content.isBlank() && pendingAttachments.isEmpty()) {
            return
        }

        // 先添加用户消息到消息列表，这样气泡会立即显示
        addUserMessage(
            content = content,
            attachments = pendingAttachments.map(::toLocalAttachment)
        )
        _pendingUploadAttachments.clear()
        
        // 如果已经有任务在运行，先取消它
        currentMessageJob?.cancel()
        
        currentMessageJob = viewModelScope.launch {
            val result = sendMessageInternal(content, pendingAttachments)
            if (result.isFailure && pendingAttachments.isNotEmpty()) {
                val existing = _pendingUploadAttachments.map { it.uriString }.toSet()
                pendingAttachments.forEach { attachment ->
                    if (attachment.uriString !in existing) {
                        _pendingUploadAttachments.add(attachment)
                    }
                }
            }
        }
    }
    
    /**
     * 发送聊天消息的内部实现
     */
    private suspend fun sendMessageInternal(
        content: String,
        pendingAttachments: List<PendingUploadAttachment> = emptyList()
    ): Result<String> {
        if (useBackend) {
            // 检查登录状态和 token 是否过期
            checkLoginStatus()
            if (!_isLoggedIn.value) {
                return Result.failure(Exception("登录已过期，请重新登录"))
            }
        }

        if (!useBackend && apiKey.isBlank()) {
            return Result.failure(Exception("请先设置 API Key"))
        }

        setLoading()
        _isProcessing.value = true

        // 清空之前的工具名称和步骤名称
        _currentToolName.value = null
        _currentStepName.value = null

        try {
            Log.d("AlianViewModel", "开始发送消息: useBackend=$useBackend, backendBaseUrl=$backendBaseUrl")

            // 确保AlianClient已创建
            if (alianClient == null) {
                updateAlianClient()
            }

            Log.d("AlianViewModel", "AlianClient已就绪，开始调用API")

            // 清除之前的UI事件
            clearUIEvents()

            val uploadedAttachmentRefs = if (pendingAttachments.isNotEmpty()) {
                Log.d("AlianViewModel", "开始上传 ${pendingAttachments.size} 个附件")
                val uploadResult = uploadAttachmentsForBackend(pendingAttachments)
                if (uploadResult.isFailure) {
                    val errorMsg = uploadResult.exceptionOrNull()?.message ?: "附件上传失败"
                    Log.e("AlianViewModel", errorMsg)
                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                    setError(errorMsg)
                    _isProcessing.value = false
                    return Result.failure(Exception(errorMsg))
                }
                uploadResult.getOrNull().orEmpty()
            } else {
                emptyList()
            }

            val messageToSend =
                if (content.isBlank() && uploadedAttachmentRefs.isNotEmpty()) " " else content

            // 调用 API，传递事件回调
            val result = alianClient?.sendMessage(
                messageToSend,
                _messages.toList(),
                attachments = uploadedAttachmentRefs,
                onEvent = { event ->
                    // 所有 UI 事件（包括 UIMessageEvent 和 UIMessageChunkEvent）都在 addUIEvent 中统一处理
                    addUIEvent(event)
                    // 如果是 DONE 事件，重置状态为 Idle，停止显示加载状态
                    if (event is UIDoneEvent) {
                        resetState()
                        _isProcessing.value = false
                        _currentToolName.value = null
                        _currentStepName.value = null
                        Log.d("AlianViewModel", "收到DONE事件，重置状态为Idle，清空当前工具名称和步骤名称")
                    }
                }
            )
            Log.d("AlianViewModel", "API调用完成: result=$result")

            if (result != null && result.isSuccess) {
                val response = result.getOrNull() ?: ""
                Log.d("AlianViewModel", "收到响应: response=$response, length=${response.length}")
                
                // 只在非 Backend 模式下添加助手消息（Backend 模式通过流式事件添加）
                if (!useBackend) {
                    Log.d("AlianViewModel", "非Backend模式，添加助手消息")
                    addAssistantMessage(response)
                    setSuccess(ChatMessage(
                        id = generateMessageId(),
                        content = response,
                        isUser = false
                    ))
                    _isProcessing.value = false
                } else {
                    Log.d("AlianViewModel", "Backend模式，消息已通过流式事件添加")
                    // Backend 模式下，确保在成功返回时重置处理状态
                    // 注意：如果后续收到 UIDoneEvent，会再次设置 _isProcessing.value = false，这是安全的
                    _isProcessing.value = false
                }
                
                Log.d("AlianViewModel", "消息发送成功")
                return Result.success(response)
            } else {
                val errorMsg = result?.exceptionOrNull()?.message ?: "发送消息失败"
                Log.e("AlianViewModel", "发送消息失败: $errorMsg")
                
                // 显示错误Toast
                Toast.makeText(context, "发送失败: $errorMsg", Toast.LENGTH_LONG).show()
                
                setError(errorMsg)
                _isProcessing.value = false
                return Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            val errorMsg = "发送消息失败: ${e.message}"
            setError(errorMsg)
            _isProcessing.value = false
            return Result.failure(e)
        }
    }

    /**
     * 播放指定消息的TTS
     */
    suspend fun playMessageTTS(messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d("AlianViewModel", "playMessageTTS被调用: messageId=$messageId")
        Log.d("AlianViewModel", "当前状态: ttsEnabled=$ttsEnabled, ttsClient=${ttsClient != null}, apiKey=${apiKey.isNotBlank()}")

        if (!ttsEnabled) {
            Log.e("AlianViewModel", "TTS未启用")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "请先在设置中启用语音播放功能", Toast.LENGTH_SHORT).show()
            }
            return@withContext Result.failure(Exception("TTS未启用"))
        }

        if (ttsClient == null) {
            Log.e("AlianViewModel", "TTS客户端未初始化")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "TTS客户端未初始化，请检查API Key", Toast.LENGTH_SHORT).show()
            }
            return@withContext Result.failure(Exception("TTS客户端未初始化"))
        }

        // 查找消息
        val message = _messages.find { it.id == messageId }
        if (message == null) {
            Log.e("AlianViewModel", "消息不存在: messageId=$messageId")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "消息不存在", Toast.LENGTH_SHORT).show()
            }
            return@withContext Result.failure(Exception("消息不存在"))
        }

        if (message.isUser) {
            Log.e("AlianViewModel", "不能播放用户消息")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "不能播放用户消息", Toast.LENGTH_SHORT).show()
            }
            return@withContext Result.failure(Exception("不能播放用户消息"))
        }

        if (message.content.isBlank()) {
            Log.e("AlianViewModel", "消息内容为空")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "消息内容为空", Toast.LENGTH_SHORT).show()
            }
            return@withContext Result.failure(Exception("消息内容为空"))
        }

        // 如果已经在播放这条消息，直接返回
        if (_isPlayingTTS.value && _currentPlayingMessageId.value == messageId) {
                        Log.d("AlianViewModel", "已经在播放这条消息")
            withContext(Dispatchers.Main) {
                            Toast.makeText(context, "正在播放中...", Toast.LENGTH_SHORT).show()
                        }
                        return@withContext Result.success(Unit)
                    }
        var result: Result<Unit> = Result.success(Unit)

        try {
            // 在主线程更新播放状态
            withContext(Dispatchers.Main) {
                _isPlayingTTS.value = true
                _currentPlayingMessageId.value = messageId
            }
            Log.d("AlianViewModel", "开始播放TTS: messageId=$messageId, content=${message.content.take(50)}...")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "开始播放...", Toast.LENGTH_SHORT).show()
            }

            result = ttsClient!!.synthesizeAndPlay(message.content, context)

            if (result.isSuccess) {
                Log.d("AlianViewModel", "TTS播放成功")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "播放完成", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e("AlianViewModel", "TTS播放失败: ${result.exceptionOrNull()?.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "播放失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e("AlianViewModel", "播放TTS失败", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "播放失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            result = Result.failure(e)
        } finally {
            // 确保状态被正确重置（在主线程更新）
            Log.d("AlianViewModel", "重置播放状态: messageId=$messageId, 重置前: isPlayingTTS=${_isPlayingTTS.value}, currentPlayingMessageId=${_currentPlayingMessageId.value}")
            withContext(Dispatchers.Main) {
                Log.d("AlianViewModel", "开始重置状态: messageId=$messageId")
                _isPlayingTTS.value = false
                _currentPlayingMessageId.value = null
                Log.d("AlianViewModel", "状态已重置: messageId=$messageId, 重置后: isPlayingTTS=${_isPlayingTTS.value}, currentPlayingMessageId=${_currentPlayingMessageId.value}")
            }
        }

        result
    }

    /**
     * 停止TTS播放
     */
    fun stopTTS() {
        if (_isPlayingTTS.value) {
            ttsClient?.cancelPlayback()
            _isPlayingTTS.value = false
            _currentPlayingMessageId.value = null
            Log.d("AlianViewModel", "TTS播放已停止")
        }
        // 清空播放队列
        ttsQueue.clear()
        isQueueProcessing = false
        Log.d("AlianViewModel", "TTS播放队列已清空")
        
        // 停止流式会话
        stopStreamingTTS()
        
        // 清空流式消息块缓冲区
        ttsChunkBuffer.clear()
        isTTSChunkPlaying = false
        currentMessageIdForTTS = null
        Log.d("AlianViewModel", "流式消息块TTS缓冲区已清空")
        
        // 清空流式消息块队列
        streamingChunkQueue.close()
        isProcessingStreamingQueue = false
        Log.d("AlianViewModel", "流式消息块队列已清空")
    }

    /**
     * 停止流式 TTS 会话
     */
    private fun stopStreamingTTS() {
        if (streamingSessionStarted) {
            ttsClient?.stopStreamingSession()
            streamingSessionStarted = false
            Log.d("AlianViewModel", "流式 TTS 会话已停止")
        }
    }

    /**
     * 累积消息块到缓冲区（流式播放）
     */
    private fun accumulateTTSChunk(messageId: String, chunk: String, done: Boolean) {
        Log.d("AlianViewModel", "累积消息块到队列: messageId=$messageId, chunk=${chunk.take(50)}..., done=$done")
        
        // 将消息块放入队列（使用 trySend 避免阻塞）
        val success = streamingChunkQueue.trySend(StreamingChunk(messageId, chunk, done)).isSuccess
        if (!success) {
            Log.e("AlianViewModel", "发送消息块到队列失败：队列已满或已关闭")
        }
        
        // 如果没有在处理队列，开始处理
        if (!isProcessingStreamingQueue) {
            processStreamingChunkQueue()
        }
    }

    /**
     * 处理流式消息块队列（保证顺序）
     */
    private fun processStreamingChunkQueue() {
        if (isProcessingStreamingQueue) {
            Log.d("AlianViewModel", "队列正在处理中，跳过")
            return
        }

        isProcessingStreamingQueue = true
        Log.d("AlianViewModel", "开始处理流式消息块队列")

        // 在协程中处理队列
        viewModelScope.launch(Dispatchers.IO) {
            try {
                while (!streamingChunkQueue.isEmpty) {
                    val streamingChunk = streamingChunkQueue.receive()
                    Log.d("AlianViewModel", "从队列取出消息块: messageId=${streamingChunk.messageId}, chunk=${streamingChunk.chunk.take(50)}..., done=${streamingChunk.done}")
                    
                    // 处理消息块
                    processStreamingChunk(streamingChunk.messageId, streamingChunk.chunk, streamingChunk.done)
                }
            } catch (e: Exception) {
                Log.e("AlianViewModel", "处理流式消息块队列异常", e)
            } finally {
                isProcessingStreamingQueue = false
                Log.d("AlianViewModel", "流式消息块队列处理完成")
            }
        }
    }

    /**
     * 处理单个流式消息块
     */
    private suspend fun processStreamingChunk(messageId: String, chunk: String, done: Boolean) {
        Log.d("AlianViewModel", "处理流式消息块: messageId=$messageId, chunk=${chunk.take(50)}..., done=$done, streamingSessionStarted=$streamingSessionStarted")
        
        // 如果是新的消息，等待前一个流式会话完成后才启动新的
        if (currentMessageIdForTTS != messageId) {
            Log.d("AlianViewModel", "新的消息ID，等待前一个流式会话完成")
            
            // 等待前一个流式会话完成
            while (streamingSessionStarted && ttsClient?.isStreamingActive() == true) {
                Log.d("AlianViewModel", "等待前一个流式会话完成...")
                delay(100)
            }
            
            // 停止之前的流式会话
            stopStreamingTTS()
            ttsChunkBuffer.clear()
            currentMessageIdForTTS = messageId
            
            Log.d("AlianViewModel", "前一个会话已完成，启动新的流式会话")
            
            // 等待消息被添加到 _unifiedChatTimeline 中
            var messageAdded = false
            var waitCount = 0
            while (!messageAdded && waitCount < 50) {
                messageAdded = _unifiedChatTimeline.any { 
                    it is MessageItem && it.message.id == messageId 
                }
                if (!messageAdded) {
                    Log.d("AlianViewModel", "等待消息被添加到 _unifiedChatTimeline: messageId=$messageId, waitCount=$waitCount")
                    delay(20)
                    waitCount++
                }
            }
            Log.d("AlianViewModel", "消息已添加到 _unifiedChatTimeline: messageId=$messageId, messageAdded=$messageAdded, waitCount=$waitCount")
            
            // 启动流式会话
            try {
                val result = ttsClient?.startStreamingSession(context)
                if (result?.isSuccess == true) {
                    streamingSessionStarted = true
                    // 在主线程设置播放状态，以便UI能正确显示停止按钮
                    withContext(Dispatchers.Main) {
                        _isPlayingTTS.value = true
                        _currentPlayingMessageId.value = messageId
                        Log.d("AlianViewModel", "播放状态已更新: isPlayingTTS=${_isPlayingTTS.value}, currentPlayingMessageId=${_currentPlayingMessageId.value}")
                    }
                    Log.d("AlianViewModel", "流式 TTS 会话已启动，播放状态已更新: messageId=$messageId")

                    // 发送缓冲区中的内容
                    if (ttsChunkBuffer.isNotEmpty()) {
                        ttsClient?.sendStreamingChunk(ttsChunkBuffer.toString())
                        ttsChunkBuffer.clear()
                    }
                } else {
                    Log.e("AlianViewModel", "流式 TTS 会话启动失败")
                }
            } catch (e: Exception) {
                Log.e("AlianViewModel", "启动流式 TTS 会话异常", e)
            }
        }
        
        // 如果流式会话已启动，直接发送文本块
        if (streamingSessionStarted && ttsClient?.isStreamingActive() == true) {
            if (chunk.isNotBlank()) {
                Log.d("AlianViewModel", "发送流式文本块: chunk=${chunk.take(50)}...")
                ttsClient?.sendStreamingChunk(chunk)
            }
            
            // 如果已完成，结束流式会话
            if (done) {
                Log.d("AlianViewModel", "流式消息完成，结束会话")
                ttsClient?.finishStreamingSession()
                streamingSessionStarted = false

                // 等待流式会话完全结束
                var waitCount = 0
                while (ttsClient?.isStreamingActive() == true && waitCount < 50) {
                    Log.d("AlianViewModel", "等待流式会话结束... ($waitCount)")
                    delay(100)
                    waitCount++
                }
                Log.d("AlianViewModel", "流式会话状态检查完成，isStreamingActive=${ttsClient?.isStreamingActive()}")

                // 等待 AudioTrack 播放完成缓冲区中的所有音频数据
                Log.d("AlianViewModel", "等待 AudioTrack 播放完成...")
                var audioWaitCount = 0
                while (ttsClient?.isAudioTrackStillPlaying() == true && audioWaitCount < 100) {
                    Log.d("AlianViewModel", "AudioTrack 仍在播放... ($audioWaitCount)")
                    delay(50)
                    audioWaitCount++
                }
                Log.d("AlianViewModel", "AudioTrack 播放完成，audioWaitCount=$audioWaitCount")

                // 额外等待一段时间，确保音频播放完成
                Log.d("AlianViewModel", "流式会话已结束，额外等待音频播放完成（500ms）...")
                delay(500)

                // 在主线程重置播放状态
                Log.d("AlianViewModel", "准备重置播放状态...")
                withContext(Dispatchers.Main) {
                    _isPlayingTTS.value = false
                    _currentPlayingMessageId.value = null
                    Log.d("AlianViewModel", "播放状态已重置: isPlayingTTS=${_isPlayingTTS.value}, currentPlayingMessageId=${_currentPlayingMessageId.value}")
                }
                Log.d("AlianViewModel", "流式会话已结束，播放状态已重置")
            }
        } else {
            // 降级到缓冲区方式
            Log.d("AlianViewModel", "流式会话未启动，使用缓冲区方式")
            fallbackToBufferedTTS(chunk, done)
        }
    }

    /**
     * 降级到缓冲区 TTS 方式
     */
    private fun fallbackToBufferedTTS(chunk: String, done: Boolean) {
        ttsChunkBuffer.append(chunk)
        Log.d("AlianViewModel", "缓冲区内容: ${ttsChunkBuffer.toString().take(100)}...")
        
        // 判断是否应该触发播放
        val shouldPlay = shouldPlayTTSNow(chunk, done)
        
        if (shouldPlay || done) {
            val textToPlay = ttsChunkBuffer.toString()
            if (textToPlay.isNotBlank()) {
                Log.d("AlianViewModel", "触发播放: textToPlay=${textToPlay.take(50)}...")

                // 在协程中播放
                viewModelScope.launch(Dispatchers.IO) {
                    // 等待前一个播放完成
                    while (isTTSChunkPlaying) {
                        Log.d("AlianViewModel", "等待前一个播放完成...")
                        delay(100)
                    }
                    
                    playBufferedTTS(textToPlay, currentMessageIdForTTS ?: "")
                }
                
                // 清空缓冲区
                ttsChunkBuffer.clear()
            }
        }
    }

    /**
     * 判断是否应该立即播放 TTS
     */
    private fun shouldPlayTTSNow(chunk: String, done: Boolean): Boolean {
        // 如果已经完成，立即播放
        if (done) {
            return true
        }
        
        // 检查是否有句子结束符
        val sentenceEnders = listOf("。", "！", "？", "！", "？", ".", "!", "?")
        val hasSentenceEnder = sentenceEnders.any { chunk.contains(it) }
        
        // 检查缓冲区是否足够长（至少20个字符）
        val bufferLength = ttsChunkBuffer.length
        val isBufferLongEnough = bufferLength >= 20
        
        // 检查是否有逗号且缓冲区足够长
        val comma = listOf("，", "、", ",", ";", "；")
        val hasComma = comma.any { chunk.contains(it) }
        
        Log.d("AlianViewModel", "shouldPlayTTSNow: hasSentenceEnder=$hasSentenceEnder, isBufferLongEnough=$isBufferLongEnough, hasComma=$hasComma")
        
        // 如果有句子结束符，立即播放
        if (hasSentenceEnder) {
            return true
        }
        
        // 如果有逗号且缓冲区足够长，立即播放
        if (hasComma && isBufferLongEnough) {
            return true
        }
        
        return false
    }

    /**
     * 播放缓冲区的 TTS（降级方案）
     */
    private suspend fun playBufferedTTS(text: String, messageId: String): Result<Unit> = withContext(
        Dispatchers.IO) {
        Log.d("AlianViewModel", "playBufferedTTS被调用: text=${text.take(50)}..., messageId=$messageId")
        Log.d("AlianViewModel", "当前状态: ttsEnabled=$ttsEnabled, ttsClient=${ttsClient != null}, apiKey=${apiKey.isNotBlank()}")

        if (!ttsEnabled) {
            Log.e("AlianViewModel", "TTS未启用")
            return@withContext Result.failure(Exception("TTS未启用"))
        }

        if (ttsClient == null) {
            Log.e("AlianViewModel", "TTS客户端未初始化")
            return@withContext Result.failure(Exception("TTS客户端未初始化"))
        }

        if (text.isBlank()) {
            Log.e("AlianViewModel", "文本内容为空")
            return@withContext Result.failure(Exception("文本内容为空"))
        }

        var result: Result<Unit> = Result.success(Unit)

        try {
            // 在主线程更新播放状态
            withContext(Dispatchers.Main) {
                _isPlayingTTS.value = true
                _currentPlayingMessageId.value = messageId
                isTTSChunkPlaying = true
            }
            Log.d("AlianViewModel", "开始播放缓冲区TTS: text=${text.take(50)}..., messageId=$messageId")

            result = ttsClient!!.synthesizeAndPlay(text, context)

            if (result.isSuccess) {
                Log.d("AlianViewModel", "缓冲区TTS播放成功")
            } else {
                Log.e("AlianViewModel", "缓冲区TTS播放失败: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            Log.e("AlianViewModel", "播放缓冲区TTS失败", e)
            result = Result.failure(e)
        } finally {
            // 确保状态被正确重置（在主线程更新）
            Log.d("AlianViewModel", "重置缓冲区播放状态")
            withContext(Dispatchers.Main) {
                _isPlayingTTS.value = false
                _currentPlayingMessageId.value = null
                isTTSChunkPlaying = false
            }
        }

        result
    }

    /**
     * 将消息添加到播放队列
     */
    private fun enqueueTTSMessage(messageId: String) {
        Log.d("AlianViewModel", "添加消息到播放队列: messageId=$messageId, 队列大小=${ttsQueue.size}")
        ttsQueue.add(messageId)
        
        // 如果没有正在处理队列，开始处理
        if (!isQueueProcessing) {
            processTTSQueue()
        }
    }

    /**
     * 处理播放队列
     */
    private fun processTTSQueue() {
        if (isQueueProcessing) {
            Log.d("AlianViewModel", "队列正在处理中，跳过")
            return
        }

        if (ttsQueue.isEmpty()) {
            Log.d("AlianViewModel", "播放队列为空")
            return
        }

        isQueueProcessing = true
        Log.d("AlianViewModel", "开始处理播放队列，队列大小=${ttsQueue.size}")

        // 在协程中处理队列
        viewModelScope.launch(Dispatchers.IO) {
            while (ttsQueue.isNotEmpty()) {
                val messageId = ttsQueue.removeAt(0)
                Log.d("AlianViewModel", "从队列取出消息播放: messageId=$messageId, 剩余队列大小=${ttsQueue.size}")
                
                // 播放当前消息
                val result = playMessageTTS(messageId)
                
                Log.d("AlianViewModel", "playMessageTTS返回: $result")
                
                // 等待当前消息播放完成（确保播放状态已重置）
                // 使用一个标志来跟踪当前消息是否正在播放
                var wasPlaying = true
                while (wasPlaying) {
                    // 检查是否还在播放当前消息
                    wasPlaying = _isPlayingTTS.value && _currentPlayingMessageId.value == messageId
                    if (wasPlaying) {
                        delay(100)
                        Log.d("AlianViewModel", "等待播放完成: messageId=$messageId, isPlaying=${_isPlayingTTS.value}, currentId=${_currentPlayingMessageId.value}")
                    }
                }

                // 额外等待一小段时间，确保音频完全播放完毕
                delay(200)

                Log.d("AlianViewModel", "消息播放完成: messageId=$messageId, isPlaying=${_isPlayingTTS.value}, currentId=${_currentPlayingMessageId.value}")
            }
            
            isQueueProcessing = false
            Log.d("AlianViewModel", "播放队列处理完成")
        }
    }

    /**
     * 检查指定消息是否正在播放
     */
    fun isMessagePlaying(messageId: String): Boolean {
        val result = _isPlayingTTS.value && _currentPlayingMessageId.value == messageId
        Log.d("AlianViewModel", "isMessagePlaying: messageId=$messageId, isPlayingTTS=${_isPlayingTTS.value}, currentPlayingMessageId=${_currentPlayingMessageId.value}, result=$result")
        return result
    }

    /**
     * 获取当前正在播放的消息ID
     */
    fun getCurrentPlayingMessageId(): String? {
        return _currentPlayingMessageId.value
    }
}
