package com.alian.assistant.infrastructure.device.accessibility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.alian.assistant.MainActivity
import com.alian.assistant.R
import java.nio.ByteBuffer

/**
 * 无障碍服务保活服务
 *
 * 功能:
 * - 定期调用无障碍服务 API (获取根节点) 以保持服务活跃
 * - 显示持久通知 "无障碍服务运行中"
 * - 与 OverlayService 分离生命周期，任务停止时不停止此服务
 *
 * 最佳实践:
 * - 使用 specialUse 前台服务类型
 * - 与 OverlayService 独立运行
 * - 仅在需要时启动，手动停止
 */
class AccessibilityKeepAliveService : Service() {

    companion object {
        private const val TAG = "AccessibilityKeepAlive"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "accessibility_keep_alive"
        private const val CHANNEL_NAME = "无障碍服务保活"

        private var instance: AccessibilityKeepAliveService? = null

        // MediaProjection 保活参数
            private var mediaProjectionResultCode: Int? = null
            private var mediaProjectionData: Intent? = null
            private var mediaProjection: MediaProjection? = null
        
            // VirtualDisplay 和 ImageReader 用于截图
            private var virtualDisplay: VirtualDisplay? = null
            private var imageReader: ImageReader? = null

        /**
         * MediaProjection 失效回调接口
         * 当 MediaProjection 被系统停止时通知调用方更新状态
         */
        interface OnMediaProjectionRevokedListener {
            fun onMediaProjectionRevoked()
        }

        private var mediaProjectionRevokedListener: OnMediaProjectionRevokedListener? = null

        /**
         * 注册 MediaProjection 失效回调
         */
        fun setOnMediaProjectionRevokedListener(listener: OnMediaProjectionRevokedListener?) {
            mediaProjectionRevokedListener = listener
        }

        /**
         * 检查 MediaProjection 是否有效
         */
        fun isMediaProjectionAvailable(): Boolean {
            return mediaProjection != null
        }
        fun start(context: Context) {
            val intent = Intent(context, AccessibilityKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AccessibilityKeepAliveService::class.java)
            context.stopService(intent)
        }

        fun isRunning(): Boolean = instance != null

        /**
         * 获取 AccessibilityKeepAliveService 实例
         */
        fun getInstance(): AccessibilityKeepAliveService? = instance

        /**
         * 设置 MediaProjection 保活参数并初始化
         */
        fun setMediaProjectionParams(resultCode: Int, data: Intent) {
            Log.d(TAG, "setMediaProjectionParams called: resultCode=$resultCode, data=$data")
            Log.d(TAG, "setMediaProjectionParams: instance=$instance")
            mediaProjectionResultCode = resultCode
            mediaProjectionData = data
            Log.d(TAG, "MediaProjection params set: resultCode=$resultCode, data=$data")

            // 如果服务正在运行，立即初始化 MediaProjection
            if (instance != null) {
                Log.d(TAG, "Calling initializeMediaProjection()...")
                instance?.initializeMediaProjection()
            } else {
                Log.w(TAG, "AccessibilityKeepAliveService instance is null, cannot initialize MediaProjection")
            }
        }

        /**
         * 获取 MediaProjection 实例（用于截图）
         */
        fun getMediaProjection(): MediaProjection? {
            return mediaProjection
        }

        /**
         * 获取当前虚拟显示 ID。
         *
         * 返回 null 表示虚拟显示尚未创建或已失效。
         */
        fun getVirtualDisplayId(): Int? {
            return virtualDisplay?.display?.displayId
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var keepAliveRunnable: Runnable? = null

    // 保活检查间隔 (30秒)
    private val keepAliveInterval = 30000L

    // MediaProjection 保活间隔 (20秒，避免 token 超时)
    private val mediaProjectionKeepAliveInterval = 20000L
    private var mediaProjectionKeepAliveRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "✅ 无障碍保活服务已创建")

        // 创建通知渠道
        createNotificationChannel()

        // 启动前台服务
        // 只声明 SPECIAL_USE 类型，因为此时用户还未授权 MediaProjection
        // MEDIA_PROJECTION 类型将在用户授权后通过 updateForegroundServiceType 添加
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        // 启动保活检查
        startKeepAliveCheck()

        // MediaProjection 将在 setMediaProjectionParams 被调用时初始化
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "无障碍保活服务启动命令")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopKeepAliveCheck()
        stopMediaProjectionKeepAlive()
        releaseMediaProjection()
        Log.d(TAG, "❌ 无障碍保活服务已销毁")
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持无障碍服务活跃"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("无障碍服务运行中")
            .setContentText("艾莲正在后台保持无障碍服务活跃")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 启动保活检查
     */
    private fun startKeepAliveCheck() {
        keepAliveRunnable = object : Runnable {
            override fun run() {
                performKeepAliveCheck()
                handler.postDelayed(this, keepAliveInterval)
            }
        }
        handler.postDelayed(keepAliveRunnable!!, keepAliveInterval)
        Log.d(TAG, "启动保活检查，间隔: ${keepAliveInterval}ms")
    }

