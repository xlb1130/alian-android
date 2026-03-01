package com.alian.assistant.infrastructure.device

import android.graphics.Bitmap
import android.util.Log
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 屏幕截图帧
 * @param bitmap 截图位图
 * @param timestamp 截图时间戳
 * @param trigger 触发源：periodic(定期捕获)、speech(语音触发)、initial(初始捕获)、manual(手动触发)
 */
data class ScreenFrame(
    val bitmap: Bitmap,
    val timestamp: Long = System.currentTimeMillis(),
    val trigger: String = "periodic"
)

/**
 * 屏幕捕获管理器
 *
 * 负责在后台持续抓取手机屏幕内容，维护截图历史队列。
 * 支持两种捕获模式：
 * - 低频模式（空闲时）：每 IDLE_CAPTURE_INTERVAL_MS 毫秒截图一次
 * - 高频模式（用户说话时）：每 SPEAKING_CAPTURE_INTERVAL_MS 毫秒截图一次
 *
 * 截图前会通过 onBeforeCapture 回调隐藏浮动窗口，截图后通过 onAfterCapture 恢复，
 * 避免自身 UI 出现在截图中（参考 MobileAgentImprove 的悬浮窗保护机制）。
 */
class ScreenCaptureManager(
    private val controller: IDeviceController
) {
    companion object {
        private const val TAG = "ScreenCaptureManager"

        private const val IDLE_CAPTURE_INTERVAL_MS = 3000L
        private const val SPEAKING_CAPTURE_INTERVAL_MS = 1000L
        private const val SPEECH_TIMEOUT_MS = 3000L
        private const val OVERLAY_HIDE_DELAY_MS = 100L
        private const val MAX_IMAGE_HISTORY_SIZE = 10
        private const val INITIAL_FRAME_WAIT_TIMEOUT_MS = 10000L
    }

    private val captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null

    private val imageHistoryLock = Any()
    private val _imageHistory = mutableListOf<ScreenFrame>()

    /** 当前图像历史的只读快照 */
    val imageHistory: List<ScreenFrame>
        get() = synchronized(imageHistoryLock) { _imageHistory.toList() }

    /** 最新一帧截图 */
    val latestFrame: ScreenFrame?
        get() = synchronized(imageHistoryLock) { _imageHistory.lastOrNull() }

    /** 当前捕获间隔（动态调整） */
    @Volatile
    private var currentCaptureInterval = IDLE_CAPTURE_INTERVAL_MS

    /** 是否正在说话 */
    @Volatile
    private var isSpeaking = false

    /** 上次语音活动时间 */
    @Volatile
    private var lastSpeechTime = 0L

    /** 是否已停止 */
    @Volatile
    private var isStopped = true

    /** 截图前回调（用于隐藏浮动窗口） */
    var onBeforeCapture: (() -> Unit)? = null

    /** 截图后回调（用于恢复浮动窗口） */
    var onAfterCapture: (() -> Unit)? = null

    /**
     * 启动持续屏幕捕获
     */
    fun start() {
        if (!isStopped) {
            Log.w(TAG, "屏幕捕获已在运行中")
            return
        }

        Log.d(TAG, "启动屏幕捕获")
        isStopped = false
        isSpeaking = false
        lastSpeechTime = System.currentTimeMillis()
        currentCaptureInterval = IDLE_CAPTURE_INTERVAL_MS

        synchronized(imageHistoryLock) {
            _imageHistory.forEach { frame ->
                if (!frame.bitmap.isRecycled) {
                    frame.bitmap.recycle()
                }
            }
            _imageHistory.clear()
        }

        captureJob = captureScope.launch {
            runCaptureLoop()
        }
    }

    /**
     * 停止屏幕捕获并释放资源
     */
    fun stop() {
        Log.d(TAG, "停止屏幕捕获")
        isStopped = true
        captureJob?.cancel()
        captureJob = null

        synchronized(imageHistoryLock) {
            _imageHistory.forEach { frame ->
                if (!frame.bitmap.isRecycled) {
                    frame.bitmap.recycle()
                }
            }
            _imageHistory.clear()
        }
    }

    /**
     * 通知语音活动开始（切换到高频捕获模式）
     */
    fun onSpeechActivityDetected() {
        Log.d(TAG, "检测到语音活动，切换到高频捕获模式")
        isSpeaking = true
        lastSpeechTime = System.currentTimeMillis()
        currentCaptureInterval = SPEAKING_CAPTURE_INTERVAL_MS

        // 立即触发一次捕获
        captureScope.launch {
            captureFrame(trigger = "speech")
        }
    }

    /**
     * 通知语音活动结束（切换到低频捕获模式）
     */
    fun onSpeechActivityEnd() {
        Log.d(TAG, "语音活动结束，切换到低频捕获模式")
        isSpeaking = false
        currentCaptureInterval = IDLE_CAPTURE_INTERVAL_MS
    }

    /**
     * 手动触发一次截图
     */
    fun captureNow() {
        captureScope.launch {
            captureFrame(trigger = "manual")
        }
    }

    /**
     * 获取最近 N 帧截图
     */
    fun getRecentFrames(count: Int): List<ScreenFrame> {
        return synchronized(imageHistoryLock) {
            _imageHistory.takeLast(count).map { frame ->
                ScreenFrame(
                    bitmap = frame.bitmap.copy(frame.bitmap.config ?: Bitmap.Config.ARGB_8888, false),
                    timestamp = frame.timestamp,
                    trigger = frame.trigger
                )
            }
        }
    }

    /**
     * 持续捕获循环
     */
    private suspend fun runCaptureLoop() {
        Log.d(TAG, "开始捕获循环")

        // 先执行一次初始捕获
        captureFrame(trigger = "initial")

        while (!isStopped) {
            try {
                // 检查语音超时
                if (isSpeaking) {
                    val timeSinceLastSpeech = System.currentTimeMillis() - lastSpeechTime
                    if (timeSinceLastSpeech > SPEECH_TIMEOUT_MS) {
                        onSpeechActivityEnd()
                    }
                }

                delay(currentCaptureInterval)

                if (!isStopped) {
                    captureFrame(trigger = "periodic")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "捕获循环被取消")
                break
            } catch (e: Exception) {
                Log.e(TAG, "捕获循环异常", e)
                delay(currentCaptureInterval)
            }
        }

        Log.d(TAG, "捕获循环结束")
    }

    /**
     * 执行一次屏幕截图
     * @param trigger 触发源
     */
    private suspend fun captureFrame(trigger: String) {
        if (isStopped) return

        try {
            withContext(Dispatchers.Main) {
                onBeforeCapture?.invoke()
            }

            delay(OVERLAY_HIDE_DELAY_MS)

            val screenshotResult = withContext(Dispatchers.IO) {
                controller.screenshotWithFallback()
            }

            withContext(Dispatchers.Main) {
                onAfterCapture?.invoke()
            }

            if (screenshotResult.isFallback) {
                Log.w(TAG, "截图失败（降级），跳过此帧 [$trigger]")
                return
            }

            if (screenshotResult.isSensitive) {
                Log.w(TAG, "检测到敏感页面，跳过此帧 [$trigger]")
                return
            }

            val bitmap = screenshotResult.bitmap
            val frame = ScreenFrame(
                bitmap = bitmap,
                timestamp = System.currentTimeMillis(),
                trigger = trigger
            )

            synchronized(imageHistoryLock) {
                _imageHistory.add(frame)

                // 限制历史记录数量，回收超出的 Bitmap
                while (_imageHistory.size > MAX_IMAGE_HISTORY_SIZE) {
                    val oldFrame = _imageHistory.removeAt(0)
                    if (!oldFrame.bitmap.isRecycled) {
                        oldFrame.bitmap.recycle()
                    }
                }
            }

            Log.d(TAG, "帧捕获完成 [$trigger]: ${bitmap.width}x${bitmap.height}, 历史数量: ${_imageHistory.size}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "帧捕获失败 [$trigger]", e)
            // 确保恢复浮动窗口
            try {
                withContext(Dispatchers.Main) {
                    onAfterCapture?.invoke()
                }
            } catch (restoreError: Exception) {
                Log.e(TAG, "恢复浮动窗口失败", restoreError)
            }
        }
    }
}
