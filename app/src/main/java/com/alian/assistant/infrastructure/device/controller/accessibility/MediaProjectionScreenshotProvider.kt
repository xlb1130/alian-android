package com.alian.assistant.infrastructure.device.controller.accessibility

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.alian.assistant.infrastructure.device.accessibility.AccessibilityKeepAliveService
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.device.controller.interfaces.IScreenshotProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaProjection 截图提供者实现
 *
 * 使用 AccessibilityKeepAliveService 中创建的 MediaProjection 进行截图
 * 不自己创建 VirtualDisplay，避免与 AccessibilityKeepAliveService 冲突
 */
class MediaProjectionScreenshotProvider(
    private val context: Context,
    private val resultCode: Int,
    private val data: Intent,
    private val onAuthorizationNeededListener: OnAuthorizationNeededListener? = null
) : IScreenshotProvider {

    companion object {
        private const val TAG = "MediaProjectionScreenshotProvider"
    }

    /**
     * 需要重新授权回调接口
     */
    interface OnAuthorizationNeededListener {
        /**
         * 当 MediaProjection 被系统停止时调用
         * 需要重新请求用户授权
         */
        fun onAuthorizationNeeded()
    }

    private val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val displayMetrics: DisplayMetrics by lazy {
        DisplayMetrics().also {
            windowManager.defaultDisplay.getRealMetrics(it)
        }
    }

    init {
        Log.i(TAG, "✓ 创建 MediaProjectionScreenshotProvider")
        // 不再需要初始化 MediaProjection，直接使用 AccessibilityKeepAliveService 的
    }

    override suspend fun screenshot(): Bitmap? = withContext(Dispatchers.IO) {
        Log.d(TAG, "screenshot: called")

        // 直接从 AccessibilityKeepAliveService 获取截图
        val instance = AccessibilityKeepAliveService.getInstance()
        if (instance != null) {
            val bitmap = instance.captureScreenshot()
            Log.d(TAG, "screenshot: result=${if (bitmap != null) "success" else "null"}")
            return@withContext bitmap
        } else {
            Log.w(TAG, "⚠️ AccessibilityKeepAliveService instance is null")
            return@withContext null
        }
    }

    override suspend fun screenshotWithFallback(): IDeviceController.ScreenshotResult {
        Log.i(TAG, "========== screenshotWithFallback: START ==========")
        try {
            return withContext(Dispatchers.IO) {
                Log.i(TAG, "========== screenshotWithFallback: inside withContext ==========")

                // 直接从 AccessibilityKeepAliveService 获取截图
                val instance = AccessibilityKeepAliveService.getInstance()
                val bitmap = if (instance != null) {
                    instance.captureScreenshot()
                } else {
                    null
                }

                if (bitmap != null) {
                    Log.i(TAG, "========== screenshotWithFallback: SUCCESS ==========")
                    return@withContext IDeviceController.ScreenshotResult(bitmap)
                } else {
                    // 创建黑屏占位图
                    Log.w(TAG, "========== screenshotWithFallback: FAILED, using fallback ==========")
                    val fallbackBitmap = createFallbackBitmap()
                    return@withContext IDeviceController.ScreenshotResult(
                        bitmap = fallbackBitmap,
                        isSensitive = false,
                        isFallback = true
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "========== screenshotWithFallback: EXCEPTION ==========")
            Log.e(TAG, "Exception: ${e.message}", e)
            e.printStackTrace()
            // 创建黑屏占位图
            val fallbackBitmap = createFallbackBitmap()
            return IDeviceController.ScreenshotResult(
                bitmap = fallbackBitmap,
                isSensitive = false,
                isFallback = true
            )
        }
    }

    override fun getScreenSize(): Pair<Int, Int> {
        val size = Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
        Log.d(TAG, "getScreenSize: ${size.first}x${size.second}")
        return size
    }

    override fun isAvailable(): Boolean {
        val available = AccessibilityKeepAliveService.isRunning()
        Log.d(TAG, "isAvailable: $available")
        return available
    }

    /**
     * 创建黑屏占位图
     */
    private fun createFallbackBitmap(): Bitmap {
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        Log.d(TAG, "createFallbackBitmap: Creating ${width}x${height} fallback bitmap")
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }
}