package com.alian.assistant.presentation.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import com.alian.assistant.common.utils.SpeechTextGuard
import com.alian.assistant.infrastructure.ai.llm.VLMClient
import com.alian.assistant.infrastructure.audio.AecVoiceCallAudioManager
import com.alian.assistant.infrastructure.audio.IAudioManager
import com.alian.assistant.infrastructure.audio.VoiceCallAudioManager
import com.alian.assistant.presentation.ui.screens.videocall.VideoCallClient
import com.alibaba.fastjson.JSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 聊天消息
 */
data class VideoCallMessage(
    val id: String = "",
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 图像识别结果
 */
data class ImageRecognitionResult(
    val id: String = "",
    val description: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 视频通话状态
 */
sealed class VideoCallState {
    object Idle : VideoCallState()           // 空闲（未开始）
    object Recording : VideoCallState()      // 录音中
    object Processing : VideoCallState()     // 处理中（发送请求）
    object Playing : VideoCallState()        // 播放中
    data class Error(val message: String) : VideoCallState()  // 错误
}

/**
 * 视频通话 ViewModel
 * 管理视频通话的状态和逻辑，复用 VoiceCall 的语音交互组件
 */
class VideoCallViewModel(private val context: Context) {
    companion object {
        private const val TAG = "VideoCallViewModel"
        private const val MAX_HISTORY_SIZE = 10
        private const val IMAGE_RECOGNITION_INTERVAL_MS = 5000L  // 5秒识别一次
        private const val MAX_IMAGE_HISTORY_SIZE = 5  // 最多保留5次识别结果
        private const val IMAGE_RECOGNITION_TIMEOUT_MS = 60000L  // 图像识别超时时间（60秒）
    }

    // 通话状态
    private val _callState = MutableStateFlow<VideoCallState>(VideoCallState.Idle)
    val callState: StateFlow<VideoCallState> = _callState

    // 对话历史
    private val _conversationHistory = mutableStateListOf<VideoCallMessage>()
    val conversationHistory: SnapshotStateList<VideoCallMessage> = _conversationHistory

    // 当前识别到的文本（实时显示）
    private val _currentRecognizedText = mutableStateOf("")
    val currentRecognizedText: State<String> = _currentRecognizedText

    // 当前正在播放的消息
    private val _currentPlayingMessage = mutableStateOf("")
    val currentPlayingMessage: State<String> = _currentPlayingMessage

    // 当前相机截图
    private val _currentFrame = mutableStateOf<Bitmap?>(null)
    val currentFrame: State<Bitmap?> = _currentFrame

    // 相机就绪状态
    private val _isCameraReady = mutableStateOf(false)
    val isCameraReady: State<Boolean> = _isCameraReady

    // 图像识别历史记录（每5秒识别一次的结果）
    private val _imageRecognitionHistory = mutableStateListOf<ImageRecognitionResult>()
    val imageRecognitionHistory: SnapshotStateList<ImageRecognitionResult> = _imageRecognitionHistory

    // 音频管理器（复用 VoiceCall 的音频管理器）
    private var audioManager: IAudioManager? = null

    // 视频通话客户端
    private var videoCallClient: VideoCallClient? = null

    // 视觉大模型客户端（用于图像识别）
    private var vlmClient: VLMClient? = null

    // 当前协程任务
    private var currentJob: Job? = null

    // 定期图像识别任务
    private var imageRecognitionJob: Job? = null

    // 协程作用域
    private val viewModelScope = CoroutineScope(Dispatchers.Main)

    // 图像识别专用协程作用域（使用独立的线程池，避免阻塞相机帧捕获）
    private val imageRecognitionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 是否正在处理消息
    private var isProcessing = false

    // 通话是否已停止（用于防止协程在停止后继续执行）
    private var isCallStopped = false

    // API 配置
    var apiKey: String = ""
    var baseUrl: String = ""
    var model: String = "qwen-vl-max"
    var ttsVoice: String = "longyingmu_v3"
    var ttsInterruptEnabled: Boolean = false
    var enableAEC: Boolean = false
    var enableStreaming: Boolean = false
    var volume: Int = 50  // 音量，取值范围 [0, 100]，默认为 50

    init {
        Log.d(TAG, "VideoCallViewModel 初始化, hashCode=${this.hashCode()}")
    }

    /**
     * 更新配置
     */
    fun updateConfig(
        apiKey: String,
        baseUrl: String,
        model: String,
        ttsVoice: String,
        ttsInterruptEnabled: Boolean = false,
        enableAEC: Boolean = false,
        enableStreaming: Boolean = false,
        volume: Int = 50
    ) {
        this.apiKey = apiKey
        this.baseUrl = baseUrl
        this.model = model
        this.ttsVoice = ttsVoice
        this.ttsInterruptEnabled = ttsInterruptEnabled
        this.enableAEC = enableAEC
        this.enableStreaming = enableStreaming
        this.volume = volume
        Log.d(TAG, "配置已更新: apiKey=$apiKey, model=$model, ttsVoice=$ttsVoice, enableStreaming=$enableStreaming, volume=$volume")
    }

    /**
     * 更新当前相机帧
     * 直接使用原始帧引用,避免不必要的复制
     * 注意: 调用方需要确保 Bitmap 在使用期间不被回收
     */
    fun updateCurrentFrame(frame: Bitmap?) {
        if (frame != null) {
            Log.d(TAG, "[DEBUG] 更新当前帧: ${frame.width}x${frame.height}")
            // 直接使用原始帧,不复制
            // 在需要保存到历史记录时才进行复制
            _currentFrame.value = frame
        } else {
            _currentFrame.value = null
        }
    }

    /**
     * 设置相机就绪状态
     */
    fun setCameraReady(ready: Boolean) {
        Log.d(TAG, "相机就绪状态变更: $ready")
        _isCameraReady.value = ready
    }

    /**
     * 开始通话
     */
    fun startCall() {
        Log.d(TAG, "开始视频通话")

        if (_callState.value !is VideoCallState.Idle) {
            Log.w(TAG, "通话已在进行中，忽略此次请求")
            return
        }

        // 重置停止标志
        isCallStopped = false

        if (apiKey.isBlank()) {
            _callState.value = VideoCallState.Error("请先设置 API Key")
            return
        }

        isProcessing = false
        _conversationHistory.clear()
        _currentRecognizedText.value = ""
        _currentPlayingMessage.value = ""

        // 初始化音频管理器
        audioManager = if (enableAEC) {
            val aecManager = AecVoiceCallAudioManager(
                context = context,
                apiKey = apiKey,
                ttsVoice = ttsVoice,
                ttsInterruptEnabled = ttsInterruptEnabled,
                volume = volume,
                metricsScene = "video_call",
                usePersistentAec = ttsInterruptEnabled
            )
            aecManager.setOnPlaybackInterrupted {
                Log.d(TAG, "播放被中断，更新状态并开始录音")
                _currentPlayingMessage.value = ""
                _callState.value = VideoCallState.Recording
                Log.d(TAG, "状态变更为 Recording (line 197)，调用栈：", Exception())
                startRecording()
            }
            aecManager
        } else {
            VoiceCallAudioManager(
                context = context,
                apiKey = apiKey,
                ttsVoice = ttsVoice,
                ttsInterruptEnabled = ttsInterruptEnabled,
                volume = volume,
                metricsScene = "video_call"
            )
        }

        // 初始化视频通话客户端
        videoCallClient = VideoCallClient(
            apiKey = apiKey,
            baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
            model = model
        )

        // 初始化视觉大模型客户端（用于图像识别）
        vlmClient = VLMClient(
            apiKey = apiKey,
            baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
            model = "qwen-vl-max"
        )

        // 设置状态为 Recording，触发 CameraPreview 组件的初始化
        _callState.value = VideoCallState.Recording
        Log.d(TAG, "状态变更为 Recording (line 225)，调用栈：", Exception())

        // 等待相机就绪后再开始录音
        viewModelScope.launch {
            Log.d(TAG, "等待相机就绪...")
            val timeout = 5000L // 5秒超时
            val startTime = System.currentTimeMillis()

            while (!_isCameraReady.value) {
                // 检查是否已停止
                if (isCallStopped) {
                    Log.d(TAG, "通话已停止，取消等待相机就绪")
                    return@launch
                }
                if (System.currentTimeMillis() - startTime > timeout) {
                    Log.e(TAG, "等待相机就绪超时")
                    _callState.value = VideoCallState.Error("相机初始化超时")
                    return@launch
                }
                delay(100)
            }

            // 再次检查是否已停止（防止在等待期间被停止）
            if (isCallStopped) {
                Log.d(TAG, "通话已停止，取消启动录音")
                return@launch
            }

            Log.d(TAG, "相机已就绪，开始录音")

            // 在独立协程中启动定期图像识别
            launch {
                startPeriodicImageRecognition()
            }

            // 在独立协程中开始录音
            launch {
                startRecording()
            }
        }
    }

    /**
     * 结束通话
     */
    fun stopCall() {
        Log.d(TAG, "结束视频通话, hashCode=${this.hashCode()}")
        Log.d(TAG, "stopCall() 调用栈：", Exception())
        Log.d(TAG, "当前状态: ${_callState.value}")

        // 设置停止标志，防止协程继续执行
        isCallStopped = true

        currentJob?.cancel()
        currentJob = null
        imageRecognitionJob?.cancel()
        imageRecognitionJob = null
        // 不取消 imageRecognitionScope，这样下次可以重新启动任务
        isProcessing = false

        audioManager?.stopAll()
        audioManager = null
        vlmClient = null

        _callState.value = VideoCallState.Idle
                Log.d(TAG, "状态变更为 Idle，调用栈：", Exception())
        _currentRecognizedText.value = ""
        _currentPlayingMessage.value = ""
        // 释放 Bitmap 资源
        _currentFrame.value?.recycle()
        _currentFrame.value = null
        _imageRecognitionHistory.clear()
        _isCameraReady.value = false

        Log.d(TAG, "视频通话已结束")
    }

    /**
     * 开始录音
     */
    private fun startRecording() {
        Log.d(TAG, "开始录音")

        // 检查是否已停止
        if (isCallStopped) {
            Log.d(TAG, "通话已停止，取消启动录音")
            return
        }

        _callState.value = VideoCallState.Recording
                Log.d(TAG, "状态变更为 Recording (line 295)，调用栈：", Exception())
        _currentRecognizedText.value = ""

        audioManager?.startRecording(
            onPartialResult = { text ->
                val filteredText = filterOutAIResponse(text)
                _currentRecognizedText.value = filteredText
            },
            onFinalResult = { text ->
                Log.d(TAG, "onFinalResult: text='$text'")
                // 检查是否已停止
                if (isCallStopped) {
                    Log.d(TAG, "通话已停止，忽略录音结果")
                    return@startRecording
                }
                if (text.isNotBlank()) {
                    onSpeechRecognized(text)
                } else {
                    startRecording()
                }
            },
            onError = { error ->
                Log.e(TAG, "录音错误: $error")
                _callState.value = VideoCallState.Error(error)
            },
            onSilenceTimeout = {
                Log.d(TAG, "静音超时，视频通话中继续录音等待用户输入")
                // 视频通话场景中，用户可能只是看着屏幕不说话，不应该自动挂断
                // 继续录音等待用户说话
                startRecording()
            }
        )
    }

    /**
     * 过滤掉识别结果中包含的上一轮AI回复
     */
    private fun filterOutAIResponse(text: String): String {
        if (text.isBlank()) {
            return text
        }

        val lastAIMessage = _conversationHistory.lastOrNull { !it.isUser }

        if (lastAIMessage != null && lastAIMessage.content.isNotBlank()) {
            val aiResponse = lastAIMessage.content.trim()
            val recognizedText = text.trim()

            if (recognizedText.startsWith(aiResponse)) {
                val filteredText = recognizedText.substring(aiResponse.length).trim()
                return filteredText
            }
        }

        return text
    }

    /**
     * 语音识别回调
     */
    private fun onSpeechRecognized(text: String) {
        Log.d(TAG, "onSpeechRecognized: text='$text', isProcessing=$isProcessing")

        // 检查是否已停止
        if (isCallStopped) {
            Log.d(TAG, "通话已停止，忽略语音识别结果")
            return
        }

        if (isProcessing) {
            Log.w(TAG, "正在处理上一条消息，忽略此次识别结果")
            return
        }

        val contentText = try {
            val json = JSON.parseObject(text)
            json.getString("text") ?: text
        } catch (e: Exception) {
            Log.w(TAG, "解析识别结果 JSON 失败，使用原始结果", e)
            text
        }

        val filteredText = filterOutAIResponse(contentText)
        val guardedText = SpeechTextGuard.sanitizeFinalText(filteredText)

        if (guardedText.isBlank()) {
            Log.w(TAG, "过滤后文本为空，忽略此次识别结果")
            startRecording()
            return
        }

        val userMessage = VideoCallMessage(
            id = generateMessageId(),
            content = guardedText,
            isUser = true
        )
        _conversationHistory.add(userMessage)
        _currentRecognizedText.value = ""

        isProcessing = true

        currentJob = viewModelScope.launch {
            try {
                processUserMessage(guardedText)
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * 处理用户消息（调用 API）
     */
    private suspend fun processUserMessage(message: String) {
        Log.d(TAG, "处理用户消息: $message, enableStreaming=$enableStreaming")

        if (enableStreaming) {
            processUserMessageStream(message)
            return
        }

        _callState.value = VideoCallState.Processing

        try {
            val result = videoCallClient?.sendMessage(
                message = message,
                conversationHistory = _conversationHistory.toList(),
                imageHistory = _imageRecognitionHistory.toList()
            )

            if (result != null && result.isSuccess) {
                val content = result.getOrNull() ?: ""
                Log.d(TAG, "API 解析后的内容: $content")

                if (content.isBlank()) {
                    Log.w(TAG, "API 返回空响应")
                    _callState.value = VideoCallState.Error("AI 返回空响应，请重试")
                    delay(2000)
                    startRecording()
                    return
                }

                val assistantMessage = VideoCallMessage(
                    id = generateMessageId(),
                    content = content,
                    isUser = false
                )
                _conversationHistory.add(assistantMessage)

                playResponse(content)

                while (_conversationHistory.size > MAX_HISTORY_SIZE) {
                    _conversationHistory.removeAt(0)
                }
            } else {
                val errorMsg = result?.exceptionOrNull()?.message ?: "API 调用失败"
                Log.e(TAG, "API 调用失败: $errorMsg")
                _callState.value = VideoCallState.Error(errorMsg)
                delay(2000)
                startRecording()
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理用户消息失败", e)
            _callState.value = VideoCallState.Error(e.message ?: "处理失败")
            delay(2000)
            startRecording()
        }
    }

    /**
     * 处理用户消息（流式 LLM + 流式 TTS）
     */
    private suspend fun processUserMessageStream(message: String) {
        Log.d(TAG, "处理用户消息（流式）: $message")

        _callState.value = VideoCallState.Processing

        try {
            val textFlow = videoCallClient?.sendMessageStream(
                message = message,
                conversationHistory = _conversationHistory.toList(),
                imageHistory = _imageRecognitionHistory.toList()
            ) ?: throw Exception("视频通话客户端未初始化")

            _callState.value = VideoCallState.Playing

            audioManager?.playTextStream(
                textFlow = textFlow,
                onFinished = { fullText ->
                    Log.d(TAG, "流式播放完成，完整文本: $fullText")

                    val assistantMessage = VideoCallMessage(
                        id = generateMessageId(),
                        content = fullText,
                        isUser = false
                    )
                    _conversationHistory.add(assistantMessage)

                    while (_conversationHistory.size > MAX_HISTORY_SIZE) {
                        _conversationHistory.removeAt(0)
                    }

                    _currentPlayingMessage.value = ""

                    CoroutineScope(Dispatchers.IO).launch {
                        delay(100)
                        // 检查是否已停止
                        if (!isCallStopped) {
                            _callState.value = VideoCallState.Recording
                            Log.d(TAG, "状态变更为 Recording (line 484)，调用栈：", Exception())
                            startRecording()
                        }
                    }
                },
                onError = streamError@{ error ->
                    if (isPlaybackInterruptedMessage(error)) {
                        Log.d(TAG, "流式播放被语音打断，按正常中断处理")
                        _currentPlayingMessage.value = ""
                        CoroutineScope(Dispatchers.IO).launch {
                            if (!isCallStopped) {
                                _callState.value = VideoCallState.Recording
                                startRecording()
                            }
                        }
                        return@streamError
                    }
                    Log.e(TAG, "流式播放错误: $error")
                    _callState.value = VideoCallState.Error(error)
                    _currentPlayingMessage.value = ""

                    CoroutineScope(Dispatchers.IO).launch {
                        delay(2000)
                        // 检查是否已停止
                        if (!isCallStopped) {
                            startRecording()
                        }
                    }
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "处理用户消息（流式）失败", e)
            _callState.value = VideoCallState.Error(e.message ?: "处理失败")
            delay(2000)
            startRecording()
        }
    }

    /**
     * 播放响应
     */
    private fun playResponse(text: String) {
        Log.d(TAG, "播放响应: text长度=${text.length}")

        _callState.value = VideoCallState.Playing
        _currentPlayingMessage.value = text

        audioManager?.playText(
            text = text,
            onFinished = {
                Log.d(TAG, "播放完成")

                _currentPlayingMessage.value = ""

                // 检查是否已停止
                if (isCallStopped) {
                    Log.d(TAG, "通话已停止，不重新启动录音")
                    return@playText
                }

                if (audioManager?.isCurrentlyPlaying() == false) {
                    _callState.value = VideoCallState.Recording
                    Log.d(TAG, "状态变更为 Recording (line 525)，调用栈：", Exception())
                    startRecording()
                } else {
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(100)
                        // 再次检查是否已停止
                        if (!isCallStopped) {
                            _callState.value = VideoCallState.Recording
                            Log.d(TAG, "状态变更为 Recording (line 530)，调用栈：", Exception())
                            startRecording()
                        }
                    }
                }
            },
            onError = { error ->
                if (isPlaybackInterruptedMessage(error)) {
                    Log.d(TAG, "播放被语音打断，按正常中断处理")
                    _currentPlayingMessage.value = ""
                    CoroutineScope(Dispatchers.IO).launch {
                        if (!isCallStopped) {
                            _callState.value = VideoCallState.Recording
                            startRecording()
                        }
                    }
                    return@playText
                }
                Log.e(TAG, "播放错误: $error")
                _callState.value = VideoCallState.Error(error)
                _currentPlayingMessage.value = ""

                CoroutineScope(Dispatchers.IO).launch {
                    delay(2000)
                    // 检查是否已停止
                    if (!isCallStopped) {
                        startRecording()
                    }
                }
            }
        )
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
        return "video_msg_${System.currentTimeMillis()}_${_conversationHistory.size}"
    }

    /**
     * 启动定期图像识别
     */
    private fun startPeriodicImageRecognition() {
        Log.d(TAG, "[DEBUG] 启动定期图像识别任务")

        // 检查是否已停止
        if (isCallStopped) {
            Log.d(TAG, "通话已停止，取消启动图像识别")
            return
        }

        imageRecognitionJob?.cancel()
        _imageRecognitionHistory.clear()

        Log.d(TAG, "[DEBUG] 准备启动图像识别协程")
        Log.d(TAG, "[DEBUG] imageRecognitionScope 是否活跃: ${imageRecognitionScope.isActive}")

        imageRecognitionJob = imageRecognitionScope.launch {
            Log.d(TAG, "[DEBUG] 图像识别协程已启动")
            var recognitionCount = 0

            // 等待相机捕获到第一帧
            Log.d(TAG, "[DEBUG] 等待相机捕获第一帧...")
            val waitTimeout = 10000L // 10秒超时
            val waitStartTime = System.currentTimeMillis()
            while (_currentFrame.value == null) {
                if (isCallStopped) {
                    Log.d(TAG, "通话已停止，取消等待相机帧")
                    return@launch
                }
                if (System.currentTimeMillis() - waitStartTime > waitTimeout) {
                    Log.w(TAG, "等待相机帧超时，取消图像识别")
                    return@launch
                }
                delay(500)
            }
            Log.d(TAG, "[DEBUG] 相机已捕获第一帧，开始图像识别")

            // 立即先识别一次
            recognitionCount++
            Log.d(TAG, "[DEBUG] 立即执行首次图像识别 #$recognitionCount")
            performImageRecognition(recognitionCount)

            // 然后开始定时识别
            while (true) {
                // 检查是否已停止
                if (isCallStopped) {
                    Log.d(TAG, "通话已停止，停止图像识别任务")
                    return@launch
                }
                delay(IMAGE_RECOGNITION_INTERVAL_MS)
                recognitionCount++

                Log.d(TAG, "[DEBUG] 图像识别定时器触发 #$recognitionCount")
                performImageRecognition(recognitionCount)
            }
        }
    }

    /**
     * 执行图像识别
     */
    private suspend fun performImageRecognition(count: Int) {
        // 确保在 IO 线程执行
        withContext(Dispatchers.IO) {
            // 获取当前帧并复制，避免与相机帧更新冲突
            val originalFrame = _currentFrame.value
            val frame = originalFrame?.copy(originalFrame.config ?: Bitmap.Config.ARGB_8888, false)

            if (frame != null && _callState.value !is VideoCallState.Idle) {
                try {
                    Log.d(TAG, "[DEBUG] 开始识别图像 #$count: ${frame.width}x${frame.height}")
                    val startTime = System.currentTimeMillis()

                    val description = recognizeImage(frame)

                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "[DEBUG] 图像识别 #$count 完成，耗时: ${elapsed}ms")

                    if (description.isNotBlank()) {
                        val result = ImageRecognitionResult(
                            id = "img_rec_${System.currentTimeMillis()}",
                            description = description
                        )
                        // 切换到主线程更新 UI
                        withContext(Dispatchers.Main) {
                            _imageRecognitionHistory.add(result)

                            // 限制历史记录数量，并立即回收超出的 Bitmap
                            while (_imageRecognitionHistory.size > MAX_IMAGE_HISTORY_SIZE) {
                                _imageRecognitionHistory.removeAt(0)
                            }
                        }

                        Log.d(TAG, "[DEBUG] 图像识别结果 #$count: $description")
                        Log.d(TAG, "[DEBUG] 图像识别结果已保存: ${description.take(100)}...")
                        Log.d(TAG, "[DEBUG] 当前图像识别历史数量: ${_imageRecognitionHistory.size}")
                    } else {
                        Log.w(TAG, "[DEBUG] 图像识别结果为空")
                    }
                } catch (e: CancellationException) {
                    // 协程被取消是正常情况，需要释放 Bitmap 并重新抛出
                    Log.d(TAG, "[DEBUG] 图像识别任务被取消")
                    frame?.recycle()
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[DEBUG] 图像识别失败", e)
                } finally {
                    // 确保在任何情况下都释放 Bitmap
                    if (frame != null && !frame.isRecycled) {
                        frame.recycle()
                        Log.d(TAG, "[DEBUG] Bitmap 已回收")
                    }
                }
            } else {
                Log.d(TAG, "[DEBUG] 跳过图像识别 #$count: frame=${frame != null}, state=${_callState.value}")
            }
        }
    }

    /**
     * 识别图像（调用 VLM）
     */
    private suspend fun recognizeImage(bitmap: Bitmap): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "[DEBUG] recognizeImage 开始: ${bitmap.width}x${bitmap.height}")
                val startTime = System.currentTimeMillis()

                // 添加超时控制，最多等待 60 秒
                val result = withTimeoutOrNull(IMAGE_RECOGNITION_TIMEOUT_MS) {
                    vlmClient?.predict(
                        prompt = "这是视频通话场景，请简要描述用户环境和状态，不超过50字。",
                        images = listOf(bitmap)
                    )
                }

                val elapsed = System.currentTimeMillis() - startTime

                if (result == null) {
                    Log.w(TAG, "[DEBUG] recognizeImage 超时，耗时: ${elapsed}ms")
                    "图像识别超时"
                } else {
                    Log.d(TAG, "[DEBUG] recognizeImage 完成，耗时: ${elapsed}ms")
                    result.getOrNull() ?: "无法识别图像"
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "[DEBUG] recognizeImage 超时")
                "图像识别超时"
            } catch (e: Exception) {
                Log.e(TAG, "[DEBUG] 识别图像失败", e)
                "图像识别失败: ${e.message}"
            }
        }
    }

    /**
     * 获取状态描述
     */
    fun getStateDescription(): String {
        return when (_callState.value) {
            is VideoCallState.Idle -> "空闲"
            is VideoCallState.Recording -> "正在录音..."
            is VideoCallState.Processing -> "正在思考..."
            is VideoCallState.Playing -> "正在播放..."
            is VideoCallState.Error -> "错误"
        }
    }

    /**
     * 获取状态颜色
     */
    fun getStateColor(): Color {
        return when (_callState.value) {
            is VideoCallState.Idle -> Color.Gray
            is VideoCallState.Recording -> Color(0xFF4CAF50)
            is VideoCallState.Processing -> Color(0xFFFFA726)
            is VideoCallState.Playing -> Color(0xFF2196F3)
            is VideoCallState.Error -> Color(0xFFF44336)
        }
    }

    /**
     * 清理资源
     * 取消所有协程作用域,防止内存泄漏
     */
    fun cleanup() {
        Log.d(TAG, "清理资源")
        stopCall()
        // 取消所有协程作用域
        viewModelScope.cancel()
        imageRecognitionScope.cancel()
    }
}
