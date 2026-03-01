package com.alian.assistant.infrastructure.device.controller.interfaces

/**
 * 输入能力接口
 * 
 * 定义了输入功能的统一接口，包括点击、滑动、文本输入等
 */
interface IInputProvider {
    
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
     *                 - 1.0: 最快（轻滑），durationMs 会被缩短
     *                 - 0.5: 中等速度
     *                 - 0.0: 最慢（重滑），durationMs 会被延长
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
     * 检查输入提供者是否可用
     */
    fun isAvailable(): Boolean
}