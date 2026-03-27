package com.alian.assistant.presentation.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import com.alian.assistant.common.utils.SpeechTextGuard
import com.alian.assistant.infrastructure.audio.AecVoiceCallAudioManager
import com.alian.assistant.infrastructure.audio.IAudioManager
import com.alian.assistant.infrastructure.audio.VoiceCallAudioManager
import com.alian.assistant.presentation.ui.screens.voicecall.MCPVoiceCallClient
import com.alibaba.fastjson.JSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 聊天消息
 */
data class VoiceCallMessage(
    val id: String = "",
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 语音通话状态
 */
sealed class VoiceCallState {
    object Idle : VoiceCallState()           // 空闲（未开始）
    object Recording : VoiceCallState()      // 录音中
    object Processing : VoiceCallState()     // 处理中（发送请求）
    object Playing : VoiceCallState()        // 播放中
    data class Error(val message: String) : VoiceCallState()  // 错误
}

/**
 * 语音通话 ViewModel
 * 独立管理语音通话的状态和逻辑，不依赖 AlianViewModel
 */
class VoiceCallViewModel(private val context: Context) {
    companion object {
        private const val TAG = "VoiceCallViewModel"
        private const val MAX_HISTORY_SIZE = 10  // 最多保留 10 条对话历史
        private const val NO_ACTIVE_TURN = -1L
    }

    // 通话状态
    private val _callState = MutableStateFlow<VoiceCallState>(VoiceCallState.Idle)
    val callState: StateFlow<VoiceCallState> = _callState

    // 对话历史
    private val _conversationHistory = mutableStateListOf<VoiceCallMessage>()
    val conversationHistory: SnapshotStateList<VoiceCallMessage> = _conversationHistory

    // 当前识别到的文本（实时显示）
    private val _currentRecognizedText = mutableStateOf("")
    val currentRecognizedText: State<String> = _currentRecognizedText

    // 当前正在播放的消息
    private val _currentPlayingMessage = mutableStateOf("")
    val currentPlayingMessage: State<String> = _currentPlayingMessage

    // 音频管理器
    private var audioManager: IAudioManager? = null

    // 语音通话客户端（支持 MCP 工具调用）
    private var voiceCallClient: MCPVoiceCallClient? = null

    // 当前协程任务
    private var currentJob: Job? = null

    // 协程作用域（用于管理所有协程）
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 是否正在处理消息（防止重复请求）
    private var isProcessing = false

    // 通话是否处于激活状态（用于拦截挂断后的异步重入）
    private var callActive = false

    // 当前激活的助手轮次
    private var activeTurnId: Long = NO_ACTIVE_TURN
    private var turnCounter: Long = 0L

    // API 配置
    var apiKey: String = ""
    var baseUrl: String = ""
    var model: String = "qwen-plus"
    var systemPrompt: String = ""
    var ttsVoice: String = "longyingmu_v3"
    var ttsSpeed: Float = 1.0f  // TTS语速
    var ttsInterruptEnabled: Boolean = false  // 实时语音打断开关
    var enableAEC: Boolean = false  // 是否启用 AEC（回声消除）
    var enableStreaming: Boolean = false  // 是否启用流式 LLM + 流式 TTS
    var volume: Int = 50  // 音量，取值范围 [0, 100]，默认为 50

    init {
        Log.d(TAG, "VoiceCallViewModel 初始化")
    }

    /**
     * 更新配置
     */
    fun updateConfig(apiKey: String, baseUrl: String, model: String, systemPrompt: String, ttsVoice: String, ttsSpeed: Float = 1.0f, ttsInterruptEnabled: Boolean = false, enableAEC: Boolean = false, enableStreaming: Boolean = false, volume: Int = 50) {
        this.apiKey = apiKey
        this.baseUrl = baseUrl
        this.model = model
        this.systemPrompt = systemPrompt
        this.ttsVoice = ttsVoice
        this.ttsSpeed = ttsSpeed
        this.ttsInterruptEnabled = ttsInterruptEnabled
        this.enableAEC = enableAEC
        this.enableStreaming = enableStreaming
        this.volume = volume
        Log.d(TAG, "配置已更新: apiKey=$apiKey, model=$model, systemPrompt=$systemPrompt, ttsVoice=$ttsVoice, ttsSpeed=$ttsSpeed, ttsInterruptEnabled=$ttsInterruptEnabled, enableAEC=$enableAEC, enableStreaming=$enableStreaming, volume=$volume")
    }

    /**
     * 开始通话
     */
    fun startCall() {
        Log.d(TAG, "开始通话")
        
        // 防止重复调用
        if (_callState.value !is VoiceCallState.Idle) {
            Log.w(TAG, "通话已在进行中，忽略此次请求")
            return
        }
        
        if (apiKey.isBlank()) {
            _callState.value = VoiceCallState.Error("请先设置 API Key")
            return
        }

        // 重置处理标志
        isProcessing = false
        activeTurnId = NO_ACTIVE_TURN

        // 清空之前的对话历史
        _conversationHistory.clear()
        _currentRecognizedText.value = ""
        _currentPlayingMessage.value = ""

        val shouldUseAec = enableAEC || ttsInterruptEnabled
        if (ttsInterruptEnabled && !enableAEC) {
            Log.w(TAG, "实时语音打断依赖 AEC，启动语音通话时自动切换到 AEC 音频链路")
        }

        // 初始化音频管理器
        audioManager = if (shouldUseAec) {
            val aecManager = AecVoiceCallAudioManager(
                context = context,
                apiKey = apiKey,
                ttsVoice = ttsVoice,
                ttsSpeed = ttsSpeed,
                ttsInterruptEnabled = ttsInterruptEnabled,
                volume = volume,
                metricsScene = "voice_call",
                usePersistentAec = ttsInterruptEnabled
            )
            // 设置播放中断回调
            aecManager.setOnPlaybackInterrupted {
                viewModelScope.launch {
                    if (!callActive || audioManager == null) {
                        Log.d(TAG, "通话未激活，忽略播放中断回调")
                        return@launch
                    }
                    interruptCurrentTurn("aec_playback_interrupted")
                }
            }
            aecManager
        } else {
            VoiceCallAudioManager(
                context = context,
                apiKey = apiKey,
                ttsVoice = ttsVoice,
                ttsSpeed = ttsSpeed,
                ttsInterruptEnabled = ttsInterruptEnabled,
                volume = volume,
                metricsScene = "voice_call"
            )
        }

        // 初始化语音通话客户端（支持 MCP 工具调用）
        voiceCallClient = MCPVoiceCallClient(
            apiKey = apiKey,
            baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
            model = model,
            systemPrompt = systemPrompt
        )

        callActive = true

        // 开始录音
        startRecording()
    }

    /**
     * 结束通话
     */
    fun stopCall() {
        Log.d(TAG, "结束通话")

        callActive = false

        // 取消所有协程（包括延迟重启录音任务）
        voiceCallClient?.cancelActiveRequest()
        currentJob?.cancel()
        currentJob = null
        viewModelScope.coroutineContext.cancelChildren()

        // 重置处理标志
        isProcessing = false
        activeTurnId = NO_ACTIVE_TURN

        // 释放音频（包含 stopAll + 内部作用域清理）
        audioManager?.release()
        audioManager = null
        voiceCallClient = null

        // 清空状态
        _callState.value = VoiceCallState.Idle
        _currentRecognizedText.value = ""
        _currentPlayingMessage.value = ""

        Log.d(TAG, "通话已结束")
    }

    /**
     * 开始录音
     */
    private fun startRecording() {
        if (!callActive || audioManager == null) {
            Log.d(TAG, "通话未激活，跳过 startRecording")
            return
        }

        Log.d(TAG, "开始录音")
        
        // 先设置状态为 Recording
        _callState.value = VoiceCallState.Recording
        Log.d(TAG, "状态已更新为: Recording")
        
        // 清空当前识别文本
        _currentRecognizedText.value = ""

        // 调用音频管理器开始录音
        audioManager?.startRecording(
            onPartialResult = partial@{ text ->
                if (!callActive || audioManager == null) {
                    Log.d(TAG, "通话未激活，忽略部分识别结果")
                    return@partial
                }
                // 实时显示识别结果（过滤 AI 回复）
                val filteredText = filterOutAIResponse(text)
                _currentRecognizedText.value = filteredText
            },
            onFinalResult = final@{ text ->
                if (!callActive || audioManager == null) {
                    Log.d(TAG, "通话未激活，忽略最终识别结果")
                    return@final
                }
                Log.d(TAG, "VoiceCallViewModel.onFinalResult 被调用: text='$text', isBlank=${text.isBlank()}")
                // 识别完成，发送到 API
                if (text.isNotBlank()) {
                    Log.d(TAG, "文本不为空，调用 onSpeechRecognized")
                    onSpeechRecognized(text)
                } else {
                    // 如果没有识别到内容，继续录音
                    Log.d(TAG, "未识别到语音内容，继续录音")
                    restartRecordingWithDelay(0, "blank_asr_final")
                }
            },
            onError = errorCb@{ error ->
                if (!callActive || audioManager == null || _callState.value is VoiceCallState.Idle) {
                    Log.d(TAG, "通话未激活，忽略录音错误: $error")
                    return@errorCb
                }
                Log.e(TAG, "录音错误: $error")
                _callState.value = VoiceCallState.Error(error)
            },
            onSilenceTimeout = {
                // 静音超时，自动挂机
                Log.d(TAG, "静音超时，自动挂机")
                stopCall()
            }
        )
    }

    /**
     * 过滤掉识别结果中包含的上一轮AI回复
     * @param text 识别的文本
     * @return 过滤后的文本
     */
    private fun filterOutAIResponse(text: String): String {
        Log.d(TAG, "开始过滤AI回复，识别结果: '$text'")
        Log.d(TAG, "对话历史大小: ${_conversationHistory.size}")
        
        if (text.isBlank()) {
            return text
        }
        
        // 获取上一条AI回复（如果是AI的消息）
        val lastAIMessage = _conversationHistory.lastOrNull { !it.isUser }
        
        Log.d(TAG, "最后一条AI消息: $lastAIMessage")
        
        if (lastAIMessage != null && lastAIMessage.content.isNotBlank()) {
            val aiResponse = lastAIMessage.content.trim()
            val recognizedText = text.trim()

            Log.d(TAG, "AI回复内容: '$aiResponse'")
            Log.d(TAG, "识别结果内容: '$recognizedText'")

            // 只在识别结果以AI回复开头时才过滤，避免误删用户说的话中包含的 AI 词汇
            if (recognizedText.startsWith(aiResponse)) {
                val filteredText = recognizedText.substring(aiResponse.length).trim()
                Log.d(TAG, "检测到AI回复（开头匹配），已过滤: 原文='$recognizedText', AI回复='$aiResponse', 过滤后='$filteredText'")
                return filteredText
            }

            Log.d(TAG, "识别结果不以AI回复开头，不进行过滤")
        } else {
            Log.d(TAG, "未找到AI消息或AI消息为空")
        }
        
        return text
    }

    /**
     * 语音识别回调
     */
    private fun onSpeechRecognized(text: String) {
        val thread = Thread.currentThread()
        Log.d(TAG, "onSpeechRecognized 被调用: text='$text', isProcessing=$isProcessing, thread=${thread.name}, isMain=${thread.name == "main"}")
        
        // 检查是否正在处理，防止重复请求
        if (isProcessing) {
            Log.w(TAG, "正在处理上一条消息，忽略此次识别结果")
            return
        }

        // 尝试从 JSON 中提取纯文本
        val contentText = try {
            val json = JSON.parseObject(text)
            json.getString("text") ?: text
        } catch (e: Exception) {
            Log.w(TAG, "解析识别结果 JSON 失败，使用原始结果", e)
            text
        }
        
        Log.d(TAG, "提取的文本内容: $contentText")

        // 过滤掉上一轮AI的回复
        val filteredText = filterOutAIResponse(contentText)

        val guardedText = SpeechTextGuard.sanitizeFinalText(filteredText)

        Log.d(TAG, "过滤后的文本内容: $filteredText, 闸门后文本: $guardedText")

        // 如果过滤后为空，则忽略此次识别
        if (guardedText.isBlank()) {
            Log.w(TAG, "过滤后文本为空，忽略此次识别结果")
            // 继续录音
            startRecording()
            return
        }

        // 添加用户消息到历史
        val userMessage = VoiceCallMessage(
            id = generateMessageId(),
            content = guardedText,
            isUser = true
        )
        _conversationHistory.add(userMessage)
        
        // 清空当前识别文本
        _currentRecognizedText.value = ""

        // 标记为正在处理
        isProcessing = true
        val turnId = activateTurn("user:$guardedText")

        // 发送到 API（使用 viewModelScope 统一管理）
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            processUserMessage(guardedText, turnId)
        }
    }

    /**
     * 处理用户消息（调用 API）
     */
    private suspend fun processUserMessage(message: String, turnId: Long) {
        if (isStaleTurn(turnId, "process_user_message_start")) return

        Log.d(TAG, "处理用户消息: turnId=$turnId, message=$message, enableStreaming=$enableStreaming")

        // 根据配置选择流式或非流式模式
        if (enableStreaming) {
            processUserMessageStream(message, turnId)
            return
        }

        _callState.value = VoiceCallState.Processing
        Log.d(TAG, "状态已更新为: Processing")

        try {
            // 调用 API
            val result = voiceCallClient?.sendMessage(
                message = message,
                conversationHistory = _conversationHistory.toList()
            )

            if (isStaleTurn(turnId, "process_user_message_response")) return

            if (result != null && result.isSuccess) {
                val content = result.getOrNull() ?: ""
                Log.d(TAG, "API 解析后的内容: $content")
                
                // 检查空响应
                if (content.isBlank()) {
                    Log.w(TAG, "API 返回空响应")
                    completeTurn(turnId, "empty_response")
                    _callState.value = VoiceCallState.Error("AI 返回空响应，请重试")
                    
                    // 等待 2 秒后重新开始录音
                    delay(2000)
                    startRecording()
                    return
                }

                // 播放响应
                playResponse(content, turnId)
            } else {
                val errorMsg = result?.exceptionOrNull()?.message ?: "API 调用失败"
                Log.e(TAG, "API 调用失败: $errorMsg")
                completeTurn(turnId, "request_failed")
                _callState.value = VoiceCallState.Error(errorMsg)
                
                // 等待 2 秒后重新开始录音
                delay(2000)
                startRecording()
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "处理用户消息任务已取消: turnId=$turnId")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "处理用户消息失败", e)
            if (isStaleTurn(turnId, "process_user_message_exception")) return
            completeTurn(turnId, "request_exception")
            _callState.value = VoiceCallState.Error(e.message ?: "处理失败")
            
            // 等待 2 秒后重新开始录音
            delay(2000)
            startRecording()
        }
    }

    /**
     * 处理用户消息（流式 LLM + 流式 TTS）
     * @param message 用户消息
     */
    private suspend fun processUserMessageStream(message: String, turnId: Long) {
        Log.d(TAG, "=== processUserMessageStream 开始 ===")
        Log.d(TAG, "处理用户消息（流式）: turnId=$turnId, message=$message")
        Log.d(TAG, "audioManager 是否为 null: ${audioManager == null}")
        Log.d(TAG, "voiceCallClient 是否为 null: ${voiceCallClient == null}")

        if (isStaleTurn(turnId, "process_stream_start")) return

        _callState.value = VoiceCallState.Processing
        Log.d(TAG, "状态已更新为: Processing")

        try {
            Log.d(TAG, "开始调用 sendMessageStream...")
            // 调用流式 API
            val textFlow = voiceCallClient?.sendMessageStream(
                message = message,
                conversationHistory = _conversationHistory.toList()
            ) ?: throw Exception("语音通话客户端未初始化")

            Log.d(TAG, "sendMessageStream 返回成功，准备调用 playTextStream")

            // 更新状态为播放中
            _callState.value = VoiceCallState.Playing

            // 流式播放
            Log.d(TAG, "调用 audioManager.playTextStream...")
            audioManager?.playTextStream(
                textFlow = textFlow,
                onFinished = streamFinished@{ fullText ->
                    if (isStaleTurn(turnId, "stream_finished")) {
                        return@streamFinished
                    }
                    Log.d(TAG, "流式播放完成: turnId=$turnId, 完整文本: $fullText")

                    if (fullText.isNotBlank()) {
                        _conversationHistory.add(
                            VoiceCallMessage(
                                id = generateMessageId(),
                                content = fullText,
                                isUser = false
                            )
                        )
                        trimConversationHistory()
                    }

                    _currentPlayingMessage.value = ""
                    completeTurn(turnId, "stream_finished")
                    restartRecordingWithDelay(100, "stream_finished")
                },
                onError = streamError@{ error ->
                    if (isStaleTurn(turnId, "stream_error")) {
                        return@streamError
                    }
                    if (isPlaybackInterruptedMessage(error)) {
                        Log.d(TAG, "流式播放被语音打断，交给统一中断流程处理: turnId=$turnId")
                        interruptCurrentTurn("stream_playback_interrupted")
                        return@streamError
                    }
                    Log.e(TAG, "流式播放错误: $error")
                    completeTurn(turnId, "stream_playback_error")
                    _callState.value = VoiceCallState.Error(error)
                    _currentPlayingMessage.value = ""

                    restartRecordingWithDelay(2000, "stream_playback_error")
                }
            )

        } catch (e: CancellationException) {
            Log.d(TAG, "处理用户消息（流式）任务已取消: turnId=$turnId")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "处理用户消息（流式）失败", e)
            if (isStaleTurn(turnId, "stream_exception")) return
            completeTurn(turnId, "stream_exception")
            _callState.value = VoiceCallState.Error(e.message ?: "处理失败")

            restartRecordingWithDelay(2000, "stream_process_exception")
        }
    }

    /**
     * 播放响应
     */
    private fun playResponse(text: String, turnId: Long) {
        if (isStaleTurn(turnId, "play_response_start")) return

        Log.d(TAG, "播放响应: turnId=$turnId, text长度=${text.length}, text前50字符=${text.take(50)}, 完整text=$text")

        _callState.value = VoiceCallState.Playing
        _currentPlayingMessage.value = text

        audioManager?.playText(
            text = text,
            onFinished = playFinished@{
                if (isStaleTurn(turnId, "play_finished")) {
                    return@playFinished
                }
                Log.d(TAG, "播放完成: turnId=$turnId")

                _conversationHistory.add(
                    VoiceCallMessage(
                        id = generateMessageId(),
                        content = text,
                        isUser = false
                    )
                )
                trimConversationHistory()
                _currentPlayingMessage.value = ""
                completeTurn(turnId, "playback_finished")
                restartRecordingWithDelay(100, "playback_finished")
            },
            onError = playError@{ error ->
                if (isStaleTurn(turnId, "play_error")) {
                    return@playError
                }
                if (isPlaybackInterruptedMessage(error)) {
                    Log.d(TAG, "播放被语音打断，交给统一中断流程处理: turnId=$turnId")
                    interruptCurrentTurn("playback_interrupted_error")
                    return@playError
                }
                Log.e(TAG, "播放错误: $error")
                completeTurn(turnId, "playback_error")
                _callState.value = VoiceCallState.Error(error)
                _currentPlayingMessage.value = ""

                restartRecordingWithDelay(2000, "playback_error")
            }
        )
    }

    private fun restartRecordingWithDelay(delayMs: Long, reason: String) {
        viewModelScope.launch {
            if (delayMs > 0) {
                delay(delayMs)
            }
            if (!callActive || audioManager == null || _callState.value is VoiceCallState.Idle) {
                Log.d(TAG, "通话未激活，跳过重启录音: reason=$reason")
                return@launch
            }
            _callState.value = VoiceCallState.Recording
            startRecording()
        }
    }

    private fun activateTurn(reason: String): Long {
        val turnId = ++turnCounter
        activeTurnId = turnId
        Log.d(TAG, "激活轮次: turnId=$turnId, reason=$reason")
        return turnId
    }

    private fun completeTurn(turnId: Long, reason: String) {
        if (activeTurnId != turnId) {
            Log.d(TAG, "轮次完成时已非激活状态，忽略: turnId=$turnId, activeTurnId=$activeTurnId, reason=$reason")
            return
        }
        Log.d(TAG, "完成轮次: turnId=$turnId, reason=$reason")
        activeTurnId = NO_ACTIVE_TURN
        isProcessing = false
        currentJob = null
    }

    private fun interruptCurrentTurn(reason: String, restartRecording: Boolean = true) {
        val interruptedTurnId = activeTurnId
        Log.d(TAG, "中断当前轮次: turnId=$interruptedTurnId, reason=$reason")
        activeTurnId = NO_ACTIVE_TURN
        isProcessing = false
        _currentPlayingMessage.value = ""
        voiceCallClient?.cancelActiveRequest()
        currentJob?.cancel()
        currentJob = null
        audioManager?.stopPlayback()

        if (restartRecording) {
            restartRecordingWithDelay(0, "interrupt_$reason")
        }
    }

    private fun isStaleTurn(turnId: Long, event: String): Boolean {
        val stale =
            turnId == NO_ACTIVE_TURN ||
                activeTurnId != turnId ||
                !callActive ||
                audioManager == null ||
                _callState.value is VoiceCallState.Idle
        if (stale) {
            Log.d(TAG, "丢弃过期回调: event=$event, turnId=$turnId, activeTurnId=$activeTurnId, callActive=$callActive")
        }
        return stale
    }

    private fun trimConversationHistory() {
        while (_conversationHistory.size > MAX_HISTORY_SIZE) {
            _conversationHistory.removeAt(0)
        }
    }

    private fun isPlaybackInterruptedMessage(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("interrupt") ||
            normalized.contains("interrupted") ||
            normalized.contains("cancel") ||
            normalized.contains("canceled") ||
            normalized.contains("中断") ||
            normalized.contains("取消")
    }

    /**
     * 生成消息 ID
     */
    private fun generateMessageId(): String {
        return "voice_msg_${System.currentTimeMillis()}_${_conversationHistory.size}"
    }

    /**
     * 获取状态描述
     */
    fun getStateDescription(): String {
        return when (_callState.value) {
            is VoiceCallState.Idle -> "空闲"
            is VoiceCallState.Recording -> "正在录音..."
            is VoiceCallState.Processing -> "正在思考..."
            is VoiceCallState.Playing -> "正在播放..."
            is VoiceCallState.Error -> "错误"
        }
    }

    /**
     * 获取状态颜色
     */
    fun getStateColor(): Color {
        return when (_callState.value) {
            is VoiceCallState.Idle -> Color.Gray
            is VoiceCallState.Recording -> Color(0xFF4CAF50)  // 绿色
            is VoiceCallState.Processing -> Color(0xFFFFA726)  // 橙色
            is VoiceCallState.Playing -> Color(0xFF2196F3)  // 蓝色
            is VoiceCallState.Error -> Color(0xFFF44336)  // 红色
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        Log.d(TAG, "清理资源")
        stopCall()
    }
}
