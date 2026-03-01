package com.alian.assistant.infrastructure.ai.asr


/**
 * 外部（小企鹅）推送 PCM 音频的消费接口。
 *
 * 约定：
 * - 采样率固定 16000；声道固定 1；编码 PCM16LE。
 * - 引擎内部可做基本校验，不匹配可忽略该帧并记录日志。
 */
interface ExternalPcmConsumer {
    fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int)
}

/**
 * 统一的“批量 PCM 识别”接口。
 *
 * 适用于各供应商的非流式（文件）引擎：
 * - 现有 File 引擎只需实现该接口，并在实现中直接调用其自身的 protected recognize(pcm)。
 * - 便于通用的 Push-PCM 适配器在 stop() 时一次性提交聚合的 PCM。
 */
interface PcmBatchRecognizer {
    suspend fun recognizeFromPcm(pcm: ByteArray)
}
