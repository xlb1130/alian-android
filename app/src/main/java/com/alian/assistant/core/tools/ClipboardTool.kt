package com.alian.assistant.core.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 剪贴板工具
 *
 * 提供剪贴板的读写功能
 */
class ClipboardTool(private val context: Context) : Tool {

    override val name = "clipboard"
    override val displayName = "剪贴板"
    override val description = "读取或写入系统剪贴板内容"

    override val params = listOf(
        ToolParam(
            name = "action",
            type = "string",
            description = "操作类型：read（读取）或 write（写入）",
            required = true
        ),
        ToolParam(
            name = "text",
            type = "string",
            description = "要写入的文本（action=write 时必填）",
            required = false
        ),
        ToolParam(
            name = "label",
            type = "string",
            description = "剪贴板标签（可选）",
            required = false,
            defaultValue = "alian"
        )
    )

    private val clipboardManager: ClipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val action = params["action"] as? String
            ?: return ToolResult.Error("缺少 action 参数")

        return when (action.lowercase()) {
            "read" -> readClipboard()
            "write" -> {
                val text = params["text"] as? String
                    ?: return ToolResult.Error("write 操作需要 text 参数")
                val label = params["label"] as? String ?: "alian"
                writeClipboard(text, label)
            }
            else -> ToolResult.Error("不支持的操作: $action（只支持 read/write）")
        }
    }

    /**
     * 读取剪贴板内容
     */
    private suspend fun readClipboard(): ToolResult = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            try {
                val clip = clipboardManager.primaryClip
                if (clip == null || clip.itemCount == 0) {
                    cont.resume(ToolResult.Success(data = "", message = "剪贴板为空"))
                } else {
                    val text = clip.getItemAt(0).coerceToText(context).toString()
                    cont.resume(ToolResult.Success(
                        data = text,
                        message = "已读取剪贴板内容（${text.length} 字符）"
                    ))
                }
            } catch (e: Exception) {
                cont.resume(ToolResult.Error("读取剪贴板失败: ${e.message}"))
            }
        }
    }

    /**
     * 写入剪贴板内容
     */
    private suspend fun writeClipboard(text: String, label: String): ToolResult = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            try {
                val clip = ClipData.newPlainText(label, text)
                clipboardManager.setPrimaryClip(clip)
                cont.resume(ToolResult.Success(
                    data = text,
                    message = "已写入剪贴板（${text.length} 字符）"
                ))
            } catch (e: Exception) {
                cont.resume(ToolResult.Error("写入剪贴板失败: ${e.message}"))
            }
        }
    }

    /**
     * 同步读取（用于非协程环境）
     */
    fun readSync(): String? {
        var result: String? = null
        val latch = CountDownLatch(1)

        mainHandler.post {
            try {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    result = clip.getItemAt(0).coerceToText(context).toString()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                latch.countDown()
            }
        }

        latch.await(1, TimeUnit.SECONDS)
        return result
    }

    /**
     * 同步写入（用于非协程环境）
     */
    fun writeSync(text: String, label: String = "alian"): Boolean {
        var success = false
        val latch = CountDownLatch(1)

        mainHandler.post {
            try {
                val clip = ClipData.newPlainText(label, text)
                clipboardManager.setPrimaryClip(clip)
                success = true
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                latch.countDown()
            }
        }

        latch.await(1, TimeUnit.SECONDS)
        return success
    }
}
