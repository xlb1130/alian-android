package com.alian.assistant.infrastructure.device.controller.accessibility

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.alian.assistant.App
import com.alian.assistant.infrastructure.device.controller.interfaces.IAppLauncher

/**
 * Intent 应用启动器实现
 * 
 * 使用 PackageManager 和 Intent 启动应用
 */
class IntentAppLauncher(
    private val context: Context
) : IAppLauncher {
    
    companion object {
        private const val TAG = "IntentAppLauncher"
    }
    
    override fun openApp(appNameOrPackage: String) {
        Log.d(TAG, "openApp: $appNameOrPackage")
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
            Log.d(TAG, "openApp: Detected package name format: $finalPackage")
        } else if (packageMap.containsKey(lowerName)) {
            // 从内置映射中查找
            finalPackage = packageMap[lowerName]!!
            Log.d(TAG, "openApp: Found in built-in map: $lowerName -> $finalPackage")
        } else {
            // 使用 AppScanner 搜索应用
            val appScanner = App.getInstance().appScanner
            val searchResults = appScanner.searchApps(appNameOrPackage, topK = 1)
            if (searchResults.isNotEmpty()) {
                finalPackage = searchResults[0].app.packageName
                Log.d(TAG, "openApp: AppScanner found: ${searchResults[0].app.appName} -> $finalPackage")
            } else {
                // 找不到，直接用原始输入尝试
                finalPackage = appNameOrPackage
                Log.w(TAG, "openApp: App not found in AppScanner: $appNameOrPackage")
            }
        }
        
        // 使用 PackageManager.getLaunchIntentForPackage 启动应用
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(finalPackage)
        
        if (launchIntent != null) {
            try {
                context.startActivity(launchIntent)
                Log.d(TAG, "openApp: $appNameOrPackage -> $finalPackage, success")
            } catch (e: Exception) {
                Log.e(TAG, "openApp failed: ${e.message}")
                e.printStackTrace()
            }
        } else {
            Log.w(TAG, "openApp: Cannot find launch intent for: $finalPackage")
        }
    }
    
    override fun openDeepLink(uri: String) {
        Log.d(TAG, "openDeepLink: $uri")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(intent)
            Log.d(TAG, "openDeepLink: $uri, success")
        } catch (e: Exception) {
            Log.e(TAG, "openDeepLink failed: ${e.message}")
            e.printStackTrace()
        }
    }
    
    override fun openIntent(action: String, data: String?) {
        Log.d(TAG, "openIntent: action=$action, data=$data")
        val intent = Intent(action)
        if (data != null) {
            intent.data = Uri.parse(data)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(intent)
            Log.d(TAG, "openIntent: action=$action, data=$data, success")
        } catch (e: Exception) {
            Log.e(TAG, "openIntent failed: ${e.message}")
            e.printStackTrace()
        }
    }
    
    override fun isAvailable(): Boolean {
        val available = context.packageManager != null
        Log.d(TAG, "isAvailable: $available")
        return available
    }
}