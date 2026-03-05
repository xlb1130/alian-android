package com.alian.assistant.core.agent.snapshot.model

/**
 * 快照指标快照。
 *
 * 设计约束：
 * 1. 仅保存可观测计数，不包含推导中的业务状态。
 * 2. 指标按会话累计，可通过仓储重置。
 *
 * @property totalRequests 总截图请求次数。
 * @property forceRefreshRequests 强制刷新请求次数。
 * @property cacheHitCount 缓存命中次数。
 * @property throttleReuseCount 节流下复用缓存次数。
 * @property freshCaptureCount 原始采集次数。
 * @property fallbackResultCount 返回降级图次数。
 */
data class SnapshotMetrics(
    val totalRequests: Int = 0,
    val forceRefreshRequests: Int = 0,
    val cacheHitCount: Int = 0,
    val throttleReuseCount: Int = 0,
    val freshCaptureCount: Int = 0,
    val fallbackResultCount: Int = 0,
) {

    /**
     * 缓存命中率。
     */
    val cacheHitRate: Double
        get() = if (totalRequests == 0) 0.0 else cacheHitCount.toDouble() / totalRequests
}
