package com.alian.assistant.infrastructure.ai.tts

import android.content.Context
import com.alian.assistant.data.model.SpeechProvider

/**
 * TTS 配置
 */
data class TtsConfig(
    val apiKey: String,
    val model: String,
    val voice: String,
    val speed: Float = 1.0f,
    val volume: Int = 50,
    val sampleRate: Int = 16000,
    // 火山引擎特有
    val appId: String = "",
    val cluster: String = ""
)

/**
 * TTS 播放状态
 */
sealed class TtsPlaybackState {
    object Idle : TtsPlaybackState()
    object Playing : TtsPlaybackState()
    object Completed : TtsPlaybackState()
    data class Error(val message: String) : TtsPlaybackState()
}

/**
 * TTS 引擎接口
 */
interface TtsEngine {
    /** 服务商标识 */
    val provider: SpeechProvider

    /** 单次合成播放 */
    suspend fun synthesizeAndPlay(text: String, context: Context): Result<Unit>

    /** 流式会话 */
    suspend fun startStreamingSession(context: Context): Result<Unit>
    fun sendStreamingChunk(chunk: String): Result<Unit>
    fun finishStreamingSession(): Result<Unit>

    /** 控制 */
    fun cancelPlayback()
    fun isCurrentlyPlaying(): Boolean
    fun release() {
        cancelPlayback()
    }

    /** 回调 */
    fun setOnAudioDataCallback(callback: ((ByteArray) -> Unit)?)
    fun setOnPlaybackStateCallback(callback: ((TtsPlaybackState) -> Unit)?)
}

/**
 * TTS 引擎工厂
 */
interface TtsEngineFactory {
    val provider: SpeechProvider
    fun create(config: TtsConfig): TtsEngine
}
