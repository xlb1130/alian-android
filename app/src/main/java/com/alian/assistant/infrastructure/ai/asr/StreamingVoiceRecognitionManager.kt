package com.alian.assistant.infrastructure.ai.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 基于流式 ASR 引擎的语音识别管理器
 * 支持在线 ASR（DashScope）和本地离线 ASR（Sherpa）自动切换。
 */
class StreamingVoiceRecognitionManager(
    private val context: Context,
    private val apiKey: String,
    private val model: String = "fun-asr-realtime-2025-09-15",
    private val offlineAsrEnabled: Boolean = false,
    private val offlineAsrAutoFallbackToCloud: Boolean = true
) {
    private val tag = "StreamingVoiceRecognitionManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var cloudAsrEngine: DashscopeStreamAsrEngine? = null
    private var sherpaRecognizer: SherpaOfflineSpeechRecognizer? = null

    private var isListening = false

    private enum class ActiveEngine {
        NONE,
        OFFLINE,
        CLOUD
    }

    private var activeEngine: ActiveEngine = ActiveEngine.NONE

    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onPartialResultCallback: ((String) -> Unit)? = null

    private var pendingStartJob: Job? = null

    fun setResultCallback(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null
    ) {
        onResultCallback = onResult
        onErrorCallback = onError
        onPartialResultCallback = onPartialResult
    }

    fun isCurrentlyListening(): Boolean {
        return when (activeEngine) {
            ActiveEngine.OFFLINE -> isListening && sherpaRecognizer?.isCurrentlyListening() == true
            ActiveEngine.CLOUD -> isListening && cloudAsrEngine?.isRunning == true
            ActiveEngine.NONE -> false
        }
    }

    fun startListening() {
        if (isListening) {
            Log.d(tag, "语音识别已在进行中")
            return
        }

        pendingStartJob?.cancel()
        pendingStartJob = null

        Log.d(
            tag,
            "开始语音识别，offlineAsrEnabled=$offlineAsrEnabled, offlineAsrAutoFallbackToCloud=$offlineAsrAutoFallbackToCloud"
        )

        if (offlineAsrEnabled) {
            tryStartOfflineWithFallback()
            return
        }

        startCloud()
    }

    fun stopListening() {
        Log.d(tag, "停止语音识别，当前状态: $isListening")

        pendingStartJob?.cancel()
        pendingStartJob = null

        if (!isListening) {
            Log.d(tag, "语音识别未在运行，无需停止")
            return
        }

        try {
            when (activeEngine) {
                ActiveEngine.OFFLINE -> sherpaRecognizer?.stopListening()
                ActiveEngine.CLOUD -> cloudAsrEngine?.stop()
                ActiveEngine.NONE -> Unit
            }
            isListening = false
            activeEngine = ActiveEngine.NONE
        } catch (e: Exception) {
            Log.e(tag, "停止语音识别失败", e)
            isListening = false
            activeEngine = ActiveEngine.NONE
            onErrorCallback?.invoke("停止语音识别失败: ${e.message}")
        }
    }

    fun cancelListening() {
        Log.d(tag, "取消语音识别")
        try {
            pendingStartJob?.cancel()
            pendingStartJob = null
            when (activeEngine) {
                ActiveEngine.OFFLINE -> sherpaRecognizer?.cancelListening()
                ActiveEngine.CLOUD -> cloudAsrEngine?.stop()
                ActiveEngine.NONE -> Unit
            }
            isListening = false
            activeEngine = ActiveEngine.NONE
        } catch (e: Exception) {
            Log.e(tag, "取消语音识别失败", e)
            onErrorCallback?.invoke("取消语音识别失败: ${e.message}")
        }
    }

    fun destroy() {
        Log.d(tag, "销毁语音识别资源")
        try {
            pendingStartJob?.cancel()
            pendingStartJob = null
            cloudAsrEngine?.stop()
            sherpaRecognizer?.destroy()
            scope.cancel()
            isListening = false
            activeEngine = ActiveEngine.NONE
        } catch (e: Exception) {
            Log.e(tag, "销毁语音识别资源时出错", e)
        }
    }

    private fun tryStartOfflineWithFallback() {
        val recognizer = sherpaRecognizer ?: SherpaOfflineSpeechRecognizer(context).also {
            sherpaRecognizer = it
        }

        pendingStartJob = scope.launch {
            val initialized = recognizer.initializeIfNeeded()
            if (!initialized) {
                Log.w(tag, "离线 ASR 初始化失败")
                pendingStartJob = null
                if (offlineAsrAutoFallbackToCloud && apiKey.isNotBlank()) {
                    startCloud()
                } else {
                    isListening = false
                    activeEngine = ActiveEngine.NONE
                    onErrorCallback?.invoke("离线语音识别初始化失败")
                }
                return@launch
            }

            val started = recognizer.startListening(
                object : SherpaOfflineSpeechRecognizer.Listener {
                    override fun onPartial(text: String) {
                        Log.d(tag, "离线 ASR 部分结果: $text")
                        onPartialResultCallback?.invoke(text)
                    }

                    override fun onFinal(text: String) {
                        Log.d(tag, "离线 ASR 最终结果: $text")
                        isListening = false
                        activeEngine = ActiveEngine.NONE
                        pendingStartJob = null
                        onResultCallback?.invoke(text)
                    }

                    override fun onError(message: String) {
                        Log.e(tag, "离线 ASR 错误: $message")
                        isListening = false
                        activeEngine = ActiveEngine.NONE
                        pendingStartJob = null
                        if (
                            offlineAsrAutoFallbackToCloud &&
                            apiKey.isNotBlank() &&
                            message != "没有录音权限"
                        ) {
                            startCloud()
                        } else {
                            onErrorCallback?.invoke(message)
                        }
                    }
                }
            )

            if (started) {
                activeEngine = ActiveEngine.OFFLINE
                isListening = true
                pendingStartJob = null
            } else {
                isListening = false
                activeEngine = ActiveEngine.NONE
                pendingStartJob = null
            }
        }
    }

    private fun startCloud() {
        if (apiKey.isBlank()) {
            isListening = false
            activeEngine = ActiveEngine.NONE
            onErrorCallback?.invoke("在线识别不可用（API Key 为空），且离线识别未成功")
            return
        }

        try {
            val listener = object : StreamingAsrEngine.Listener {
                override fun onFinal(text: String) {
                    Log.d(tag, "在线 ASR 最终结果: $text")
                    isListening = false
                    activeEngine = ActiveEngine.NONE
                    pendingStartJob = null
                    onResultCallback?.invoke(text)
                }

                override fun onError(message: String) {
                    Log.e(tag, "在线 ASR 错误: $message")
                    isListening = false
                    activeEngine = ActiveEngine.NONE
                    pendingStartJob = null
                    onErrorCallback?.invoke(message)
                }

                override fun onPartial(text: String) {
                    Log.d(tag, "在线 ASR 部分结果: $text")
                    onPartialResultCallback?.invoke(text)
                }

                override fun onStopped() {
                    Log.d(tag, "在线 ASR 录音阶段结束")
                }
            }

            cloudAsrEngine = DashscopeStreamAsrEngine(
                apiKey = apiKey,
                model = model,
                context = context,
                scope = scope,
                listener = listener
            )
            cloudAsrEngine?.start()
            activeEngine = ActiveEngine.CLOUD
            isListening = cloudAsrEngine?.isRunning == true
            if (!isListening) {
                activeEngine = ActiveEngine.NONE
                pendingStartJob = null
                onErrorCallback?.invoke("在线语音识别启动失败")
            } else {
                pendingStartJob = null
            }
        } catch (e: Exception) {
            Log.e(tag, "启动在线语音识别失败", e)
            isListening = false
            activeEngine = ActiveEngine.NONE
            pendingStartJob = null
            onErrorCallback?.invoke("启动在线语音识别失败: ${e.message}")
        }
    }
}

