package com.alian.assistant.infrastructure.device.controller

import android.content.Context
import com.alian.assistant.infrastructure.device.controller.accessibility.AccessibilityController
import com.alian.assistant.infrastructure.device.controller.factory.ControllerConfig
import com.alian.assistant.infrastructure.device.controller.factory.FallbackStrategy
import com.alian.assistant.infrastructure.device.controller.interfaces.ControllerType
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.device.controller.shizuku.ShizukuController

/**
 * 混合控制器
 * 
 * 结合 Shizuku 和无障碍服务的优势，提供智能降级机制
 */
class HybridController(
    private val context: Context,
    private val config: ControllerConfig
) : IDeviceController {
    
    private val shizukuController: ShizukuController by lazy {
        ShizukuController(context)
    }
    
    private val accessibilityController: AccessibilityController by lazy {
        AccessibilityController(
            context,
            config.mediaProjectionResultCode ?: -1,
            config.mediaProjectionData,
            config.onMediaProjectionAuthNeeded
        )
    }
    
    private var currentController: IDeviceController? = null
    
    init {
        // 初始化时选择最佳控制器
        currentController = selectBestController()
    }
    
    /**
     * 选择最佳控制器
     */
    private fun selectBestController(): IDeviceController {
        return when (config.fallbackStrategy) {
            FallbackStrategy.SHIZUKU_FIRST -> {
                if (config.isShizukuAvailable()) {
                    shizukuController
                } else if (config.isAccessibilityAvailable()) {
                    accessibilityController
                } else {
                    shizukuController
                }
            }
            FallbackStrategy.ACCESSIBILITY_FIRST -> {
                if (config.isAccessibilityAvailable()) {
                    accessibilityController
                } else if (config.isShizukuAvailable()) {
                    shizukuController
                } else {
                    accessibilityController
                }
            }
            FallbackStrategy.AUTO -> {
                // 自动选择：优先 Shizuku
                if (config.isShizukuAvailable()) {
                    shizukuController
                } else if (config.isAccessibilityAvailable()) {
                    accessibilityController
                } else {
                    shizukuController
                }
            }
        }
    }
    
    /**
     * 获取当前使用的控制器
     */
    private fun getCurrentController(): IDeviceController {
        var controller = currentController
        
        // 如果当前控制器不可用，尝试降级
        if (controller == null || !controller.isAvailable()) {
            println("[HybridController] Current controller not available, trying fallback")
            controller = tryFallback(controller!!)
            currentController = controller
        }
        
        return controller
    }
    
    /**
     * 尝试降级到备用控制器
     */
    private fun tryFallback(current: IDeviceController): IDeviceController {
        if (!config.fallbackEnabled) {
            println("[HybridController] Fallback disabled, returning current controller")
            return current
        }
        
        return when (current.getControllerType()) {
            ControllerType.SHIZUKU -> {
                if (accessibilityController.isAvailable()) {
                    println("[HybridController] Falling back to AccessibilityController")
                    accessibilityController
                } else {
                    println("[HybridController] No fallback available, returning current controller")
                    current
                }
            }
            ControllerType.ACCESSIBILITY -> {
                if (shizukuController.isAvailable()) {
                    println("[HybridController] Falling back to ShizukuController")
                    shizukuController
                } else {
                    println("[HybridController] No fallback available, returning current controller")
                    current
                }
            }
            ControllerType.HYBRID -> {
                // 混合控制器不应该递归调用
                selectBestController()
            }
        }
    }
    
    override fun tap(x: Int, y: Int) {
        getCurrentController().tap(x, y)
    }
    
    override fun longPress(x: Int, y: Int, durationMs: Int) {
        getCurrentController().longPress(x, y, durationMs)
    }
    
    override fun doubleTap(x: Int, y: Int) {
        getCurrentController().doubleTap(x, y)
    }
    
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, velocity: Float) {
        getCurrentController().swipe(x1, y1, x2, y2, durationMs, velocity)
    }
    
    override fun type(text: String) {
        getCurrentController().type(text)
    }
    
    override fun back() {
        getCurrentController().back()
    }
    
    override fun home() {
        getCurrentController().home()
    }
    
    override fun enter() {
        getCurrentController().enter()
    }
    
    override suspend fun screenshot(): android.graphics.Bitmap? {
        return getCurrentController().screenshot()
    }
    
    override suspend fun screenshotWithFallback(): IDeviceController.ScreenshotResult {
        return getCurrentController().screenshotWithFallback()
    }
    
    override fun getScreenSize(): Pair<Int, Int> {
        return getCurrentController().getScreenSize()
    }
    
    override fun openApp(appNameOrPackage: String) {
        getCurrentController().openApp(appNameOrPackage)
    }
    
    override fun openDeepLink(uri: String) {
        getCurrentController().openDeepLink(uri)
    }
    
    override fun getCurrentPackage(): String? {
        return getCurrentController().getCurrentPackage()
    }
    
    override fun isAvailable(): Boolean {
        // 检查是否有至少一个控制器可用
        return shizukuController.isAvailable() || accessibilityController.isAvailable()
    }
    
    override fun getControllerType(): ControllerType {
        return ControllerType.HYBRID
    }

    override fun unbindService() {
        try {
            shizukuController.unbindService()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 获取当前使用的具体控制器类型
     */
    fun getCurrentControllerType(): ControllerType {
        return getCurrentController().getControllerType()
    }
    
    /**
     * 手动切换控制器
     */
    fun switchController(type: ControllerType): Boolean {
        val newController = when (type) {
            ControllerType.SHIZUKU -> shizukuController
            ControllerType.ACCESSIBILITY -> accessibilityController
            ControllerType.HYBRID -> this
        }
        
        if (newController.isAvailable()) {
            currentController = newController
            println("[HybridController] Switched to $type")
            return true
        } else {
            println("[HybridController] Cannot switch to $type (not available)")
            return false
        }
    }
}