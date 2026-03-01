package com.alian.assistant.presentation.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import com.alian.assistant.infrastructure.audio.AecVoiceCallAudioManager
import com.alian.assistant.infrastructure.audio.IAudioManager
import com.alian.assistant.infrastructure.audio.VoiceCallAudioManager
import com.alian.assistant.presentation.ui.screens.videocall.ImageFrame
import com.alian.assistant.presentation.ui.screens.videocall.MCPVLMOnlyVideoCallClient
import com.alibaba.fastjson.JSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 仅使用视觉大模型的视频通话 ViewModel
 * 功能特点：
 * 1. 相机帧每5秒抓取并压缩保存
 * 2. ASR识别到用户文本后，与图像一起交由视觉大模型处理
 * 3. 支持流式返回响应
 */
class VLMOnlyVideoCallViewModel(private val context: Context) {
    companion object {
        private const val TAG = "VLMOnlyVideoCallViewModel"
        private const val MAX_HISTORY_SIZE = 10
        private const val FRAME_CAPTURE_INTERVAL_MS = 5000L  // 5秒抓取一次帧（默认）
        private const val MAX_IMAGE_HISTORY_SIZE = 5  // 最多保留5帧

        // 混合捕获模式配置
        private const val SPEAKING_CAPTURE_INTERVAL_MS = 2000L  // 说话时2秒捕获一次
        private const val IDLE_CAPTURE_INTERVAL_MS = 15000L  // 空闲时15秒捕获一次
        private const val SPEECH_TIMEOUT_MS = 3000L  // 3秒无语音认为说话结束
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

    // 图像历史记录（每5秒抓取的帧）
    private val _imageHistory = mutableStateListOf<ImageFrame>()
    val imageHistory: SnapshotStateList<ImageFrame> = _imageHistory

    // 音频管理器（复用 VoiceCall 的音频管理器）
    private var audioManager: IAudioManager? = null

    // 视频通话客户端（仅使用视觉大模型）
    private var videoCallClient: MCPVLMOnlyVideoCallClient? = null

    // 当前协程任务
    private var currentJob: Job? = null

    // 定期帧捕获任务
    private var frameCaptureJob: Job? = null

    // 协程作用域
    private val viewModelScope = CoroutineScope(Dispatchers.Main)

    // 帧捕获专用协程作用域
    private val frameCaptureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 是否正在处理消息
    private var isProcessing = false

    // 通话是否已停止
    private var isCallStopped = false

    // 语音活动状态跟踪（混合捕获模式）
    private var isSpeaking = false  // 是否正在说话
    private var lastSpeechTime = 0L  // 上次检测到语音的时间
    private var currentCaptureInterval = FRAME_CAPTURE_INTERVAL_MS  // 当前捕获间隔

    // API 配置
    var apiKey: String = ""
    var baseUrl: String = ""
    var model: String = "qwen-vl-max"
    var systemPrompt: String = ""
    var ttsVoice: String = "longyingmu_v3"
    var ttsSpeed: Float = 1.0f  // TTS语速
    var ttsInterruptEnabled: Boolean = false
    var enableAEC: Boolean = false
    var enableStreaming: Boolean = false
    var volume: Int = 50  // 音量，取值范围 [0, 100]，默认为 50

    init {
        Log.d(TAG, "VLMOnlyVideoCallViewModel 初始化")
    }

    /**
     * 更新配置
     */
    fun updateConfig(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        ttsVoice: String,
        ttsSpeed: Float = 1.0f,
        ttsInterruptEnabled: Boolean = false,
        enableAEC: Boolean = false,
        enableStreaming: Boolean = false,
        volume: Int = 50
    ) {
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
        Log.d(TAG, "配置已更新: apiKey=$apiKey, model=$model, systemPrompt=$systemPrompt, ttsVoice=$ttsVoice, ttsSpeed=$ttsSpeed, enableStreaming=$enableStreaming, volume=$volume")
    }

    /**
     * 更新当前相机帧
     * 注意: CameraPreview 已经复制了 Bitmap，这里直接使用即可
     */
    fun updateCurrentFrame(frame: Bitmap?) {
        if (frame != null) {
            // 释放旧的 Bitmap
            _currentFrame.value?.recycle()

            // 直接使用传入的 Bitmap（CameraPreview 已经复制过了）
            Log.d(TAG, "[VLM-ONLY] 更新当前帧: ${frame.width}x${frame.height}")
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
     * 语音活动检测：检测到用户开始说话
     */
    private fun onSpeechActivityDetected() {
        Log.d(TAG, "[VLM-ONLY] 检测到语音活动")
        isSpeaking = true
        lastSpeechTime = System.currentTimeMillis()

        // 切换到高频捕获模式
        currentCaptureInterval = SPEAKING_CAPTURE_INTERVAL_MS
        Log.d(TAG, "[VLM-ONLY] 切换到高频捕获模式：${currentCaptureInterval}ms")

        // 立即捕获当前帧，确保语音与图像关联
        viewModelScope.launch {
            captureFrame(trigger = "speech")
        }
    }

    /**
     * 语音活动检测：检测到用户停止说话
     */
    private fun onSpeechActivityEnd() {
        Log.d(TAG, "[VLM-ONLY] 检测到语音活动结束")
        isSpeaking = false

        // 切换到低频捕获模式
        currentCaptureInterval = IDLE_CAPTURE_INTERVAL_MS
        Log.d(TAG, "[VLM-ONLY] 切换到低频捕获模式：${currentCaptureInterval}ms")
    }

    /**
     * 开始通话
     */
    fun startCall() {
        Log.d(TAG, "[VLM-ONLY] 开始视频通话")

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
        _imageHistory.clear()

        // 初始化音频管理器
        audioManager = if (enableAEC) {
            val aecManager = AecVoiceCallAudioManager(
                context = context,
                apiKey = apiKey,
                ttsVoice = ttsVoice,
                ttsSpeed = ttsSpeed,
                ttsInterruptEnabled = ttsInterruptEnabled,
                volume = volume
            )
            aecManager.setOnPlaybackInterrupted {
                Log.d(TAG, "播放被中断，更新状态并开始录音")
                _currentPlayingMessage.value = ""
                _callState.value = VideoCallState.Recording
                startRecording()
            }
            aecManager
        } else {
            VoiceCallAudioManager(
                context = context,
                apiKey = apiKey,
                ttsVoice = ttsVoice,
                ttsSpeed = ttsSpeed,
                ttsInterruptEnabled = ttsInterruptEnabled,
                volume = volume
            )
        }

        // 初始化视频通话客户端（仅使用视觉大模型）
        videoCallClient = MCPVLMOnlyVideoCallClient(
            apiKey = apiKey,
            baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
            model = model,
            systemPrompt = systemPrompt
        )

        // 设置状态为 Recording，触发 CameraPreview 组件的初始化
        _callState.value = VideoCallState.Recording

        // 等待相机就绪后再开始录音
        viewModelScope.launch {
            Log.d(TAG, "等待相机就绪...")
            val timeout = 5000L // 5秒超时
            val startTime = System.currentTimeMillis()

            while (!_isCameraReady.value) {
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

            if (isCallStopped) {
                Log.d(TAG, "通话已停止，取消启动录音")
                return@launch
            }

            Log.d(TAG, "相机已就绪，开始录音和帧捕获")

            // 在独立协程中启动定期帧捕获
            launch {
                startPeriodicFrameCapture()
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
        Log.d(TAG, "[VLM-ONLY] 结束视频通话")

        // 设置停止标志
        isCallStopped = true

        currentJob?.cancel()
        currentJob = null
        frameCaptureJob?.cancel()
        frameCaptureJob = null
        isProcessing = false

        audioManager?.stopAll()
        audioManager = null
        videoCallClient = null

        _callState.value = VideoCallState.Idle
        _currentRecognizedText.value = ""
        _currentPlayingMessage.value = ""

        // 释放 Bitmap 资源
        _currentFrame.value?.recycle()
        _currentFrame.value = null

        // 释放图像历史中的 Bitmap
        _imageHistory.forEach { it.bitmap.recycle() }
        _imageHistory.clear()

        _isCameraReady.value = false

        Log.d(TAG, "[VLM-ONLY] 视频通话已结束")
    }

    /**
     * 开始录音
     */
    private fun startRecording() {
        Log.d(TAG, "[VLM-ONLY] 开始录音")

        if (isCallStopped) {
            Log.d(TAG, "通话已停止，取消启动录音")
            return
        }

        _callState.value = VideoCallState.Recording
        _currentRecognizedText.value = ""

        audioManager?.startRecording(
            onPartialResult = { text ->
                val filteredText = filterOutAIResponse(text)
                _currentRecognizedText.value = filteredText

                // 检测到语音活动，触发帧捕获
                if (filteredText.isNotBlank()) {
                    onSpeechActivityDetected()
                }
            },
            onFinalResult = { text ->
                Log.d(TAG, "[VLM-ONLY] onFinalResult: text='$text'")
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
                Log.d(TAG, "静音超时，继续录音等待用户输入")
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
        Log.d(TAG, "[VLM-ONLY] onSpeechRecognized: text='$text', isProcessing=$isProcessing")

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

        if (filteredText.isBlank()) {
            Log.w(TAG, "过滤后文本为空，忽略此次识别结果")
            startRecording()
            return
        }

        val userMessage = VideoCallMessage(
            id = generateMessageId(),
            content = filteredText,
            isUser = true
        )
        _conversationHistory.add(userMessage)
        _currentRecognizedText.value = ""

        isProcessing = true

        currentJob = viewModelScope.launch {
            try {
                processUserMessage(filteredText)
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * 处理用户消息（调用 API）
     */
    private suspend fun processUserMessage(message: String) {
        Log.d(TAG, "[VLM-ONLY] 处理用户消息: $message, enableStreaming=$enableStreaming")

        if (enableStreaming) {
            processUserMessageStream(message)
            return
        }

        _callState.value = VideoCallState.Processing

        try {
            val result = videoCallClient?.sendMessage(
                message = message,
                conversationHistory = _conversationHistory.toList(),
                imageHistory = _imageHistory.toList()
            )

            if (result != null && result.isSuccess) {
                val content = result.getOrNull() ?: ""
                Log.d(TAG, "[VLM-ONLY] API 响应: $content")

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
     * 处理用户消息（流式）
     */
    private suspend fun processUserMessageStream(message: String) {
        Log.d(TAG, "[VLM-ONLY] 处理用户消息（流式）: $message")

        _callState.value = VideoCallState.Processing

        try {
            val textFlow = videoCallClient?.sendMessageStream(
                message = message,
                conversationHistory = _conversationHistory.toList(),
                imageHistory = _imageHistory.toList()
            ) ?: throw Exception("视频通话客户端未初始化")

            _callState.value = VideoCallState.Playing

            // 直接调用 playTextStream，不使用 withContext 包裹
            audioManager?.playTextStream(
                textFlow = textFlow,
                onFinished = { fullText ->
                    Log.d(TAG, "[VLM-ONLY] 流式播放完成，完整文本: $fullText")

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

                    viewModelScope.launch {
                        delay(100)
                        if (!isCallStopped) {
                            _callState.value = VideoCallState.Recording
                            startRecording()
                        }
                    }
                },
                onError = { error ->
                    Log.e(TAG, "[VLM-ONLY] 流式播放错误: $error")
                    _callState.value = VideoCallState.Error(error)
                    _currentPlayingMessage.value = ""

                    viewModelScope.launch {
                        delay(2000)
                        if (!isCallStopped) {
                            startRecording()
                        }
                    }
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "[VLM-ONLY] 处理用户消息（流式）失败", e)
            _callState.value = VideoCallState.Error(e.message ?: "处理失败")
            delay(2000)
            startRecording()
        }
    }

    /**
     * 播放响应
     */
    private fun playResponse(text: String) {
        Log.d(TAG, "[VLM-ONLY] 播放响应: text长度=${text.length}")

        _callState.value = VideoCallState.Playing
        _currentPlayingMessage.value = text

        audioManager?.playText(
            text = text,
            onFinished = {
                Log.d(TAG, "[VLM-ONLY] 播放完成")

                _currentPlayingMessage.value = ""

                if (isCallStopped) {
                    Log.d(TAG, "通话已停止，不重新启动录音")
                    return@playText
                }

                if (audioManager?.isCurrentlyPlaying() == false) {
                    _callState.value = VideoCallState.Recording
                    startRecording()
                } else {
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(100)
                        if (!isCallStopped) {
                            _callState.value = VideoCallState.Recording
                            startRecording()
                        }
                    }
                }
            },
            onError = { error ->
                Log.e(TAG, "[VLM-ONLY] 播放错误: $error")
                _callState.value = VideoCallState.Error(error)
                _currentPlayingMessage.value = ""

                CoroutineScope(Dispatchers.IO).launch {
                    delay(2000)
                    if (!isCallStopped) {
                        startRecording()
                    }
                }
            }
        )
    }

    /**
     * 生成消息 ID
     */
    private fun generateMessageId(): String {
        return "vlm_video_msg_${System.currentTimeMillis()}_${_conversationHistory.size}"
    }

    /**
     * 启动混合帧捕获模式
     * 结合语音触发 + 定期捕获的混合模式
     */
    private fun startPeriodicFrameCapture() {
        Log.d(TAG, "[VLM-ONLY] 启动混合帧捕获模式")

        if (isCallStopped) {
            Log.d(TAG, "通话已停止，取消启动帧捕获")
            return
        }

        frameCaptureJob?.cancel()
        _imageHistory.clear()

        // 重置语音活动状态
        isSpeaking = false
        lastSpeechTime = System.currentTimeMillis()
        currentCaptureInterval = FRAME_CAPTURE_INTERVAL_MS

        frameCaptureJob = frameCaptureScope.launch {
            var captureCount = 0

            // 等待相机捕获到第一帧
            Log.d(TAG, "[VLM-ONLY] 等待相机捕获第一帧...")
            val waitTimeout = 10000L // 10秒超时
            val waitStartTime = System.currentTimeMillis()
            while (_currentFrame.value == null) {
                if (isCallStopped) {
                    Log.d(TAG, "通话已停止，取消等待相机帧")
                    return@launch
                }
                if (System.currentTimeMillis() - waitStartTime > waitTimeout) {
                    Log.w(TAG, "等待相机帧超时，取消帧捕获")
                    return@launch
                }
                delay(500)
            }
            Log.d(TAG, "[VLM-ONLY] 相机已捕获第一帧，开始帧捕获")

            // 立即先捕获一次（初始帧）
            captureCount++
            Log.d(TAG, "[VLM-ONLY] 执行初始帧捕获 #$captureCount")
            captureFrame(trigger = "initial")

            // 开始混合捕获循环
            while (true) {
                if (isCallStopped) {
                    Log.d(TAG, "通话已停止，停止帧捕获任务")
                    return@launch
                }

                // 检查是否需要切换捕获模式
                val timeSinceLastSpeech = System.currentTimeMillis() - lastSpeechTime
                if (isSpeaking && timeSinceLastSpeech > SPEECH_TIMEOUT_MS) {
                    // 3秒无语音，认为说话结束
                    onSpeechActivityEnd()
                }

                // 使用动态捕获间隔
                delay(currentCaptureInterval)
                captureCount++

                Log.d(TAG, "[VLM-ONLY] 定期帧捕获触发 #$captureCount [间隔:${currentCaptureInterval}ms, 说话:${isSpeaking}]")
                captureFrame(trigger = "periodic")
            }
        }
    }

    /**
     * 捕获帧
     * @param trigger 触发源：speech(语音触发)、periodic(定期捕获)、initial(初始捕获)
     */
    private suspend fun captureFrame(trigger: String) {
        withContext(Dispatchers.IO) {
            // 获取当前帧并复制（在主线程中获取，避免并发问题）
            val frame = withContext(Dispatchers.Main) {
                val originalFrame = _currentFrame.value
                if (originalFrame != null && !originalFrame.isRecycled) {
                    originalFrame.copy(originalFrame.config ?: Bitmap.Config.ARGB_8888, false)
                } else {
                    null
                }
            }

            if (frame != null && _callState.value !is VideoCallState.Idle) {
                var addedToHistory = false
                try {
                    Log.d(TAG, "[VLM-ONLY] 捕获帧 [触发源:$trigger]: ${frame.width}x${frame.height}")
                    val startTime = System.currentTimeMillis()

                    // 保存到历史记录
                    val imageFrame = ImageFrame(
                        bitmap = frame,
                        timestamp = System.currentTimeMillis(),
                        trigger = trigger
                    )

                    // 切换到主线程更新 UI
                    withContext(Dispatchers.Main) {
                        _imageHistory.add(imageFrame)
                        addedToHistory = true

                        // 限制历史记录数量，并立即回收超出的 Bitmap
                        while (_imageHistory.size > MAX_IMAGE_HISTORY_SIZE) {
                            val oldFrame = _imageHistory.removeAt(0)
                            if (!oldFrame.bitmap.isRecycled) {
                                oldFrame.bitmap.recycle()
                                Log.d(TAG, "[VLM-ONLY] 旧帧 Bitmap 已回收")
                            }
                        }
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "[VLM-ONLY] 帧捕获 [触发源:$trigger] 完成，耗时: ${elapsed}ms")
                    Log.d(TAG, "[VLM-ONLY] 当前图像历史数量: ${_imageHistory.size}")
                } catch (e: CancellationException) {
                    // 协程被取消是正常情况，需要释放 Bitmap 并重新抛出
                    Log.d(TAG, "[VLM-ONLY] 帧捕获任务被取消")
                    frame?.recycle()
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[VLM-ONLY] 帧捕获失败", e)
                } finally {
                    // 只有未添加到历史记录时才回收 Bitmap
                    // 如果已添加到历史记录，由历史记录管理 Bitmap 的生命周期
                    if (!addedToHistory && frame != null && !frame.isRecycled) {
                        frame.recycle()
                        Log.d(TAG, "[VLM-ONLY] Bitmap 已回收（未添加到历史记录）")
                    }
                }
            } else {
                Log.d(TAG, "[VLM-ONLY] 跳过帧捕获 [触发源:$trigger]: frame=${frame != null}, state=${_callState.value}")
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
        Log.d(TAG, "[VLM-ONLY] 清理资源")
        stopCall()
        // 取消所有协程作用域
        viewModelScope.cancel()
        frameCaptureScope.cancel()
    }
}