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
            // 中文等使用剪贴板方式
            Log.d(TAG, "type: using clipboard method for non-ASCII text")
            typeViaClipboard(text)
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
            val result = rootNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Log.d(TAG, "enter (focus), result: $result")
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
            typeViaClipboard(text)
            return
        }

        // 查找可编辑的元素
        val editableNodes = service.findAllEditable()
        if (editableNodes.isNotEmpty()) {
            // 使用第一个可编辑元素
            val node = editableNodes[0]

            // 记录操作前的文本
            val beforeText = node.text?.toString() ?: ""
            Log.d(TAG, "typeViaAccessibility: 操作前文本: '$beforeText'")

            // 先聚焦元素
            service.focusNode(node)
            Thread.sleep(100)

            // 清空现有文本
            // 方法1: 尝试使用 ACTION_SET_TEXT 清空
            val clearResult = service.setText(node, "")
            Log.d(TAG, "typeViaAccessibility: 清空文本结果: $clearResult")
            Thread.sleep(100)

            // 如果清空失败，尝试选中文本再删除
            if (!clearResult && beforeText.isNotEmpty()) {
                Log.d(TAG, "typeViaAccessibility: 清空失败，尝试选中文本删除")
                // 尝试选中文本
                val selectionArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, beforeText.length)
                }
                val selectionResult = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
                Log.d(TAG, "typeViaAccessibility: 选中文本结果: $selectionResult")
                Thread.sleep(50)
                // 删除选中的文本 - 使用空格替换或直接重新设置
                // 注意：Android Accessibility API 没有 ACTION_DELETE，需要通过其他方式
                // 这里我们直接重新设置空文本，因为前面的 setText 失败可能是暂时的
                Thread.sleep(100)
            }

            // 设置新文本
            val setResult = service.setText(node, text)
            Log.d(TAG, "typeViaAccessibility: 设置文本 '$text', 结果: $setResult")

            // 等待设置完成
            Thread.sleep(200)

            // 验证文本是否真正设置成功
            val afterText = node.text?.toString() ?: ""
            Log.d(TAG, "typeViaAccessibility: 操作后文本: '$afterText'")

            val verified = afterText.contains(text)
            if (verified) {
                Log.d(TAG, "typeViaAccessibility: ✓ 文本验证成功")
                return
            } else {
                Log.w(TAG, "typeViaAccessibility: ✗ 文本验证失败! 期望包含: '$text', 实际: '$afterText'")
            }
        }

        // 如果找不到可编辑元素或设置失败，降级到剪贴板方式
        Log.w(TAG, "typeViaAccessibility: Cannot find editable node or setText failed, fallback to clipboard")
        typeViaClipboard(text)
    }
    
    /**
     * 通过剪贴板方式输入文本
     */
    private fun typeViaClipboard(text: String) {
        Log.d(TAG, "typeViaClipboard: text=$text")

        if (clipboardManager != null) {
            try {
                // 使用 CountDownLatch 等待剪贴板设置完成
                val latch = CountDownLatch(1)
                var clipboardSet = false

                // 必须在主线程操作剪贴板
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

                // 等待剪贴板设置完成 (最多等 1 秒)
                val success = latch.await(1, TimeUnit.SECONDS)
                if (!success) {
                    Log.e(TAG, "typeViaClipboard: Clipboard timeout")
                    return
                }

                if (!clipboardSet) {
                    Log.e(TAG, "typeViaClipboard: Clipboard set failed")
                    return
                }

                // 稍等一下确保剪贴板生效
                Thread.sleep(200)

                // 发送粘贴按键
                val service = accessibilityService
                if (service != null) {
                    // 查找可编辑的输入框
                    val editableNodes = service.findAllEditable()
                    if (editableNodes.isNotEmpty()) {
                        // 使用第一个可编辑元素
                        val node = editableNodes[0]

                        // 记录粘贴前的文本
                        val beforeText = node.text?.toString() ?: ""
                        Log.d(TAG, "typeViaClipboard: 粘贴前文本: '$beforeText'")

                        // 先聚焦到输入框
                        val focusResult = service.focusNode(node)
                        Log.d(TAG, "typeViaClipboard: Focus action sent, result: $focusResult")

                        // 稍等一下确保聚焦完成
                        Thread.sleep(100)

                        // 方法1: 尝试使用 ACTION_PASTE
                        val pasteResult = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        Log.d(TAG, "typeViaClipboard: ACTION_PASTE 结果: $pasteResult")
                        Thread.sleep(200)

                        // 验证粘贴结果
                        val afterText1 = node.text?.toString() ?: ""
                        val verified1 = afterText1.contains(text)

                        if (verified1) {
                            Log.d(TAG, "typeViaClipboard: ✓ ACTION_PASTE 验证成功")
                            return
                        }

                        Log.w(TAG, "typeViaClipboard: ✗ ACTION_PASTE 验证失败! 期望包含: '$text', 实际: '$afterText1'")

                        // 方法2: 如果 ACTION_PASTE 失败，使用全局 KEYCODE_PASTE
                        Log.d(TAG, "typeViaClipboard: 尝试使用全局 KEYCODE_PASTE")
                        val keyEventResult = service.dispatchKeyEvent(279) // KEYCODE_PASTE = 279
                        Log.d(TAG, "typeViaClipboard: KEYCODE_PASTE 结果: $keyEventResult")
                        Thread.sleep(300)

                        // 再次验证
                        val afterText2 = node.text?.toString() ?: ""
                        val verified2 = afterText2.contains(text)

                        if (verified2) {
                            Log.d(TAG, "typeViaClipboard: ✓ KEYCODE_PASTE 验证成功")
                        } else {
                            Log.e(TAG, "typeViaClipboard: ✗ KEYCODE_PASTE 验证失败! 期望包含: '$text', 实际: '$afterText2'")
                        }
                    } else {
                        Log.w(TAG, "typeViaClipboard: No editable node found for paste")
                    }
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