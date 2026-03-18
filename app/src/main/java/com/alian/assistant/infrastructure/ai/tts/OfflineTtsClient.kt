package com.alian.assistant.infrastructure.ai.tts

import android.content.Context

/**
 * 离线 TTS 客户端统一接口
 */
interface OfflineTtsClient {
    suspend fun synthesizeAndPlay(text: String, context: Context): Result<Unit>
    fun cancelPlayback()
    fun isCurrentlyPlaying(): Boolean
    suspend fun isAudioTrackStillPlaying(): Boolean
    fun setOnAudioDataCallback(callback: ((ByteArray) -> Unit)?)
    fun release()
}
