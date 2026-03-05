package com.alian.assistant.core.agent.config.policy

/**
 * 快照（截图）策略。
 *
 * 设计约束：
 * 1. 仅定义缓存和节流等策略，不绑定具体采集实现。
 * 2. 与运行时截图实现解耦，便于替换 Shizuku/Accessibility/VirtualDisplay。
 *
 * @property cacheEnabled 是否启用截图缓存。
 * @property cacheTtlMs 缓存有效期（毫秒）。
 * @property cacheMaxEntries 最大缓存条目数。
 * @property throttleEnabled 是否启用截图节流。
 * @property minCaptureIntervalMs 最小截图间隔（毫秒）。
 * @property compressionQuality 截图压缩质量（0-100）。
 * @property maxSizeKb 截图目标最大体积（KB）。
 */
data class SnapshotPolicy(
    val cacheEnabled: Boolean = true,
    val cacheTtlMs: Long = 2_000L,
    val cacheMaxEntries: Int = 3,
    val throttleEnabled: Boolean = true,
    val minCaptureIntervalMs: Long = 1_000L,
    val compressionQuality: Int = 85,
    val maxSizeKb: Int = 150,
)
