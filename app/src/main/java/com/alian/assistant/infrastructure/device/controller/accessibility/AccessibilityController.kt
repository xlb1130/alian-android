package com.alian.assistant.infrastructure.device.controller.accessibility

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.alian.assistant.infrastructure.device.accessibility.AlianAccessibilityService
import com.alian.assistant.infrastructure.device.controller.interfaces.ControllerType
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController

/**
 * 无障碍服务设备控制器实现
 * 
 * 使用无障碍服务 API 实现设备控制功能
 */
class AccessibilityController(
    private val context: Context,
    private val resultCode: Int = -1,
    private val data: Intent? = null,
    private val onMediaProjectionAuthNeeded: (() -> Unit)? = null
) : IDeviceController, MediaProjectionScreenshotProvider.OnAuthorizationNeededListener {
    
    companion object {
        private const val TAG = "AccessibilityController"
    }
    
    private val screenshotProvider: MediaProjectionScreenshotProvider? by lazy {
        Log.d(TAG, "初始化 screenshotProvider: resultCode=$resultCode, data=$data")
        // RESULT_OK = -1，所以检查 resultCode == RESULT_OK 而不是 != -1
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            Log.d(TAG, "✓ 创建 MediaProjectionScreenshotProvider")
            MediaProjectionScreenshotProvider(context, resultCode, data, this)
        } else {
            Log.w(TAG, "✗ 无法创建 MediaProjectionScreenshotProvider: resultCode=$resultCode, data=${if (data != null) "available" else "null"}")
            null
        }
    }

    override fun onAuthorizationNeeded() {
        Log.w(TAG, "⚠️ MediaProjection 需要重新授权")
        onMediaProjectionAuthNeeded?.invoke()
    }
    
    private val appLauncher: IntentAppLauncher by lazy {
        IntentAppLauncher(context)
    }
    
    private val inputProvider: AccessibilityInputProvider by lazy {
        AccessibilityInputProvider(context)
    }
    
    override fun tap(x: Int, y: Int) {
        Log.d(TAG, "tap: ($x, $y)")
        inputProvider.tap(x, y)
    }
    
    override fun longPress(x: Int, y: Int, durationMs: Int) {
        Log.d(TAG, "longPress: ($x, $y), duration: ${durationMs}ms")
        inputProvider.longPress(x, y, durationMs)
    }
    
    override fun doubleTap(x: Int, y: Int) {
        Log.d(TAG, "doubleTap: ($x, $y)")
        inputProvider.doubleTap(x, y)
    }
    
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, velocity: Float) {
        Log.d(TAG, "swipe: ($x1,$y1) -> ($x2,$y2), duration: ${durationMs}ms, velocity: $velocity")
        inputProvider.swipe(x1, y1, x2, y2, durationMs, velocity)
    }
    
    override fun type(text: String) {
        Log.d(TAG, "type: text=$text")
        inputProvider.type(text)
    }
    
    override fun back() {
        Log.d(TAG, "back")
        inputProvider.back()
    }
    
    override fun home() {
        Log.d(TAG, "home")
        inputProvider.home()
    }
    
    override fun enter() {
        Log.d(TAG, "enter")
        inputProvider.enter()
    }
    
    override suspend fun screenshot(): Bitmap? {
        Log.d(TAG, "screenshot: called")
        val provider = screenshotProvider
        if (provider == null) {
            Log.w(TAG, "screenshot: ScreenshotProvider not available")
            return null
        }
        return provider.screenshot()
    }
    
    override suspend fun screenshotWithFallback(): IDeviceController.ScreenshotResult {
        Log.d(TAG, "screenshotWithFallback: called")
        val provider = screenshotProvider
        Log.d(TAG, "screenshotWithFallback: provider=${if (provider != null) "available (${provider.javaClass.simpleName})" else "NULL"}")

        if (provider == null) {
            Log.w(TAG, "screenshotWithFallback: ScreenshotProvider not available, creating fallback")
            // 创建黑屏占位图
            val fallbackBitmap = createFallbackBitmap()
            return IDeviceController.ScreenshotResult(
                bitmap = fallbackBitmap,
                isSensitive = false,
                isFallback = true
            )
        }

        Log.d(TAG, "screenshotWithFallback: calling provider.screenshotWithFallback()")
        val result = provider.screenshotWithFallback()
        Log.d(TAG, "screenshotWithFallback: result - isFallback=${result.isFallback}, isSensitive=${result.isSensitive}, bitmap=${if (result.bitmap != null) "${result.bitmap.width}x${result.bitmap.height}" else "null"}")
        return result
    }
    
    override fun getScreenSize(): Pair<Int, Int> {
        Log.d(TAG, "getScreenSize: called")
        val provider = screenshotProvider
        if (provider == null) {
            Log.w(TAG, "getScreenSize: ScreenshotProvider not available, using default")
            return Pair(1080, 2400)
        }
        return provider.getScreenSize()
    }
    
    override fun openApp(appNameOrPackage: String) {
        Log.d(TAG, "openApp: $appNameOrPackage")
        appLauncher.openApp(appNameOrPackage)
    }
    
    override fun openDeepLink(uri: String) {
        Log.d(TAG, "openDeepLink: $uri")
        appLauncher.openDeepLink(uri)
    }
    
    override fun getCurrentPackage(): String? {
        Log.d(TAG, "getCurrentPackage: called")
        val service = AlianAccessibilityService.getInstance()
        if (service == null) {
            Log.w(TAG, "getCurrentPackage: 无障碍服务未连接")
            return null
        }
        
        // 从根节点获取包名
        val rootNode = service.rootNode
        if (rootNode != null) {
            val packageName = rootNode.packageName?.toString()
            Log.d(TAG, "getCurrentPackage: $packageName")
            return packageName
        }
        
        Log.w(TAG, "getCurrentPackage: 无法获取根节点")
        return null
    }
    
    override fun isAvailable(): Boolean {
        val available = AlianAccessibilityService.isConnected()
        Log.d(TAG, "isAvailable: $available")
        return available
    }
    
    override fun getControllerType(): ControllerType {
        return ControllerType.ACCESSIBILITY
    }

    override fun unbindService() {
        // Accessibility controller doesn't bind a service that needs explicit unbind.
        // Keep no-op to avoid crashing on activity destroy.
        Log.d(TAG, "unbindService: no-op for AccessibilityController")
    }

    /**
     * 创建黑屏占位图
     */
    private fun createFallbackBitmap(): Bitmap {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }
}
