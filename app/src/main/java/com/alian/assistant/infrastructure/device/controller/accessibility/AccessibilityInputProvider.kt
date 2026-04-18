@file:Suppress("DEPRECATION")

package com.alian.assistant.infrastructure.device.controller.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.alian.assistant.infrastructure.device.accessibility.AlianAccessibilityService
import com.alian.assistant.infrastructure.device.controller.interfaces.IInputProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍输入提供者实现
 * 
 * 使用无障碍服务 API 进行输入操作
 */
class AccessibilityInputProvider(
    private val context: Context
) : IInputProvider {
    
    companion object {
        private const val TAG = "AccessibilityInputProvider"
    }
    
    private val accessibilityService: AlianAccessibilityService?
        get() = AlianAccessibilityService.getInstance()
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipboardManager: ClipboardManager? by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    
    override fun tap(x: Int, y: Int) {
        Log.d(TAG, "tap: ($x, $y)")
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "tap: AccessibilityService not available")
            return
        }
        
        val result = service.performClick(x, y, 100)
        Log.d(TAG, "tap: ($x, $y), result: $result")
    }
    
    override fun longPress(x: Int, y: Int, durationMs: Int) {
        Log.d(TAG, "longPress: ($x, $y), duration: ${durationMs}ms")
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "longPress: AccessibilityService not available")
            return
        }
        
        val result = service.performLongPress(x, y, durationMs.toLong())
        Log.d(TAG, "longPress: ($x, $y), result: $result")
    }
    
    override fun doubleTap(x: Int, y: Int) {
        Log.d(TAG, "doubleTap: ($x, $y)")
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "doubleTap: AccessibilityService not available")
            return
        }
        
        // 执行两次点击
        service.performClick(x, y, 100)
        Handler(Looper.getMainLooper()).postDelayed({
            service.performClick(x, y, 100)
        }, 100)
        
        Log.d(TAG, "doubleTap: ($x, $y) completed")
    }
    
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, velocity: Float) {
        Log.d(TAG, "swipe: ($x1,$y1) -> ($x2,$y2), duration: ${durationMs}ms, velocity: $velocity")
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "swipe: AccessibilityService not available")
            return
        }
        
        val result = service.performSwipe(x1, y1, x2, y2, durationMs.toLong(), velocity)
        Log.d(TAG, "swipe: ($x1,$y1) -> ($x2,$y2), result: $result")
    }
    
    override fun type(text: String) {
        Log.d(TAG, "type: text=$text")
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "type: AccessibilityService not available")
            return
        }
        
        // 检查是否包含非 ASCII 字符
        val hasNonAscii = text.any { it.code > 127 }
        
        if (hasNonAscii) {
            // 中文等使用剪贴板方式（首次调用，需要清空输入框）
            Log.d(TAG, "type: using clipboard method for non-ASCII text")
            typeViaClipboard(text, clearBeforePaste = true)
        } else {
            // 纯英文数字尝试使用无障碍输入
            Log.d(TAG, "type: using accessibility method for ASCII text")
            typeViaAccessibility(text)
        }
    }
    
    override fun back() {
        Log.d(TAG, "back")
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "back: AccessibilityService not available")
            return
        }

        // 使用无障碍服务发送返回键
        val result = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Log.d(TAG, "back, result: $result")
    }
    
    override fun home() {
        Log.d(TAG, "home")
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "home: AccessibilityService not available")
            return
        }

        // 使用无障碍服务发送 Home 键
        val result = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        Log.d(TAG, "home, result: $result")
    }
    
    override fun enter() {
        Log.d(TAG, "enter")
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "enter: AccessibilityService not available")
            return
        }
        
        // 使用无障碍服务发送回车键
        val rootNode = service.rootNode
        if (rootNode != null) {
            try {
                val result = rootNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Log.d(TAG, "enter (focus), result: $result")
            } finally {
                rootNode.recycle()
            }
        } else {
            Log.w(TAG, "enter: Cannot get root node for enter action")
        }
    }
    
    override fun isAvailable(): Boolean {
        val available = accessibilityService != null
        Log.d(TAG, "isAvailable: $available")
        return available
    }
    
    /**
     * 通过无障碍服务输入文本
     */
    private fun typeViaAccessibility(text: String) {
        Log.d(TAG, "typeViaAccessibility: text=$text")
        val service = accessibilityService ?: return

        // 查找当前聚焦的可编辑元素
        val rootNode = service.rootNode
        if (rootNode == null) {
            Log.w(TAG, "typeViaAccessibility: Cannot get root node")
            // typeViaClipboard(text, clearBeforePaste = true)
            return
        }
        rootNode.recycle()

        // 查找可编辑的元素
        val editableNodes = service.findAllEditable()
        if (editableNodes.isEmpty()) {
            Log.w(TAG, "typeViaAccessibility: No editable node found")
            // typeViaClipboard(text, clearBeforePaste = true)
            return
        }

        try {
            // 使用第一个可编辑元素
            val node = editableNodes[0]

            // 记录操作前的文本
            val beforeText = node.text?.toString() ?: ""
            Log.d(TAG, "typeViaAccessibility: 操作前文本: '$beforeText'")

            // 先聚焦元素
            service.focusNode(node)
            Thread.sleep(100)

            // 【关键】直接设置目标文本（setText 内部会处理清空和设置）
            val setResult = service.setText(node, text)
            Log.d(TAG, "typeViaAccessibility: 设置文本 '$text', 结果: $setResult")

            // 等待设置完成
            Thread.sleep(200)

            // 验证文本是否设置成功
            val afterText = node.text?.toString() ?: ""
            Log.d(TAG, "typeViaAccessibility: 操作后文本: '$afterText'")

            // 如果目标文本已存在于输入框中，认为成功
            val verified = afterText.contains(text) || afterText == text
            if (verified) {
                Log.d(TAG, "typeViaAccessibility: ✓ 文本验证成功")
            } else {
                Log.w(TAG, "typeViaAccessibility: ✗ 文本验证失败! 期望: '$text', 实际: '$afterText'")
            }
        } finally {
            editableNodes.forEach { runCatching { it.recycle() } }
        }
        
        // 不再降级到剪贴板方式，避免剪贴板污染问题
        // Log.w(TAG, "typeViaAccessibility: setText failed, fallback to clipboard")
        // typeViaClipboard(text, clearBeforePaste = true)
    }
    
    /**
     * 通过剪贴板方式输入文本
     * 
     * @param text 要输入的文本
     * @param clearBeforePaste 粘贴前是否需要清空输入框（从 typeViaAccessibility 降级时可能已有内容）
     */
    private fun typeViaClipboard(text: String, clearBeforePaste: Boolean = true) {
        Log.d(TAG, "typeViaClipboard: text=$text, clearBeforePaste=$clearBeforePaste")

        if (clipboardManager != null) {
            try {
                // 查找可编辑的输入框
                val service = accessibilityService
                if (service == null) {
                    Log.e(TAG, "typeViaClipboard: AccessibilityService not available")
                    return
                }

                val editableNodes = service.findAllEditable()
                if (editableNodes.isEmpty()) {
                    Log.w(TAG, "typeViaClipboard: No editable node found")
                    return
                }

                try {
                    val node = editableNodes[0]

                    // 记录操作前的文本
                    val beforeText = node.text?.toString() ?: ""
                    Log.d(TAG, "typeViaClipboard: 操作前文本: '$beforeText'")

                    // 先聚焦到输入框
                    service.focusNode(node)
                    Thread.sleep(100)

                    // 只有在需要时才清空输入框
                    if (clearBeforePaste && beforeText.isNotEmpty()) {
                        Log.d(TAG, "typeViaClipboard: 清空现有文本: '$beforeText'")
                        
                        // 方法1: 尝试使用 ACTION_SET_TEXT 清空
                        val clearResult = service.setText(node, "")
                        Log.d(TAG, "typeViaClipboard: ACTION_SET_TEXT 清空结果: $clearResult")
                        Thread.sleep(100)

                        // 方法2: 如果清空失败，尝试全选后删除
                        if (!clearResult) {
                            Log.d(TAG, "typeViaClipboard: 尝试全选后删除")
                            val selectionArgs = Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, beforeText.length)
                            }
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
                            Thread.sleep(50)
                        }

                        // 验证是否清空成功
                        val afterClearText = node.text?.toString() ?: ""
                        if (afterClearText.isNotEmpty()) {
                            Log.w(TAG, "typeViaClipboard: 清空后仍有文本: '$afterClearText'")
                        }
                    }

                    // 设置剪贴板内容
                    val latch = CountDownLatch(1)
                    var clipboardSet = false

                    mainHandler.post {
                        try {
                            val clip = ClipData.newPlainText("baozi_input", text)
                            clipboardManager?.setPrimaryClip(clip)
                            clipboardSet = true
                            Log.d(TAG, "typeViaClipboard: Clipboard set successfully: $text")
                        } catch (e: Exception) {
                            Log.e(TAG, "typeViaClipboard: Failed to set clipboard: ${e.message}")
                        } finally {
                            latch.countDown()
                        }
                    }

                    // 等待剪贴板设置完成
                    val success = latch.await(1, TimeUnit.SECONDS)
                    if (!success || !clipboardSet) {
                        Log.e(TAG, "typeViaClipboard: Clipboard set failed or timeout")
                        return
                    }

                    Thread.sleep(200)

                    // 执行粘贴
                    val pasteResult = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    Log.d(TAG, "typeViaClipboard: ACTION_PASTE 结果: $pasteResult")
                    Thread.sleep(200)

                    // 验证：目标文本存在于输入框中即可
                    val afterText = node.text?.toString() ?: ""
                    val verified = afterText.contains(text) || afterText == text
                    
                    if (verified) {
                        Log.d(TAG, "typeViaClipboard: ✓ 验证成功，输入文本: '$afterText'")
                    } else {
                        Log.e(TAG, "typeViaClipboard: ✗ 验证失败! 期望: '$text', 实际: '$afterText'")
                        
                        // 如果验证失败，尝试使用 ACTION_SET_TEXT 直接设置
                        Log.d(TAG, "typeViaClipboard: 尝试使用 ACTION_SET_TEXT 直接设置")
                        val setResult = service.setText(node, text)
                        Log.d(TAG, "typeViaClipboard: ACTION_SET_TEXT 结果: $setResult")
                        Thread.sleep(100)
                        
                        val finalText = node.text?.toString() ?: ""
                        if (finalText.contains(text) || finalText == text) {
                            Log.d(TAG, "typeViaClipboard: ✓ ACTION_SET_TEXT 成功")
                        } else {
                            Log.e(TAG, "typeViaClipboard: ✗ 最终验证失败: '$finalText'")
                        }
                    }
                } finally {
                    editableNodes.forEach { runCatching { it.recycle() } }
                }
            } catch (e: Exception) {
                Log.e(TAG, "typeViaClipboard: Clipboard method failed: ${e.message}")
                e.printStackTrace()
            }
        } else {
            Log.e(TAG, "typeViaClipboard: ClipboardManager is null, Context not set")
        }
    }
}