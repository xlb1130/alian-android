package com.alian.assistant.infrastructure.ai.asr

import android.content.Context
import android.util.Log
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.data.model.SpeechProvider
import com.alian.assistant.data.model.SpeechProviderConfig
import com.alian.assistant.infrastructure.ai.asr.bailian.BailianAsrEngine
import com.alian.assistant.infrastructure.ai.asr.volcano.VolcanoAsrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 基于统一 ASR 引擎的语音识别管理器
 * 支持在线服务商（百炼/火山）和本地离线 ASR（Sherpa）自动切换。
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
    private val settingsManager = SettingsManager(context)

    private var cloudAsrEngine: AsrEngine? = null
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
            cloudAsrEngine?.release()
            cloudAsrEngine = null
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
                if (offlineAsrAutoFallbackToCloud && canUseCloudAsr()) {
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
                            canUseCloudAsr() &&
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
        val runtimeConfig = resolveCloudConfig()

        if (runtimeConfig == null) {
            isListening = false
            activeEngine = ActiveEngine.NONE
            onErrorCallback?.invoke("在线识别配置不完整，请检查语音服务商凭证")
            return
        }

        try {
            val listener = object : AsrListener {
                override fun onPartial(text: String) {
                    Log.d(tag, "在线 ASR 部分结果: $text")
                    onPartialResultCallback?.invoke(text)
                }

                override fun onFinal(text: String) {
                    Log.d(tag, "在线 ASR 最终结果: $text")
                    isListening = false
                    activeEngine = ActiveEngine.NONE
                    pendingStartJob = null
                    onResultCallback?.invoke(text)
                }

                override fun onError(error: String) {
                    Log.e(tag, "在线 ASR 错误: $error")
                    isListening = false
                    activeEngine = ActiveEngine.NONE
                    pendingStartJob = null
                    onErrorCallback?.invoke(error)
                }

                override fun onStopped() {
                    Log.d(tag, "在线 ASR 录音阶段结束")
                }
            }

            cloudAsrEngine?.release()
            cloudAsrEngine = when (runtimeConfig.provider) {
                SpeechProvider.VOLCANO -> VolcanoAsrEngine(
                    config = runtimeConfig.config,
                    context = context,
                    scope = scope,
                    listener = listener,
                    externalPcmMode = false
                )

                SpeechProvider.BAILIAN -> BailianAsrEngine(
                    config = runtimeConfig.config,
                    context = context,
                    scope = scope,
                    listener = listener,
                    externalPcmMode = false
                )
            }

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

    private data class RuntimeCloudConfig(
        val provider: SpeechProvider,
        val config: AsrConfig
    )

    private fun resolveCloudConfig(): RuntimeCloudConfig? {
        val settings = settingsManager.settings.value
        val provider = settings.speechProvider
        val providerConfig = SpeechProviderConfig.get(provider)
        val credentials = settingsManager.getSpeechCredentials(provider)
        val speechModels = settingsManager.getSpeechModels(provider)

        val resolvedApiKey = credentials.apiKey.ifBlank { apiKey }
        val resolvedModel = speechModels.asrModel.ifEmpty {
            if (model.isNotBlank()) model else providerConfig.asrDefaultModel
        }
        val resolvedResourceId = if (provider == SpeechProvider.VOLCANO) {
            if (resolvedModel.startsWith("volc.", ignoreCase = true)) resolvedModel else ""
        } else {
            credentials.asrResourceId
        }

        val asrConfig = AsrConfig(
            apiKey = resolvedApiKey,
            model = resolvedModel,
            language = "zh",
            appId = credentials.appId,
            cluster = credentials.cluster,
            resourceId = resolvedResourceId
        )

        return when (provider) {
            SpeechProvider.BAILIAN -> {
                if (asrConfig.apiKey.isBlank()) null else RuntimeCloudConfig(provider, asrConfig)
            }

            SpeechProvider.VOLCANO -> {
                if (
                    asrConfig.apiKey.isBlank() ||
                    asrConfig.appId.isBlank() ||
                    asrConfig.resourceId.isBlank()
                ) {
                    null
                } else {
                    RuntimeCloudConfig(provider, asrConfig)
                }
            }
        }
    }

    private fun canUseCloudAsr(): Boolean = resolveCloudConfig() != null
}
