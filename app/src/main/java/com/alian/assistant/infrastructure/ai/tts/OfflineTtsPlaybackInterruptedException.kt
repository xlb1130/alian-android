package com.alian.assistant.infrastructure.ai.tts

/**
 * 离线 TTS 播放被主动打断/取消（非后端不可用错误）
 */
class OfflineTtsPlaybackInterruptedException(
    message: String = "离线 TTS 播放被打断"
) : Exception(message)
