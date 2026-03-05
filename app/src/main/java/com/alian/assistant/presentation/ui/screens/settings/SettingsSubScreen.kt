package com.alian.assistant.presentation.ui.screens.settings

import androidx.annotation.StringRes
import com.alian.assistant.R

/**
 * 设置页面二级页面枚举
 * 
 * 分组说明：
 * - 🎨 外观与个性化：主题模式、Alian 设置
 * - 🧠 AI 模型配置：模型配置、语音服务商、API 配置
 * - ⚙️ 执行与控制：执行设置、语音交互、设备控制器
 * - 🔐 权限与安全：权限管理、Shizuku 设置
 * - 🔌 扩展能力：MCP 管理
 * - 🆕 版本更新：更新检查、频道与下载
 * - ❓ 帮助与反馈：帮助、反馈、关于
 */
enum class SettingsSubScreen(@StringRes val titleResId: Int) {
    // 🎨 外观与个性化
    THEME(R.string.settings_subscreen_theme),
    ALIAN(R.string.settings_subscreen_alian),
    
    // 🧠 AI 模型配置
    MODEL_CONFIG(R.string.settings_subscreen_model_config),
    SPEECH_PROVIDER(R.string.settings_subscreen_speech_provider),
    API(R.string.settings_subscreen_api),
    
    // ⚙️ 执行与控制
    EXECUTION(R.string.settings_subscreen_execution),
    VOICE_INTERACTION(R.string.settings_subscreen_voice_interaction),
    DEVICE_CONTROLLER(R.string.settings_subscreen_device_controller),
    
    // 🔐 权限与安全
    PERMISSION_MANAGEMENT(R.string.settings_subscreen_permission_management),
    SHIZUKU(R.string.settings_subscreen_shizuku),
    
    // 🔌 扩展能力
    MCP(R.string.settings_subscreen_mcp),

    // 🆕 版本更新
    UPDATE(R.string.settings_subscreen_update),
    
    // ❓ 帮助与反馈
    HELP(R.string.settings_subscreen_help),
    FEEDBACK(R.string.settings_subscreen_feedback),
    ABOUT(R.string.settings_subscreen_about)
}
