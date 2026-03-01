package com.alian.assistant.infrastructure.device.controller.shizuku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.alian.assistant.IShellService
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.device.controller.interfaces.IScreenshotProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Shizuku 截图提供者实现
 * 
 * 使用 Shizuku 执行 shell 命令进行截图
 */
class ShizukuScreenshotProvider(
    private val context: Context? = null,
    private val shellService: IShellService? = null
) : IScreenshotProvider {
    
    companion object {
        // 使用 /data/local/tmp，shell 用户有权限访问
        private const val SCREENSHOT_PATH = "/data/local/tmp/autopilot_screen.png"
    }
    
    override suspend fun screenshot(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 截图到 /data/local/tmp 并设置权限让 App 可读
            val output = exec("screencap -p $SCREENSHOT_PATH && chmod 666 $SCREENSHOT_PATH")
            delay(500)
            
            // 尝试直接读取
            val file = File(SCREENSHOT_PATH)
            if (file.exists() && file.canRead() && file.length() > 0) {
                println("[ShizukuScreenshotProvider] Reading screenshot from: $SCREENSHOT_PATH, size: ${file.length()}")
                return@withContext BitmapFactory.decodeFile(SCREENSHOT_PATH)
            }
            
            // 如果无法直接读取，通过 shell cat 读取二进制数据
            println("[ShizukuScreenshotProvider] Cannot read directly, trying shell cat...")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $SCREENSHOT_PATH"))
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            
            if (bytes.isNotEmpty()) {
                println("[ShizukuScreenshotProvider] Read ${bytes.size} bytes via shell")
                return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            
            println("[ShizukuScreenshotProvider] Screenshot file empty or not accessible")
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    override suspend fun screenshotWithFallback(): IDeviceController.ScreenshotResult = withContext(Dispatchers.IO) {
        try {
            // 截图到 /data/local/tmp 并设置权限让 App 可读
            val output = exec("screencap -p $SCREENSHOT_PATH && chmod 666 $SCREENSHOT_PATH")
            delay(500)
            
            // 检查是否截图失败（敏感页面保护）
            if (output.contains("Status: -1") || output.contains("Failed") || output.contains("error")) {
                println("[ShizukuScreenshotProvider] Screenshot blocked (sensitive screen), returning fallback")
                return@withContext createFallbackScreenshot(isSensitive = true)
            }
            
            // 尝试直接读取
            val file = File(SCREENSHOT_PATH)
            if (file.exists() && file.canRead() && file.length() > 0) {
                println("[ShizukuScreenshotProvider] Reading screenshot from: $SCREENSHOT_PATH, size: ${file.length()}")
                val bitmap = BitmapFactory.decodeFile(SCREENSHOT_PATH)
                if (bitmap != null) {
                    return@withContext IDeviceController.ScreenshotResult(bitmap)
                }
            }
            
            // 如果无法直接读取，通过 shell cat 读取二进制数据
            println("[ShizukuScreenshotProvider] Cannot read directly, trying shell cat...")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $SCREENSHOT_PATH"))
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            
            if (bytes.isNotEmpty()) {
                println("[ShizukuScreenshotProvider] Read ${bytes.size} bytes via shell")
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    return@withContext IDeviceController.ScreenshotResult(bitmap)
                }
            }
            
            println("[ShizukuScreenshotProvider] Screenshot file empty or not accessible, returning fallback")
            createFallbackScreenshot(isSensitive = false)
        } catch (e: Exception) {
            e.printStackTrace()
            println("[ShizukuScreenshotProvider] Screenshot exception, returning fallback")
            createFallbackScreenshot(isSensitive = false)
        }
    }
    
    override fun getScreenSize(): Pair<Int, Int> {
        val output = exec("wm size")
        
        println("[ShizukuScreenshotProvider] wm size 输出: $output")
        
        // 输出格式: Physical size: 1080x2400
        val match = Regex("(\\d+)x(\\d+)").find(output)
        val (physicalWidth, physicalHeight) = if (match != null) {
            val (w, h) = match.destructured
            Pair(w.toInt(), h.toInt())
        } else {
            println("[ShizukuScreenshotProvider] ⚠️ 无法解析屏幕尺寸，使用默认值 1080x2400")
            Pair(1080, 2400)
        }
        
        println("[ShizukuScreenshotProvider] 物理尺寸: ${physicalWidth}x${physicalHeight}")
        
        // 检测屏幕方向
        val orientation = getScreenOrientation()
        println("[ShizukuScreenshotProvider] 屏幕方向: $orientation (0=竖屏, 1/3=横屏)")
        
        val (width, height) = if (orientation == 1 || orientation == 3) {
            // 横屏：交换宽高
            println("[ShizukuScreenshotProvider] 横屏模式，交换宽高")
            Pair(physicalHeight, physicalWidth)
        } else {
            // 竖屏
            println("[ShizukuScreenshotProvider] 竖屏模式")
            Pair(physicalWidth, physicalHeight)
        }
        
        // 验证屏幕尺寸合理性
        if (width < 300 || height < 300) {
            println("[ShizukuScreenshotProvider] ⚠️ 警告: 屏幕尺寸异常小 (${width}x${height})，可能解析错误")
        }
        if (width > 5000 || height > 5000) {
            println("[ShizukuScreenshotProvider] ⚠️ 警告: 屏幕尺寸异常大 (${width}x${height})，可能解析错误")
        }
        
        println("[ShizukuScreenshotProvider] 最终屏幕尺寸: ${width}x${height}")
        
        return Pair(width, height)
    }
    
    override fun isAvailable(): Boolean {
        return shellService != null
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
    
    /**
     * 获取屏幕方向
     * @return 0=竖屏, 1=横屏(90°), 2=倒置竖屏, 3=横屏(270°)
     */
    private fun getScreenOrientation(): Int {
        val output = exec("dumpsys window displays | grep mCurrentOrientation")
        
        println("[ShizukuScreenshotProvider] 屏幕方向检测输出: $output")
        
        // 输出格式: mCurrentOrientation=0 或 mCurrentOrientation=1
        val match = Regex("mCurrentOrientation=(\\d)").find(output)
        val orientation = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        
        val orientationName = when (orientation) {
            0 -> "竖屏 (0°)"
            1 -> "横屏 (90°)"
            2 -> "倒置竖屏 (180°)"
            3 -> "横屏 (270°)"
            else -> "未知方向"
        }
        
        println("[ShizukuScreenshotProvider] 解析结果: $orientationName")
        
        return orientation
    }
    
    /**
     * 创建黑屏占位图（降级处理）
     */
    private fun createFallbackScreenshot(isSensitive: Boolean): IDeviceController.ScreenshotResult {
        val (width, height) = getScreenSize()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // 默认是黑色，无需填充
        return IDeviceController.ScreenshotResult(
            bitmap = bitmap,
            isSensitive = isSensitive,
            isFallback = true
        )
    }
}