    /**
     * 停止保活检查
     */
    private fun stopKeepAliveCheck() {
        keepAliveRunnable?.let {
            handler.removeCallbacks(it)
            keepAliveRunnable = null
        }
        Log.d(TAG, "停止保活检查")
    }

    /**
     * 执行保活检查 - 调用无障碍服务 API
     */
    private fun performKeepAliveCheck() {
        val accessibilityService = AlianAccessibilityService.getInstance()
        if (accessibilityService != null) {
            // 调用无障碍服务 API 以保持活跃
            val rootNode = accessibilityService.rootNode
            if (rootNode != null) {
                Log.d(TAG, "保活检查: 无障碍服务正常，根节点可用")
                // 释放节点资源
                rootNode.recycle()
            } else {
                Log.w(TAG, "保活检查: 无障碍服务已连接，但根节点为空")
            }
        } else {
            Log.w(TAG, "保活检查: 无障碍服务未连接")
        }
    }

    /**
     * 初始化 MediaProjection
     */
    private fun initializeMediaProjection() {
        // 检查是否已经初始化
        if (mediaProjection != null) {
            Log.w(TAG, "MediaProjection already initialized, skipping")
            return
        }

        val resultCode = mediaProjectionResultCode
        val data = mediaProjectionData

        if (resultCode == null || data == null) {
            Log.w(TAG, "MediaProjection params not set, skipping initialization")
            return
        }

        try {

            // 更新前台服务类型，添加 MEDIA_PROJECTION
            // 根据 Android 14 官方文档，需要再次调用 startForeground() 并添加新的服务类型
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                    Log.i(TAG, "✓ Foreground service type updated to include MEDIA_PROJECTION")
                } catch (e: Exception) {
                    Log.e(TAG, "✗ Failed to update foreground service type: ${e.message}")
                }
            }

            Log.d(TAG, "initializeMediaProjection: Creating MediaProjection...")
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            Log.d(TAG, "initializeMediaProjection: MediaProjection created, registering callback...")

