package com.alian.assistant.data.repository

import com.alian.assistant.core.agent.config.policy.SnapshotPolicy
import com.alian.assistant.core.agent.snapshot.model.SnapshotMetrics
import com.alian.assistant.core.agent.snapshot.service.SnapshotService
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.device.snapshot.ControllerSnapshotCaptureAdapter
import com.alian.assistant.infrastructure.device.snapshot.MemorySnapshotCache
import com.alian.assistant.infrastructure.device.snapshot.SnapshotThrottler

/**
 * 默认快照仓储实现。
 *
 * 设计说明：
 * 1. 组合 SnapshotService，不在仓储层重复实现策略逻辑。
 * 2. 提供单类可注入入口，便于后续替换为更复杂实现。
 */
class DefaultSnapshotRepository(
    controller: IDeviceController,
    policy: SnapshotPolicy,
) : SnapshotRepository {

    private val snapshotService = SnapshotService(
        policy = policy,
        capturePort = ControllerSnapshotCaptureAdapter(controller),
        cache = MemorySnapshotCache(),
        throttler = SnapshotThrottler(),
    )

    override suspend fun capture(forceRefresh: Boolean): IDeviceController.ScreenshotResult {
        return snapshotService.capture(forceRefresh)
    }

    override fun invalidateCurrentScene() {
        snapshotService.invalidateCurrentScene()
    }

    override fun getMetricsSnapshot(): SnapshotMetrics {
        return snapshotService.getMetricsSnapshot()
    }

    override fun resetMetrics() {
        snapshotService.resetMetrics()
    }
}
