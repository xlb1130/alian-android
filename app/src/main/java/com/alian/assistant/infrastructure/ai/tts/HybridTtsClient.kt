package com.alian.assistant.infrastructure.ai.tts

import android.content.Context
import android.util.Log
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.data.model.SpeechProvider
import com.alian.assistant.data.model.SpeechProviderConfig
import com.alian.assistant.infrastructure.ai.tts.bailian.BailianTtsEngine
import com.alian.assistant.infrastructure.ai.tts.volcano.VolcanoTtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive

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
        private const val STREAM_WAIT_POLL_MS = 100L
        private const val STREAM_ONLY_GRACE_MS = 15000L
    }

    private val settingsManager = SettingsManager(appContext)

    private var onlineEngine: TtsEngine? = null
    private var onlineEngineSignature: String = ""

    private var offlineClient: OfflineTtsClient? = null
    private var offlineBackend: OfflineBackend? = null

    private var onAudioDataCallback: ((ByteArray) -> Unit)? = null

    @Volatile
    private var offlineUnavailableReason: Throwable? = null

    private var offlineStreamingContext: Context? = null
    private val offlineStreamingBuffer = StringBuilder()

    @Volatile
    private var offlineStreamingActive = false

    @Volatile
    private var onlineStreamingActive = false

    @Volatile
    private var onlineStreamingFinished = true

    private enum class OfflineBackend {
        SHERPA_ONNX,
        ANDROID_NATIVE
    }

    suspend fun synthesizeAndPlay(text: String, context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext Result.success(Unit)
        }

        if (offlineTtsEnabled) {
            val offlineResult = synthesizeWithOffline(text, context)
            if (offlineResult.isSuccess) {
                return@withContext offlineResult
            }
            val offlineError = offlineResult.exceptionOrNull()
            if (isPlaybackInterruptedFailure(offlineError)) {
                Log.d(TAG, "Offline TTS playback interrupted, skip cloud fallback")
                return@withContext offlineResult
            }
            if (!offlineTtsAutoFallbackToCloud) {
                return@withContext offlineResult
            }
            Log.w(TAG, "Offline TTS failed, fallback to cloud: ${offlineError?.message}")
        }

        synthesizeWithCloud(text, context)
    }

    suspend fun startStreamingSession(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (offlineTtsEnabled) {
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
            offlineStreamingContext = null
            offlineStreamingActive = false

            if (text.isBlank()) {
                return Result.success(Unit)
            }

            offlineStreamingActive = true
            val playbackResult = runBlocking(Dispatchers.IO) {
                synthesizeAndPlay(text, context)
            }
            offlineStreamingActive = false

            if (playbackResult.isFailure) {
                Log.e(
                    TAG,
                    "Offline streaming playback failed: ${playbackResult.exceptionOrNull()?.message}",
                    playbackResult.exceptionOrNull()
                )
            }
            return playbackResult
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
            cancelPlayback()
            return
        }

        onlineEngine?.cancelPlayback()
        onlineStreamingActive = false
        onlineStreamingFinished = true
    }

    fun cancelPlayback() {
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
            return offlineStreamingActive || offlineClient?.isCurrentlyPlaying() == true
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

    /**
     * 等待流式会话与音频播放真正完成。
     * - 正常条件：stream inactive 且 AudioTrack 不再播放。
     * - 保护条件：若持续仅 streamActive=true 但 audioPlaying=false，超过 grace 后强制收敛。
     */
    suspend fun awaitStreamingPlaybackCompleted(
        streamOnlyGraceMs: Long = STREAM_ONLY_GRACE_MS,
        pollIntervalMs: Long = STREAM_WAIT_POLL_MS,
        onProgress: ((waitedMs: Long, streamActive: Boolean, audioPlaying: Boolean) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        require(pollIntervalMs > 0) { "pollIntervalMs 必须大于 0" }
        require(streamOnlyGraceMs >= pollIntervalMs) { "streamOnlyGraceMs 必须不小于 pollIntervalMs" }

        var waitedMs = 0L
        var streamOnlyMs = 0L

        while (currentCoroutineContext().isActive) {
            val streamActive = isStreamingActive()
            val audioPlaying = isAudioTrackStillPlaying()

            if (!streamActive && !audioPlaying) {
                return@withContext Result.success(Unit)
            }

            if (streamActive && !audioPlaying) {
                streamOnlyMs += pollIntervalMs
                if (streamOnlyMs >= streamOnlyGraceMs) {
                    Log.w(
                        TAG,
                        "Streaming state stuck without audio for ${streamOnlyMs}ms, force settle session"
                    )
                    onlineStreamingActive = false
                    onlineStreamingFinished = true
                    return@withContext Result.success(Unit)
                }
            } else {
                streamOnlyMs = 0L
            }

            onProgress?.invoke(waitedMs, streamActive, audioPlaying)
            delay(pollIntervalMs)
            waitedMs += pollIntervalMs
        }

        Result.failure(CancellationException("等待流式播放完成被取消"))
    }

    fun isCurrentlyPlaying(): Boolean {
        return offlineClient?.isCurrentlyPlaying() == true ||
            onlineEngine?.isCurrentlyPlaying() == true
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
        offlineClient?.release()
        offlineClient = null
        offlineBackend = null
        onlineEngine?.release()
        onlineEngine = null
        onlineEngineSignature = ""
    }

    private suspend fun synthesizeWithOffline(text: String, context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        offlineUnavailableReason?.let { reason ->
            if (!isPlaybackInterruptedFailure(reason)) {
                return@withContext Result.failure(reason)
            }
            offlineUnavailableReason = null
        }

        val client = ensureOfflineClient()
        val result = client.synthesizeAndPlay(text, context)
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            if (shouldMarkOfflineUnavailable(error)) {
                offlineUnavailableReason = error
            }
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

    private fun ensureOfflineClient(): OfflineTtsClient {
        val desiredBackend = resolveOfflineBackend()
        val cached = offlineClient
        if (cached != null && offlineBackend == desiredBackend) {
            return cached
        }

        if (cached != null && offlineBackend != desiredBackend) {
            cached.release()
            offlineClient = null
            offlineUnavailableReason = null
        }

        val created: OfflineTtsClient = when (desiredBackend) {
            OfflineBackend.SHERPA_ONNX -> SherpaOnnxOfflineTtsClient(
                appContext = appContext,
                speed = rate,
                volume = volume
            )

            OfflineBackend.ANDROID_NATIVE -> AndroidNativeOfflineTtsClient(
                appContext = appContext,
                speed = rate,
                volume = volume
            )
        }

        Log.d(TAG, "Offline TTS backend initialized: $desiredBackend")
        created.setOnAudioDataCallback(onAudioDataCallback)
        offlineClient = created
        offlineBackend = desiredBackend
        return created
    }

    private fun resolveOfflineBackend(): OfflineBackend {
        val settings = settingsManager.settings.value
        return if (settings.offlineTtsUseAndroidNative) {
            OfflineBackend.ANDROID_NATIVE
        } else {
            OfflineBackend.SHERPA_ONNX
        }
    }

    private fun shouldMarkOfflineUnavailable(error: Throwable?): Boolean {
        if (error == null) {
            return false
        }
        return !isPlaybackInterruptedFailure(error)
    }

    private fun isPlaybackInterruptedFailure(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is OfflineTtsPlaybackInterruptedException || current is CancellationException) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
