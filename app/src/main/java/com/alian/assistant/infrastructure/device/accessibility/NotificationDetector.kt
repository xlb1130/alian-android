package com.alian.assistant.infrastructure.device.accessibility

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 通知遮挡检测器
 *
 * 功能：
 * - 检测屏幕顶部是否有通知横幅（Heads-up Notification）
 * - 检测系统级通知（电池、蓝牙、录屏等）
 * - 检测第三方推送通知
 * - 提供通知遮挡状态查询
 *
 * 检测方式：
 * 1. 通过 AccessibilityNodeInfo 检测屏幕顶部可见 UI 元素
 * 2. 检测是否有占据顶部区域的 View
 * 3. 检测通知关键词（"正在输入"、"新消息"、"连接成功"等）
 */
class NotificationDetector(private val accessibilityService: AlianAccessibilityService) {

    companion object {
        private const val TAG = "NotificationDetector"

        // 通知关键词列表
        private val NOTIFICATION_KEYWORDS = listOf(
            "正在输入",
            "新消息",
            "连接成功",
            "连接失败",
            "电量低",
            "正在录音",
            "录屏中",
            "充电中",
            "下载中",
            "安装中",
            "更新中",
            "正在同步",
            "消息提醒",
            "通知",
            "提醒"
        )

        // 状态栏高度阈值（像素）
        private const val STATUS_BAR_HEIGHT_THRESHOLD = 120

        // 最小通知宽度（屏幕宽度的百分比）
        private const val MIN_NOTIFICATION_WIDTH_RATIO = 0.6f

        // 最小通知高度（像素）
        private const val MIN_NOTIFICATION_HEIGHT = 40

        // 检测间隔（毫秒）
        private const val DETECTION_INTERVAL = 500L

        // 通知等待时间（毫秒）
        const val NOTIFICATION_WAIT_TIME = 1500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var detectionRunnable: Runnable? = null
    private var isDetecting = false

    // 通知状态
    private var hasNotification = false
    private var lastNotificationText = ""

    // 屏幕尺寸
    private var screenWidth = 0
    private var screenHeight = 0

    /**
     * 开始检测通知
     */
    fun startDetection() {
        if (isDetecting) {
            Log.d(TAG, "通知检测已在运行")
            return
        }

        isDetecting = true
        detectionRunnable = object : Runnable {
            override fun run() {
                detectNotification()
                handler.postDelayed(this, DETECTION_INTERVAL)
            }
        }
        handler.post(detectionRunnable!!)
        Log.d(TAG, "✓ 通知检测已启动")
    }

    /**
     * 停止检测通知
     */
    fun stopDetection() {
        if (!isDetecting) {
            return
        }

        detectionRunnable?.let {
            handler.removeCallbacks(it)
        }
        detectionRunnable = null
        isDetecting = false
        hasNotification = false
        lastNotificationText = ""
        Log.d(TAG, "✓ 通知检测已停止")
    }

    /**
     * 检测当前是否有通知遮挡
     * @return 是否有通知遮挡
     */
    fun hasNotificationBlocking(): Boolean {
        return hasNotification
    }

    /**
     * 获取最后检测到的通知文本
     * @return 通知文本
     */
    fun getLastNotificationText(): String {
        return lastNotificationText
    }

    /**
     * 执行通知检测
     */
    private fun detectNotification() {
        try {
            val rootNode = accessibilityService.rootNode
            if (rootNode == null) {
                Log.w(TAG, "根节点为空，无法检测通知")
                return
            }

            // 更新屏幕尺寸
            updateScreenSize(rootNode)

            // 检测顶部区域的通知
            val notificationNodes = findNotificationNodes(rootNode)

            if (notificationNodes.isNotEmpty()) {
                hasNotification = true
                lastNotificationText = extractNotificationText(notificationNodes)
                Log.d(TAG, "⚠️ 检测到通知遮挡: $lastNotificationText")
                Log.d(TAG, "   通知节点数量: ${notificationNodes.size}")
            } else {
                hasNotification = false
                lastNotificationText = ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "通知检测异常: ${e.message}")
        }
    }

    /**
     * 查找通知节点
     * @param root 根节点
     * @return 通知节点列表
     */
    private fun findNotificationNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val notificationNodes = mutableListOf<AccessibilityNodeInfo>()

        try {
            // 遍历节点树
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)

            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()

                // 获取节点边界
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                // 检查是否在顶部区域
                if (isInTopArea(bounds)) {
                    // 检查是否是通知节点
                    if (isNotificationNode(node, bounds)) {
                        notificationNodes.add(node)
                    }
                }

                // 添加子节点
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "查找通知节点异常: ${e.message}")
        }

        return notificationNodes
    }

    /**
     * 检查节点是否在顶部区域
     * @param bounds 节点边界
     * @return 是否在顶部区域
     */
    private fun isInTopArea(bounds: Rect): Boolean {
        // 检查 y 坐标是否小于状态栏高度阈值
        return bounds.top < STATUS_BAR_HEIGHT_THRESHOLD
    }

    /**
     * 检查是否是通知节点
     * @param node 节点
     * @param bounds 节点边界
     * @return 是否是通知节点
     */
    private fun isNotificationNode(node: AccessibilityNodeInfo, bounds: Rect): Boolean {
        // 检查高度
        if (bounds.height() < MIN_NOTIFICATION_HEIGHT) {
            return false
        }

        // 检查宽度（至少占屏幕宽度的 60%）
        val minWidth = (screenWidth * MIN_NOTIFICATION_WIDTH_RATIO).toInt()
        if (bounds.width() < minWidth) {
            return false
        }

        // 检查是否包含通知关键词
        val text = node.text?.toString() ?: ""
        val contentDescription = node.contentDescription?.toString() ?: ""
        val nodeText = "$text $contentDescription"

        for (keyword in NOTIFICATION_KEYWORDS) {
            if (nodeText.contains(keyword, ignoreCase = true)) {
                return true
            }
        }

        // 检查节点类名（某些系统通知有特定的类名）
        val className = node.className?.toString() ?: ""
        if (className.contains("Notification", ignoreCase = true) ||
            className.contains("HeadsUp", ignoreCase = true) ||
            className.contains("StatusBar", ignoreCase = true)) {
            return true
        }

        return false
    }

    /**
     * 提取通知文本
     * @param nodes 通知节点列表
     * @return 通知文本
     */
    private fun extractNotificationText(nodes: List<AccessibilityNodeInfo>): String {
        val texts = mutableListOf<String>()

        for (node in nodes) {
            val text = node.text?.toString()
            val contentDescription = node.contentDescription?.toString()

            if (!text.isNullOrEmpty()) {
                texts.add(text)
            }
            if (!contentDescription.isNullOrEmpty() && !texts.contains(contentDescription)) {
                texts.add(contentDescription)
            }
        }

        return texts.joinToString("; ")
    }

    /**
     * 更新屏幕尺寸
     * @param root 根节点
     */
    private fun updateScreenSize(root: AccessibilityNodeInfo) {
        val bounds = Rect()
        root.getBoundsInScreen(bounds)

        if (bounds.width() > 0 && bounds.height() > 0) {
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        }
    }

    /**
     * 手动触发一次通知检测
     * @return 是否有通知遮挡
     */
    fun checkNotificationNow(): Boolean {
        detectNotification()
        return hasNotification
    }
}