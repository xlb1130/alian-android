package com.alian.assistant.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import com.alian.assistant.core.agent.PhoneCallAgent
import com.alian.assistant.core.agent.PhoneCallMessage
import com.alian.assistant.infrastructure.ai.llm.VLMClient
import com.alian.assistant.core.agent.AgentPermissionCheck
import com.alian.assistant.infrastructure.audio.AecVoiceCallAudioManager
import com.alian.assistant.infrastructure.audio.IAudioManager
import com.alian.assistant.infrastructure.audio.VoiceCallAudioManager
import com.alian.assistant.infrastructure.device.ScreenCaptureManager
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.presentation.ui.screens.phonecall.MCPPhoneCallClient
import com.alian.assistant.presentation.ui.screens.phonecall.PhoneCallOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 手机通话状态
 */
sealed class PhoneCallState {
    object Idle : PhoneCallState()           // 空闲（未开始）
    object Recording : PhoneCallState()      // 录音中
    object Processing : PhoneCallState()     // 处理中（发送请求）
    object Playing : PhoneCallState()        // 播放中
    data class Operating(val description: String) : PhoneCallState()  // 操作中
    data class Error(val message: String) : PhoneCallState()  // 错误
}

/**
 * 浮动窗口状态
 */
sealed class FloatingWindowState {
    object Disabled : FloatingWindowState()  // 禁用
    object Normal : FloatingWindowState()    // 正常状态
    object Minimized : FloatingWindowState() // 最小化
    object Maximized : FloatingWindowState() // 最大化
    object Hidden : FloatingWindowState()    // 隐藏
}

/**
 * 手机通话 ViewModel
 * 管理手机通话的状态和逻辑
 */
class PhoneCallViewModel(private val context: Context) {
    companion object {
        private const val TAG = "PhoneCallViewModel"
        private const val MAX_HISTORY_SIZE = 20  // 最多保留 20 条对话历史
    }

    // 通话状态
    private val _callState = MutableStateFlow<PhoneCallState>(PhoneCallState.Idle)
    val callState: StateFlow<PhoneCallState> = _callState

    // 浮动窗口状态
    private val _floatingWindowState = MutableStateFlow<FloatingWindowState>(FloatingWindowState.Disabled)
    val floatingWindowState: StateFlow<FloatingWindowState> = _floatingWindowState

    // 对话历史
    private val _conversationHistory = mutableStateListOf<PhoneCallMessage>()
    val conversationHistory: SnapshotStateList<PhoneCallMessage> = _conversationHistory

    // 当前识别到的文本（实时显示）
    private val _currentRecognizedText = mutableStateOf("")
    val currentRecognizedText: State<String> = _currentRecognizedText

    // 当前正在播放的消息
    private val _currentPlayingMessage = mutableStateOf("")
    val currentPlayingMessage: State<String> = _currentPlayingMessage

    // 当前屏幕截图
    private val _currentScreen = mutableStateOf<Bitmap?>(null)
    val currentScreen: State<Bitmap?> = _currentScreen

    // AI 是否正在操作手机
    private val _isAiOperating = mutableStateOf(false)
    val isAiOperating: State<Boolean> = _isAiOperating

    // AI 当前执行的操作描述
    private val _currentOperation = mutableStateOf("")
    val currentOperation: State<String> = _currentOperation

    // 浮动窗口位置
    private val _floatingWindowPosition = mutableStateOf(Pair(0, 0))
    val floatingWindowPosition: State<Pair<Int, Int>> = _floatingWindowPosition

    // 浮动窗口透明度
    private val _floatingWindowOpacity = mutableStateOf(0.95f)
    val floatingWindowOpacity: State<Float> = _floatingWindowOpacity

    // 音频管理器（复用 VoiceCall 的音频管理器）
    private var audioManager: IAudioManager? = null

    // PhoneCallAgent
    private var phoneCallAgent: PhoneCallAgent? = null

    // VLMClient
    private var vlmClient: VLMClient? = null

    // MCPPhoneCallClient（多模态 VLM 客户端，支持多帧截图）
    private var phoneCallClient: MCPPhoneCallClient? = null

