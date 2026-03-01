package com.alian.assistant.infrastructure.device.controller.factory

import android.content.Intent

/**
 * 执行策略枚举
 * 定义了设备控制器的执行策略
 */
enum class ExecutionStrategy {
    SHIZUKU_ONLY,       // 仅使用 Shizuku
    ACCESSIBILITY_ONLY, // 仅使用无障碍服务
    AUTO,               // 自动选择最佳策略
    HYBRID              // 混合使用（智能降级）
}

/**
 * 降级策略枚举
 * 定义了降级时的策略选择
 */
enum class FallbackStrategy {
    AUTO,               // 自动降级
    SHIZUKU_FIRST,      // 优先使用 Shizuku
    ACCESSIBILITY_FIRST // 优先使用无障碍服务
}

/**
 * Shizuku 权限级别枚举
 */
enum class ShizukuPrivilegeLevel {
    NONE,       // 未连接
    ADB,        // ADB 模式 (UID 2000)
    ROOT        // Root 模式 (UID 0)
}

/**
 * 控制器配置数据类
 * 
 * 包含了设备控制器的所有配置选项
 */
data class ControllerConfig(
    // ========== Shizuku 配置 ==========
    val shizukuEnabled: Boolean = true,
    val shizukuPrivilegeLevel: ShizukuPrivilegeLevel = ShizukuPrivilegeLevel.ADB,

    // ========== 无障碍配置 ==========
    val accessibilityEnabled: Boolean = true,
    val accessibilityService: Any? = null,  // AlianAccessibilityService 实例

    // ========== MediaProjection 配置 ==========
    val mediaProjectionEnabled: Boolean = true,
    val mediaProjectionResultCode: Int? = null,
    val mediaProjectionData: Intent? = null,
    val onMediaProjectionAuthNeeded: (() -> Unit)? = null,  // MediaProjection 需要重新授权时的回调

    // ========== 降级配置 ==========
    val fallbackEnabled: Boolean = true,
    val fallbackStrategy: FallbackStrategy = FallbackStrategy.AUTO,

    // ========== 性能配置 ==========
    val screenshotCacheEnabled: Boolean = true,
    val gestureDelayMs: Int = 100,
    val inputDelayMs: Int = 50
) {
    /**
     * 获取当前执行策略
     */
    fun getExecutionStrategy(): ExecutionStrategy {
        return when {
            shizukuEnabled && accessibilityEnabled -> ExecutionStrategy.HYBRID
            shizukuEnabled -> ExecutionStrategy.SHIZUKU_ONLY
            accessibilityEnabled -> ExecutionStrategy.ACCESSIBILITY_ONLY
            else -> ExecutionStrategy.AUTO
        }
    }
    
    /**
     * 检查 Shizuku 是否可用
     */
    fun isShizukuAvailable(): Boolean {
        return shizukuEnabled && shizukuPrivilegeLevel != ShizukuPrivilegeLevel.NONE
    }
    
    /**
     * 检查无障碍服务是否可用
     */
    fun isAccessibilityAvailable(): Boolean {
        return accessibilityEnabled && accessibilityService != null
    }
    
    /**
     * 检查 MediaProjection 是否可用
     */
    fun isMediaProjectionAvailable(): Boolean {
        return mediaProjectionEnabled && 
               mediaProjectionResultCode != null && 
               mediaProjectionData != null
    }
    
    companion object {
        /**
         * 创建默认配置
         */
        fun createDefault(): ControllerConfig {
            return ControllerConfig()
        }
        
        /**
         * 创建仅 Shizuku 配置
         */
        fun createShizukuOnly(): ControllerConfig {
            return ControllerConfig(
                shizukuEnabled = true,
                accessibilityEnabled = false,
                fallbackEnabled = false
            )
        }
        
        /**
         * 创建仅无障碍配置
         */
        fun createAccessibilityOnly(): ControllerConfig {
            return ControllerConfig(
                shizukuEnabled = false,
                accessibilityEnabled = true,
                fallbackEnabled = false
            )
        }
    }
}