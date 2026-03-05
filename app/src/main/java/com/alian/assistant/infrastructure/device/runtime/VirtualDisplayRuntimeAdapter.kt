package com.alian.assistant.infrastructure.device.runtime

import com.alian.assistant.core.agent.config.policy.VirtualDisplayPolicy
import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.core.agent.runtime.ExecutionRuntime
import com.alian.assistant.core.agent.runtime.RuntimeHealth
import com.alian.assistant.core.agent.runtime.RuntimeSource
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.device.controller.shizuku.ShizukuController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 虚拟屏执行运行时适配器（PoC）。
 *
 * 说明：
 * - 当前版本复用既有控制器能力，重点验证“运行时抽象 + 自动降级”链路。
 * - 后续可替换为独立 `VirtualDisplayEngine` 注入实现。
 */
class VirtualDisplayRuntimeAdapter(
    private val controller: IDeviceController,
    private val policy: VirtualDisplayPolicy,
) : ExecutionRuntime {

    private val probe = RuntimeHealthProbe(
        controller = controller,
        runtimeSource = RuntimeSource.VIRTUAL_DISPLAY,
    )
    private val commandEngine: VirtualDisplayCommandEngine? =
        (controller as? ShizukuController)?.let { VirtualDisplayCommandEngine(it) }

    override val runtimeName: String = "virtual_display"
    override val runtimeSource: RuntimeSource = RuntimeSource.VIRTUAL_DISPLAY

    override suspend fun prepare(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!policy.enabled) {
            return@withContext Result.failure(IllegalStateException("虚拟屏策略未启用"))
        }

        if (commandEngine != null) {
            val prepared = commandEngine.prepare()
            if (prepared.isSuccess) {
                return@withContext Result.success(Unit)
            }
            return@withContext Result.failure(
                prepared.exceptionOrNull() ?: IllegalStateException("虚拟屏命令引擎准备失败")
            )
        }

        val health = probe.probe()
        if (!health.healthy) {
            return@withContext Result.failure(IllegalStateException("虚拟屏预热失败: ${health.message}"))
        }

        Result.success(Unit)
    }

    override suspend fun captureSnapshot(): Result<IDeviceController.ScreenshotResult> = withContext(Dispatchers.IO) {
        runCatching { controller.screenshotWithFallback() }
    }

    override suspend fun inject(action: Action, velocity: Float?): Result<Unit> = withContext(Dispatchers.IO) {
        if (commandEngine != null) {
            return@withContext commandEngine.inject(action, velocity)
        }

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
        if (commandEngine != null) {
            val prepared = commandEngine.prepare()
            if (prepared.isSuccess) {
                return@withContext RuntimeHealth(
                    healthy = true,
                    source = runtimeSource,
                    message = "virtual display command engine ready",
                    checkedAtMs = System.currentTimeMillis(),
                )
            }
            return@withContext RuntimeHealth(
                healthy = false,
                source = runtimeSource,
                message = prepared.exceptionOrNull()?.message ?: "virtual display command engine unavailable",
                checkedAtMs = System.currentTimeMillis(),
            )
        }
        probe.probe()
    }

    override suspend fun close() {
        // PoC 版本不持有独占资源。
    }
}
