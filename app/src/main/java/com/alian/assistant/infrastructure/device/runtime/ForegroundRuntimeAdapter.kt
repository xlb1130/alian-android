package com.alian.assistant.infrastructure.device.runtime

import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.core.agent.runtime.ExecutionRuntime
import com.alian.assistant.core.agent.runtime.RuntimeHealth
import com.alian.assistant.core.agent.runtime.RuntimeSource
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 前台执行运行时适配器。
 */
class ForegroundRuntimeAdapter(
    private val controller: IDeviceController,
) : ExecutionRuntime {

    private val probe = RuntimeHealthProbe(
        controller = controller,
        runtimeSource = RuntimeSource.FOREGROUND,
    )

    override val runtimeName: String = "foreground"
    override val runtimeSource: RuntimeSource = RuntimeSource.FOREGROUND

    override suspend fun prepare(): Result<Unit> = withContext(Dispatchers.IO) {
        if (controller.isAvailable()) Result.success(Unit)
        else Result.failure(IllegalStateException("前台控制器不可用"))
    }

    override suspend fun captureSnapshot(): Result<IDeviceController.ScreenshotResult> = withContext(Dispatchers.IO) {
        runCatching { controller.screenshotWithFallback() }
    }

    override suspend fun inject(action: Action, velocity: Float?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            when (action.type) {
                "click" -> {
                    val x = action.x ?: 0
                    val y = action.y ?: 0
                    controller.tap(x, y)
                }
                "double_tap" -> {
                    val x = action.x ?: 0
                    val y = action.y ?: 0
                    controller.doubleTap(x, y)
                }
                "long_press" -> {
                    val x = action.x ?: 0
                    val y = action.y ?: 0
                    controller.longPress(x, y)
                }
                "swipe" -> {
                    val x1 = action.x ?: 0
                    val y1 = action.y ?: 0
                    val x2 = action.x2 ?: x1
                    val y2 = action.y2 ?: y1
                    controller.swipe(x1, y1, x2, y2, velocity = velocity ?: 0.5f)
                }
                "type" -> controller.type(action.text.orEmpty())
                "system_button" -> {
                    when (action.button?.lowercase()) {
                        "back" -> controller.back()
                        "home" -> controller.home()
                        "enter" -> controller.enter()
                        else -> Unit
                    }
                }
                "open_app" -> action.text?.let { controller.openApp(it) }
                "open" -> action.text?.let { controller.openDeepLink(it) }
                else -> Unit
            }
            Unit
        }
    }

    override suspend fun health(): RuntimeHealth = withContext(Dispatchers.IO) {
        probe.probe()
    }

    override suspend fun close() {
        // 前台运行时不持有额外资源。
    }
}
