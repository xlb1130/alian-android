package com.alian.assistant.core.agent.config.policy

/**
 * 自动化能力开关集合。
 *
 * 设计约束：
 * 1. 每个新能力必须由开关控制，支持独立回滚。
 * 2. 开关语义应稳定，避免一版多义。
 *
 * @property screenshotPipelineEnabled 是否启用新截图管线。
 * @property inAppUpdateEnabled 是否启用应用内更新能力。
 * @property virtualDisplayExecutionEnabled 是否启用虚拟屏执行模式。
 */
data class FeatureFlags(
    val screenshotPipelineEnabled: Boolean = false,
    val inAppUpdateEnabled: Boolean = true,
    val virtualDisplayExecutionEnabled: Boolean = false,
)
