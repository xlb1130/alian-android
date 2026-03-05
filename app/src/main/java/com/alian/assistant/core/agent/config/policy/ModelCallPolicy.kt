package com.alian.assistant.core.agent.config.policy

/**
 * 模型调用策略。
 *
 * 设计约束：
 * 1. 所有模型请求相关阈值统一由该策略提供，避免散落常量。
 * 2. 重试参数用于网络波动场景，业务失败不应盲目重试。
 *
 * @property maxRetries 网络或瞬态错误最大重试次数。
 * @property requestTimeoutMs 单次请求超时时间（毫秒）。
 * @property maxTokens 模型输出最大 token 限制。
 * @property temperature 采样温度，自动化场景建议低值。
 * @property topP nucleus sampling 参数。
 * @property frequencyPenalty 频率惩罚，抑制重复输出。
 */
data class ModelCallPolicy(
    val maxRetries: Int = 3,
    val requestTimeoutMs: Long = 90_000L,
    val maxTokens: Int = 4096,
    val temperature: Float = 0.0f,
    val topP: Float = 0.85f,
    val frequencyPenalty: Float = 0.2f,
)
