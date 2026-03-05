package com.alian.assistant.core.agent.snapshot.port

import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController

/**
 * 快照采集端口。
 *
 * 设计约束：
 * 1. 隔离具体设备控制器实现（Accessibility/Shizuku/VirtualDisplay）。
 * 2. 仅提供原始截图采集与场景标识能力，不包含缓存和节流策略。
 */
interface SnapshotCapturePort {

    /**
     * 采集原始截图结果。
     */
    suspend fun captureRaw(): IDeviceController.ScreenshotResult

    /**
     * 返回当前场景标识。
     *
     * 说明：
     * - 默认建议使用前台包名。
     * - 返回空值时，上层会回退到通用场景键。
     */
    fun currentSceneKey(): String?
}
