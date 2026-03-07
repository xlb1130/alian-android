package com.alian.assistant.infrastructure.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.util.Log
import com.alibaba.fastjson.JSON
import com.alian.assistant.common.utils.SpeechTextGuard
import com.alian.assistant.common.utils.VoiceTerminalMetrics
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.infrastructure.ai.asr.SherpaOfflineSpeechRecognizer
import com.alian.assistant.infrastructure.ai.tts.CosyVoiceTTSClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 支持 AEC（回声消除）的语音通话音频管理器
 * 
 * 功能：
 * 1. 使用 AecAudioProcessor 进行回声消除
 * 2. 使用 AecQwenSpeechClient 进行语音识别
 * 3. 实现真正的语音打断功能
 */
class AecVoiceCallAudioManager(
    private val context: Context,
    private val apiKey: String,
    private val ttsVoice: String,
    private val ttsSpeed: Float = 1.0f,  // TTS语速
    private val ttsInterruptEnabled: Boolean = false,
    private val volume: Int = 50,  // 音量，取值范围 [0, 100]，默认为 50
    private val metricsScene: String = "voice_call",
    /**
     * 是否启用长生命周期 AEC 模式（仅 MobileAgent / InterruptOrchestrator 使用）
     * 默认关闭，以保证 VoiceCall / VideoCall 行为与当前版本完全一致
     */
    private val usePersistentAec: Boolean = false
) : IAudioManager {
    companion object {
        private const val TAG = "AecVoiceCallAudioManager"
        private const val SILENCE_DURATION_MS = Long.MAX_VALUE  // 静音持续时间
    }

    // 音频管理器
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // AEC 音频处理器
    private var aecAudioProcessor: AecAudioProcessor? = null

    // 语音识别客户端（支持 AEC）
    private var aecQwenSpeechClient: AecQwenSpeechClient? = null

    // 离线语音识别客户端
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

    // 是否需要在音频焦点恢复时自动重启录音
    private var shouldAutoResumeRecording = false

    // 静音检测计时器
    private var silenceTimer: Job? = null

    // 最后一次检测到音频的时间
    private var lastAudioTime: Long = 0

    // 播放中断回调（语音打断时调用）
    private var onPlaybackInterrupted: (() -> Unit)? = null

    // Persistent AEC 模式：标记底层 AEC 是否已经启动（AudioRecord 是否已经在录音）
    private var aecStarted: Boolean = false

    // Persistent AEC 模式：标记当前是否有识别 Session 在进行（是否向 ASR 喂音频）
    private var recognitionActive: Boolean = false

    private enum class ActiveAsrEngine {
        NONE,
        OFFLINE,
        CLOUD
    }

    @Volatile
    private var activeAsrEngine: ActiveAsrEngine = ActiveAsrEngine.NONE

    // 统一的协程作用域，用于管理所有协程
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(managerJob + Dispatchers.IO)
    private val settingsManager = SettingsManager(context)
    private val offlineAsrEnabled: Boolean
        get() = settingsManager.settings.value.offlineAsrEnabled
    private val offlineAsrAutoFallbackToCloud: Boolean
        get() = settingsManager.settings.value.offlineAsrAutoFallbackToCloud

    init {
        Log.d(
            TAG,
            "AecVoiceCallAudioManager 初始化, offlineAsrEnabled=$offlineAsrEnabled, offlineAsrAutoFallbackToCloud=$offlineAsrAutoFallbackToCloud"
        )
        initializeClients()
    }

    /**
     * 在 Persistent AEC 模式下，确保底层 AEC（AudioRecord）只启动一次
     */
    private fun ensureAecStarted() {
        if (!usePersistentAec) return

        if (!aecStarted) {
            Log.d(TAG, "ensureAecStarted: 首次启动 AEC（AudioRecord）")
            aecAudioProcessor?.startRecording()
            aecStarted = true
        } else {
            Log.d(TAG, "ensureAecStarted: AEC 已启动，跳过重新启动")
        }
    }

    /**
     * 设置播放中断回调
     */
    fun setOnPlaybackInterrupted(callback: () -> Unit) {
        onPlaybackInterrupted = callback
    }

    /**
     * 初始化客户端
     */
    private fun initializeClients() {
        // 初始化语音识别客户端（支持 AEC）
        aecQwenSpeechClient = AecQwenSpeechClient(
            apiKey = apiKey,
            url = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            model = "fun-asr-realtime-2025-09-15",
            sampleRate = 16000,
            audioFormat = "pcm",
            vadEnabled = true
        )

        // 初始化 AEC 音频处理器
        aecAudioProcessor = AecAudioProcessor(
            sampleRate = 16000,
            channelConfig = AudioFormat.CHANNEL_IN_MONO,
            audioFormat = AudioFormat.ENCODING_PCM_16BIT,
            metricsScene = metricsScene
        )

        // 设置 AEC 处理后的音频回调
        aecAudioProcessor?.setOnProcessedAudio { processedAudio ->
            when (activeAsrEngine) {
                ActiveAsrEngine.CLOUD -> aecQwenSpeechClient?.addAudioData(processedAudio)
                ActiveAsrEngine.OFFLINE -> offlineAsrRecognizer?.addAudioData(processedAudio)
                ActiveAsrEngine.NONE -> Unit
            }
        }

        // 设置用户说话检测回调（用于实时语音打断）
        aecAudioProcessor?.setOnUserSpeechDetected {
            // 实时语音打断：如果正在播放 TTS 且启用了语音打断，则停止播放
            if (ttsInterruptEnabled && isPlaying) {
                Log.d(TAG, "检测到用户说话，实时停止 TTS 播放")
                stopPlayback()
                // 通知 ViewModel 播放被中断
                onPlaybackInterrupted?.invoke()
            }
        }

        // 初始化语音合成客户端
        cosyVoiceTTSClient = CosyVoiceTTSClient(
            apiKey = apiKey,
            wsUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            model = "cosyvoice-v3-flash",
            voice = ttsVoice,
            rate = ttsSpeed,
            volume = volume
        )

        // 设置音频数据回调，将播放的音频数据提供给 AecAudioProcessor 作为参考信号
        cosyVoiceTTSClient?.setOnAudioDataCallback { audioData ->
            aecAudioProcessor?.playAudio(audioData)
        }

        Log.d(TAG, "客户端初始化完成")
    }

    /**
     * 开始录音
     */
    override fun startRecording(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
        onSilenceTimeout: () -> Unit
    ) {
        val useOfflineAsr = offlineAsrEnabled
        Log.d(
            TAG,
            "开始录音, isRecording=$isRecording, usePersistentAec=$usePersistentAec, useOfflineAsr=$useOfflineAsr"
        )

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

        startCloudRecording(onPartialResult, onFinalResult, onError, onSilenceTimeout)
    }

    private fun startOfflineRecording(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
        onSilenceTimeout: () -> Unit
    ) {
        recordingJob = managerScope.launch {
            try {
                if (!usePersistentAec) {
                    ensureResourcesReleased()
                    delay(200)
                } else {
                    Log.d(TAG, "离线 ASR + Persistent AEC：跳过资源释放等待")
                }

                if (aecAudioProcessor == null) {
                    initializeClients()
                }

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
                    if (shouldFallbackToCloud()) {
                        Log.w(TAG, "离线 ASR 初始化失败，回退在线 ASR")
                        startCloudRecording(onPartialResult, onFinalResult, onError, onSilenceTimeout)
                    } else {
                        onError("离线语音识别初始化失败")
                    }
                    return@launch
                }

                isRecording = true
                isStopping = false
                shouldAutoResumeRecording = false
                lastAudioTime = System.currentTimeMillis()
                activeAsrEngine = ActiveAsrEngine.OFFLINE
                requestAudioFocus()

                if (usePersistentAec) {
                    Log.d(TAG, "离线 ASR：Persistent AEC 模式启动")
                    ensureAecStarted()
                    aecAudioProcessor?.enableOutput()
                    recognitionActive = true
                } else {
                    Log.d(TAG, "离线 ASR：标准 AEC 模式启动")
                    aecAudioProcessor?.startRecording()
                }

                delay(100)

                val started = recognizer.startListening(
                    object : SherpaOfflineSpeechRecognizer.Listener {
                        override fun onPartial(text: String) {
                            if (isPlaying) {
                                if (ttsInterruptEnabled) {
                                    Log.d(TAG, "离线 ASR 检测到说话，触发实时打断")
                                    stopPlayback()
                                    onPlaybackInterrupted?.invoke()
                                } else {
                                    Log.d(TAG, "正在播放 TTS，忽略离线部分结果")
                                    return
                                }
                            }

                            lastAudioTime = System.currentTimeMillis()
                            onPartialResult(text)
                        }

                        override fun onFinal(text: String) {
                            if (isPlaying && !ttsInterruptEnabled) {
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

                            if (shouldFallbackToCloud() && message != "没有录音权限") {
                                Log.w(TAG, "离线 ASR 运行失败，回退在线 ASR")
                                startCloudRecording(onPartialResult, onFinalResult, onError, onSilenceTimeout)
                            } else {
                                onError(message)
                            }
                        }
                    },
                    externalPcmMode = true
                )

                if (!started) {
                    isRecording = false
                    activeAsrEngine = ActiveAsrEngine.NONE
                    recognitionActive = false
                    if (usePersistentAec) {
                        aecAudioProcessor?.disableOutput()
                    } else {
                        aecAudioProcessor?.stopRecording()
                    }
                    if (shouldFallbackToCloud()) {
                        Log.w(TAG, "离线 ASR 启动失败，回退在线 ASR")
                        startCloudRecording(onPartialResult, onFinalResult, onError, onSilenceTimeout)
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

    private fun startCloudRecording(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
        onSilenceTimeout: () -> Unit
    ) {
        // 初始化 SDK（只在客户端未初始化时才初始化）
        recordingJob = managerScope.launch {
            Log.d(TAG, "startRecording: 开始协程执行")
            // 标准模式下，严格确保资源已释放；Persistent 模式下跳过等待 AudioRecord 完全释放
            if (!usePersistentAec) {
                Log.d(TAG, "startRecording: 调用 ensureResourcesReleased()（标准模式）")
                ensureResourcesReleased()
                Log.d(TAG, "startRecording: ensureResourcesReleased() 完成")
            } else {
                Log.d(TAG, "startRecording: Persistent AEC 模式，跳过 ensureResourcesReleased() 的录音释放等待")
            }

            // 检查客户端是否已初始化
            if (aecQwenSpeechClient == null || aecAudioProcessor == null) {
                Log.d(TAG, "客户端未初始化，开始初始化")
                initializeClients()
                Log.d(TAG, "客户端初始化完成")
            }

            val initResult = aecQwenSpeechClient?.initialize(context)
            if (initResult?.isFailure == true) {
                Log.e(TAG, "语音识别客户端初始化失败")
                onError("语音识别客户端初始化失败")
                return@launch
            }

            isRecording = true
            isStopping = false
            shouldAutoResumeRecording = false
            lastAudioTime = System.currentTimeMillis()
            activeAsrEngine = ActiveAsrEngine.NONE

            // 请求音频焦点
            requestAudioFocus()

            // 设置回调
            aecQwenSpeechClient?.setCallbacks(
                onPartialResult = { text ->
                    Log.d(TAG, "部分识别结果: $text")

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

                    // 实时语音打断已经在 AecAudioProcessor 中实现，这里不需要再检测
                    onPartialResult(contentText)
                },
                onFinalResult = { text ->
                    val thread = Thread.currentThread()
                    Log.d(TAG, "最终识别结果: $text, isStopping=$isStopping, thread=${thread.name}, isMain=${thread.name == "main"}")

                    // 标记为正在停止，防止重复调用
                    isStopping = true

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

                    Log.d(TAG, "调用外部 onFinalResult 回调: text=$text")
                    try {
                        onFinalResult(guardedText)
                        Log.d(TAG, "外部 onFinalResult 回调调用完成")
                    } catch (e: Exception) {
                        Log.e(TAG, "调用外部 onFinalResult 回调失败", e)
                    }

                    // 根据模式停止：标准模式停止录音 + AEC；Persistent 模式仅停止识别会话
                    Log.d(TAG, "准备停止录音/识别, usePersistentAec=$usePersistentAec")
                    CoroutineScope(Dispatchers.IO).launch {
                        if (usePersistentAec) {
                            stopRecognitionSessionInternal()
                        } else {
                            stopRecording()
                        }
                        Log.d(TAG, "录音/识别已停止, usePersistentAec=$usePersistentAec")
                    }
                },
                onError = { error ->
                    Log.e(TAG, "录音错误: ${error.message}")

                    // 标记为正在停止
                    isStopping = true

                    if (usePersistentAec) {
                        stopRecognitionSessionInternal()
                    } else {
                        stopRecording()
                    }

                    onError(error.message ?: "录音错误")
                }
            )

            // 启动/启用 AEC
            if (usePersistentAec) {
                Log.d(TAG, "Persistent AEC 模式：ensureAecStarted + enableOutput")
                ensureAecStarted()
                aecAudioProcessor?.enableOutput()
                recognitionActive = true
            } else {
                // 先启动 AEC 音频处理器
                Log.d(TAG, "启动 AEC 音频处理器（标准模式）")
                aecAudioProcessor?.startRecording()
                Log.d(TAG, "AEC 音频处理器已启动")
            }
            
            // 等待一小段时间，确保 AudioRecord 已经开始录音
            delay(100)

            // 再启动语音识别
            Log.d(TAG, "启动语音识别")
            val startResult = aecQwenSpeechClient?.startRecognition()
            if (startResult?.isFailure == true) {
                Log.e(TAG, "启动语音识别失败: ${startResult.exceptionOrNull()?.message}")
                onError("启动语音识别失败")
                stopRecording()
                return@launch
            }
            Log.d(TAG, "语音识别已启动")
            activeAsrEngine = ActiveAsrEngine.CLOUD

            // 启动静音检测
            startSilenceDetection(onSilenceTimeout)
            Log.d(TAG, "静音检测已启动")
        }
    }

    /**
     * 在 Persistent AEC 模式下，仅停止当前识别 Session，而不停止底层 AEC
     */
    private fun stopRecognitionSessionInternal() {
        Log.d(TAG, "stopRecognitionSessionInternal, recognitionActive=$recognitionActive")

        if (!recognitionActive && !isRecording) {
            return
        }

        // 标记状态
        isRecording = false
        recognitionActive = false
        activeAsrEngine = ActiveAsrEngine.NONE

        // 停止静音检测
        silenceTimer?.cancel()
        silenceTimer = null

        // 关闭输出但保持录音继续进行
        aecAudioProcessor?.disableOutput()

        // 停止语音识别
        aecQwenSpeechClient?.stopRecognition()

        Log.d(TAG, "Persistent AEC 模式：已停止识别 Session，底层 AEC 仍在运行")
    }

    /**
     * 启动静音检测
     */
    private fun startSilenceDetection(onSilenceTimeout: () -> Unit) {
        Log.d(TAG, "启动静音检测")

        silenceTimer = managerScope.launch {
            while (isRecording && !isStopping) {
                delay(100)

                val currentTime = System.currentTimeMillis()
                val silenceDuration = currentTime - lastAudioTime

                // 如果静音时间超过阈值，自动停止录音并挂机
                if (silenceDuration > SILENCE_DURATION_MS) {
                    Log.d(TAG, "检测到静音超过 ${SILENCE_DURATION_MS}ms，自动停止录音并挂机")
                    isStopping = true
                    stopRecording()
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
        Log.d(
            TAG,
            "停止录音, usePersistentAec=$usePersistentAec, activeAsrEngine=$activeAsrEngine, recognitionActive=$recognitionActive"
        )

        if (!isRecording && activeAsrEngine == ActiveAsrEngine.NONE && (!usePersistentAec || !recognitionActive)) {
            return
        }

        if (activeAsrEngine == ActiveAsrEngine.OFFLINE) {
            val shouldCancel = isStopping
            isRecording = false
            recognitionActive = false
            silenceTimer?.cancel()
            silenceTimer = null
            activeAsrEngine = ActiveAsrEngine.NONE

            if (usePersistentAec) {
                aecAudioProcessor?.disableOutput()
            } else {
                aecAudioProcessor?.stopRecording()
            }

            managerScope.launch {
                try {
                    if (shouldCancel) {
                        offlineAsrRecognizer?.cancelListening()
                    } else {
                        offlineAsrRecognizer?.stopListening()
                    }
                    Log.d(TAG, "离线语音识别已停止")
                } catch (e: Exception) {
                    Log.e(TAG, "停止离线语音识别失败", e)
                } finally {
                    isStopping = false
                }
            }
            return
        }

        if (usePersistentAec && activeAsrEngine == ActiveAsrEngine.CLOUD) {
            // Persistent 模式：仅结束当前识别 Session，不停止底层 AEC
            stopRecognitionSessionInternal()
            return
        }

        // 标准模式：保持原有行为，完全停止录音和 AEC
        isRecording = false
        isStopping = true

        // 停止静音检测
        silenceTimer?.cancel()
        silenceTimer = null

        // 停止 AEC 音频处理器（异步，不等待）
        aecAudioProcessor?.stopRecording()

        // 停止语音识别（异步，不等待）
        aecQwenSpeechClient?.stopRecognition()
        activeAsrEngine = ActiveAsrEngine.NONE

        // 等待一小段时间，确保 AudioRecord 已被释放
        // 这样可以避免在重新初始化时出现麦克风错误
        managerScope.launch {
            delay(500)
            Log.d(TAG, "录音已停止，等待重新初始化")
            isStopping = false
        }

        Log.d(TAG, "录音已停止")
    }

    /**
     * 播放文本
     */
    override fun playText(
        text: String,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "准备播放文本: text长度=${text.length}, text前50字符=${text.take(50)}")

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
            stopRecording()
        }

        // 播放
        managerScope.launch {
            try {
                Log.d(TAG, "开始调用 TTS 合成并播放: text=$text")

                // 使用自定义的播放逻辑，以便提供参考信号给 AEC
                playTextWithAEC(text)

                Log.d(TAG, "TTS 播放完成")
                onFinished()
                isPlaying = false
            } catch (e: Exception) {
                Log.e(TAG, "TTS 播放异常", e)
                isPlaying = false
                onError(e.message ?: "播放异常")
            }
        }
    }

    /**
     * 流式播放文本（用于流式 LLM + 流式 TTS）
     * @param textFlow 流式文本输入
     * @param onFinished 播放完成回调，参数为完整文本
     * @param onError 错误回调
     */
    override suspend fun playTextStream(
        textFlow: Flow<String>,
        onFinished: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "准备流式播放文本")

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
            stopRecording()
        }

        try {
            // 启动流式 TTS 会话
            val startResult = cosyVoiceTTSClient?.startStreamingSession(context)
            if (startResult?.isFailure == true) {
                Log.e(TAG, "启动流式 TTS 会话失败")
                isPlaying = false
                onError("启动流式 TTS 会话失败")
                cosyVoiceTTSClient?.stopStreamingSession()
                return
            }

            // 通知 AEC 处理器 TTS 播放已开始，启动打断保护期
            aecAudioProcessor?.notifyPlaybackStarted()

            Log.d(TAG, "流式 TTS 会话已启动，开始接收文本块")

            // 收集完整的文本
            val fullText = StringBuilder()
            var chunkCount = 0

            // 使用协程并发处理：一边 collect 文本块，一边发送给 TTS
            val collectJob = managerScope.launch {
                try {
                    textFlow.collect { chunk ->
                        chunkCount++
                        Log.d(TAG, "收到文本块 #$chunkCount: ${chunk.take(50)}...")
                        fullText.append(chunk)

                        // 发送文本块到流式 TTS
                        val sendResult = cosyVoiceTTSClient?.sendStreamingChunk(chunk)
                        if (sendResult?.isFailure == true) {
                            Log.e(TAG, "发送文本块到流式 TTS 失败")
                            onError("发送文本块失败")
                            return@collect
                        }
                    }
                    Log.d(TAG, "textFlow.collect 完成，共收到 $chunkCount 个文本块")

                    // 所有文本块已发送，结束流式会话
                    Log.d(TAG, "所有文本块已发送，结束流式会话")
                    val finishResult = cosyVoiceTTSClient?.finishStreamingSession()
                    if (finishResult?.isFailure == true) {
                        Log.e(TAG, "结束流式 TTS 会话失败")
                        onError("结束流式 TTS 会话失败")
                        return@launch
                    }
                    Log.d(TAG, "finish-task 已发送")
                } catch (e: Exception) {
                    Log.e(TAG, "collect 文本块异常", e)
                    onError(e.message ?: "collect 文本块异常")
                }
            }

            // 等待 collect 完成（文本块发送完成）
            collectJob.join()

            Log.d(TAG, "等待音频数据播放完成...")

            // 等待播放完成，超时时间为 30 秒
            var waitCount = 0
            val maxWaitCount = 300  // 30 秒
            while (cosyVoiceTTSClient?.isStreamingActive() == true && waitCount < maxWaitCount) {
                delay(100)
                waitCount++
                if (waitCount % 50 == 0) {
                    Log.d(TAG, "等待音频数据... waitCount=$waitCount/${maxWaitCount}")
                }
            }

            if (waitCount >= maxWaitCount) {
                Log.w(TAG, "等待音频数据超时")
            } else {
                Log.d(TAG, "音频数据播放完成")
            }

            // 额外等待 AudioTrack 播放完缓冲区的数据
            Log.d(TAG, "流式会话已完成，等待 AudioTrack 播放完缓冲区...")
            var audioWaitCount = 0
            val maxAudioWaitCount = 100  // 10 秒
            while (cosyVoiceTTSClient?.isAudioTrackStillPlaying() == true && audioWaitCount < maxAudioWaitCount) {
                delay(100)
                audioWaitCount++
                if (audioWaitCount % 50 == 0) {
                    Log.d(TAG, "等待 AudioTrack 播放... audioWaitCount=$audioWaitCount/${maxAudioWaitCount}")
                }
            }

            if (audioWaitCount >= maxAudioWaitCount) {
                Log.w(TAG, "等待 AudioTrack 播放超时")
            } else {
                Log.d(TAG, "AudioTrack 播放完成")
            }

            Log.d(TAG, "流式 TTS 播放完成")
            // 通知 AEC 处理器 TTS 播放已停止
            aecAudioProcessor?.notifyPlaybackStopped()
            // 先更新状态，再调用完成回调，避免状态不一致
            isPlaying = false
            onFinished(fullText.toString())

            // 正常完成后停止流式会话
           // cosyVoiceTTSClient?.stopStreamingSession()

        } catch (e: Exception) {
            Log.e(TAG, "流式 TTS 播放异常", e)
            isPlaying = false
            onError(e.message ?: "播放异常")
            // 异常时也要停止流式会话
            cosyVoiceTTSClient?.stopStreamingSession()
        }
    }

    /**
     * 使用 AEC 播放文本
     */
    private suspend fun playTextWithAEC(text: String) {
        // 通知 AEC 处理器 TTS 播放已开始，启动打断保护期
        aecAudioProcessor?.notifyPlaybackStarted()

        try {
            // 音频数据回调已经设置好了，CosyVoiceTTSClient 播放时会自动将音频数据提供给 AecAudioProcessor
            val result = cosyVoiceTTSClient?.synthesizeAndPlay(text, context)

            if (result?.isFailure == true) {
                throw result.exceptionOrNull() ?: Exception("播放失败")
            }
        } finally {
            // 播放结束后通知 AEC 处理器
            aecAudioProcessor?.notifyPlaybackStopped()
        }
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

        // 通知 AEC 处理器 TTS 播放已停止
        aecAudioProcessor?.notifyPlaybackStopped()

        // 停止 AEC 音频处理器的播放
        aecAudioProcessor?.stopPlayback()

        // 取消 TTS 播放
        cosyVoiceTTSClient?.cancelPlayback()

        Log.d(TAG, "播放已停止")
    }

    /**
     * 停止所有音频操作
     */
    override fun stopAll() {
        Log.d(TAG, "停止所有音频操作")

        isStopping = false

        // 停止静音检测
        silenceTimer?.cancel()
        silenceTimer = null
        recordingJob?.cancel()
        recordingJob = null

        // 停止播放
        if (isPlaying) {
            isPlaying = false
            aecAudioProcessor?.notifyPlaybackStopped()
            aecAudioProcessor?.stopPlayback()
            cosyVoiceTTSClient?.cancelPlayback()
        }

        // 释放音频焦点
        abandonAudioFocus()

        // 释放客户端
        aecQwenSpeechClient?.release()
        offlineAsrRecognizer?.cancelListening()
        offlineAsrRecognizer?.destroy()
        aecAudioProcessor?.release()
        cosyVoiceTTSClient?.cancelPlayback()

        // 清空引用
        aecQwenSpeechClient = null
        offlineAsrRecognizer = null
        aecAudioProcessor = null
        cosyVoiceTTSClient = null
        activeAsrEngine = ActiveAsrEngine.NONE
        aecStarted = false
        recognitionActive = false

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
                        // 如果之前因失去焦点而停止录音，现在重新启动
                        if (!isRecording && shouldAutoResumeRecording) {
                            shouldAutoResumeRecording = false
                            // 注意：这里需要调用 startRecording，但需要传递回调参数
                            // 由于回调参数在调用 startRecording 时由外部传入，这里无法直接恢复
                            // 因此只记录日志，实际恢复由外部控制（如 InterruptOrchestrator 会重新调用 startListening）
                            Log.d(TAG, "音频焦点已恢复，等待外部重新启动录音")
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.d(TAG, "失去音频焦点")
                        // 只停止播放，不释放客户端
                        if (isPlaying) {
                            stopPlayback()
                        }
                        // 停止录音
                        if (isRecording) {
                            stopRecording()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.d(TAG, "暂时失去音频焦点")
                        // 停止录音，避免在失去焦点时继续录音导致无法捕捉人声
                        if (isRecording) {
                            shouldAutoResumeRecording = true
                            stopRecording()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Log.d(TAG, "暂时失去音频焦点（可降低音量）")
                    }
                }
            },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
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
     * 确保资源已完全释放
     * 在重新初始化前调用，避免资源冲突
     */
    private suspend fun ensureResourcesReleased() {
        var retryCount = 0
        val maxRetries = 10

        while (retryCount < maxRetries) {
            val released = checkResourcesReleased()
            Log.d(TAG, "ensureResourcesReleased: retry=$retryCount, released=$released, isRecording=$isRecording, processorRecording=${aecAudioProcessor?.isCurrentlyRecording()}")
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
        val recordingState = aecAudioProcessor?.isCurrentlyRecording() != true
        val offlineState = offlineAsrRecognizer?.isCurrentlyListening() != true
        val clientState = !isRecording && !recognitionActive && activeAsrEngine == ActiveAsrEngine.NONE
        Log.d(
            TAG,
            "checkResourcesReleased: recordingState=$recordingState, offlineState=$offlineState, clientState=$clientState"
        )
        return recordingState && offlineState && clientState
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

    private fun shouldFallbackToCloud(): Boolean {
        return offlineAsrAutoFallbackToCloud && apiKey.isNotBlank()
    }
}