    // 屏幕捕获管理器（持续截图）
    private var screenCaptureManager: ScreenCaptureManager? = null

    // IDeviceController
    private var deviceController: IDeviceController? = null

    // 当前协程任务
    private var currentJob: Job? = null

    // 协程作用域
    private val viewModelScope = CoroutineScope(Dispatchers.Main)

    // 是否正在处理消息（防止重复请求）
    private var isProcessing = false

    // 权限检查器（复用 AgentPermissionCheck）
    private val permissionChecker = AgentPermissionCheck(context)

    // MediaProjection 权限状态
    private var mediaProjectionResultCode: Int? = null
    private var mediaProjectionData: Intent? = null

    // 浮动窗口视图引用（用于控制显示/隐藏）
    private var floatingWindowView: com.alian.assistant.presentation.ui.screens.phonecall.PhoneCallFloatingWindowView? = null

    // API 配置
    var apiKey: String = ""
    var baseUrl: String = ""
    var model: String = "qwen-plus"
    var systemPrompt: String = ""
    var ttsVoice: String = "longyingmu_v3"
    var ttsSpeed: Float = 1.0f
    var ttsInterruptEnabled: Boolean = false
    var enableAEC: Boolean = false
    var enableStreaming: Boolean = false
    var volume: Int = 50

    // 手机通话配置
    var autoOperate: Boolean = true  // 是否自动执行操作
    var confirmBeforeAction: Boolean = false  // 操作前是否确认
    var showOperationDetails: Boolean = true  // 是否显示操作详情
    var maxOperationSteps: Int = 10  // 最大操作步数
    var operationTimeout: Int = 5000  // 操作超时时间（毫秒）

    init {
        Log.d(TAG, "PhoneCallViewModel 初始化")
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
        volume: Int = 50,
        deviceController: IDeviceController? = null
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
        this.deviceController = deviceController

        // 初始化 VLMClient
        if (apiKey.isNotBlank()) {
            vlmClient = VLMClient(
                apiKey = apiKey,
                baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
                model = model
            )
        }

        // 初始化 MCPPhoneCallClient
        if (apiKey.isNotBlank()) {
            phoneCallClient = MCPPhoneCallClient(
                apiKey = apiKey,
                baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
                model = model,
                systemPrompt = systemPrompt
            )
        }

        // 初始化 PhoneCallAgent
        if (vlmClient != null && deviceController != null) {
            phoneCallAgent = PhoneCallAgent(
                vlmClient = vlmClient!!,
                controller = deviceController!!,
                onActionSpeak = { text ->
                    // 在执行动作时播放 TTS
                    playResponse(text)
                },
                onHideFloatingWindow = { hideFloatingWindowForOperation() },
                onShowFloatingWindow = { showFloatingWindowForOperation() }
            )
        }

        // 初始化屏幕捕获管理器
        if (deviceController != null) {
            screenCaptureManager = ScreenCaptureManager(deviceController!!)
        }

        Log.d(TAG, "配置已更新")
    }

    /**
     * 检查手机通话所需的权限
     */
    fun checkPhoneCallPermissions(): AgentPermissionCheck.CheckResult {
        return permissionChecker.checkPermissions(
            mediaProjectionResultCode = mediaProjectionResultCode,
            mediaProjectionData = mediaProjectionData
        )
    }

    /**
     * 检查所有缺失的手机通话权限
     */
    fun checkAllPhoneCallPermissions(): AgentPermissionCheck.CheckResult {
        return permissionChecker.checkAllPermissions(
            mediaProjectionResultCode = mediaProjectionResultCode,
            mediaProjectionData = mediaProjectionData
        )
    }

