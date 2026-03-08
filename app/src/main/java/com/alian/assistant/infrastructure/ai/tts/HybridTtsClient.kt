package com.alian.assistant.infrastructure.ai.tts

import android.content.Context
import android.util.Log
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
 * 2. 离线失败可按配置回退在线 CosyVoice
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

    private var onlineClient: CosyVoiceTTSClient? = null
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

        val client = ensureOnlineClient()
            ?: return@withContext Result.failure(IllegalStateException("Online TTS API Key is empty"))

        client.startStreamingSession(context)
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

        val client = ensureOnlineClient()
            ?: return Result.failure(IllegalStateException("Online TTS API Key is empty"))
        return client.sendStreamingChunk(chunk)
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

        val client = ensureOnlineClient()
            ?: return Result.failure(IllegalStateException("Online TTS API Key is empty"))
        return client.finishStreamingSession()
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

        onlineClient?.stopStreamingSession()
    }

    fun cancelPlayback() {
        offlineStreamingJob?.cancel()
        offlineStreamingJob = null
        offlineStreamingActive = false
        offlineStreamingBuffer.clear()
        offlineStreamingContext = null

        offlineClient?.cancelPlayback()
        onlineClient?.cancelPlayback()
    }

    fun isStreamingActive(): Boolean {
        if (offlineTtsEnabled) {
            return offlineStreamingActive || offlineStreamingJob?.isActive == true
        }
        return onlineClient?.isStreamingActive() == true
    }

    fun isTaskFinished(): Boolean {
        if (offlineTtsEnabled) {
            return !isStreamingActive()
        }
        return onlineClient?.isTaskFinished() ?: false
    }

    suspend fun isAudioTrackStillPlaying(): Boolean {
        if (offlineTtsEnabled) {
            return offlineClient?.isAudioTrackStillPlaying() == true
        }
        return onlineClient?.isAudioTrackStillPlaying() ?: false
    }

    fun isCurrentlyPlaying(): Boolean {
        return offlineClient?.isCurrentlyPlaying() == true ||
            onlineClient?.isCurrentlyPlaying() == true ||
            offlineStreamingJob?.isActive == true
    }

    fun setStreamingFullText(text: String) {
        if (offlineTtsEnabled) {
            offlineStreamingBuffer.clear()
            if (text.isNotBlank()) {
                offlineStreamingBuffer.append(text)
            }
        } else {
            onlineClient?.setStreamingFullText(text)
        }
    }

    fun setOnAudioDataCallback(callback: ((ByteArray) -> Unit)?) {
        onAudioDataCallback = callback
        onlineClient?.setOnAudioDataCallback(callback)
        offlineClient?.setOnAudioDataCallback(callback)
    }

    fun release() {
        cancelPlayback()
        scope.coroutineContext.cancelChildren()
        offlineClient?.release()
        offlineClient = null
        onlineClient?.cancelPlayback()
        onlineClient = null
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
        val client = ensureOnlineClient()
            ?: return@withContext Result.failure(IllegalStateException("Online TTS API Key is empty"))
        client.synthesizeAndPlay(text, context)
    }

    private fun ensureOnlineClient(): CosyVoiceTTSClient? {
        if (apiKey.isBlank()) return null
        val cached = onlineClient
        if (cached != null) return cached

        return CosyVoiceTTSClient(
            apiKey = apiKey,
            voice = voice,
            rate = rate,
            volume = volume
        ).also {
            it.setOnAudioDataCallback(onAudioDataCallback)
            onlineClient = it
        }
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
