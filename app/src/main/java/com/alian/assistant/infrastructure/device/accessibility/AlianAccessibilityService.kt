package com.alian.assistant.infrastructure.device.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import java.util.ArrayDeque

/**
 * 艾莲无障碍服务
 *
 * 功能:
 * 1. 获取屏幕元素树 (AccessibilityNodeInfo)
 * 2. 查找元素 (文本/描述/资源 ID)
 * 3. 执行无障碍手势 (点击/滑动/长按)
 * 4. 监听屏幕变化
 */
class AlianAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "AlianAccessibilityService"

        @Volatile
        private var instance: AlianAccessibilityService? = null

        // 滚动方向常量
        const val SCROLL_FORWARD = 1
        const val SCROLL_BACKWARD = -1

        /**
         * 获取服务实例
         */
        fun getInstance(): AlianAccessibilityService? = instance

        /**
         * 检查服务是否已连接
         */
        fun isConnected(): Boolean = instance != null
    }

    // 通知检测器
    private var notificationDetector: NotificationDetector? = null

    /**
     * 获取当前根节点
     */
    val rootNode: AccessibilityNodeInfo?
        get() = rootInActiveWindow

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ 无障碍服务已连接")

        // 初始化并启动通知检测
        notificationDetector = NotificationDetector(this)
        notificationDetector?.startDetection()
        Log.d(TAG, "✅ 通知检测已启动")
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // 停止通知检测
        notificationDetector?.stopDetection()
        notificationDetector = null
        
        instance = null
        Log.d(TAG, "❌ 无障碍服务已断开")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 监听屏幕变化事件
        event?.let {
            when (it.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.d(TAG, "窗口状态变化: ${it.packageName}")
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // 内容变化 (频繁触发,可选择性处理)
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    Log.d(TAG, "视图被点击: ${it.contentDescription}")
                }
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    // 通知状态变化
                    val text = if (it.text.isNotEmpty()) it.text[0].toString() else ""
                    Log.d(TAG, "⚠️ 通知状态变化: $text")
                }
                else -> {
                    // 其他事件类型,忽略
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
    }

    // ========== 元素查找方法 ==========

    /**
     * 通过文本查找元素
     *
     * @param text 文本内容
     * @param exactMatch 是否精确匹配 (默认 false, 包含即可)
     * @return 匹配的元素列表
     */
    fun findByText(text: String, exactMatch: Boolean = false): List<AccessibilityNodeInfo> {
        val root = rootNode ?: return emptyList()

        val results = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            // 检查当前节点
            val nodeText = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""

            val isMatch = if (exactMatch) {
                nodeText == text || contentDesc == text
            } else {
                nodeText.contains(text, ignoreCase = true) ||
                contentDesc.contains(text, ignoreCase = true)
            }

            if (isMatch) {
                results.add(node)
            }

            // 遍历子节点
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }

        Log.d(TAG, "通过文本查找 '$text': 找到 ${results.size} 个结果")
        return results
    }

    /**
     * 通过文本查找元素 (返回第一个匹配)
     */
    fun findFirstByText(text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        return findByText(text, exactMatch).firstOrNull()
    }

    /**
     * 通过内容描述查找元素
     */
    fun findByContentDescription(description: String): AccessibilityNodeInfo? {
        val root = rootNode ?: return null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (node.contentDescription?.toString()?.equals(description, ignoreCase = true) == true) {
                Log.d(TAG, "找到内容描述匹配: $description")
                return node
            }

            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }

        Log.d(TAG, "未找到内容描述: $description")
        return null
    }

    /**
     * 通过资源 ID 查找元素
     */
    fun findByResourceId(resourceId: String): AccessibilityNodeInfo? {
        val root = rootNode ?: return null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (node.viewIdResourceName?.contains(resourceId) == true) {
                Log.d(TAG, "找到资源 ID 匹配: $resourceId")
                return node
            }

            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }

        Log.d(TAG, "未找到资源 ID: $resourceId")
        return null
    }

    /**
     * 通过视图 ID 查找元素 (自定义 ID)
     */
    fun findByViewId(viewId: String): AccessibilityNodeInfo? {
        val root = rootNode ?: return null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            // viewIdResourceName 格式: "包名:id/视图ID"
            if (node.viewIdResourceName?.endsWith(":id/$viewId") == true) {
                Log.d(TAG, "找到视图 ID 匹配: $viewId")
                return node
            }

            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }

        Log.d(TAG, "未找到视图 ID: $viewId")
        return null
    }

    /**
     * 查找所有可点击的元素
     */
    fun findAllClickable(): List<AccessibilityNodeInfo> {
        val root = rootNode ?: return emptyList()

        val results = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (node.isClickable) {
                results.add(node)
            }

            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }

        Log.d(TAG, "找到 ${results.size} 个可点击元素")
        return results
    }

    /**
     * 查找所有可编辑的元素 (输入框)
     */
    fun findAllEditable(): List<AccessibilityNodeInfo> {
        val root = rootNode ?: return emptyList()

        val results = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (node.isEditable) {
                results.add(node)
            }

            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }

        Log.d(TAG, "找到 ${results.size} 个可编辑元素")
        return results
    }

    /**
     * 在指定区域内查找元素
     */
    fun findByBounds(rect: Rect): List<AccessibilityNodeInfo> {
        val root = rootNode ?: return emptyList()

        val results = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val nodeBounds = Rect()
            node.getBoundsInScreen(nodeBounds)

            if (Rect.intersects(rect, nodeBounds)) {
                results.add(node)
            }

            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }

        Log.d(TAG, "在区域 $rect 内找到 ${results.size} 个元素")
        return results
    }

    // ========== 元素操作方法 ==========

    /**
     * 点击元素
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) {
            Log.w(TAG, "元素不可点击: ${node.text}")
            return false
        }

        // 添加诊断信息
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val isVisible = node.isVisibleToUser
        val isEnabled = node.isEnabled
        val isFocusable = node.isFocusable

        Log.d(TAG, "元素诊断: ${node.text}")
        Log.d(TAG, "  - 可点击: ${node.isClickable}")
        Log.d(TAG, "  - 可见: $isVisible")
        Log.d(TAG, "  - 启用: $isEnabled")
        Log.d(TAG, "  - 可聚焦: $isFocusable")
        Log.d(TAG, "  - 边界: $bounds")

        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "点击元素: ${node.text}, 结果: $result")

        if (!result) {
            Log.w(TAG, "点击失败，可能原因:")
            if (!isVisible) Log.w(TAG, "  - 元素不可见")
            if (!isEnabled) Log.w(TAG, "  - 元素被禁用")
            if (!isFocusable) Log.w(TAG, "  - 元素不可聚焦")
        }

        return result
    }

    /**
     * 长按元素
     */
    fun longClickNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) {
            Log.w(TAG, "元素不可点击: ${node.text}")
            return false
        }

        val result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        Log.d(TAG, "长按元素: ${node.text}, 结果: $result")
        return result
    }

    /**
     * 设置文本 (输入框)
     */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) {
            Log.w(TAG, "元素不可编辑: ${node.text}")
            return false
        }

        val currentText = node.text?.toString() ?: ""
        Log.d(TAG, "setText: 当前文本 '$currentText', 目标文本 '$text'")

        // 方法1: 直接使用 ACTION_SET_TEXT
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }

        val setResult = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.d(TAG, "setText: ACTION_SET_TEXT 结果: $setResult")

        if (setResult) {
            // 验证是否真的设置成功
            Thread.sleep(100)
            val afterText = node.text?.toString() ?: ""
            val verified = afterText.contains(text) || afterText == text
            Log.d(TAG, "setText: 验证结果 $verified, 期望 '$text', 实际 '$afterText'")
            if (verified) return true
        }

        // 方法2: 如果 ACTION_SET_TEXT 失败，尝试选中文本再粘贴
        Log.d(TAG, "setText: ACTION_SET_TEXT 失败，尝试选中文本再粘贴")
        return setTextViaSelectionAndPaste(node, text, currentText)
    }

    /**
     * 通过选中文本再粘贴的方式设置文本
     */
    private fun setTextViaSelectionAndPaste(
        node: AccessibilityNodeInfo,
        text: String,
        currentText: String
    ): Boolean {
        try {
            // 1. 聚焦元素
            focusNode(node)
            Thread.sleep(100)

            // 2. 如果有现有文本，全选
            if (currentText.isNotEmpty()) {
                val selectionArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentText.length)
                }
                val selectionResult = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
                Log.d(TAG, "setTextViaSelectionAndPaste: 选中文本结果: $selectionResult")
                Thread.sleep(50)
            }

            // 3. 执行粘贴
            val pasteResult = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.d(TAG, "setTextViaSelectionAndPaste: 粘贴结果: $pasteResult")
            Thread.sleep(200)

            // 4. 验证
            val afterText = node.text?.toString() ?: ""
            val verified = afterText.contains(text)
            Log.d(TAG, "setTextViaSelectionAndPaste: 验证结果 $verified, 期望 '$text', 实际 '$afterText'")

            return verified
        } catch (e: Exception) {
            Log.e(TAG, "setTextViaSelectionAndPaste: 异常: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * 聚焦元素
     */
    fun focusNode(node: AccessibilityNodeInfo): Boolean {
        val result = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Log.d(TAG, "聚焦元素: ${node.text}, 结果: $result")
        return result
    }

    /**
     * 发送全局按键事件（通过 ADB 命令）
     * @param keycode 按键代码，例如 KeyEvent.KEYCODE_PASTE = 279
     */
    fun dispatchKeyEvent(keycode: Int): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent $keycode"))
            val exitCode = process.waitFor()
            val success = exitCode == 0
            Log.d(TAG, "发送按键事件: keycode=$keycode, 结果=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "发送按键事件失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 滚动元素
     */
    fun scrollNode(node: AccessibilityNodeInfo, direction: Int): Boolean {
        val action = when (direction) {
            SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return false
        }

        val result = node.performAction(action)
        Log.d(TAG, "滚动元素: ${node.text}, 方向: $direction, 结果: $result")
        return result
    }

    // ========== 手势操作方法 ==========

    /**
     * 执行点击手势
     */
    fun performClick(x: Int, y: Int, duration: Long = 100): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val result = dispatchGesture(gesture, null, null)
        Log.d(TAG, "执行点击手势: ($x, $y), 结果: $result")
        return result
    }

    /**
     * 执行长按手势
     */
    fun performLongPress(x: Int, y: Int, duration: Long = 500): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        val result = dispatchGesture(gesture, null, null)
        Log.d(TAG, "执行长按手势: ($x, $y), 结果: $result")
        return result
    }

    /**
     * 执行滑动手势
     * @param startX 起点X坐标
     * @param startY 起点Y坐标
     * @param endX 终点X坐标
     * @param endY 终点Y坐标
     * @param duration 基础滑动持续时间（毫秒），会被 velocity 影响
     * @param velocity 滑动力度/速度，范围 0.0-1.0，默认 0.5
     *                 - 1.0: 最快（轻滑），duration 会被缩短到 50%
     *                 - 0.5: 中等速度，duration 不变
     *                 - 0.0: 最慢（重滑），duration 会被延长到 200%
     */
    fun performSwipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        duration: Long = 300,
        velocity: Float = 0.5f
    ): Boolean {
        // 根据力度计算实际持续时间
        // velocity 0.0 -> actualDuration = duration * 2.0 (最慢)
        // velocity 0.5 -> actualDuration = duration (中等)
        // velocity 1.0 -> actualDuration = duration * 0.5 (最快)
        val velocityFactor = 1.5f - velocity  // 0.0->1.5, 0.5->1.0, 1.0->0.5
        val actualDuration = (duration * velocityFactor).toLong().coerceIn(50L, 3000L)

        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, actualDuration))
            .build()

        val result = dispatchGesture(gesture, null, null)
        Log.d(TAG, "执行滑动手势: ($startX,$startY) -> ($endX,$endY), duration: $actualDuration ms (velocity: $velocity), 结果: $result")
        return result
    }

    /**
     * 执行手势 (带回调)
     */
    fun performGestureWithCallback(
        gesture: GestureDescription,
        onComplete: () -> Unit = {},
        onCancel: () -> Unit = {}
    ): Boolean {
        val callback = InternalGestureCallback(onComplete, onCancel)
        val handler = Handler(Looper.getMainLooper())
        return dispatchGesture(gesture, callback, handler)
    }

    // ========== 辅助方法 ==========

    /**
     * 打印元素树 (调试用)
     */
    fun printNodeTree(node: AccessibilityNodeInfo? = null, depth: Int = 0) {
        val root = node ?: rootNode ?: return

        val indent = "  ".repeat(depth)
        val bounds = Rect()
        root.getBoundsInScreen(bounds)

        Log.d(TAG, "$indent├─ ${root.text ?: root.contentDescription ?: "无文本"} " +
                    "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] " +
                    "clickable=${root.isClickable} editable=${root.isEditable}")

        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { printNodeTree(it, depth + 1) }
            printNodeTree(root.getChild(i), depth + 1)
        }
    }


    // ========== 通知检测方法 ==========

    /**
     * 检查是否有通知遮挡
     * @return 是否有通知遮挡
     */
    fun hasNotificationBlocking(): Boolean {
        return notificationDetector?.hasNotificationBlocking() ?: false
    }

    /**
     * 获取最后检测到的通知文本
     * @return 通知文本
     */
    fun getLastNotificationText(): String {
        return notificationDetector?.getLastNotificationText() ?: ""
    }

    /**
     * 手动触发一次通知检测
     * @return 是否有通知遮挡
     */
    fun checkNotificationNow(): Boolean {
        return notificationDetector?.checkNotificationNow() ?: false
    }

    /**
     * 等待通知消失
     * @param maxWaitTime 最大等待时间（毫秒）
     * @return 是否在等待时间内通知消失
     */
    suspend fun waitForNotificationClear(maxWaitTime: Long = NotificationDetector.NOTIFICATION_WAIT_TIME): Boolean {
        if (!hasNotificationBlocking()) {
            return true
        }

        Log.d(TAG, "⚠️ 检测到通知遮挡，等待通知消失...")
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < maxWaitTime) {
            if (!hasNotificationBlocking()) {
                Log.d(TAG, "✓ 通知已消失")
                return true
            }
            delay(100)
        }

        Log.w(TAG, "⚠️ 等待超时，通知仍然存在")
        return false
    }

    /**
     * 内部手势回调
     * 注意: 在较新的 Android API 中,GestureResultCallback 可能不可用
     */
    private class InternalGestureCallback(
        private val onComplete: () -> Unit,
        private val onCancel: () -> Unit
    ) : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            onComplete()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            onCancel()
        }
    }
}