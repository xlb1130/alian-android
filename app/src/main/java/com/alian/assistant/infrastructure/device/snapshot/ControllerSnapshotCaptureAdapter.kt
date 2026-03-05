package com.alian.assistant.infrastructure.device.snapshot

import com.alian.assistant.core.agent.snapshot.port.SnapshotCapturePort
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController

/**
 * 基于 IDeviceController 的快照采集适配器。
 *
 * 说明：
 * - 将控制器能力适配为 `SnapshotCapturePort`，供领域服务调用。
 */
class ControllerSnapshotCaptureAdapter(
    private val controller: IDeviceController,
) : SnapshotCapturePort {

    override suspend fun captureRaw(): IDeviceController.ScreenshotResult {
        return controller.screenshotWithFallback()
    }

    override fun currentSceneKey(): String? {
        return controller.getCurrentPackage()
    }
}
