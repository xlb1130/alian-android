package com.alian.assistant.presentation.ui.screens.phonecall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alian.assistant.MainActivity
import com.alian.assistant.R
import com.alian.assistant.infrastructure.device.ScreenCaptureManager
import com.alian.assistant.infrastructure.device.accessibility.AccessibilityKeepAliveService

/**
 * 手机通话浮动窗口服务
 * 
 * 负责在浮动窗口模式下显示手机通话界面，并作为前台服务运行
 * 
 * 保活机制：
 * - 使用 FOREGROUND_SERVICE_TYPE_MICROPHONE 保活录音功能
 * - 使用 FOREGROUND_SERVICE_TYPE_CAMERA 保活视频截图功能
 * - 显示持久通知，确保服务不被系统杀死
 */
class PhoneCallOverlayService : Service() {

    companion object {
        private const val TAG = "PhoneCallOverlayService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "phone_call_overlay"
        private const val CHANNEL_NAME = "手机通话"

        private var instance: PhoneCallOverlayService? = null

        /**
         * 启动服务
         */
        fun start(context: Context) {
            val intent = Intent(context, PhoneCallOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, PhoneCallOverlayService::class.java)
            context.stopService(intent)
        }

        /**
         * 获取服务实例
         */
        fun getInstance(): PhoneCallOverlayService? = instance
    }

    private var notificationManager: NotificationManager? = null

    // 浮动窗口视图引用（用于截图保护）
    private var floatingWindowView: PhoneCallFloatingWindowView? = null

    // MediaProjection 权限是否可用
    private var isMediaProjectionAvailable: Boolean = false

    // MediaProjection 失效监听器
    private val mediaProjectionRevokedListener = object : AccessibilityKeepAliveService.Companion.OnMediaProjectionRevokedListener {
        override fun onMediaProjectionRevoked() {
            Log.w(TAG, "⚠️ MediaProjection 权限已失效，更新通知")
            isMediaProjectionAvailable = false
            updateNotificationForMediaProjectionState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        
        // 注册 MediaProjection 失效监听
        AccessibilityKeepAliveService.setOnMediaProjectionRevokedListener(mediaProjectionRevokedListener)
        
        // 检查当前 MediaProjection 状态
        isMediaProjectionAvailable = AccessibilityKeepAliveService.isMediaProjectionAvailable()
        Log.d(TAG, "PhoneCallOverlayService created, isMediaProjectionAvailable=$isMediaProjectionAvailable")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        
        // 启动前台服务 - 使用麦克风和相机类型保活
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 需要指定前台服务类型
            // 使用麦克风和相机类型，确保录音和截图功能不被杀死
            startForeground(
                NOTIFICATION_ID, 
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        floatingWindowView = null
        
        // 移除 MediaProjection 失效监听
        AccessibilityKeepAliveService.setOnMediaProjectionRevokedListener(null)
        
        Log.d(TAG, "PhoneCallOverlayService destroyed")
    }

    /**
     * 绑定浮动窗口视图，并设置截图保护回调
     *
     * 截图保护机制（参考 MobileAgentImprove）：
     * - 仅在最小化状态下，截图前隐藏悬浮窗，避免遮挡屏幕内容
     * - 若本次截图前隐藏过，则截图后恢复悬浮窗显示
     *
     * @param view 浮动窗口视图
     * @param screenCaptureManager 屏幕捕获管理器
     * @param shouldHideOverlayForCapture 是否需要在本次截图前隐藏悬浮窗
     */
    fun bindFloatingWindowAndScreenCapture(
        view: PhoneCallFloatingWindowView,
        screenCaptureManager: ScreenCaptureManager,
        shouldHideOverlayForCapture: () -> Boolean
    ) {
        floatingWindowView = view
        var hiddenForCapture = false

        // 设置截图保护回调：截图前隐藏悬浮窗
        screenCaptureManager.onBeforeCapture = {
            if (shouldHideOverlayForCapture()) {
                Log.d(TAG, "截图保护：隐藏悬浮窗（最小化模式）")
                floatingWindowView?.setVisible(false)
                hiddenForCapture = true
            } else {
                hiddenForCapture = false
            }
        }

        // 设置截图保护回调：截图后恢复悬浮窗
        screenCaptureManager.onAfterCapture = {
            if (hiddenForCapture) {
                Log.d(TAG, "截图保护：恢复悬浮窗")
                floatingWindowView?.setVisible(true)
                hiddenForCapture = false
            }
        }

        Log.d(TAG, "已绑定浮动窗口和截图保护回调")
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
                description = "手机通话功能运行中，正在使用麦克风和屏幕截图"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                // 设置为前台服务类型
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(false)
                }
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 根据 MediaProjection 状态设置通知内容
        val contentText = if (isMediaProjectionAvailable) {
            "正在使用麦克风录音和屏幕截图"
        } else {
            "正在使用麦克风录音"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手机通话中")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.splash_icon)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.splash_icon))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * 更新通知内容（用于 MediaProjection 状态变化）
     */
    private fun updateNotificationForMediaProjectionState() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 根据 MediaProjection 状态设置通知内容
        val contentText = if (isMediaProjectionAvailable) {
            "正在使用麦克风录音和屏幕截图"
        } else {
            "正在使用麦克风录音"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手机通话中")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.splash_icon)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.splash_icon))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 设置 MediaProjection 可用状态
     */
    fun setMediaProjectionAvailable(available: Boolean) {
        if (isMediaProjectionAvailable != available) {
            isMediaProjectionAvailable = available
            updateNotificationForMediaProjectionState()
            Log.d(TAG, "MediaProjection 可用状态已更新: $available")
        }
    }

    /**
     * 更新通知内容
     */
    fun updateNotification(title: String, content: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.splash_icon)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.splash_icon))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
}
