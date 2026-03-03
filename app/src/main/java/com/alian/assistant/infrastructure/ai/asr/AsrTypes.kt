package com.alian.assistant.infrastructure.ai.asr

import com.alian.assistant.data.model.SpeechProvider

/**
 * ASR 配置
 */
data class AsrConfig(
    val apiKey: String,
    val model: String,
    val language: String = "zh",
    val sampleRate: Int = 16000,
    // 火山引擎特有
    val appId: String = "",
    val cluster: String = ""
)

/**
 * ASR 监听器
 */
interface AsrListener {
    /** 接收中间结果 */
    fun onPartial(text: String)

    /** 接收最终结果 */
    fun onFinal(text: String)

    /** 处理错误 */
    fun onError(error: String)

    /** VAD 状态变化 */
    fun onVadStateChanged(speaking: Boolean) {}

    /** 录音阶段结束（例如用户松手或静音自动判停） */
    fun onStopped() {}

    /** 接收实时音频振幅（用于波形动画） */
    fun onAmplitude(amplitude: Float) {}
}

/**
 * ASR 引擎工厂
 */
interface AsrEngineFactory {
    val provider: SpeechProvider
    fun create(config: AsrConfig, listener: AsrListener): AsrEngine
}
