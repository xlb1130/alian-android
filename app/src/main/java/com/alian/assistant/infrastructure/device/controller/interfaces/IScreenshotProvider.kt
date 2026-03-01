package com.alian.assistant.infrastructure.device.controller.interfaces

import android.graphics.Bitmap

/**
 * 截图能力接口
 * 
 * 定义了截图功能的统一接口，支持不同的截图实现方式
 */
interface IScreenshotProvider {
    
    /**
     * 截图
     */
    suspend fun screenshot(): Bitmap?
    
    /**
     * 截图（带降级处理）
     */
    suspend fun screenshotWithFallback(): IDeviceController.ScreenshotResult
    
    /**
     * 获取屏幕尺寸
     */
    fun getScreenSize(): Pair<Int, Int>
    
    /**
     * 检查截图提供者是否可用
     */
    fun isAvailable(): Boolean
}