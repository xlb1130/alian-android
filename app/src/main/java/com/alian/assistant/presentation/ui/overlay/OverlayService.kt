package com.alian.assistant.presentation.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.alian.assistant.MainActivity
import com.alian.assistant.R
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.infrastructure.ai.tts.HybridTtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 七彩悬浮窗服务 - 显示当前执行步骤
 * 放在屏幕顶部状态栏下方，不影响截图识别
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"

        private var instance: OverlayService? = null
        private var stopCallback: (() -> Unit)? = null
        private var continueCallback: (() -> Unit)? = null
        private var confirmCallback: ((Boolean) -> Unit)? = null  // 敏感操作确认回调
        private var isTakeOverMode = false
        private var isConfirmMode = false  // 敏感操作确认模式
        private var isChatMode = false  // 问答模式（语音打断后）
        private var chatCallback: (() -> Unit)? = null  // 问答模式下的回调

        // 等待 instance 回调队列
        private val pendingCallbacks = mutableListOf<() -> Unit>()

        // 步骤详情相关
        private var currentStepText = ""
        private var currentStepDetail = ""

        // TTS 相关 - 这些值从设置中读取，作为默认值
        private var ttsApiKey = ""
        private var ttsVoice = "longyingmu_v3"
        private var offlineTtsEnabled = false
        private var offlineTtsAutoFallbackToCloud = true

        fun show(context: Context, text: String, onStop: (() -> Unit)? = null) {
            // 检查悬浮窗权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(context)) {
                    Log.e(TAG, "悬浮窗权限未授予，无法显示悬浮窗")
                    stopCallback?.invoke()
                    return
                }
            }

            stopCallback = onStop
            isTakeOverMode = false
            isConfirmMode = false
            instance?.updateText(text) ?: run {
                val intent = Intent(context, OverlayService::class.java).apply {
                    putExtra("text", text)
                }
                ContextCompat.startForegroundService(context, intent)
            }
            instance?.setNormalMode()
        }

        fun hide(context: Context) {
            stopCallback = null
            continueCallback = null
            confirmCallback = null
            chatCallback = null
            isTakeOverMode = false
            isConfirmMode = false
            isChatMode = false
            pendingCallbacks.clear()
            // 停止 TTS
            stopTTS()
            // 只有当 service 已经启动完成时才停止它
            // 否则会导致 ForegroundServiceDidNotStartInTimeException
            if (instance != null) {
                context.stopService(Intent(context, OverlayService::class.java))
            }
        }

        fun update(text: String) {
            instance?.updateText(text)
        }

        /** 更新步骤文本和详情 */
        fun updateStep(stepText: String, detail: String = "") {
            currentStepText = stepText
            currentStepDetail = detail
            instance?.updateStepText()
            // 如果 TTS 启用，播放当前动作描述
            if (instance?.ttsEnabled == true && detail.isNotEmpty()) {
                playTTS(detail)
            }
        }

        /** 设置 TTS 配置 */
        fun setTTSConfig(
            enabled: Boolean,
            apiKey: String = "",
            voice: String = "longyingmu_v3",
            offlineEnabled: Boolean = false,
            offlineAutoFallbackToCloud: Boolean = true
        ) {
            ttsApiKey = apiKey
            ttsVoice = voice
            offlineTtsEnabled = offlineEnabled
            offlineTtsAutoFallbackToCloud = offlineAutoFallbackToCloud
            instance?.overlayView?.post {
                instance?.setTTSEnabled(enabled)
                instance?.updateTTSClient()
            }
        }

        /** 播放 TTS */
        private fun playTTS(text: String) {
            instance?.playTTS(text)
        }

        /** 播放 TTS（带完成回调） */
        fun playTTSWithCallback(text: String, onComplete: () -> Unit) {
            instance?.playTTSWithCallback(text, onComplete)
        }

        /** 退出问答模式，恢复正常模式 */
        fun exitChatMode() {
            isChatMode = false
            chatCallback = null
            instance?.setNormalMode()
        }

        /** 停止 TTS */
        private fun stopTTS() {
            instance?.stopTTS()
        }

        /** 等待 TTS 播放完成后隐藏悬浮窗 */
        suspend fun hideAfterTTS(context: Context) {
            // 等待当前 TTS 播放完成
            if (instance?.isTTSPlaying() == true) {
                withContext(Dispatchers.IO) {
                    delay(100) // 检查间隔
                    while (instance?.isTTSPlaying() == true) {
                        delay(100)
                    }
                }
            }
            // 额外等待 500ms 确保播放完全结束
            delay(500)
            hide(context)
        }

        /** 截图时临时隐藏悬浮窗 */
        fun setVisible(visible: Boolean) {
            instance?.overlayView?.post {
                instance?.overlayView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            }
        }

        /** 显示人机协作模式 - 等待用户手动完成操作 */
        fun showTakeOver(message: String, onContinue: () -> Unit) {
            val action: () -> Unit = {
                Log.d(TAG, "showTakeOver: $message")
                continueCallback = onContinue
                isTakeOverMode = true
                isConfirmMode = false
                instance?.setTakeOverMode(message)
                Unit
            }

            if (instance != null) {
                action()
            } else {
                // 悬浮窗尚未启动，加入等待队列
                Log.d(TAG, "showTakeOver: instance is null, queuing...")
                pendingCallbacks.add(action)
            }
        }

        /** 显示敏感操作确认模式 - 用户确认或取消 */
        fun showConfirm(message: String, onConfirm: (Boolean) -> Unit) {
            val action: () -> Unit = {
                Log.d(TAG, "showConfirm: $message")
                confirmCallback = onConfirm
                isConfirmMode = true
                isTakeOverMode = false
                instance?.setConfirmMode(message)
                Unit
            }

            if (instance != null) {
                action()
            } else {
                // 悬浮窗尚未启动，加入等待队列
                Log.d(TAG, "showConfirm: instance is null, queuing...")
                pendingCallbacks.add(action)
            }
        }

        /** 显示问答模式 - 语音打断后进入对话 */
        fun showChatMode(onChat: () -> Unit) {
            val action: () -> Unit = {
                Log.d(TAG, "showChatMode")
                chatCallback = onChat
                isChatMode = true
                isTakeOverMode = false
                isConfirmMode = false
                instance?.setChatMode()
                Unit
            }

            if (instance != null) {
                action()
            } else {
                // 悬浮窗尚未启动，加入等待队列
                Log.d(TAG, "showChatMode: instance is null, queuing...")
                pendingCallbacks.add(action)
            }
        }

        /** 当 instance 可用时执行等待中的回调 */
        private fun processPendingCallbacks() {
            Log.d(TAG, "processPendingCallbacks: ${pendingCallbacks.size} pending")
            pendingCallbacks.forEach { it.invoke() }
            pendingCallbacks.clear()
        }

        /**
         * 获取悬浮窗的位置和尺寸
         * @return Rect(left, top, right, bottom) 或 null（如果悬浮窗未显示）
         */
        fun getOverlayRect(): Rect? {
            return instance?.overlayView?.let { view ->
                val params = instance?.dragParams ?: instance?.windowParams
                if (params == null) return null

                val width = view.width
                val height = view.height
                val x = params.x
                val y = params.y

                Rect(x, y, x + width, y + height)
            }
        }

        /**
         * 检查操作点是否在悬浮窗附近
         * @param x 操作点 X 坐标
         * @param y 操作点 Y 坐标
         * @param proximityThreshold 附近距离阈值（像素），默认为 100
         * @return true 如果操作点在悬浮窗附近，需要隐藏悬浮窗
         */
        fun isActionNearOverlay(x: Int, y: Int, proximityThreshold: Int = 100): Boolean {
            val rect = getOverlayRect() ?: return false

            // 扩展矩形，包含附近区域
            val expandedRect = Rect(
                rect.left - proximityThreshold,
                rect.top - proximityThreshold,
                rect.right + proximityThreshold,
                rect.bottom + proximityThreshold
            )

            return expandedRect.contains(x, y)
        }

        /**
         * 安全执行操作 - 自动处理悬浮窗隐藏/恢复
         * @param x 操作点 X 坐标
         * @param y 操作点 Y 坐标
         * @param proximityThreshold 附近距离阈值（像素），默认为 100
         * @param action 要执行的操作
         */
        suspend fun executeWithOverlayProtection(
            x: Int? = null,
            y: Int? = null,
            proximityThreshold: Int = 100,
            action: suspend () -> Unit
        ) {
            val shouldHideOverlay = if (x != null && y != null) {
                isActionNearOverlay(x, y, proximityThreshold)
            } else {
                false
            }

            if (shouldHideOverlay) {
                instance?.overlayView?.post {
                    instance?.overlayView?.visibility = View.INVISIBLE
                }
                delay(100) // 等待悬浮窗完全隐藏
            }

            try {
                action()
            } finally {
                if (shouldHideOverlay) {
                    instance?.overlayView?.post {
                        instance?.overlayView?.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null  // 保存悬浮窗布局参数
    private var textView: TextView? = null
    private var actionButton: TextView? = null
    private var cancelButton: TextView? = null  // 确认模式下的取消按钮
    private var divider: View? = null
    private var divider2: View? = null  // 确认模式下第二个分隔线
    private var animator: ValueAnimator? = null

    // 步骤详情相关
    private var stepIndicator: TextView? = null
    private var ttsButton: TextView? = null  // TTS 喇叭按钮

    // 拖动相关
    private var dragParams: WindowManager.LayoutParams? = null

    // TTS 客户端（实例变量，每个服务实例有自己的 TTS 客户端）
    private var ttsClient: HybridTtsClient? = null

    // TTS 播放协程作用域
    private val ttsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 当前 TTS 播放任务
    private var currentTTSJob: Job? = null

    // TTS 播放完成回调
    private var ttsCompletionCallback: (() -> Unit)? = null

    // TTS 是否启用（实例变量，只影响本次执行，不修改设置）
    private var ttsEnabled: Boolean = false

    /** 检查 TTS 是否正在播放 */
    fun isTTSPlaying(): Boolean {
        return currentTTSJob?.isActive == true
    }

    /** 设置 TTS 播放完成后的回调 */
    fun setTTSCompletionCallback(callback: () -> Unit) {
        ttsCompletionCallback = callback
    }

    /** 播放 TTS（带完成回调） */
    fun playTTSWithCallback(text: String, onComplete: () -> Unit) {
        setTTSCompletionCallback(onComplete)
        playTTS(text)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 从设置中读取 TTS 默认值
        val settingsManager = SettingsManager(this)
        val settings = settingsManager.settings.value
        ttsEnabled = settings.ttsEnabled
        ttsApiKey = settings.apiKey
        ttsVoice = settings.ttsVoice
        offlineTtsEnabled = settings.offlineTtsEnabled
        offlineTtsAutoFallbackToCloud = settings.offlineTtsAutoFallbackToCloud

        // 必须第一时间调用 startForeground，否则会崩溃
        startForegroundNotification()

        // 创建悬浮窗（可能因权限问题失败）
        try {
            createOverlayView()
        } catch (e: Exception) {
            Log.e(TAG, "createOverlayView failed: ${e.message}")
        }

        // 处理在 service 启动前排队的回调
        processPendingCallbacks()
    }

    private fun startForegroundNotification() {
        val channelId = "baozi_overlay"
        val channelName = getString(R.string.overlay_channel_name)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.overlay_channel_desc)
                    setShowBadge(false)
                }
                val notificationManager = this.getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle(getString(R.string.overlay_notification_title))
                .setContentText(getString(R.string.overlay_notification_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            // Android 14+ 需要指定前台服务类型
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1001, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundNotification error: ${e.message}")
            // 降级：使用最简单的通知确保 startForeground 被调用
            try {
                val fallbackNotification = NotificationCompat.Builder(this, channelId)
                    .setContentTitle(getString(R.string.overlay_app_name))
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build()
                startForeground(1001, fallbackNotification)
            } catch (e2: Exception) {
                Log.e(TAG, "fallback startForeground also failed: ${e2.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "AutoPilot"
        updateText(text)

        // 确保 ForegroundService 完全启动后再开始麦克风测试
//        ttsScope.launch {
//            kotlinx.coroutines.delay(100)  // 等待前台服务完全启动
//            startMicrophoneTest()
//        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        animator?.cancel()
        overlayView?.let { windowManager?.removeView(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        // 主题颜色 - Indigo 色系
        val primaryColor = Color.parseColor("#6366F1")
        val primaryLight = Color.parseColor("#818CF8")
        val backgroundDark = Color.parseColor("#141414")
        val textPrimary = Color.parseColor("#FAFAFA")
        val textSecondary = Color.parseColor("#B0B0B0")
        val successColor = Color.parseColor("#34D399")
        val errorColor = Color.parseColor("#F87171")

        // 主容器 - 垂直布局
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        // 顶部操作栏容器 - 水平布局
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 14, 20, 14)
        }

        // 毛玻璃渐变背景 - 深色半透明 + Indigo 渐变
        val gradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            colors = intArrayOf(
                Color.parseColor("#CC141414"),  // 深色半透明
                Color.parseColor("#DD1E1E1E")   // 稍浅
            )
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
        }

        // 创建带边框的背景（使用 LayerDrawable 实现毛玻璃+边框效果）
        val borderDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            setStroke(1, Color.parseColor("#406366F1"))  // Indigo 半透明边框
        }

        val layers = arrayOf(gradientDrawable, borderDrawable)
        val layerDrawable = LayerDrawable(layers)
        layerDrawable.setLayerInset(1, 0, 0, 0, 0)  // 边框在最上层
        container.background = layerDrawable

        // TTS 喇叭按钮
        ttsButton = TextView(this).apply {
            text = if (ttsEnabled) "🔊" else "🔇"  // 根据 ttsEnabled 设置初始状态
            textSize = 16f
            setTextColor(if (ttsEnabled) primaryColor else textSecondary)
            gravity = Gravity.CENTER
            setPadding(10, 6, 10, 6)
            setShadowLayer(4f, 0f, 1f, Color.parseColor("#60000000"))
            isClickable = true

            // 按钮背景（半透明）
            val ttsBtnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                colors = intArrayOf(
                    Color.parseColor("#40FFFFFF"),
                    Color.parseColor("#20FFFFFF")
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            background = ttsBtnBg

            setOnClickListener {
                toggleTTS()
            }
        }
        container.addView(ttsButton)

        // 第一个分隔线
        val ttsDividerParams = LinearLayout.LayoutParams(1, 28).apply {
            setMargins(14, 0, 14, 0)
        }
        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#30FFFFFF"))
        }, ttsDividerParams)

        // 状态文字 - 不带箭头
        textView = TextView(this).apply {
            text = getString(R.string.overlay_app_name)
            textSize = 13.5f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setPadding(14, 6, 14, 6)
            setShadowLayer(6f, 0f, 2f, Color.parseColor("#80000000"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
            // 设置最大宽度，防止长文本挤压按钮
            maxWidth = 560
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        }
        container.addView(textView)

        // 第二个分隔线 - 更精致的样式
        divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#30FFFFFF"))
        }
        val dividerParams = LinearLayout.LayoutParams(1, 28).apply {
            setMargins(14, 0, 14, 0)
        }
        container.addView(divider, dividerParams)

        // 动作按钮（停止/继续/确认）- 现代圆角按钮
        actionButton = TextView(this).apply {
            text = getString(R.string.overlay_stop)
            textSize = 12.5f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setPadding(14, 6, 14, 6)
            setShadowLayer(4f, 0f, 1f, Color.parseColor("#60000000"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.03f

            // 按钮背景
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                colors = intArrayOf(
                    Color.parseColor("#E06366F1"),
                    Color.parseColor("#D04F46E5")
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            background = btnBg

            setOnClickListener {
                when {
                    isChatMode -> {
                        chatCallback?.invoke()
                        chatCallback = null
                        isChatMode = false
                        setNormalMode()
                    }
                    isConfirmMode -> {
                        confirmCallback?.invoke(true)
                        confirmCallback = null
                        isConfirmMode = false
                        setNormalMode()
                    }
                    isTakeOverMode -> {
                        continueCallback?.invoke()
                        continueCallback = null
                        isTakeOverMode = false
                        setNormalMode()
                    }
                    else -> {
                        stopCallback?.invoke()
                        hide(this@OverlayService)
                    }
                }
            }
        }
        container.addView(actionButton)

        // 第二个分隔线（确认模式用）
        divider2 = View(this).apply {
            setBackgroundColor(Color.parseColor("#30FFFFFF"))
            visibility = View.GONE
        }
        val divider2Params = LinearLayout.LayoutParams(1, 28).apply {
            setMargins(14, 0, 14, 0)
        }
        container.addView(divider2, divider2Params)

        // 取消按钮（确认模式用）- 红色警告样式
        cancelButton = TextView(this).apply {
            text = getString(R.string.overlay_cancel)
            textSize = 12.5f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setPadding(14, 6, 14, 6)
            setShadowLayer(4f, 0f, 1f, Color.parseColor("#60000000"))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.03f
            visibility = View.GONE

            // 红色按钮背景
            val cancelBtnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                colors = intArrayOf(
                    Color.parseColor("#E0F87171"),
                    Color.parseColor("#D0EF4444")
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            background = cancelBtnBg

            setOnClickListener {
                if (isConfirmMode) {
                    confirmCallback?.invoke(false)
                    confirmCallback = null
                    isConfirmMode = false
                    setNormalMode()
                }
            }
        }
        container.addView(cancelButton)

        // 将操作栏添加到主容器
        mainContainer.addView(container)

        // 微妙的呼吸动画效果
        startBreathingAnimation(mainContainer)

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        // 保存 params 到成员变量，以便在 lambda 中修改
        dragParams = params

        // 添加拖动功能
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val dragThreshold = 20f

        // 使用 GestureDetector 来处理拖动
        val gestureDetector =
            GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    initialX = dragParams?.x ?: 0
                    initialY = dragParams?.y ?: 0
                    initialTouchX = e.rawX
                    initialTouchY = e.rawY
                    isDragging = false
                    Log.d(TAG, "GestureDetector onDown")
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    val deltaX = e2.rawX - initialTouchX
                    val deltaY = e2.rawY - initialTouchY
                    if (abs(deltaX) > dragThreshold || abs(deltaY) > dragThreshold) {
                        isDragging = true
                        Log.d(TAG, "GestureDetector onScroll, isDragging: true")
                    }
                    if (isDragging) {
                        dragParams?.x = initialX + deltaX.toInt()
                        dragParams?.y = initialY + deltaY.toInt()
                        windowManager?.updateViewLayout(mainContainer, dragParams)
                    }
                    return true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    Log.d(TAG, "GestureDetector onSingleTapUp")
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    Log.d(TAG, "GestureDetector onSingleTapConfirmed - no action (removed expand)")
                    return true
                }
            })

        textView?.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        overlayView = mainContainer
        windowParams = params
        windowManager?.addView(overlayView, params)

        // 初始化 TTS 客户端
        updateTTSClient()
    }

    private fun startBreathingAnimation(view: View) {
        // 呼吸动画 - 微妙的透明度变化
        animator = ValueAnimator.ofFloat(0.9f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE

            addUpdateListener { animation ->
                val alpha = animation.animatedValue as Float
                view.alpha = alpha
            }
            start()
        }
    }

    private fun updateText(text: String) {
        currentStepText = text
        updateStepText()
    }

    private fun updateStepText() {
        textView?.post {
            // 限制显示的文本长度，避免按钮样式错乱
            val maxDisplayLength = 15
            val displayText = if (currentStepText.length > maxDisplayLength) {
                "${currentStepText.take(maxDisplayLength)}..."
            } else {
                currentStepText
            }
            textView?.text = displayText
        }
    }

    /** 更新 TTS 按钮 */
    private fun updateTTSButton() {
        ttsButton?.post {
            ttsButton?.text = if (ttsEnabled) "🔊" else "🔇"
            val primaryColor = Color.parseColor("#6366F1")
            val textSecondary = Color.parseColor("#B0B0B0")
            ttsButton?.setTextColor(if (ttsEnabled) primaryColor else textSecondary)
        }
    }

    /** 设置 TTS 启用状态（从设置读取初始值） */
    fun setTTSEnabled(enabled: Boolean) {
        ttsEnabled = enabled
        updateTTSButton()
    }

    /** 切换 TTS 开关（只影响本次执行，不修改设置） */
    private fun toggleTTS() {
        ttsEnabled = !ttsEnabled
        updateTTSButton()
    }

    /** 更新 TTS 客户端 */
    private fun updateTTSClient() {
        ttsClient?.release()
        ttsClient = null
        if (ttsEnabled && (offlineTtsEnabled || ttsApiKey.isNotEmpty())) {
            ttsClient = HybridTtsClient(
                appContext = this,
                apiKey = ttsApiKey,
                voice = ttsVoice,
                offlineTtsEnabled = offlineTtsEnabled,
                offlineTtsAutoFallbackToCloud = offlineTtsAutoFallbackToCloud
            )
            Log.d(
                TAG,
                "TTS客户端已初始化: voice=$ttsVoice, offlineTtsEnabled=$offlineTtsEnabled"
            )
        } else {
            ttsClient = null
            Log.d(TAG, "TTS客户端已禁用")
        }
    }

    /** 播放 TTS */
    private fun playTTS(text: String) {
        if (!ttsEnabled || ttsClient == null || text.isEmpty()) {
            return
        }

        // 取消之前的 TTS 播放
        currentTTSJob?.cancel()
        ttsClient?.cancelPlayback()

        currentTTSJob = ttsScope.launch {
            try {
                Log.d(TAG, "播放 TTS: $text")
                ttsClient?.synthesizeAndPlay(text, this@OverlayService)
                // 播放完成后调用回调
                ttsCompletionCallback?.invoke()
                ttsCompletionCallback = null
            } catch (e: Exception) {
                Log.e(TAG, "TTS 播放失败: ${e.message}")
                // 即使失败也调用回调
                ttsCompletionCallback?.invoke()
                ttsCompletionCallback = null
            }
        }
    }

    /** 停止 TTS */
    private fun stopTTS() {
        currentTTSJob?.cancel()
        currentTTSJob = null
        ttsClient?.cancelPlayback()
        ttsScope.coroutineContext.cancelChildren()
        Log.d(TAG, "TTS 已停止")
    }

    /** 切换到人机协作模式 */
    private fun setTakeOverMode(message: String) {
        Log.d(TAG, "setTakeOverMode: $message")
        overlayView?.post {
            overlayView?.visibility = View.VISIBLE

            // 限制消息长度，确保不超过一行的一半（约20个字符）
            val maxDisplayLength = 20
            val displayMessage = if (message.length > maxDisplayLength) {
                "${message.take(maxDisplayLength)}..."
            } else {
                message
            }
            textView?.text = "👆 $displayMessage"

            actionButton?.text = getString(R.string.overlay_continue)
            actionButton?.visibility = View.VISIBLE  // 确保按钮可见

            // 绿色按钮背景
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                colors = intArrayOf(
                    Color.parseColor("#E034D399"),
                    Color.parseColor("#D010B981")
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            actionButton?.background = btnBg

            divider2?.visibility = View.GONE
            cancelButton?.visibility = View.GONE
            Log.d(TAG, "setTakeOverMode 完成: actionButton可见=${actionButton?.visibility}")
        }
    }

    /** 切换到正常模式 */
    private fun setNormalMode() {
        Log.d(TAG, "setNormalMode")
        overlayView?.post {
            actionButton?.text = getString(R.string.overlay_stop)
            actionButton?.visibility = View.VISIBLE  // 确保按钮可见

            // Indigo 按钮背景
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                colors = intArrayOf(
                    Color.parseColor("#E06366F1"),
                    Color.parseColor("#D04F46E5")
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            actionButton?.background = btnBg

            divider2?.visibility = View.GONE
            cancelButton?.visibility = View.GONE
        }
    }

    /** 切换到敏感操作确认模式 */
    private fun setConfirmMode(message: String) {
        Log.d(TAG, "setConfirmMode: $message")
        overlayView?.post {
            overlayView?.visibility = View.VISIBLE

            // 限制消息长度，确保按钮有足够空间显示
            val maxDisplayLength = 15
            val displayMessage = if (message.length > maxDisplayLength) {
                "${message.take(maxDisplayLength)}..."
            } else {
                message
            }
            textView?.text = "⚠️ $displayMessage"

            actionButton?.text = getString(R.string.overlay_confirm)
            actionButton?.visibility = View.VISIBLE  // 确保按钮可见

            // 绿色确认按钮背景
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                colors = intArrayOf(
                    Color.parseColor("#E034D399"),
                    Color.parseColor("#D010B981")
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            actionButton?.background = btnBg

            divider2?.visibility = View.VISIBLE
            cancelButton?.visibility = View.VISIBLE
        }
    }

    /** 切换到问答模式 - 语音打断后进入对话 */
    private fun setChatMode() {
        Log.d(TAG, "setChatMode")
        overlayView?.post {
            overlayView?.visibility = View.VISIBLE

            textView?.text = getString(R.string.overlay_chat_mode)

            actionButton?.text = getString(R.string.overlay_chat)
            actionButton?.visibility = View.VISIBLE  // 确保按钮可见

            // 紫色按钮背景
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f
                colors = intArrayOf(
                    Color.parseColor("#E08B5CF6"),
                    Color.parseColor("#D07C3AED")
                )
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            actionButton?.background = btnBg

            divider2?.visibility = View.GONE
            cancelButton?.visibility = View.GONE
            Log.d(TAG, "setChatMode 完成: actionButton可见=${actionButton?.visibility}")
        }
    }
}
