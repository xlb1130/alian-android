package com.alian.assistant.infrastructure.device.controller.shizuku

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.alian.assistant.IShellService
import com.alian.assistant.infrastructure.device.controller.interfaces.IInputProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 输入提供者实现
 * 
 * 使用 Shizuku 执行 shell 命令进行输入操作
 */
class ShizukuInputProvider(
    private val context: Context? = null,
    private val shellService: IShellService? = null
) : IInputProvider {
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipboardManager: ClipboardManager? by lazy {
        context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    
    override fun tap(x: Int, y: Int) {
        val command = "input tap $x $y"
        
        println("[ShizukuInputProvider] 🖱️ 执行点击: ($x, $y)")
        println("[ShizukuInputProvider] 命令: $command")
        
        val result = exec(command)
        if (result.isNotEmpty()) {
            println("[ShizukuInputProvider] 执行结果: $result")
        } else {
            println("[ShizukuInputProvider] ✅ 点击命令已发送（无返回输出）")
        }
    }
    
    override fun longPress(x: Int, y: Int, durationMs: Int) {
        exec("input swipe $x $y $x $y $durationMs")
    }
    
    override fun doubleTap(x: Int, y: Int) {
        exec("input tap $x $y && input tap $x $y")
    }
    
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, velocity: Float) {
        // Shizuku 的 input swipe 命令通过 duration 控制速度
        // velocity 0.0-1.0 转换为 duration 的调整因子
        val velocityFactor = 1.5f - velocity
        val actualDuration = (durationMs * velocityFactor).toInt().coerceIn(50, 3000)
        exec("input swipe $x1 $y1 $x2 $y2 $actualDuration")
    }
    
    override fun type(text: String) {
        // 检查是否包含非 ASCII 字符
        val hasNonAscii = text.any { it.code > 127 }
        
        if (hasNonAscii) {
            // 中文等使用剪贴板方式
            typeViaClipboard(text)
        } else {
            // 纯英文数字使用 input text
            val escaped = text.replace("'", "'\\''")
            exec("input text '$escaped'")
        }
    }
    
    override fun back() {
        exec("input keyevent 4")
    }
    
    override fun home() {
        exec("input keyevent 3")
    }
    
    override fun enter() {
        exec("input keyevent 66")
    }
    
    override fun isAvailable(): Boolean {
        return shellService != null
    }
    
    /**
     * 通过剪贴板方式输入中文
     * 使用 Android ClipboardManager API 设置剪贴板，然后发送粘贴按键
     */
    private fun typeViaClipboard(text: String) {
        println("[ShizukuInputProvider] 尝试输入中文: $text")
        
        // 方法1: 使用 Android 剪贴板 API + 粘贴 (最可靠，不需要额外 App)
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
                        println("[ShizukuInputProvider] ✅ 已设置剪贴板: $text")
                    } catch (e: Exception) {
                        println("[ShizukuInputProvider] ❌ 设置剪贴板异常: ${e.message}")
                    } finally {
                        latch.countDown()
                    }
                }
                
                // 等待剪贴板设置完成 (最多等 1 秒)
                val success = latch.await(1, TimeUnit.SECONDS)
                if (!success) {
                    println("[ShizukuInputProvider] ❌ 等待剪贴板超时")
                    return
                }
                
                if (!clipboardSet) {
                    println("[ShizukuInputProvider] ❌ 剪贴板设置失败")
                    return
                }
                
                // 稍等一下确保剪贴板生效
                Thread.sleep(200)
                
                // 发送粘贴按键 (KEYCODE_PASTE = 279)
                exec("input keyevent 279")
                println("[ShizukuInputProvider] ✅ 已发送粘贴按键")
                return
            } catch (e: Exception) {
                println("[ShizukuInputProvider] ❌ 剪贴板方式失败: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("[ShizukuInputProvider] ❌ ClipboardManager 为 null，Context 未设置")
        }
        
        // 方法2: 使用 ADB Keyboard 广播 (备选，需要安装 ADBKeyboard)
        val escaped = text.replace("\"", "\\\"")
        val adbKeyboardResult = exec("am broadcast -a ADB_INPUT_TEXT --es msg \"$escaped\"")
        println("[ShizukuInputProvider] ADBKeyboard 广播结果: $adbKeyboardResult")
        
        if (adbKeyboardResult.contains("result=0")) {
            println("[ShizukuInputProvider] ✅ ADBKeyboard 输入成功")
            return
        }
        
        // 方法3: 使用 cmd input text (Android 12+ 可能支持 UTF-8)
        println("[ShizukuInputProvider] 尝试 cmd input text...")
        exec("cmd input text '$text'")
    }
    
    /**
     * 执行 shell 命令 (本地，无权限)
     */
    private fun execLocal(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            reader.close()
            output
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
    
    /**
     * 执行 shell 命令 (通过 Shizuku)
     */
    private fun exec(command: String): String {
        return try {
            shellService?.exec(command) ?: execLocal(command)
        } catch (e: Exception) {
            e.printStackTrace()
            execLocal(command)
        }
    }
}
