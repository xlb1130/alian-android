package com.alian.assistant.infrastructure.device.controller.shizuku

import android.content.Context
import com.alian.assistant.App
import com.alian.assistant.IShellService
import com.alian.assistant.infrastructure.device.controller.interfaces.IAppLauncher
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 应用启动器实现
 * 
 * 使用 Shizuku 执行 shell 命令启动应用
 */
class ShizukuAppLauncher(
    private val context: Context? = null,
    private val shellService: IShellService? = null
) : IAppLauncher {
    
    override fun openApp(appNameOrPackage: String) {
        // 常见应用名到包名的映射 (作为备选)
        val packageMap = mapOf(
            "settings" to "com.android.settings",
            "设置" to "com.android.settings",
            "chrome" to "com.android.chrome",
            "浏览器" to "com.android.browser",
            "camera" to "com.android.camera",
            "相机" to "com.android.camera",
            "phone" to "com.android.dialer",
            "电话" to "com.android.dialer",
            "contacts" to "com.android.contacts",
            "联系人" to "com.android.contacts",
            "messages" to "com.android.mms",
            "短信" to "com.android.mms",
            "gallery" to "com.android.gallery3d",
            "相册" to "com.android.gallery3d",
            "clock" to "com.android.deskclock",
            "时钟" to "com.android.deskclock",
            "calculator" to "com.android.calculator2",
            "计算器" to "com.android.calculator2",
            "calendar" to "com.android.calendar",
            "日历" to "com.android.calendar",
            "files" to "com.android.documentsui",
            "文件" to "com.android.documentsui"
        )
        
        val lowerName = appNameOrPackage.lowercase().trim()
        val finalPackage: String
        
        if (appNameOrPackage.contains(".")) {
            // 已经是包名格式
            finalPackage = appNameOrPackage
        } else if (packageMap.containsKey(lowerName)) {
            // 从内置映射中查找
            finalPackage = packageMap[lowerName]!!
        } else {
            // 使用 AppScanner 搜索应用
            val appScanner = App.getInstance().appScanner
            val searchResults = appScanner.searchApps(appNameOrPackage, topK = 1)
            if (searchResults.isNotEmpty()) {
                finalPackage = searchResults[0].app.packageName
                println("[ShizukuAppLauncher] AppScanner found: ${searchResults[0].app.appName} -> $finalPackage")
            } else {
                // 找不到，直接用原始输入尝试
                finalPackage = appNameOrPackage
                println("[ShizukuAppLauncher] App not found in AppScanner: $appNameOrPackage")
            }
        }
        
        // 使用 monkey 命令启动应用 (最可靠)
        val result = exec("monkey -p $finalPackage -c android.intent.category.LAUNCHER 1 2>/dev/null")
        println("[ShizukuAppLauncher] openApp: $appNameOrPackage -> $finalPackage, result: $result")
    }
    
    override fun openDeepLink(uri: String) {
        exec("am start -a android.intent.action.VIEW -d \"$uri\"")
    }
    
    override fun openIntent(action: String, data: String?) {
        val cmd = buildString {
            append("am start -a $action")
            if (data != null) {
                append(" -d \"$data\"")
            }
        }
        exec(cmd)
    }
    
    override fun isAvailable(): Boolean {
        return shellService != null
    }
    
    /**
     * 执行 shell 命令 (本地，无权限)
     */
    private fun execLocal(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            reader.close()
            output
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
    
    /**
     * 执行 shell 命令 (通过 Shizuku)
     */
    private fun exec(command: String): String {
        return try {
            shellService?.exec(command) ?: execLocal(command)
        } catch (e: Exception) {
            e.printStackTrace()
            execLocal(command)
        }
    }
}