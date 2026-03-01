package com.alian.assistant.common.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.alian.assistant.infrastructure.device.accessibility.AlianAccessibilityService

/**
 * 无障碍服务工具类
 */
object AccessibilityUtils {

    /**
     * 检查无障碍服务是否已启用
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceName = AlianAccessibilityService::class.java.name
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        if (TextUtils.isEmpty(enabledServices)) {
            return false
        }

        // 系统可能存储多种格式：
        // 1. 全限定名: com.alian.assistant/com.alian.assistant.infrastructure.device.accessibility.AlianAccessibilityService
        // 2. 相对路径: com.alian.assistant/.agent.accessibility.AlianAccessibilityService
        // 3. 短类名: com.alian.assistant/agent.accessibility.AlianAccessibilityService
        
        // 提取类名部分（去掉包名）
        val simpleClassName = AlianAccessibilityService::class.java.simpleName
        
        val services = enabledServices.split(":")
        return services.any { enabledService ->
            // 检查是否包含完整类名
            enabledService.contains(serviceName) ||
            // 检查是否以 / + 完整类名结尾
            enabledService.endsWith("/" + serviceName) ||
            // 检查是否包含短类名
            enabledService.contains(simpleClassName)
        }
    }

    /**
     * 打开无障碍服务设置页面
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            // 如果无法打开设置页面，尝试打开通用设置
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e("AccessibilityUtils", "Failed to open accessibility settings", e2)
            }
        }
    }
}