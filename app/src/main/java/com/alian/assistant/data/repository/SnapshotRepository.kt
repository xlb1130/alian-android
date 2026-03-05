package com.alian.assistant.data.repository

import com.alian.assistant.core.agent.snapshot.model.SnapshotMetrics
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController

/**
 * 快照仓储接口。
 *
 * 职责：
 * 1. 为应用层提供统一截图入口。
 * 2. 隔离快照领域服务与具体执行链路。
 */
interface SnapshotRepository {

    /**
     * 采集截图。
     *
     * @param forceRefresh 是否强制刷新，绕过缓存/节流。
     */
    suspend fun capture(forceRefresh: Boolean = false): IDeviceController.ScreenshotResult

    /**
     * 失效当前场景缓存。
     */
    fun invalidateCurrentScene()

    /**
     * 获取当前快照指标。
     */
    fun getMetricsSnapshot(): SnapshotMetrics

    /**
     * 重置快照指标。
     */
    fun resetMetrics()
}
