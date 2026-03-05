package com.alian.assistant.core.agent.config.policy

/**
 * 虚拟屏执行策略。
 *
 * 设计约束：
 * 1. 默认关闭，必须显式开启后才启用虚拟屏链路。
 * 2. 建议结合健康检查与自动降级机制使用。
 *
 * @property enabled 是否启用虚拟屏执行。
 * @property autoFallbackEnabled 虚拟屏异常时是否自动降级。
 * @property prepareTimeoutMs 虚拟屏准备超时（毫秒）。
 */
data class VirtualDisplayPolicy(
    val enabled: Boolean = false,
    val autoFallbackEnabled: Boolean = true,
    val prepareTimeoutMs: Long = 8_000L,
)
