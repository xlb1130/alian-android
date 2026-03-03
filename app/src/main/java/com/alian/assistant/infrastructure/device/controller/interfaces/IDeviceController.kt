package com.alian.assistant.infrastructure.device.controller.interfaces

import android.graphics.Bitmap

/**
 * 设备控制器统一接口
 * 
 * 定义了设备控制器的核心功能，包括输入操作、截图、应用启动等
 */
interface IDeviceController {
    
    /**
     * 点击屏幕
     */
    fun tap(x: Int, y: Int)
    
    /**
     * 长按
     */
    fun longPress(x: Int, y: Int, durationMs: Int = 1000)
    
    /**
     * 双击
     */
    fun doubleTap(x: Int, y: Int)
    
    /**
     * 滑动
     * @param x1 起点X坐标
     * @param y1 起点Y坐标
     * @param x2 终点X坐标
     * @param y2 终点Y坐标
     * @param durationMs 滑动持续时间（毫秒），会被 velocity 影响
     * @param velocity 滑动力度/速度，范围 0.0-1.0，默认 0.5
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 500, velocity: Float = 0.5f)
    
    /**
     * 输入文本
     */
    fun type(text: String)
    
    /**
     * 返回键
     */
    fun back()
    
    /**
     * Home 键
     */
    fun home()
    
    /**
     * 回车键
     */
    fun enter()
    
    /**
     * 截图
     */
    suspend fun screenshot(): Bitmap?
    
    /**
     * 截图（带降级处理）
     */
    suspend fun screenshotWithFallback(): ScreenshotResult
    
    /**
     * 获取屏幕尺寸
     */
    fun getScreenSize(): Pair<Int, Int>
    
    /**
     * 打开 App
     */
    fun openApp(appNameOrPackage: String)
    
    /**
     * 打开 DeepLink
     */
    fun openDeepLink(uri: String)
    
    /**
     * 获取当前前台应用的包名
     * @return 当前前台应用的包名，如果获取失败则返回 null
     */
    fun getCurrentPackage(): String?
    
    /**
     * 检查控制器是否可用
     */
    fun isAvailable(): Boolean
    
    /**
     * 获取控制器类型
     */
    fun getControllerType(): ControllerType
    
    /**
     * 截图结果
     */
    data class ScreenshotResult(
        val bitmap: Bitmap,
        val isSensitive: Boolean = false,  // 是否是敏感页面（截图失败）
        val isFallback: Boolean = false,   // 是否是降级的黑屏占位图
        val errorType: ScreenshotErrorType = ScreenshotErrorType.NONE  // 截图失败的具体原因
    )

    fun unbindService()
}

/**
 * 截图错误类型枚举
 * 用于区分截图失败的具体原因，以便向用户提供准确的提示信息
 */
enum class ScreenshotErrorType {
    NONE,                    // 成功，无错误
    SERVICE_NOT_RUNNING,     // AccessibilityKeepAliveService 未运行
    MEDIA_PROJECTION_NULL,   // MediaProjection 为 null（权限未授权或已失效）
    IMAGE_READER_NULL,       // ImageReader 未初始化
    VIRTUAL_DISPLAY_NULL,    // VirtualDisplay 未创建
    NO_IMAGE_AVAILABLE,      // ImageReader 中没有可用图像
    SENSITIVE_PAGE,          // 敏感页面（银行等 App）
    SHELL_COMMAND_FAILED,    // Shell 命令执行失败（Shizuku 模式）
    FILE_NOT_ACCESSIBLE,     // 截图文件无法访问
    UNKNOWN                  // 未知错误
}

/**
 * 控制器类型枚举
 */
enum class ControllerType {
    SHIZUKU,           // Shizuku 实现
    ACCESSIBILITY,     // 无障碍服务实现
    HYBRID            // 混合实现（自动选择）
}