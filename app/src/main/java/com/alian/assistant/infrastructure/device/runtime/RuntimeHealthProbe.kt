package com.alian.assistant.infrastructure.device.runtime

import com.alian.assistant.core.agent.runtime.RuntimeHealth
import com.alian.assistant.core.agent.runtime.RuntimeSource
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.device.controller.interfaces.ScreenshotErrorType

/**
 * 运行时健康探针。
 *
 * 设计约束：
 * 1. 探针只做轻量检查（可用性+一次快照）。
 * 2. 异常场景返回不健康结果，不抛出异常。
 */
class RuntimeHealthProbe(
    private val controller: IDeviceController,
    private val runtimeSource: RuntimeSource,
) {

    /**
     * 执行健康探针。
     */
    suspend fun probe(): RuntimeHealth {
        val now = System.currentTimeMillis()

        if (!controller.isAvailable()) {
            return RuntimeHealth(
                healthy = false,
                source = runtimeSource,
                message = "控制器不可用",
                checkedAtMs = now,
            )
        }

        val screenshot = runCatching { controller.screenshotWithFallback() }.getOrNull()
        if (screenshot == null) {
            return RuntimeHealth(
                healthy = false,
                source = runtimeSource,
                message = "快照探针失败",
                checkedAtMs = now,
            )
        }

        if (screenshot.errorType != ScreenshotErrorType.NONE && screenshot.isFallback) {
            return RuntimeHealth(
                healthy = false,
                source = runtimeSource,
                message = "快照异常: ${screenshot.errorType}",
                checkedAtMs = now,
            )
        }

        return RuntimeHealth(
            healthy = true,
            source = runtimeSource,
            message = "运行时健康",
            checkedAtMs = now,
        )
    }
}
