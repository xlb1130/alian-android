package com.alian.assistant.infrastructure.device.controller.interfaces

/**
 * 应用启动接口
 * 
 * 定义了应用启动功能的统一接口
 */
interface IAppLauncher {
    
    /**
     * 打开 App
     * @param appNameOrPackage 应用名称或包名
     */
    fun openApp(appNameOrPackage: String)
    
    /**
     * 打开 DeepLink
     * @param uri DeepLink URI
     */
    fun openDeepLink(uri: String)
    
    /**
     * 通过 Intent 打开
     * @param action Intent action
     * @param data Intent data
     */
    fun openIntent(action: String, data: String? = null)
    
    /**
     * 检查应用启动器是否可用
     */
    fun isAvailable(): Boolean
}