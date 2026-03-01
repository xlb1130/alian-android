package com.alian.assistant.infrastructure.audio

import kotlinx.coroutines.flow.Flow

/**
 * 音频管理器接口
 * 用于统一 VoiceCallAudioManager 和 AecVoiceCallAudioManager 的接口
 */
interface IAudioManager {
    /**
     * 开始录音
     */
    fun startRecording(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit,
        onSilenceTimeout: () -> Unit = {}
    )

    /**
     * 停止录音
     */
    fun stopRecording()

    /**
     * 播放文本（非流式）
     */
    fun playText(
        text: String,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    )

    /**
     * 流式播放文本（用于流式 LLM + 流式 TTS）
     * @param textFlow 流式文本输入
     * @param onFinished 播放完成回调，参数为完整文本
     * @param onError 错误回调
     */
    suspend fun playTextStream(
        textFlow: Flow<String>,
        onFinished: (String) -> Unit,
        onError: (String) -> Unit
    )

    /**
     * 停止播放
     */
    fun stopPlayback()

    /**
     * 停止所有音频操作
     */
    fun stopAll()

    /**
     * 检查是否正在录音
     */
    fun isCurrentlyRecording(): Boolean

    /**
     * 检查是否正在播放
     */
    fun isCurrentlyPlaying(): Boolean

    /**
     * 释放所有资源（在通话结束时调用）
     * 取消所有协程并释放音频资源
     */
    fun release()
}