    /**
     * 设置 MediaProjection 权限结果
     */
    fun setMediaProjectionResult(resultCode: Int, data: Intent) {
        mediaProjectionResultCode = resultCode
        mediaProjectionData = data
        Log.d(TAG, "MediaProjection 权限已设置: resultCode=$resultCode, data=${data != null}")

        // 同步更新 PhoneCallOverlayService 的 MediaProjection 状态
        PhoneCallOverlayService.getInstance()?.setMediaProjectionAvailable(true)

        // 立即检查权限状态
        val checkResult = checkAllPhoneCallPermissions()
        Log.d(TAG, "设置 MediaProjection 后的权限检查结果: $checkResult")
    }

    /**
     * 获取权限的详细描述
     */
    fun getPermissionDescription(result: AgentPermissionCheck.CheckResult): String {
        return permissionChecker.getPermissionDescription(result)
    }

    /**
     * 获取权限的标题
     */
    fun getPermissionTitle(result: AgentPermissionCheck.CheckResult): String {
        return permissionChecker.getPermissionTitle(result)
    }

    /**
     * 打开权限设置
     */
    fun openPermissionSettings(result: AgentPermissionCheck.CheckResult) {
        permissionChecker.openPermissionSettings(result)
    }

    /**
     * 开始通话
     */
    fun startCall() {
        Log.d(TAG, "开始通话")

        // 防止重复调用
        if (_callState.value !is PhoneCallState.Idle) {
            Log.w(TAG, "通话已在进行中，忽略此次请求")
            return
        }

        // 检查权限
        val permissionResult = checkPhoneCallPermissions()
        if (permissionResult !is AgentPermissionCheck.CheckResult.Granted) {
            val title = getPermissionTitle(permissionResult)
            val description = getPermissionDescription(permissionResult)
            _callState.value = PhoneCallState.Error("$title\n\n$description")
            return
        }

        if (apiKey.isBlank()) {
            _callState.value = PhoneCallState.Error("请先设置 API Key")
            return
        }

        if (deviceController == null || !deviceController!!.isAvailable()) {
            _callState.value = PhoneCallState.Error("设备控制器不可用，请检查权限")
            return
        }

        // 重置处理标志
        isProcessing = false

        // 清空之前的对话历史
        _conversationHistory.clear()
        _currentRecognizedText.value = ""
        _currentPlayingMessage.value = ""
        _currentOperation.value = ""

        // 启动持续屏幕捕获
        screenCaptureManager?.start()
        Log.d(TAG, "屏幕捕获已启动")

        // 初始化音频管理器
        audioManager = if (enableAEC) {
            AecVoiceCallAudioManager(
                context = context,
                apiKey = apiKey,
                ttsVoice = ttsVoice,
                ttsSpeed = ttsSpeed,
                ttsInterruptEnabled = ttsInterruptEnabled,
                volume = volume
            )
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

        // 开始录音
        startRecording()
    }

    /**
     * 结束通话
     */
    fun stopCall() {
        Log.d(TAG, "结束通话")

        // 取消所有协程
        currentJob?.cancel()
        currentJob = null

        // 重置处理标志
        isProcessing = false

        // 停止屏幕捕获
        screenCaptureManager?.stop()
        Log.d(TAG, "屏幕捕获已停止")

        // 停止音频
        audioManager?.stopAll()
        audioManager = null

        // 清空状态
        _callState.value = PhoneCallState.Idle
        _currentRecognizedText.value = ""
        _currentPlayingMessage.value = ""
        _currentOperation.value = ""
        _isAiOperating.value = false

        // 禁用浮动窗口并停止前台服务
        _floatingWindowState.value = FloatingWindowState.Disabled
        PhoneCallOverlayService.stop(context)
        Log.d(TAG, "前台服务已停止")

        Log.d(TAG, "通话已结束")
    }

    /**
     * 开始录音
     */
    private fun startRecording() {
        Log.d(TAG, "开始录音")

        // 先设置状态为 Recording
        _callState.value = PhoneCallState.Recording

        // 清空当前识别文本
        _currentRecognizedText.value = ""

        // 调用音频管理器开始录音
        audioManager?.startRecording(
            onPartialResult = { text ->
                // 实时显示识别结果
                _currentRecognizedText.value = text
            },
            onFinalResult = { text ->
                Log.d(TAG, "语音识别完成: $text")
                // 识别完成，发送到处理
                if (text.isNotBlank()) {
                    onSpeechRecognized(text)
                } else {
                    // 如果没有识别到内容，继续录音
                    startRecording()
                }
            },
            onError = { error ->
                Log.e(TAG, "录音错误: $error")
                _callState.value = PhoneCallState.Error(error)
            },
            onSilenceTimeout = {
                // 静音超时，自动挂机
                Log.d(TAG, "静音超时，自动挂机")
                stopCall()
            }
        )
    }

    /**
     * 语音识别回调
     */
    private fun onSpeechRecognized(text: String) {
        Log.d(TAG, "语音识别回调: $text")

        // 检查是否正在处理，防止重复请求
        if (isProcessing) {
            Log.w(TAG, "正在处理上一条消息，忽略此次识别结果")
            return
        }

        // 添加用户消息到历史
        val userMessage = PhoneCallMessage(
            id = generateMessageId(),
            content = text,
            isUser = true
        )
        _conversationHistory.add(userMessage)

        // 清空当前识别文本
        _currentRecognizedText.value = ""

        // 标记为正在处理
        isProcessing = true

        // 发送到处理（使用 viewModelScope）
        currentJob = viewModelScope.launch {
            try {
                processUserMessage(text)
            } finally {
                // 处理完成后重置标志
                isProcessing = false
            }
        }
    }

    /**
     * 处理用户消息
     */
    private suspend fun processUserMessage(message: String) {
        Log.d(TAG, "处理用户消息: $message")

        _callState.value = PhoneCallState.Processing

        // 通知屏幕捕获管理器：用户正在说话，切换到高频模式
        screenCaptureManager?.onSpeechActivityDetected()

        try {
             // 从屏幕捕获管理器获取最近的截图历史
            val recentFrames = screenCaptureManager?.getRecentFrames(3) ?: emptyList()
            val screenBitmaps = recentFrames.map { it.bitmap }
            Log.d(TAG, "获取到 ${screenBitmaps.size} 帧屏幕截图")

            // 更新当前屏幕截图（使用最新的一帧）
            _currentScreen.value = screenBitmaps.lastOrNull()

            // 优先使用 PhoneCallAgent（支持意图解析 + 操作决策 + 手机操作）
            if (phoneCallAgent != null) {
                val result = phoneCallAgent!!.processUserInput(
                    userText = message,
                    screenHistory = screenBitmaps,
                    conversationHistory = _conversationHistory.toList()
                )

                if (result.error != null) {
                    _callState.value = PhoneCallState.Error(result.error)
                    delay(2000)
                    startRecording()
                    return
                }

                // 如果 Agent 执行了操作，更新操作状态
                if (result.shouldOperate && result.actions.isNotEmpty()) {
                    setAiOperating(true, result.actions.lastOrNull()?.description ?: "执行操作中")
                    // 操作完成后恢复状态
                    setAiOperating(false)
                }

                // 添加助手消息到历史
                val assistantMessage = PhoneCallMessage(
                    id = generateMessageId(),
                    content = result.response,
                    isUser = false,
                    actions = result.actions
                )
                _conversationHistory.add(assistantMessage)

                // 播放响应
                playResponse(result.response)
            } else if (phoneCallClient != null) {
                // 降级：使用 MCPPhoneCallClient（仅 VLM 问答，不支持操作手机）
                Log.w(TAG, "PhoneCallAgent 未初始化，降级使用 MCPPhoneCallClient（仅支持问答）")
                val screenHistory = screenCaptureManager?.getRecentFrames(3) ?: emptyList()
                val result = phoneCallClient!!.sendPhoneCallMessage(
                    message = message,
                    conversationHistory = _conversationHistory.toList(),
                    screenHistory = screenHistory
                )

                if (result.isSuccess) {
                    val responseText = result.getOrNull() ?: "好的，我明白了。"

                    // 添加助手消息到历史
                    val assistantMessage = PhoneCallMessage(
                        id = generateMessageId(),
                        content = responseText,
                        isUser = false
                    )
                    _conversationHistory.add(assistantMessage)

                    // 播放响应
                    playResponse(responseText)
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "请求失败"
                    Log.e(TAG, "MCPPhoneCallClient 请求失败: $errorMsg")
                    _callState.value = PhoneCallState.Error(errorMsg)
                    delay(2000)
                    startRecording()
                    return
                }
            } else {
                _callState.value = PhoneCallState.Error("PhoneCallAgent 和 MCPPhoneCallClient 均未初始化")
                delay(2000)
                startRecording()
                return
            }

            // 限制历史大小
            while (_conversationHistory.size > MAX_HISTORY_SIZE) {
                _conversationHistory.removeAt(0)
            }

            // 恢复低频截图模式
            screenCaptureManager?.onSpeechActivityEnd()
        } catch (e: Exception) {
            Log.e(TAG, "处理用户消息失败", e)
            _callState.value = PhoneCallState.Error(e.message ?: "处理失败")
            delay(2000)
            startRecording()
        }
    }

    /**
     * 播放响应
     */
    private fun playResponse(text: String) {
        Log.d(TAG, "播放响应: $text")

        _callState.value = PhoneCallState.Playing
        _currentPlayingMessage.value = text

        audioManager?.playText(
            text = text,
            onFinished = {
                Log.d(TAG, "播放完成")

                // 清空播放消息
                _currentPlayingMessage.value = ""

                // 检查是否还在播放
                if (audioManager?.isCurrentlyPlaying() == false) {
                    _callState.value = PhoneCallState.Recording
                    startRecording()
                } else {
                    // 正常完成播放，等待一小段时间后开始录音
                    viewModelScope.launch {
                        delay(100)
                        _callState.value = PhoneCallState.Recording
                        startRecording()
                    }
                }
            },
            onError = { error ->
                Log.e(TAG, "播放错误: $error")
                _callState.value = PhoneCallState.Error(error)
                _currentPlayingMessage.value = ""

                // 等待 2 秒后重新开始录音
                viewModelScope.launch {
                    delay(2000)
                    startRecording()
                }
            }
        )
    }

    /**
     * 处理文本输入
     */
    fun handleTextInput(text: String) {
        Log.d(TAG, "处理文本输入: $text")

        // 检查是否正在处理，防止重复请求
        if (isProcessing) {
            Log.w(TAG, "正在处理上一条消息，忽略此次输入")
            return
        }

        // 添加用户消息到历史
        val userMessage = PhoneCallMessage(
            id = generateMessageId(),
            content = text,
            isUser = true
        )
        _conversationHistory.add(userMessage)

        // 标记为正在处理
        isProcessing = true

        // 发送到处理（使用 viewModelScope）
        currentJob = viewModelScope.launch {
            try {
                processUserMessage(text)
            } finally {
                // 处理完成后重置标志
                isProcessing = false
            }
        }
    }

    /**
     * 启用浮动窗口模式
     */
    fun enableFloatingMode() {
        Log.d(TAG, "启用浮动窗口模式")
        _floatingWindowState.value = FloatingWindowState.Normal
        // 启动前台服务
        PhoneCallOverlayService.Companion.start(context)
    }

    /**
     * 禁用浮动窗口模式
     */
    fun disableFloatingMode() {
        Log.d(TAG, "禁用浮动窗口模式")
        _floatingWindowState.value = FloatingWindowState.Disabled
        // 停止前台服务
        PhoneCallOverlayService.Companion.stop(context)
    }

    /**
     * 切换浮动窗口模式
     */
    fun toggleFloatingMode() {
        when (_floatingWindowState.value) {
            FloatingWindowState.Disabled -> enableFloatingMode()
            FloatingWindowState.Normal -> disableFloatingMode()
            else -> {
                _floatingWindowState.value = FloatingWindowState.Normal
            }
        }
    }

    /**
     * 最小化浮动窗口
     */
    fun minimizeFloatingWindow() {
        Log.d(TAG, "最小化浮动窗口")
        _floatingWindowState.value = FloatingWindowState.Minimized
    }

    /**
     * 最大化浮动窗口
     */
    fun maximizeFloatingWindow() {
        Log.d(TAG, "最大化浮动窗口")
        _floatingWindowState.value = FloatingWindowState.Maximized
    }

    /**
     * 隐藏浮动窗口
     */
    fun hideFloatingWindow() {
        Log.d(TAG, "隐藏浮动窗口")
        _floatingWindowState.value = FloatingWindowState.Hidden
    }

    /**
     * 显示浮动窗口
     */
    fun showFloatingWindow() {
        Log.d(TAG, "显示浮动窗口")
        _floatingWindowState.value = FloatingWindowState.Normal
    }

    /**
     * 设置浮动窗口视图引用
     */
    fun setFloatingWindowView(view: com.alian.assistant.presentation.ui.screens.phonecall.PhoneCallFloatingWindowView?) {
        floatingWindowView = view
        Log.d(TAG, "浮动窗口视图引用已设置: ${view != null}")
    }

    /**
     * 隐藏悬浮窗（轻量级，用于 PhoneCallAgent 调用）
     */
    suspend fun hideFloatingWindowForOperation() {
        Log.d(TAG, "隐藏悬浮窗（操作前）")
        floatingWindowView?.setVisible(false)
    }

    /**
     * 显示悬浮窗（轻量级，用于 PhoneCallAgent 调用）
     */
    suspend fun showFloatingWindowForOperation() {
        Log.d(TAG, "显示悬浮窗（操作后）")
        floatingWindowView?.setVisible(true)
    }

    /**
     * 更新浮动窗口位置
     */
    fun updateFloatingWindowPosition(x: Int, y: Int) {
        _floatingWindowPosition.value = Pair(x, y)
    }

    /**
     * 更新浮动窗口透明度
     */
    fun updateFloatingWindowOpacity(opacity: Float) {
        val validOpacity = opacity.coerceIn(0.3f, 1.0f)
        _floatingWindowOpacity.value = validOpacity
    }

    /**
     * 设置 AI 操作状态
     */
    fun setAiOperating(isOperating: Boolean, description: String = "") {
        _isAiOperating.value = isOperating
        _currentOperation.value = description

        if (isOperating) {
            _callState.value = PhoneCallState.Operating(description)
            // AI 操作时降低浮动窗透明度
            _floatingWindowOpacity.value = 0.7f
        } else {
            // 恢复正常透明度
            _floatingWindowOpacity.value = 0.95f
        }
    }

    /**
     * 生成消息 ID
     */
    private fun generateMessageId(): String {
        return "phonecall_msg_${System.currentTimeMillis()}_${_conversationHistory.size}"
    }

    /**
     * 获取状态描述
     */
    fun getStateDescription(): String {
        return when (_callState.value) {
            is PhoneCallState.Idle -> "空闲"
            is PhoneCallState.Recording -> "正在录音..."
            is PhoneCallState.Processing -> "正在思考..."
            is PhoneCallState.Playing -> "正在播放..."
            is PhoneCallState.Operating -> "正在操作..."
            is PhoneCallState.Error -> "错误"
        }
    }

    /**
     * 获取状态颜色
     */
    fun getStateColor(): Color {
        return when (_callState.value) {
            is PhoneCallState.Idle -> Color.Gray
            is PhoneCallState.Recording -> Color(0xFF8B5CF6)  // 紫色
            is PhoneCallState.Processing -> Color(0xFFFFA726)  // 橙色
            is PhoneCallState.Playing -> Color(0xFF2196F3)  // 蓝色
            is PhoneCallState.Operating -> Color(0xFF10B981)  // 绿色
            is PhoneCallState.Error -> Color(0xFFF44336)  // 红色
        }
    }

       /**
     * 获取屏幕捕获管理器（供 OverlayService 设置截图保护回调）
     */
    fun getScreenCaptureManager(): ScreenCaptureManager? = screenCaptureManager

    /**
     * 清理资源
     */
    fun cleanup() {
        Log.d(TAG, "清理资源")
        stopCall()
        screenCaptureManager?.stop()
        screenCaptureManager = null
    }
}