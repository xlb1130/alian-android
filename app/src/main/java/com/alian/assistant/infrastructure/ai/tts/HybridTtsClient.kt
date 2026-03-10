package com.alian.assistant.infrastructure.ai.tts

import android.content.Context
import android.util.Log
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.data.model.SpeechProvider
import com.alian.assistant.data.model.SpeechProviderConfig
import com.alian.assistant.infrastructure.ai.tts.bailian.BailianTtsEngine
import com.alian.assistant.infrastructure.ai.tts.volcano.VolcanoTtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 统一 TTS 客户端：
 * 1. 离线 TTS 开启时优先使用 sherpa-onnx
 * 2. 离线失败可按配置回退在线 TTS（按语音服务商动态切换）
 */
class HybridTtsClient(
    private val appContext: Context,
    private val apiKey: String,
    private val voice: String = "longyingmu_v3",
    private val rate: Float = 1.0f,
    private val volume: Int = 50,
    private val offlineTtsEnabled: Boolean = false,
    private val offlineTtsAutoFallbackToCloud: Boolean = true
) {
    companion object {
        private const val TAG = "HybridTtsClient"
    }

    private val settingsManager = SettingsManager(appContext)

    private var onlineEngine: TtsEngine? = null
    private var onlineEngineSignature: String = ""

    private var offlineClient: SherpaOnnxOfflineTtsClient? = null

    private var onAudioDataCallback: ((ByteArray) -> Unit)? = null

    @Volatile
    private var offlineUnavailableReason: Throwable? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var offlineStreamingContext: Context? = null
    private val offlineStreamingBuffer = StringBuilder()
    private var offlineStreamingJob: Job? = null

    @Volatile
    private var offlineStreamingActive = false

    @Volatile
    private var onlineStreamingActive = false

    @Volatile
    private var onlineStreamingFinished = true

    suspend fun synthesizeAndPlay(text: String, context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext Result.success(Unit)
        }

        if (offlineTtsEnabled) {
            val offlineResult = synthesizeWithOffline(text, context)
            if (offlineResult.isSuccess) {
                return@withContext offlineResult
            }
            if (!offlineTtsAutoFallbackToCloud) {
                return@withContext offlineResult
            }
            Log.w(TAG, "Offline TTS failed, fallback to cloud: ${offlineResult.exceptionOrNull()?.message}")
        }

        synthesizeWithCloud(text, context)
    }

    suspend fun startStreamingSession(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (offlineTtsEnabled) {
            offlineStreamingJob?.cancel()
            offlineStreamingBuffer.clear()
            offlineStreamingContext = context
            offlineStreamingActive = true
            return@withContext Result.success(Unit)
        }

        val engine = ensureOnlineEngine()
            ?: return@withContext Result.failure(IllegalStateException("Online TTS 配置不完整"))

        val result = engine.startStreamingSession(context)
        if (result.isSuccess) {
            onlineStreamingActive = true
            onlineStreamingFinished = false
        }
        result
    }

    fun sendStreamingChunk(chunk: String): Result<Unit> {
        if (offlineTtsEnabled) {
            if (!offlineStreamingActive) {
                return Result.failure(IllegalStateException("Offline streaming session is not active"))
            }
            if (chunk.isNotBlank()) {
                offlineStreamingBuffer.append(chunk)
            }
            return Result.success(Unit)
        }

        val engine = ensureOnlineEngine()
            ?: return Result.failure(IllegalStateException("Online TTS 配置不完整"))

        if (!onlineStreamingActive) {
            return Result.failure(IllegalStateException("Online streaming session is not active"))
        }

        return engine.sendStreamingChunk(chunk)
    }

    fun finishStreamingSession(): Result<Unit> {
        if (offlineTtsEnabled) {
            if (!offlineStreamingActive) {
                return Result.failure(IllegalStateException("Offline streaming session is not active"))
            }

            val text = offlineStreamingBuffer.toString()
            val context = offlineStreamingContext ?: appContext
            offlineStreamingBuffer.clear()
            offlineStreamingActive = false

            if (text.isBlank()) {
                return Result.success(Unit)
            }

            offlineStreamingJob?.cancel()
            offlineStreamingJob = scope.launch {
                val playbackResult = synthesizeAndPlay(text, context)
                if (playbackResult.isFailure) {
                    Log.e(
                        TAG,
                        "Offline streaming playback failed: ${playbackResult.exceptionOrNull()?.message}",
                        playbackResult.exceptionOrNull()
                    )
                }
            }
            return Result.success(Unit)
        }

        val engine = ensureOnlineEngine()
            ?: return Result.failure(IllegalStateException("Online TTS 配置不完整"))

        if (!onlineStreamingActive) {
            return Result.failure(IllegalStateException("Online streaming session is not active"))
        }

        val result = engine.finishStreamingSession()
        if (result.isSuccess) {
            // 已发送 finish，会话完成由服务端事件驱动
            onlineStreamingActive = false
        }
        return result
    }

    fun stopStreamingSession() {
        if (offlineTtsEnabled) {
            offlineStreamingActive = false
            offlineStreamingBuffer.clear()
            offlineStreamingContext = null
            offlineStreamingJob?.cancel()
            offlineStreamingJob = null
            cancelPlayback()
            return
        }

        onlineEngine?.cancelPlayback()
        onlineStreamingActive = false
        onlineStreamingFinished = true
    }

    fun cancelPlayback() {
        offlineStreamingJob?.cancel()
        offlineStreamingJob = null
        offlineStreamingActive = false
        offlineStreamingBuffer.clear()
        offlineStreamingContext = null

        offlineClient?.cancelPlayback()

        onlineEngine?.cancelPlayback()
        onlineStreamingActive = false
        onlineStreamingFinished = true
    }

    fun isStreamingActive(): Boolean {
        if (offlineTtsEnabled) {
            return offlineStreamingActive || offlineStreamingJob?.isActive == true
        }

        val enginePlaying = onlineEngine?.isCurrentlyPlaying() == true
        return onlineStreamingActive || !onlineStreamingFinished || enginePlaying
    }

    fun isTaskFinished(): Boolean {
        if (offlineTtsEnabled) {
            return !isStreamingActive()
        }
        return !isStreamingActive()
    }

    suspend fun isAudioTrackStillPlaying(): Boolean {
        if (offlineTtsEnabled) {
            return offlineClient?.isAudioTrackStillPlaying() == true
        }
        return onlineEngine?.isCurrentlyPlaying() == true
    }

    fun isCurrentlyPlaying(): Boolean {
        return offlineClient?.isCurrentlyPlaying() == true ||
            onlineEngine?.isCurrentlyPlaying() == true ||
            offlineStreamingJob?.isActive == true
    }

    fun setStreamingFullText(text: String) {
        if (offlineTtsEnabled) {
            offlineStreamingBuffer.clear()
            if (text.isNotBlank()) {
                offlineStreamingBuffer.append(text)
            }
        }
        // 在线模式由服务端 session 管理，不需要额外维护全文缓存
    }

    fun setOnAudioDataCallback(callback: ((ByteArray) -> Unit)?) {
        onAudioDataCallback = callback
        onlineEngine?.setOnAudioDataCallback(callback)
        offlineClient?.setOnAudioDataCallback(callback)
    }

    fun release() {
        cancelPlayback()
        scope.coroutineContext.cancelChildren()
        offlineClient?.release()
        offlineClient = null
        onlineEngine?.release()
        onlineEngine = null
        onlineEngineSignature = ""
    }

    private suspend fun synthesizeWithOffline(text: String, context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        offlineUnavailableReason?.let {
            return@withContext Result.failure(it)
        }

        val client = ensureOfflineClient()
        val result = client.synthesizeAndPlay(text, context)
        if (result.isFailure) {
            offlineUnavailableReason = result.exceptionOrNull()
        }
        result
    }

    private suspend fun synthesizeWithCloud(text: String, context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val engine = ensureOnlineEngine()
            ?: return@withContext Result.failure(IllegalStateException("Online TTS 配置不完整"))
        onlineStreamingFinished = true
        engine.synthesizeAndPlay(text, context)
    }

    private fun ensureOnlineEngine(): TtsEngine? {
        val runtime = resolveRuntimeOnlineConfig() ?: return null
        val signature = runtime.signature

        val cached = onlineEngine
        if (cached != null && signature == onlineEngineSignature) {
            return cached
        }

        onlineEngine?.release()

        val newEngine: TtsEngine = when (runtime.provider) {
            SpeechProvider.BAILIAN -> BailianTtsEngine(runtime.config)
            SpeechProvider.VOLCANO -> VolcanoTtsEngine(runtime.config)
        }

        newEngine.setOnAudioDataCallback(onAudioDataCallback)
        newEngine.setOnPlaybackStateCallback { state ->
            when (state) {
                TtsPlaybackState.Completed,
                is TtsPlaybackState.Error -> {
                    onlineStreamingFinished = true
                    onlineStreamingActive = false
                }

                TtsPlaybackState.Playing -> {
                    onlineStreamingFinished = false
                }

                TtsPlaybackState.Idle -> Unit
            }
        }

        onlineEngine = newEngine
        onlineEngineSignature = signature
        return newEngine
    }

    private data class RuntimeOnlineConfig(
        val provider: SpeechProvider,
        val config: TtsConfig,
        val signature: String
    )

    private fun resolveRuntimeOnlineConfig(): RuntimeOnlineConfig? {
        val settings = settingsManager.settings.value
        val provider = settings.speechProvider
        val providerConfig = SpeechProviderConfig.get(provider)
        val credentials = settingsManager.getSpeechCredentials(provider)
        val models = settingsManager.getSpeechModels(provider)

        val resolvedApiKey = credentials.apiKey.ifBlank { apiKey }
        val resolvedModel = models.ttsModel.ifEmpty { providerConfig.ttsDefaultModel }
        val resolvedResourceId = if (provider == SpeechProvider.VOLCANO) {
            if (resolvedModel.startsWith("seed-tts-", ignoreCase = true)) resolvedModel else ""
        } else {
            credentials.ttsResourceId
        }

        val ttsConfig = TtsConfig(
            apiKey = resolvedApiKey,
            model = resolvedModel,
            voice = voice,
            speed = rate,
            volume = volume,
            sampleRate = 16000,
            appId = credentials.appId,
            cluster = credentials.cluster,
            resourceId = resolvedResourceId
        )

        val available = when (provider) {
            SpeechProvider.BAILIAN -> ttsConfig.apiKey.isNotBlank()
            SpeechProvider.VOLCANO -> {
                ttsConfig.apiKey.isNotBlank() &&
                    ttsConfig.appId.isNotBlank() &&
                    ttsConfig.resourceId.isNotBlank()
            }
        }

        if (!available) {
            return null
        }

        val signature = listOf(
            provider.name,
            ttsConfig.apiKey,
            ttsConfig.appId,
            ttsConfig.resourceId,
            ttsConfig.model,
            ttsConfig.voice,
            ttsConfig.speed,
            ttsConfig.volume
        ).joinToString("|")

        return RuntimeOnlineConfig(provider, ttsConfig, signature)
    }

    private fun ensureOfflineClient(): SherpaOnnxOfflineTtsClient {
        val cached = offlineClient
        if (cached != null) return cached

        return SherpaOnnxOfflineTtsClient(
            appContext = appContext,
            speed = rate,
            volume = volume
        ).also {
            it.setOnAudioDataCallback(onAudioDataCallback)
            offlineClient = it
        }
    }
}
