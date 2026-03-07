package com.alian.assistant.infrastructure.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import com.alibaba.fastjson.JSON
import com.alian.assistant.common.utils.SpeechTextGuard
import com.alian.assistant.common.utils.VoiceTerminalMetrics
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.infrastructure.ai.asr.SherpaOfflineSpeechRecognizer
import com.alian.assistant.infrastructure.ai.tts.CosyVoiceTTSClient
import com.alian.assistant.infrastructure.ai.asr.QwenSpeechClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音通话音频管理器
 * 管理录音和播放功能，复用现有的 QwenSpeechClient 和 CosyVoiceTTSClient
 */
class VoiceCallAudioManager(
    private val context: Context,
    private val apiKey: String,
    private val ttsVoice: String,
    private val ttsSpeed: Float = 1.0f,  // TTS语速
    private val ttsInterruptEnabled: Boolean = false,  // 实时语音打断开关
    private val volume: Int = 50,  // 音量，取值范围 [0, 100]，默认为 50
    private val metricsScene: String = "voice_call"
) : IAudioManager {
    companion object {
        private const val TAG = "VoiceCallAudioManager"
        private const val SILENCE_DURATION_MS = Long.MAX_VALUE  // 静音持续时间（毫秒），禁用静音超时检测
    }

    // 音频管理器
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // 语音识别客户端
    private var qwenSpeechClient: QwenSpeechClient? = null
    private var offlineAsrRecognizer: SherpaOfflineSpeechRecognizer? = null

    // 语音合成客户端
    private var cosyVoiceTTSClient: CosyVoiceTTSClient? = null

    // 当前录音任务
    private var recordingJob: Job? = null

    // 是否正在录音
    private var isRecording = false

    // 是否正在停止录音
    private var isStopping = false

    // 是否正在播放
    private var isPlaying = false

    // 静音检测计时器
    private var silenceTimer: Job? = null

    // 最后一次检测到音频的时间
    private var lastAudioTime: Long = 0

    // 统一的协程作用域，用于管理所有协程
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(managerJob + Dispatchers.IO)
    private val settingsManager = SettingsManager(context)
    private val offlineAsrEnabled: Boolean
        get() = settingsManager.settings.value.offlineAsrEnabled
    private val offlineAsrAutoFallbackToCloud: Boolean
        get() = settingsManager.settings.value.offlineAsrAutoFallbackToCloud

    private enum class ActiveAsrEngine {
        NONE,
        OFFLINE,
        CLOUD
    }

    private var activeAsrEngine: ActiveAsrEngine = ActiveAsrEngine.NONE

    init {
        Log.d(
            TAG,
            "VoiceCallAudioManager 初始化, offlineAsrEnabled=$offlineAsrEnabled, offlineAsrAutoFallbackToCloud=$offlineAsrAutoFallbackToCloud"
        )
        initializeClients()
    }

    /**
     * 初始化客户端
     */
    private fun initializeClients() {
        // 初始化语音识别客户端
        qwenSpeechClient = QwenSpeechClient(
            apiKey = apiKey,
            url = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            model = "fun-asr-realtime-2025-09-15",
            sampleRate = 16000,
            audioFormat = "pcm",
            vadEnabled = true
        )

        // 初始化语音合成客户端
        cosyVoiceTTSClient = CosyVoiceTTSClient(
            apiKey = apiKey,
            wsUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            model = "cosyvoice-v3-flash",
            voice = ttsVoice,
            rate = ttsSpeed,
            volume = volume
        )

        Log.d(TAG, "客户端初始化完成")
    }

    /**
     * 开始录音
     * @param onPartialResult 部分识别结果回调
     * @param onFinalResult 最终识别结果回调
     * @param onError 错误回调
     * @param onSilenceTimeout 静音超时回调（自动挂机）
     */
    override fun startRecording(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
        onSilenceTimeout: () -> Unit
    ) {
        val useOfflineAsr = offlineAsrEnabled
        Log.d(TAG, "开始录音, useOfflineAsr=$useOfflineAsr")

        if (isRecording) {
            Log.w(TAG, "已经在录音中，忽略此次请求")
            return
        }

        // 检查录音权限
        if (!hasRecordPermission()) {
            Log.e(TAG, "没有录音权限")
            onError("没有录音权限，请检查设置")
            return
        }

        if (useOfflineAsr) {
            startOfflineRecording(onPartialResult, onFinalResult, onError, onSilenceTimeout)
            return
        }

        startOnlineRecording(onPartialResult, onFinalResult, onError, onSilenceTimeout)
    }

    private fun startOfflineRecording(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
        onSilenceTimeout: () -> Unit
    ) {
        recordingJob = managerScope.launch {
            try {
                ensureResourcesReleased()
                delay(200)

                val recognizer =
                    offlineAsrRecognizer ?: SherpaOfflineSpeechRecognizer(
                        context = context,
                        enableEndpoint = true
                    ).also {
                        offlineAsrRecognizer = it
                    }

                val initialized = recognizer.initializeIfNeeded()
                if (!initialized) {
                    Log.e(TAG, "离线 ASR 初始化失败")
                    isRecording = false
                    activeAsrEngine = ActiveAsrEngine.NONE
                    if (offlineAsrAutoFallbackToCloud && apiKey.isNotBlank()) {
                        Log.w(TAG, "离线 ASR 初始化失败，回退在线 ASR")
                        startOnlineRecording(
                            onPartialResult = onPartialResult,
                            onFinalResult = onFinalResult,
                            onError = onError,
                            onSilenceTimeout = onSilenceTimeout
                        )
                    } else {
                        onError("离线语音识别初始化失败")
                    }
                    return@launch
                }

                isRecording = true
                isStopping = false
                lastAudioTime = System.currentTimeMillis()
                requestAudioFocus()
                activeAsrEngine = ActiveAsrEngine.OFFLINE

                val started = recognizer.startListening(
                    object : SherpaOfflineSpeechRecognizer.Listener {
                        override fun onPartial(text: String) {
                            if (isPlaying) {
                                Log.d(TAG, "正在播放 TTS，忽略离线部分结果")
                                return
                            }
                            lastAudioTime = System.currentTimeMillis()
                            onPartialResult(text)
                        }

                        override fun onFinal(text: String) {
                            if (isPlaying) {
                                Log.d(TAG, "正在播放 TTS，忽略离线最终结果")
                                return
                            }
                            isStopping = true
                            stopRecording()
                            val guardedText = SpeechTextGuard.sanitizeFinalText(text)
                            if (guardedText.isBlank()) {
                                Log.d(TAG, "离线最终结果被判定为噪声，忽略")
                                VoiceTerminalMetrics.recordAsrFinalFiltered(
                                    scene = metricsScene,
                                    source = "offline",
                                    reason = "noise_guard"
                                )
                            } else {
                                VoiceTerminalMetrics.recordAsrFinalAccepted(
                                    scene = metricsScene,
                                    source = "offline",
                                    textLength = guardedText.length
                                )
                            }
                            onFinalResult(guardedText)
                        }

                        override fun onError(message: String) {
                            Log.e(TAG, "离线 ASR 错误: $message")
                            isStopping = true
                            stopRecording()
                            if (offlineAsrAutoFallbackToCloud && apiKey.isNotBlank()) {
                                startOnlineRecording(
                                    onPartialResult = onPartialResult,
                                    onFinalResult = onFinalResult,
                                    onError = onError,
                                    onSilenceTimeout = onSilenceTimeout
                                )
                            } else {
                                onError(message)
                            }
                        }
                    }
                )

                if (!started) {
                    isRecording = false
                    activeAsrEngine = ActiveAsrEngine.NONE
                    if (offlineAsrAutoFallbackToCloud && apiKey.isNotBlank()) {
                        Log.w(TAG, "离线 ASR 启动失败，回退在线 ASR")
                        startOnlineRecording(
                            onPartialResult = onPartialResult,
                            onFinalResult = onFinalResult,
                            onError = onError,
                            onSilenceTimeout = onSilenceTimeout
                        )
                    } else {
                        onError("离线语音识别启动失败")
                    }
                    return@launch
                }

                startSilenceDetection(onSilenceTimeout)
            } catch (e: Exception) {
                Log.e(TAG, "离线录音启动异常", e)
                isRecording = false
                activeAsrEngine = ActiveAsrEngine.NONE
                onError(e.message ?: "离线录音启动异常")
            }
        }
    }

    private fun startOnlineRecording(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
        onSilenceTimeout: () -> Unit
    ) {
        // 在 IO 线程中启动录音，确保上一次的 AudioRecord 已完全释放
        recordingJob = managerScope.launch {
            try {
                // 确保资源已完全释放
                ensureResourcesReleased()

                // 等待一小段时间，确保上一次的 AudioRecord 已完全释放
                // 这可以避免在快速停止和重新开始录音时出现麦克风错误
                delay(200)

                // 重新初始化语音识别客户端（因为每次停止录音后都会释放资源）
                initializeClients()

                isRecording = true
                isStopping = false
                lastAudioTime = System.currentTimeMillis()
                activeAsrEngine = ActiveAsrEngine.NONE

                // 请求音频焦点
                requestAudioFocus()

                // 设置回调
                qwenSpeechClient?.setCallbacks(
                    onPartialResult = { text ->
                        Log.d(TAG, "部分识别结果: $text")

                        // 如果正在播放 TTS，忽略语音识别结果（避免录制到 TTS 播放的声音）
                        if (isPlaying) {
                            Log.d(TAG, "正在播放 TTS，忽略语音识别结果")
                            return@setCallbacks
                        }

                        // 检测到音频活动，重置静音计时器
                        lastAudioTime = System.currentTimeMillis()

                        // 尝试从 JSON 中提取纯文本
                        val contentText = try {
                            val json = JSON.parseObject(text)
                            json.getString("text") ?: text
                        } catch (e: Exception) {
                            Log.w(TAG, "解析部分识别结果 JSON 失败，使用原始结果", e)
                            text
                        }

                        onPartialResult(contentText)
                    },
                    onFinalResult = { text ->
                        Log.d(TAG, "最终识别结果: $text")

                        // 如果正在播放 TTS，忽略语音识别结果
                        if (isPlaying) {
                            Log.d(TAG, "正在播放 TTS，忽略语音识别结果")
                            return@setCallbacks
                        }

                        // 标记为正在停止，防止重复调用
                        isStopping = true

                        // 停止录音
                        stopRecording()

                        val guardedText = SpeechTextGuard.sanitizeFinalText(text)
                        if (guardedText.isBlank()) {
                            Log.d(TAG, "在线最终结果被判定为噪声，忽略")
                            VoiceTerminalMetrics.recordAsrFinalFiltered(
                                scene = metricsScene,
                                source = "cloud",
                                reason = "noise_guard"
                            )
                        } else {
                            VoiceTerminalMetrics.recordAsrFinalAccepted(
                                scene = metricsScene,
                                source = "cloud",
                                textLength = guardedText.length
                            )
                        }
                        onFinalResult(guardedText)
                    },
                    onError = { error ->
                        Log.e(TAG, "录音错误: ${error.message}")

                        // 标记为正在停止
                        isStopping = true

                        stopRecording()

                        onError(error.message ?: "录音错误")
                    }
                )

                // 切换到主线程启动语音识别
                withContext(Dispatchers.Main) {
                    val result = qwenSpeechClient?.recognizeSpeechRealTime(context)

                    if (result?.isFailure == true) {
                        val error = result.exceptionOrNull()
                        Log.e(TAG, "录音启动失败: ${error?.message}")
                        isRecording = false
                        activeAsrEngine = ActiveAsrEngine.NONE
                        onError(error?.message ?: "录音启动失败")
                    }
                }

                if (!isRecording) {
                    return@launch
                }

                activeAsrEngine = ActiveAsrEngine.CLOUD

                // 启动静音检测
                startSilenceDetection(onSilenceTimeout)

            } catch (e: Exception) {
                Log.e(TAG, "录音启动异常", e)
                isRecording = false
                activeAsrEngine = ActiveAsrEngine.NONE
                onError(e.message ?: "录音启动异常")
            }
        }
    }

    /**
     * 启动静音检测
     */
    private fun startSilenceDetection(onSilenceTimeout: () -> Unit) {
        Log.d(TAG, "启动静音检测")

        silenceTimer = managerScope.launch {
            while (isRecording && !isStopping) {
                delay(100)  // 每 100ms 检查一次

                // 如果正在播放 TTS，跳过静音检测
                if (isPlaying) {
                    continue
                }

                val currentTime = System.currentTimeMillis()
                val silenceDuration = currentTime - lastAudioTime

                // 如果静音时间超过阈值，自动停止录音并挂机
                if (silenceDuration > SILENCE_DURATION_MS) {
                    Log.d(TAG, "检测到静音超过 ${SILENCE_DURATION_MS}ms，自动停止录音并挂机")
                    isStopping = true
                    stopRecording()
                    // 调用静音超时回调，通知 ViewModel 自动挂机
                    onSilenceTimeout()
                    break
                }
            }
        }
    }

    /**
     * 停止录音
     */
    override fun stopRecording() {
        Log.d(TAG, "停止录音, activeAsrEngine=$activeAsrEngine")

        if (!isRecording && activeAsrEngine == ActiveAsrEngine.NONE) {
            return
        }

        // 标记为不在录音
        isRecording = false

        // 停止静音检测
        silenceTimer?.cancel()
        silenceTimer = null

        // 取消录音任务
        recordingJob?.cancel()
        recordingJob = null

        val engineToStop = activeAsrEngine
        activeAsrEngine = ActiveAsrEngine.NONE
        managerScope.launch {
            try {
                when (engineToStop) {
                    ActiveAsrEngine.OFFLINE -> {
                        if (isStopping) {
                            offlineAsrRecognizer?.cancelListening()
                        } else {
                            offlineAsrRecognizer?.stopListening()
                        }
                        Log.d(TAG, "离线语音识别已停止")
                    }

                    ActiveAsrEngine.CLOUD -> {
                        qwenSpeechClient?.stopRecognition()
                        Log.d(TAG, "在线语音识别已停止")
                    }

                    ActiveAsrEngine.NONE -> Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "停止语音识别失败", e)
            }
        }

        // 等待一小段时间，确保 AudioRecord 已被释放
        // 这样可以避免在重新初始化时出现麦克风错误
        managerScope.launch {
            delay(200)
            Log.d(TAG, "录音已停止，等待重新初始化")
        }

        Log.d(TAG, "录音已停止")
    }

    /**
     * 播放文本
     * @param text 要播放的文本
     * @param onFinished 播放完成回调
     * @param onError 错误回调
     */
    override fun playText(
        text: String,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "准备播放文本: text长度=${text.length}, text前50字符=${text.take(50)}, 完整text=$text")

        if (isPlaying) {
            Log.w(TAG, "正在播放中，忽略新的播放请求")
            onError("正在播放中，请稍后再试")
            return
        }

        isPlaying = true

        // 如果没有启用实时语音打断，则在播放 TTS 时停止录音
        val wasRecording = isRecording
        if (!ttsInterruptEnabled && wasRecording) {
            Log.d(TAG, "未启用实时语音打断，播放 TTS 时停止录音")
            // 清除所有回调，避免录音停止后的识别结果干扰
            qwenSpeechClient?.setCallbacks(
                onPartialResult = null,
                onFinalResult = null,
                onError = null
            )
            stopRecording()
        }

        // 不在这里请求音频焦点，让 CosyVoiceTTSClient 自己管理
        // 避免音频焦点冲突

        // 播放
        managerScope.launch {
            try {
                Log.d(TAG, "开始调用 TTS 合成并播放: text=$text")
                val result = cosyVoiceTTSClient?.synthesizeAndPlay(text, context)

                if (result?.isSuccess == true) {
                    Log.d(TAG, "TTS 播放完成")
                    // 先更新状态，再调用完成回调，避免状态不一致
                    isPlaying = false
                    onFinished()
                } else {
                    val error = result?.exceptionOrNull()
                    Log.e(TAG, "TTS 播放失败: ${error?.message}")
                    isPlaying = false
                    onError(error?.message ?: "播放失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS 播放异常", e)
                isPlaying = false
                onError(e.message ?: "播放异常")
            }
        }
    }

    /**
     * 流式播放文本（非AEC版本，暂不支持流式）
     * @param textFlow 流式文本输入
     * @param onFinished 播放完成回调，参数为完整文本
     * @param onError 错误回调
     */
    override suspend fun playTextStream(
        textFlow: Flow<String>,
        onFinished: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.w(TAG, "非AEC版本暂不支持流式播放，回退到非流式模式")

        // 收集所有文本块
        val fullText = StringBuilder()
        textFlow.collect { chunk ->
            fullText.append(chunk)
        }

        // 使用非流式播放
        playText(
            text = fullText.toString(),
            onFinished = { onFinished(fullText.toString()) },
            onError = onError
        )
    }

    /**
     * 停止播放
     */
    override fun stopPlayback() {
        Log.d(TAG, "停止播放")

        if (!isPlaying) {
            return
        }

        isPlaying = false

        // 取消 TTS 播放
        cosyVoiceTTSClient?.cancelPlayback()

        Log.d(TAG, "播放已停止")
    }

    /**
     * 停止所有音频操作
     */
    override fun stopAll() {
        Log.d(TAG, "停止所有音频操作")

        // 重置停止标志
        isStopping = false

        // 先停止静音检测
        silenceTimer?.cancel()
        silenceTimer = null

        // 取消录音任务
        recordingJob?.cancel()
        recordingJob = null

        // 停止播放
        if (isPlaying) {
            isPlaying = false
            cosyVoiceTTSClient?.cancelPlayback()
        }

        // 释放音频焦点
        abandonAudioFocus()

        // 释放客户端（先保存引用，避免在释放过程中被设置为 null）
        val speechClient = qwenSpeechClient
        val offlineRecognizer = offlineAsrRecognizer
        val ttsClient = cosyVoiceTTSClient

        // 清空引用
        qwenSpeechClient = null
        offlineAsrRecognizer = null
        cosyVoiceTTSClient = null
        activeAsrEngine = ActiveAsrEngine.NONE

        // 在后台线程释放语音识别客户端，避免阻塞主线程
        managerScope.launch {
            try {
                speechClient?.stopRecognition()
                offlineRecognizer?.cancelListening()
                offlineRecognizer?.destroy()
                Log.d(TAG, "语音识别客户端已释放")
            } catch (e: Exception) {
                Log.e(TAG, "释放语音识别客户端失败", e)
            }
        }

        // 在后台线程释放语音合成客户端
        managerScope.launch {
            try {
                ttsClient?.cancelPlayback()
                Log.d(TAG, "语音合成客户端已释放")
            } catch (e: Exception) {
                Log.e(TAG, "释放语音合成客户端失败", e)
            }
        }

        // 重置状态
        isRecording = false
        isPlaying = false

        Log.d(TAG, "所有音频操作已停止")
    }

    /**
     * 请求音频焦点
     */
    private fun requestAudioFocus() {
        val result = audioManager.requestAudioFocus(
            { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d(TAG, "获得音频焦点")
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.d(TAG, "失去音频焦点（可能是来电或系统音量）")
                        // 完全失去音频焦点，停止所有音频操作
                        stopAll()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.d(TAG, "暂时失去音频焦点（可能是来电或通知）")
                        // 暂时失去焦点时，暂停播放但不停止录音
                        if (isPlaying) {
                            Log.d(TAG, "暂停播放")
                            cosyVoiceTTSClient?.cancelPlayback()
                            isPlaying = false
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Log.d(TAG, "暂时失去音频焦点（可降低音量）")
                        // 对于语音通话，不降低音量，保持正常播放
                    }
                }
            },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "请求音频焦点失败")
        }
    }

    /**
     * 释放音频焦点
     */
    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocus { }
        Log.d(TAG, "音频焦点已释放")
    }

    /**
     * 检查是否正在录音
     */
    override fun isCurrentlyRecording(): Boolean {
        return isRecording
    }

    /**
     * 检查是否正在播放
     */
    override fun isCurrentlyPlaying(): Boolean {
        return isPlaying
    }

    /**
     * 检查是否有录音权限
     */
    private fun hasRecordPermission(): Boolean {
        return PackageManager.PERMISSION_GRANTED ==
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * 释放所有资源（在通话结束时调用）
     * 取消所有协程并释放音频资源
     */
    override fun release() {
        Log.d(TAG, "释放所有资源")

        // 停止所有音频操作
        stopAll()

        // 取消所有协程
        managerJob.cancel()

        Log.d(TAG, "所有资源已释放")
    }

    /**
     * 确保资源已完全释放
     * 在重新初始化前调用，避免资源冲突
     */
    private suspend fun ensureResourcesReleased() {
        var retryCount = 0
        val maxRetries = 10

        while (retryCount < maxRetries) {
            val released = checkResourcesReleased()
            if (released) {
                Log.d(TAG, "资源已完全释放")
                return
            }

            Log.w(TAG, "资源未完全释放，等待... ($retryCount/$maxRetries)")
            delay(50)
            retryCount++
        }

        Log.e(TAG, "资源释放超时")
    }

    /**
     * 检查资源是否已释放
     */
    private fun checkResourcesReleased(): Boolean {
        return !isRecording &&
            activeAsrEngine == ActiveAsrEngine.NONE &&
            offlineAsrRecognizer?.isCurrentlyListening() != true
    }
}