            // 注册回调
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "⚠️ MediaProjection stopped by system - resultData may be expired")
                    mediaProjection = null
                    stopMediaProjectionKeepAlive()
                    cleanupVirtualDisplay()

                    // 清除静态变量中的授权数据，表示权限已失效
                    mediaProjectionResultCode = null
                    mediaProjectionData = null

                    // 不再尝试重新初始化，因为 resultData 可能已失效
                    // 用户需要重新授权
                    Log.w(TAG, "⚠️ MediaProjection stopped and will not be reinitialized automatically")
                    Log.w(TAG, "⚠️ User needs to re-authorize MediaProjection")

                    // 通知监听器权限已失效
                    mediaProjectionRevokedListener?.onMediaProjectionRevoked()
                }
            }
            mediaProjection?.registerCallback(callback, Handler(Looper.getMainLooper()))

            Log.d(TAG, "initializeMediaProjection: Callback registered, creating VirtualDisplay...")

            // 创建持久的 VirtualDisplay 和 ImageReader
            createVirtualDisplay()

            Log.i(TAG, "✓ MediaProjection initialized for keep-alive")

            startMediaProjectionKeepAlive()
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to initialize MediaProjection: ${e.message}")
            Log.e(TAG, "✗ Exception type: ${e.javaClass.simpleName}")
            e.printStackTrace()
            mediaProjection = null
        }
    }

    /**
     * 创建持久的 VirtualDisplay 和 ImageReader
     */
    private fun createVirtualDisplay() {
        if (mediaProjection == null) {
            Log.w(TAG, "Cannot create VirtualDisplay: MediaProjection is null")
            Log.w(TAG, "  - mediaProjectionResultCode: $mediaProjectionResultCode")
            Log.w(TAG, "  - mediaProjectionData: ${mediaProjectionData != null}")
            return
        }

        try {
            Log.d(TAG, "createVirtualDisplay: Starting...")
            Log.d(TAG, "  - MediaProjection: ${mediaProjection != null}")

            // 清理旧的 VirtualDisplay 和 ImageReader
            cleanupVirtualDisplay()

            // 获取屏幕尺寸
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val density = displayMetrics.densityDpi

            Log.d(TAG, "createVirtualDisplay: Creating ImageReader")
            Log.d(TAG, "  - width: $width")
            Log.d(TAG, "  - height: $height")
            Log.d(TAG, "  - density: $density")
            Log.d(TAG, "  - densityDpi: $density")

            // 创建 ImageReader - 使用 3 个缓冲区以减少缓冲区耗尽导致图像丢失
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

            if (imageReader == null) {
                Log.e(TAG, "✗ createVirtualDisplay: Failed to create ImageReader")
                return
            }

            Log.d(TAG, "createVirtualDisplay: ImageReader created successfully")
            Log.d(TAG, "  - ImageReader width: ${imageReader?.width}")
            Log.d(TAG, "  - ImageReader height: ${imageReader?.height}")
            Log.d(TAG, "  - ImageReader surface: ${imageReader?.surface != null}")

            // 设置 ImageReader 监听器，用于调试和确认图像是否正常到达
            imageReader?.setOnImageAvailableListener({ reader ->
                Log.d(TAG, "OnImageAvailableListener: New image available")
                Log.d(TAG, "  - ImageReader: ${reader.width}x${reader.height}")
            }, Handler(Looper.getMainLooper()))

            Log.d(TAG, "createVirtualDisplay: Creating VirtualDisplay...")

            // 创建 VirtualDisplay
            val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            if (virtualDisplay != null) {
                Log.i(TAG, "✓ VirtualDisplay and ImageReader created (${width}x${height}, density=$density)")
                Log.i(TAG, "  - VirtualDisplay displayId: ${virtualDisplay?.display?.displayId}")
            } else {
                Log.e(TAG, "✗ createVirtualDisplay: VirtualDisplay is null after creation")
                Log.e(TAG, "  - ImageReader surface: ${imageReader?.surface != null}")
                Log.e(TAG, "  - MediaProjection: ${mediaProjection != null}")
                cleanupVirtualDisplay()
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to create VirtualDisplay: ${e.message}")
            Log.e(TAG, "✗ Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "✗ Exception cause: ${e.cause}")
            e.printStackTrace()
            cleanupVirtualDisplay()
        }
    }

    /**
     * 清理 VirtualDisplay 和 ImageReader
     */
    private fun cleanupVirtualDisplay() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            Log.d(TAG, "✓ VirtualDisplay and ImageReader cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up VirtualDisplay: ${e.message}")
        } finally {
            virtualDisplay = null
            imageReader = null
        }
    }

    /**
     * 释放 MediaProjection
     */
    private fun releaseMediaProjection() {
        try {
            cleanupVirtualDisplay()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaProjection: ${e.message}")
        } finally {
            mediaProjection = null
        }
    }

    /**
     * 启动 MediaProjection 保活检查
     */
    private fun startMediaProjectionKeepAlive() {
        if (mediaProjection == null) {
            Log.w(TAG, "MediaProjection not initialized, cannot start keep-alive")
            return
        }

        if (mediaProjectionKeepAliveRunnable != null) {
            Log.d(TAG, "MediaProjection keep-alive already running")
            return
        }

        mediaProjectionKeepAliveRunnable = object : Runnable {
            override fun run() {
                performMediaProjectionKeepAlive()
                handler.postDelayed(this, mediaProjectionKeepAliveInterval)
            }
        }
        handler.postDelayed(mediaProjectionKeepAliveRunnable!!, mediaProjectionKeepAliveInterval)
        Log.d(TAG, "✓ MediaProjection keep-alive started, interval: ${mediaProjectionKeepAliveInterval}ms")
    }

    /**
     * 停止 MediaProjection 保活检查
     */
    private fun stopMediaProjectionKeepAlive() {
        mediaProjectionKeepAliveRunnable?.let {
            handler.removeCallbacks(it)
            mediaProjectionKeepAliveRunnable = null
        }
        Log.d(TAG, "✓ MediaProjection keep-alive stopped")
    }

    /**
     * 从 ImageReader 中捕获截图
     * @return Bitmap 截图，如果失败则返回 null
     */
    fun captureScreenshot(): Bitmap? {
        if (imageReader == null) {
            Log.w(TAG, "⚠️ captureScreenshot: ImageReader is null")
            Log.w(TAG, "  - mediaProjection: ${mediaProjection != null}")
            Log.w(TAG, "  - virtualDisplay: ${virtualDisplay != null}")
            Log.w(TAG, "  - mediaProjectionResultCode: $mediaProjectionResultCode")
            Log.w(TAG, "  - mediaProjectionData: ${mediaProjectionData != null}")
            return null
        }

        try {
            // 记录 ImageReader 状态
            Log.d(TAG, "captureScreenshot: ImageReader status check")
            Log.d(TAG, "  - width: ${imageReader?.width}")
            Log.d(TAG, "  - height: ${imageReader?.height}")
            Log.d(TAG, "  - surface: ${imageReader?.surface != null}")

            // 获取最新图像 - 使用 acquireNextImage 而不是 acquireLatestImage
            // acquireLatestImage 会丢弃旧图像，而 acquireNextImage 会等待新图像
            val image = imageReader?.acquireNextImage()
            if (image != null) {
                Log.d(TAG, "captureScreenshot: Image acquired successfully")
                Log.d(TAG, "  - width: ${image.width}")
                Log.d(TAG, "  - height: ${image.height}")
                Log.d(TAG, "  - format: ${image.format}")
                Log.d(TAG, "  - timestamp: ${image.timestamp}")
                Log.d(TAG, "  - planes: ${image.planes.size}")

                val bitmap = imageToBitmap(image)
                image.close()
                if (bitmap != null) {
                    Log.d(TAG, "✓ captureScreenshot: Success (${bitmap.width}x${bitmap.height})")
                } else {
                    Log.w(TAG, "✗ captureScreenshot: Failed to convert image to bitmap")
                    Log.w(TAG, "  - Image details: width=${image.width}, height=${image.height}, format=${image.format}")
                }
                return bitmap
            } else {
                Log.w(TAG, "✗ captureScreenshot: No image available")
                Log.w(TAG, "  - ImageReader width: ${imageReader?.width}")
                Log.w(TAG, "  - ImageReader height: ${imageReader?.height}")
                Log.w(TAG, "  - VirtualDisplay: ${virtualDisplay != null}")
                Log.w(TAG, "  - MediaProjection: ${mediaProjection != null}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ captureScreenshot: Exception: ${e.message}")
            Log.e(TAG, "  - Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "  - ImageReader: ${imageReader != null}")
            Log.e(TAG, "  - VirtualDisplay: ${virtualDisplay != null}")
            Log.e(TAG, "  - MediaProjection: ${mediaProjection != null}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * 将 Image 转换为 Bitmap
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        try {
            val planes = image.planes
            Log.d(TAG, "imageToBitmap: Image details")
            Log.d(TAG, "  - width: ${image.width}")
            Log.d(TAG, "  - height: ${image.height}")
            Log.d(TAG, "  - format: ${image.format}")
            Log.d(TAG, "  - planes count: ${planes.size}")

            if (planes.isEmpty()) {
                Log.e(TAG, "imageToBitmap: No planes available")
                return null
            }

            val plane = planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            Log.d(TAG, "imageToBitmap: Plane[0] details")
            Log.d(TAG, "  - buffer capacity: ${buffer.capacity()}")
            Log.d(TAG, "  - buffer remaining: ${buffer.remaining()}")
            Log.d(TAG, "  - pixelStride: $pixelStride")
            Log.d(TAG, "  - rowStride: $rowStride")
            Log.d(TAG, "  - rowPadding: $rowPadding")

            // 创建 bitmap
            val bitmapWidth = image.width + rowPadding / pixelStride
            Log.d(TAG, "imageToBitmap: Creating bitmap with width=$bitmapWidth, height=${image.height}")

            val bitmap = Bitmap.createBitmap(
                bitmapWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 如果有 padding，需要裁剪
            return if (rowPadding != 0) {
                Log.d(TAG, "imageToBitmap: Cropping bitmap (rowPadding=$rowPadding)")
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "imageToBitmap: Error: ${e.message}")
            Log.e(TAG, "  - Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "  - Image width: ${image.width}")
            Log.e(TAG, "  - Image height: ${image.height}")
            Log.e(TAG, "  - Image format: ${image.format}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * 执行 MediaProjection 保活检查
     * 通过调用 captureScreenshot() 来保持 VirtualDisplay 和 ImageReader 的活跃
     */
    private fun performMediaProjectionKeepAlive() {
        if (mediaProjection == null) {
            Log.w(TAG, "MediaProjection is null, skipping keep-alive")
            return
        }

        // 检查 VirtualDisplay 状态
        if (virtualDisplay == null) {
            Log.w(TAG, "⚠ MediaProjection keep-alive: VirtualDisplay is null, attempting to recreate...")
            createVirtualDisplay()
        }

        // 检查 ImageReader 状态
        if (imageReader == null) {
            Log.w(TAG, "⚠ MediaProjection keep-alive: ImageReader is null, attempting to recreate...")
            createVirtualDisplay()
        }

        try {
            // 调用 captureScreenshot() 来保持 VirtualDisplay 和 ImageReader 的活跃
            // 这会触发 ImageReader 的缓冲区更新，确保 VirtualDisplay 继续渲染
            val bitmap = captureScreenshot()
            if (bitmap != null) {
                Log.d(TAG, "✓ MediaProjection keep-alive: screenshot captured successfully (${bitmap.width}x${bitmap.height})")
                // 回收 bitmap 以避免内存泄漏
                bitmap.recycle()
            } else {
                Log.w(TAG, "⚠ MediaProjection keep-alive: screenshot capture returned null")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠ MediaProjection keep-alive: heartbeat failed - ${e.message}")
        }
    }
}
