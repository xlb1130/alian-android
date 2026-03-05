package com.alian.assistant.infrastructure.device.runtime

import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.infrastructure.device.accessibility.AccessibilityKeepAliveService
import com.alian.assistant.infrastructure.device.controller.shizuku.ShizukuController

/**
 * 虚拟屏命令执行引擎。
 *
 * 职责：
 * 1. 将输入与启动命令定向到虚拟 display。
 * 2. 对命令执行结果做轻量错误判定，向上层返回 Result。
 */
class VirtualDisplayCommandEngine(
    private val shizukuController: ShizukuController,
) {

    fun prepare(): Result<Int> {
        if (!shizukuController.isAvailable()) {
            return Result.failure(IllegalStateException("Shizuku service unavailable"))
        }
        val displayId = AccessibilityKeepAliveService.getVirtualDisplayId()
            ?: return Result.failure(IllegalStateException("virtual display not ready"))
        if (displayId < 0) {
            return Result.failure(IllegalStateException("invalid virtual display id: $displayId"))
        }

        return Result.success(displayId)
    }

    fun inject(action: Action, velocity: Float?): Result<Unit> {
        val displayId = prepare().getOrElse { return Result.failure(it) }

        return when (action.type) {
            "click" -> runDisplayInput(displayId, "tap ${action.x ?: 0} ${action.y ?: 0}")
            "double_tap" -> {
                val x = action.x ?: 0
                val y = action.y ?: 0
                runDisplayInput(displayId, "tap $x $y && input -d $displayId tap $x $y")
            }
            "long_press" -> {
                val x = action.x ?: 0
                val y = action.y ?: 0
                runDisplayInput(displayId, "swipe $x $y $x $y 900")
            }
            "swipe" -> {
                val x1 = action.x ?: 0
                val y1 = action.y ?: 0
                val x2 = action.x2 ?: x1
                val y2 = action.y2 ?: y1
                val durationMs = velocityToDurationMs(velocity)
                runDisplayInput(displayId, "swipe $x1 $y1 $x2 $y2 $durationMs")
            }
            "type" -> {
                val text = action.text.orEmpty()
                val escaped = text.replace("'", "'\\''")
                runDisplayInput(displayId, "text '$escaped'")
            }
            "system_button" -> {
                val keyCode = when (action.button?.lowercase()) {
                    "back" -> 4
                    "home" -> 3
                    "enter" -> 66
                    else -> return Result.success(Unit)
                }
                runDisplayInput(displayId, "keyevent $keyCode")
            }
            "open_app" -> shizukuController.openAppOnDisplay(action.text.orEmpty(), displayId)
            "open" -> shizukuController.openDeepLinkOnDisplay(action.text.orEmpty(), displayId)
            else -> Result.success(Unit)
        }
    }

    private fun runDisplayInput(displayId: Int, operation: String): Result<Unit> {
        val output = shizukuController.execShell("input -d $displayId $operation")
        if (output.hasShellError()) {
            return Result.failure(IllegalStateException("display input failed: $output"))
        }
        return Result.success(Unit)
    }

    private fun velocityToDurationMs(velocity: Float?): Int {
        val normalized = (velocity ?: 0.5f).coerceIn(0f, 1f)
        val factor = 1.5f - normalized
        return (500 * factor).toInt().coerceIn(50, 3000)
    }

    private fun String.hasShellError(): Boolean {
        return contains("Error", ignoreCase = true) ||
            contains("Exception", ignoreCase = true) ||
            contains("invalid", ignoreCase = true) ||
            contains("unknown option", ignoreCase = true)
    }
}

