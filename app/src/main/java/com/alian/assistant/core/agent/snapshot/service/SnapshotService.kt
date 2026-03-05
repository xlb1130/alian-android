package com.alian.assistant.core.agent.snapshot.service

import com.alian.assistant.core.agent.config.policy.SnapshotPolicy
import com.alian.assistant.core.agent.snapshot.model.SnapshotMetrics
import com.alian.assistant.core.agent.snapshot.port.SnapshotCapturePort
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.device.snapshot.MemorySnapshotCache
import com.alian.assistant.infrastructure.device.snapshot.SnapshotThrottler

/**
 * 快照领域服务。
 *
 * 职责：
 * 1. 协调缓存、节流和原始采集。
 * 2. 为上层提供统一截图入口，避免重复实现策略分支。
 *
 * 失败行为：
 * - 原始采集失败时返回控制器提供的降级截图结果，不抛异常。
 */
class SnapshotService(
    private val policy: SnapshotPolicy,
    private val capturePort: SnapshotCapturePort,
    private val cache: MemorySnapshotCache,
    private val throttler: SnapshotThrottler,
) {
    private var metrics: SnapshotMetrics = SnapshotMetrics()

    /**
     * 获取截图。
     *
     * @param forceRefresh 是否强制刷新；为 true 时会绕过缓存与节流。
     */
    suspend fun capture(forceRefresh: Boolean = false): IDeviceController.ScreenshotResult {
        metrics = metrics.copy(
            totalRequests = metrics.totalRequests + 1,
            forceRefreshRequests = metrics.forceRefreshRequests + if (forceRefresh) 1 else 0,
        )
        val sceneKey = capturePort.currentSceneKey().orEmpty().ifBlank { "global" }

        if (!forceRefresh && policy.cacheEnabled) {
            cache.getIfFresh(sceneKey, policy.cacheTtlMs)?.let {
                metrics = metrics.copy(
                    cacheHitCount = metrics.cacheHitCount + 1,
                )
                return it
            }
        }

        if (!forceRefresh && policy.throttleEnabled) {
            if (!throttler.allowNow(policy.minCaptureIntervalMs)) {
                cache.getIfFresh(sceneKey, policy.cacheTtlMs)?.let {
                    metrics = metrics.copy(
                        cacheHitCount = metrics.cacheHitCount + 1,
                        throttleReuseCount = metrics.throttleReuseCount + 1,
                    )
                    return it
                }
            }
        }

        val fresh = capturePort.captureRaw()
        metrics = metrics.copy(
            freshCaptureCount = metrics.freshCaptureCount + 1,
            fallbackResultCount = metrics.fallbackResultCount + if (fresh.isFallback) 1 else 0,
        )
        throttler.markCaptured()

        if (policy.cacheEnabled && shouldCache(fresh)) {
            cache.put(sceneKey, fresh, policy.cacheMaxEntries)
        }

        return fresh
    }

    /**
     * 获取快照指标快照。
     */
    fun getMetricsSnapshot(): SnapshotMetrics = metrics

    /**
     * 重置快照指标。
     */
    fun resetMetrics() {
        metrics = SnapshotMetrics()
    }

    /**
     * 失效当前场景缓存。
     */
    fun invalidateCurrentScene() {
        val sceneKey = capturePort.currentSceneKey().orEmpty().ifBlank { "global" }
        cache.invalidate(sceneKey)
    }

    private fun shouldCache(result: IDeviceController.ScreenshotResult): Boolean {
        // 敏感页截图通常是占位图或黑屏，缓存价值低且可能污染判断。
        if (result.isSensitive) return false
        return true
    }
}
