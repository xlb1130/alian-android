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
