package com.alian.assistant.infrastructure.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.infrastructure.ai.asr.QwenSpeechClient
import com.alian.assistant.infrastructure.ai.asr.SherpaOfflineSpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class VoiceRecognitionManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isListening = false
    private var audioFilePath: String = ""
    
    // 用于千问语音识别的客户端
    private var qwenSpeechClient: QwenSpeechClient? = null
    private var offlineAsrRecognizer: SherpaOfflineSpeechRecognizer? = null
    private val settingsManager = SettingsManager(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val offlineAsrEnabled: Boolean
        get() = settingsManager.settings.value.offlineAsrEnabled
    private val offlineAsrAutoFallbackToCloud: Boolean
        get() = settingsManager.settings.value.offlineAsrAutoFallbackToCloud

    companion object {
        private const val TAG = "VoiceRecognitionManager"
        
        // 权限请求常量
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 1001
    }

    // 初始化千问语音识别客户端
    fun initializeQwenSpeechClient(
        apiKey: String, 
        model: String = "fun-asr-realtime-2025-09-15",
        sampleRate: Int = 16000,
        audioFormat: String = "pcm",
        vadEnabled: Boolean = true,
        punctuationEnabled: Boolean = true
    ) {
        qwenSpeechClient = QwenSpeechClient(apiKey, "wss://dashscope.aliyuncs.com/api-ws/v1/inference", model, sampleRate, audioFormat, vadEnabled)
    }

    // 检查是否支持语音识别
    fun isSpeechRecognitionAvailable(): Boolean {
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        Log.d(TAG, "语音识别可用性: $isAvailable")
        
        // 检查设备是否支持录音功能
        val packageManager = context.packageManager
        val hasMicrophone = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        Log.d(TAG, "设备是否有麦克风: $hasMicrophone")
        
        return isAvailable || hasMicrophone  // 如果有麦克风，我们可以通过千问API进行识别
    }

    // 检查录音权限
    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 开始语音识别（使用千问API）
    fun startListeningWithQwen(listener: RecognitionListener) {
        if (offlineAsrEnabled) {
            Log.d(TAG, "离线ASR开关开启，优先使用离线语音识别")
            startListeningOffline(listener)
            return
        }

        startListeningWithQwenInternal(listener)
    }

    private fun startListeningOffline(listener: RecognitionListener) {
        Log.d(TAG, "开始使用离线ASR进行语音识别")

        if (!hasRecordAudioPermission()) {
            Log.e(TAG, "录音权限未授予")
            listener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        isListening = true
        listener.onReadyForSpeech(null)
        listener.onBeginningOfSpeech()

        scope.launch {
            val recognizer =
                offlineAsrRecognizer ?: SherpaOfflineSpeechRecognizer(
                    context = context,
                    enableEndpoint = true
                ).also {
                    offlineAsrRecognizer = it
                }

            val initialized = recognizer.initializeIfNeeded()
            if (!initialized) {
                Log.e(TAG, "离线ASR初始化失败")
                isListening = false
                if (shouldFallbackToCloud()) {
                    Log.w(TAG, "离线ASR初始化失败，回退千问在线识别")
                    startListeningWithQwenInternal(listener)
                } else {
                    listener.onError(SpeechRecognizer.ERROR_CLIENT)
                }
                return@launch
            }

            val started = recognizer.startListening(
                object : SherpaOfflineSpeechRecognizer.Listener {
                    override fun onPartial(text: String) {
                        listener.onPartialResults(buildRecognitionBundle(text))
                    }

                    override fun onFinal(text: String) {
                        listener.onResults(buildRecognitionBundle(text))
                        listener.onEndOfSpeech()
                        isListening = false
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "离线ASR识别错误: $message")
                        isListening = false
                        if (shouldFallbackToCloud() && message != "没有录音权限") {
                            Log.w(TAG, "离线ASR识别失败，回退千问在线识别")
                            startListeningWithQwenInternal(listener)
                        } else {
                            listener.onError(SpeechRecognizer.ERROR_SERVER)
                        }
                    }
                }
            )

            if (!started) {
                Log.e(TAG, "离线ASR启动失败")
                isListening = false
                if (shouldFallbackToCloud()) {
                    startListeningWithQwenInternal(listener)
                } else {
                    listener.onError(SpeechRecognizer.ERROR_CLIENT)
                }
            }
        }
    }

    private fun startListeningWithQwenInternal(listener: RecognitionListener) {
        Log.d(TAG, "开始使用千问API进行语音识别")

        if (!hasRecordAudioPermission()) {
            Log.e(TAG, "录音权限未授予")
            listener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        if (qwenSpeechClient == null) {
            Log.e(TAG, "千问语音识别客户端未初始化")
            listener.onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        try {
            isListening = true
            listener.onReadyForSpeech(null)
            listener.onBeginningOfSpeech()

            qwenSpeechClient?.setCallbacks(
                onPartialResult = { partialText ->
                    Log.d(TAG, "收到部分识别结果: $partialText")
                    listener.onPartialResults(buildRecognitionBundle(partialText))
                },
                onFinalResult = { finalText ->
                    Log.d(TAG, "收到最终识别结果: $finalText")
                    listener.onResults(buildRecognitionBundle(finalText))
                    listener.onEndOfSpeech()
                    isListening = false
                },
                onError = { error ->
                    Log.e(TAG, "语音识别错误: ${error.message}")
                    listener.onError(SpeechRecognizer.ERROR_SERVER)
                    isListening = false
                }
            )

            scope.launch {
                qwenSpeechClient?.recognizeSpeechRealTime(context)?.let { result ->
                    if (result.isFailure) {
                        Log.e(TAG, "千问API识别失败: ${result.exceptionOrNull()?.message}")
                        if (isListening) {
                            listener.onError(SpeechRecognizer.ERROR_SERVER)
                            isListening = false
                        }
                    }
                } ?: run {
                    if (isListening) {
                        listener.onError(SpeechRecognizer.ERROR_CLIENT)
                        isListening = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "开始语音识别失败: ${e.message}", e)
            listener.onError(SpeechRecognizer.ERROR_AUDIO)
        }
    }

    private fun buildRecognitionBundle(text: String): Bundle {
        return Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }
    }

    private fun shouldFallbackToCloud(): Boolean {
        return offlineAsrAutoFallbackToCloud && qwenSpeechClient != null
    }

    // 停止语音识别并使用千问API进行实时识别
    fun stopListeningWithQwen(listener: RecognitionListener) {
        Log.d(TAG, "停止录音并结束千问API识别")
        
        if (!isListening) {
            Log.d(TAG, "语音识别未在运行，无需停止")
            return
        }

        if (offlineAsrRecognizer?.isCurrentlyListening() == true) {
            try {
                offlineAsrRecognizer?.stopListening()
                isListening = false
            } catch (e: Exception) {
                Log.e(TAG, "停止离线ASR失败: ${e.message}", e)
                listener.onError(SpeechRecognizer.ERROR_AUDIO)
            }
            return
        }

        try {
            // 直接停止识别，结果将通过回调返回
            qwenSpeechClient?.stopRecognition()
            isListening = false
            
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败: ${e.message}", e)
            listener.onError(SpeechRecognizer.ERROR_AUDIO)
            isListening = false
        }
    }

    // 开始语音识别（使用系统原生或千问API）
    fun startListening(listener: RecognitionListener) {
        Log.d(TAG, "开始语音识别")

        if (offlineAsrEnabled) {
            Log.d(TAG, "离线ASR开关开启，优先使用离线语音识别")
            startListeningOffline(listener)
            return
        }

        // 优先尝试使用千问API
        if (qwenSpeechClient != null) {
            Log.d(TAG, "使用千问API进行语音识别")
            startListeningWithQwenInternal(listener)
        } else {
            Log.d(TAG, "使用系统原生语音识别")
            
            if (!isSpeechRecognitionAvailable()) {
                Log.e(TAG, "语音识别不可用")
                listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                return
            }

            if (!hasRecordAudioPermission()) {
                Log.e(TAG, "录音权限未授予")
                listener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                return
            }

            Log.d(TAG, "语音识别可用，权限已授予")

            // 如果已有实例，先销毁
            if (speechRecognizer != null) {
                Log.d(TAG, "销毁现有的SpeechRecognizer实例")
                speechRecognizer?.destroy()
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "准备就绪")
                    listener.onReadyForSpeech(params)
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "开始说话")
                    listener.onBeginningOfSpeech()
                }

                override fun onRmsChanged(rmsdB: Float) {
                    Log.d(TAG, "声音强度: $rmsdB")
                    listener.onRmsChanged(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    Log.d(TAG, "缓冲区接收")
                    listener.onBufferReceived(buffer)
                }

                override fun onEndOfSpeech() {
                    Log.d(TAG, "结束说话")
                    listener.onEndOfSpeech()
                }

                override fun onError(error: Int) {
                    Log.e(TAG, "语音识别错误: $error")
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "录音权限不足"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                        else -> "语音识别错误: $error"
                    }
                    Log.e(TAG, errorMessage)
                    isListening = false
                    listener.onError(error)
                }

                override fun onResults(results: Bundle?) {
                    Log.d(TAG, "语音识别结果返回")
                    val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.getOrNull(0)
                    if (!spokenText.isNullOrEmpty()) {
                        Log.d(TAG, "识别到文本: $spokenText")
                    } else {
                        Log.d(TAG, "未识别到任何文本")
                    }
                    isListening = false
                    listener.onResults(results)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    Log.d(TAG, "部分结果")
                    listener.onPartialResults(partialResults)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    Log.d(TAG, "事件: $eventType")
                    listener.onEvent(eventType, params)
                }
            })

            Log.d(TAG, "创建新的SpeechRecognizer实例并设置监听器")

            val intent = Intent().apply {
                action = RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            Log.d(TAG, "准备启动语音识别")
            speechRecognizer?.startListening(intent)
            isListening = true
            Log.d(TAG, "语音识别已启动")
        }
    }

    // 停止语音识别
    fun stopListening() {
        Log.d(TAG, "停止语音识别，当前状态: $isListening")
        if (!isListening) {
            Log.d(TAG, "语音识别未在运行，无需停止")
            return
        }

        if (offlineAsrRecognizer?.isCurrentlyListening() == true) {
            try {
                offlineAsrRecognizer?.stopListening()
                isListening = false
            } catch (e: Exception) {
                Log.e(TAG, "停止离线ASR失败: ${e.message}", e)
            }
            return
        }

        // 如果使用千问API，则调用对应的方法
        if (qwenSpeechClient != null) {
            Log.d(TAG, "使用千问API的录音停止逻辑")
            // 完成语音识别过程
            try {
                // 创建一个空的识别监听器来处理结果
                val dummyListener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        Log.e(TAG, "停止录音时发生错误: $error")
                        isListening = false
                    }

                    override fun onResults(results: Bundle?) {
                        Log.d(TAG, "录音结果已返回")
                        isListening = false
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }
                stopListeningWithQwen(dummyListener)
            } catch (e: Exception) {
                Log.e(TAG, "停止录音失败: ${e.message}", e)
                isListening = false
            }
        } else {
            speechRecognizer?.stopListening()
            isListening = false
        }
    }

    // 取消语音识别
    fun cancelListening() {
        Log.d(TAG, "取消语音识别，当前状态: $isListening")
        if (isListening) {
            if (offlineAsrRecognizer?.isCurrentlyListening() == true) {
                try {
                    offlineAsrRecognizer?.cancelListening()
                    isListening = false
                    Log.d(TAG, "离线语音识别已取消")
                } catch (e: Exception) {
                    Log.e(TAG, "取消离线语音识别失败: ${e.message}", e)
                }
                return
            }

            // 如果使用千问API，则调用对应的方法
            if (qwenSpeechClient != null) {
                Log.d(TAG, "使用千问API的录音取消逻辑")
                // 停止千问语音识别并清理资源
                try {
                    // 调用千问客户端的停止方法
                    qwenSpeechClient?.stopRecognition()
                    isListening = false
                    
                    // 删除临时音频文件
                    val audioFile = File(audioFilePath)
                    if (audioFile.exists()) {
                        audioFile.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "取消录音失败: ${e.message}", e)
                }
            } else {
                speechRecognizer?.cancel()
            }
            isListening = false
            Log.d(TAG, "语音识别已取消")
        } else {
            Log.d(TAG, "语音识别未在运行，无需取消")
        }
    }
    
    // 检查是否正在监听
    fun isCurrentlyListening(): Boolean {
        return isListening
    }

    // 销毁资源
    fun destroy() {
        Log.d(TAG, "销毁语音识别资源")
        
        // 清理千问API相关的资源
        try {
            // 停止千问语音识别
            qwenSpeechClient?.stopRecognition()
            offlineAsrRecognizer?.cancelListening()
            offlineAsrRecognizer?.destroy()
            
            // 删除临时音频文件
            if (audioFilePath.isNotEmpty()) {
                val audioFile = File(audioFilePath)
                if (audioFile.exists()) {
                    audioFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理录音资源失败: ${e.message}", e)
        }
        
        // 清理原生识别器资源
        speechRecognizer?.destroy()
        speechRecognizer = null
        offlineAsrRecognizer = null
        scope.cancel()
        isListening = false
        Log.d(TAG, "语音识别资源已销毁")
    }
}
