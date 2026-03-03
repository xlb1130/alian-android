package com.alian.assistant.presentation.ui.screens.settings

/**
 * 设置页面二级页面枚举
 * 
 * 分组说明：
 * - 🎨 外观与个性化：主题模式、Alian 设置
 * - 🧠 AI 模型配置：模型配置、语音服务商、API 配置
 * - ⚙️ 执行与控制：执行设置、语音交互、设备控制器
 * - 🔐 权限与安全：权限管理、Shizuku 设置
 * - 🔌 扩展能力：MCP 管理
 * - ❓ 帮助与反馈：帮助、反馈、关于
 */
enum class SettingsSubScreen(val title: String) {
    // 🎨 外观与个性化
    THEME("主题模式"),
    ALIAN("Alian 设置"),
    
    // 🧠 AI 模型配置
    MODEL_CONFIG("模型配置"),
    SPEECH_PROVIDER("语音服务商"),
    API("API 配置"),
    
    // ⚙️ 执行与控制
    EXECUTION("执行设置"),
    VOICE_INTERACTION("语音交互"),
    DEVICE_CONTROLLER("设备控制器"),
    
    // 🔐 权限与安全
    PERMISSION_MANAGEMENT("权限管理"),
    SHIZUKU("Shizuku 设置"),
    
    // 🔌 扩展能力
    MCP("MCP 管理"),
    
    // ❓ 帮助与反馈
    HELP("帮助"),
    FEEDBACK("反馈与调试"),
    ABOUT("关于")
}
