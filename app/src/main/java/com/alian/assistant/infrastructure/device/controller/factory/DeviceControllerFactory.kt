package com.alian.assistant.infrastructure.device.controller.factory

import android.content.Context
import android.content.Intent
import android.util.Log
import com.alian.assistant.infrastructure.device.controller.HybridController
import com.alian.assistant.infrastructure.device.controller.accessibility.AccessibilityController
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.device.controller.shizuku.ShizukuController

/**
 * 设备控制器工厂
 *
 * 根据配置创建相应的设备控制器实例
 */
object DeviceControllerFactory {
    private const val TAG = "DeviceControllerFactory"
    
    /**
     * 创建设备控制器
     *
     * @param context 上下文
     * @param config 控制器配置
     * @return 设备控制器实例
     */
    fun createController(
        context: Context,
        config: ControllerConfig
    ): IDeviceController {
        val strategy = config.getExecutionStrategy()
        val fallbackStrategy = config.fallbackStrategy

        Log.d(TAG, "========== Creating Device Controller ==========")
        Log.d(TAG, "Execution Strategy: $strategy")
        Log.d(TAG, "Fallback Strategy: $fallbackStrategy")
        Log.d(TAG, "Shizuku Available: ${config.isShizukuAvailable()}")
        Log.d(TAG, "Accessibility Available: ${config.isAccessibilityAvailable()}")
        Log.d(TAG, "Media Projection Result Code: ${config.mediaProjectionResultCode}")
        Log.d(TAG, "Media Projection Data: ${if (config.mediaProjectionData != null) "Available" else "Null"}")
        Log.d(TAG, "Screenshot Cache Enabled: ${config.screenshotCacheEnabled}")
        Log.d(TAG, "Gesture Delay: ${config.gestureDelayMs}ms")
        Log.d(TAG, "Input Delay: ${config.inputDelayMs}ms")

        return when (strategy) {
            ExecutionStrategy.SHIZUKU_ONLY -> {
                Log.d(TAG, "Creating ShizukuController (SHIZUKU_ONLY mode)")
                if (!config.isShizukuAvailable()) {
                    Log.w(TAG, "WARNING: Shizuku is not available!")
                }
                ShizukuController(context)
            }
            ExecutionStrategy.ACCESSIBILITY_ONLY -> {
                Log.d(TAG, "Creating AccessibilityController (ACCESSIBILITY_ONLY mode)")
                if (!config.isAccessibilityAvailable()) {
                    Log.w(TAG, "WARNING: Accessibility service is not available!")
                }
                AccessibilityController(
                    context,
                    config.mediaProjectionResultCode ?: -1,
                    config.mediaProjectionData,
                    config.onMediaProjectionAuthNeeded
                )
            }
            ExecutionStrategy.AUTO -> {
                // 自动选择最佳策略
                Log.d(TAG, "AUTO mode: selecting best controller...")
                val bestController = selectBestController(context, config)
                Log.d(TAG, "AUTO mode selected: ${bestController.getControllerType()}")
                bestController
            }
            ExecutionStrategy.HYBRID -> {
                // 混合模式，使用 HybridController
                Log.d(TAG, "Creating HybridController (HYBRID mode)")
                Log.d(TAG, "HybridController will use fallback strategy: $fallbackStrategy")
                HybridController(context, config)
            }
        }
    }
    
    /**
     * 选择最佳控制器
     *
     * @param context 上下文
     * @param config 控制器配置
     * @return 最佳控制器实例
     */
    private fun selectBestController(
        context: Context,
        config: ControllerConfig
    ): IDeviceController {
        Log.d(TAG, "selectBestController: Starting selection process")

        // 优先级：Shizuku > 无障碍服务
        if (config.isShizukuAvailable()) {
            Log.d(TAG, "selectBestController: Shizuku is available, selecting ShizukuController")
            return ShizukuController(context)
        } else {
            Log.d(TAG, "selectBestController: Shizuku is NOT available")
        }

        if (config.isAccessibilityAvailable()) {
            Log.d(TAG, "selectBestController: Accessibility service is available, selecting AccessibilityController")
            return AccessibilityController(
                context,
                config.mediaProjectionResultCode ?: -1,
                config.mediaProjectionData,
                config.onMediaProjectionAuthNeeded
            )
        } else {
            Log.d(TAG, "selectBestController: Accessibility service is NOT available")
        }

        // 如果都不可用，默认返回 ShizukuController（即使不可用）
        Log.w(TAG, "selectBestController: No controller available, returning ShizukuController as fallback")
        return ShizukuController(context)
    }
    
    /**
     * 创建 Shizuku 控制器
     */
    fun createShizukuController(context: Context): IDeviceController {
        Log.d(TAG, "createShizukuController: Creating ShizukuController instance")
        return ShizukuController(context)
    }

    /**
     * 创建无障碍控制器
     */
    fun createAccessibilityController(
        context: Context,
        resultCode: Int = -1,
        data: Intent? = null,
        onMediaProjectionAuthNeeded: (() -> Unit)? = null
    ): IDeviceController {
        Log.d(TAG, "createAccessibilityController: Creating AccessibilityController instance")
        Log.d(TAG, "createAccessibilityController: ResultCode=$resultCode, Data=${if (data != null) "Available" else "Null"}")
        return AccessibilityController(context, resultCode, data, onMediaProjectionAuthNeeded)
    }

    /**
     * 创建混合控制器
     */
    fun createHybridController(
        context: Context,
        config: ControllerConfig
    ): IDeviceController {
        Log.d(TAG, "createHybridController: Creating HybridController instance")
        Log.d(TAG, "createHybridController: Strategy=${config.getExecutionStrategy()}, Fallback=${config.fallbackStrategy}")
        return HybridController(context, config)
    }
}