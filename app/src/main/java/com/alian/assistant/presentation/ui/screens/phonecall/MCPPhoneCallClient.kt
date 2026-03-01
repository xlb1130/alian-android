package com.alian.assistant.presentation.ui.screens.phonecall

import android.text.format.DateFormat
import android.util.Log
import com.alian.assistant.core.agent.PhoneCallMessage
import com.alian.assistant.infrastructure.device.ScreenFrame
import com.alian.assistant.presentation.ui.screens.videocall.ImageFrame
import com.alian.assistant.presentation.ui.screens.videocall.MCPVLMOnlyVideoCallClient
import com.alian.assistant.presentation.viewmodel.VideoCallMessage
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机通话专用 VLM 客户端
 *
 * 继承 MCPVLMOnlyVideoCallClient，复用其 MCP 工具调用、流式响应、重试等基础能力。
 * 核心差异：
 * - 图像来源从摄像头帧（ImageFrame）变为屏幕截图（ScreenFrame）
 * - System Prompt 增加手机操作上下文
 * - 支持 PhoneCallMessage 类型的对话历史
 */
class MCPPhoneCallClient(
    apiKey: String,
    baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    model: String = "qwen-vl-max",
    systemPrompt: String
) : MCPVLMOnlyVideoCallClient(
    apiKey = apiKey,
    baseUrl = baseUrl,
    model = model,
    systemPrompt = systemPrompt
) {
    companion object {
        private const val TAG = "MCPPhoneCallClient"
    }

    /**
     * 发送消息（非流式），使用 PhoneCallMessage 和 ScreenFrame
     * @param message 用户消息
     * @param conversationHistory PhoneCall 对话历史
     * @param screenHistory 屏幕截图历史
     * @return Result<String> AI 响应内容
     */
    suspend fun sendPhoneCallMessage(
        message: String,
        conversationHistory: List<PhoneCallMessage> = emptyList(),
        screenHistory: List<ScreenFrame> = emptyList()
    ): Result<String> {
        // 将 PhoneCallMessage 转换为 VideoCallMessage
        val videoCallHistory = conversationHistory.map { phoneMsg ->
            VideoCallMessage(
                id = phoneMsg.id,
                content = phoneMsg.content,
                isUser = phoneMsg.isUser,
                timestamp = phoneMsg.timestamp
            )
        }

        // 将 ScreenFrame 转换为 ImageFrame
        val imageHistory = screenHistory.map { screenFrame ->
            ImageFrame(
                bitmap = screenFrame.bitmap,
                timestamp = screenFrame.timestamp,
                trigger = screenFrame.trigger
            )
        }

        return sendMessage(
            message = message,
            conversationHistory = videoCallHistory,
            imageHistory = imageHistory
        )
    }

    /**
     * 发送消息（流式），使用 PhoneCallMessage 和 ScreenFrame
     * @param message 用户消息
     * @param conversationHistory PhoneCall 对话历史
     * @param screenHistory 屏幕截图历史
     * @return Flow<String> 流式响应
     */
    fun sendPhoneCallMessageStream(
        message: String,
        conversationHistory: List<PhoneCallMessage> = emptyList(),
        screenHistory: List<ScreenFrame> = emptyList()
    ): Flow<String> {
        // 将 PhoneCallMessage 转换为 VideoCallMessage
        val videoCallHistory = conversationHistory.map { phoneMsg ->
            VideoCallMessage(
                id = phoneMsg.id,
                content = phoneMsg.content,
                isUser = phoneMsg.isUser,
                timestamp = phoneMsg.timestamp
            )
        }

        // 将 ScreenFrame 转换为 ImageFrame
        val imageHistory = screenHistory.map { screenFrame ->
            ImageFrame(
                bitmap = screenFrame.bitmap,
                timestamp = screenFrame.timestamp,
                trigger = screenFrame.trigger
            )
        }

        return sendMessageStream(
            message = message,
            conversationHistory = videoCallHistory,
            imageHistory = imageHistory
        )
    }

    /**
     * 覆写消息构建，针对手机屏幕截图场景优化
     *
     * 与父类的区别：
     * - 图像附带时间戳标注，帮助 VLM 理解屏幕变化的时间顺序
     * - 最近的截图放在最后（最新的屏幕状态）
     */
    override fun buildMessages(
        conversationHistory: List<VideoCallMessage>,
        imageHistory: List<ImageFrame>
    ): JSONArray {
        Log.d(TAG, "[PhoneCall] buildMessages 开始")

        val messages = JSONArray()

        // 添加系统提示
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        // 添加对话历史（限制最近 5 条，PhoneCall 需要更多上下文）
        val recentHistory = conversationHistory.takeLast(5)
        Log.d(TAG, "[PhoneCall] 添加对话历史: ${recentHistory.size}")

        // 添加非最后一条的历史消息（纯文本）
        val historyWithoutLast = if (recentHistory.size > 1) recentHistory.dropLast(1) else emptyList()
        for (msg in historyWithoutLast) {
            val role = if (msg.isUser) "user" else "assistant"
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", msg.content)
            })
        }

        // 最后一条用户消息附带屏幕截图
        val lastMessage = recentHistory.lastOrNull()
        if (lastMessage != null && lastMessage.isUser) {
            val contentArray = JSONArray()

            // 添加屏幕截图（最近 3 张，按时间顺序）
            val recentImages = imageHistory.takeLast(3)
            Log.d(TAG, "[PhoneCall] 添加屏幕截图: ${recentImages.size}")

            for ((index, imageFrame) in recentImages.withIndex()) {
                val timeStr = DateFormat.format("HH:mm:ss", imageFrame.timestamp)
                val isLatest = index == recentImages.size - 1
                val label = if (isLatest) "当前屏幕" else "历史屏幕 (${timeStr})"

                Log.d(TAG, "[PhoneCall] 添加截图 [$label]: ${imageFrame.bitmap.width}x${imageFrame.bitmap.height}")

                // 添加截图时间标注
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", "[$label]")
                })

                val imageUrl = bitmapToBase64Url(imageFrame.bitmap)
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", imageUrl)
                    })
                })
            }

            // 添加用户文本消息
            contentArray.put(JSONObject().apply {
                put("type", "text")
                put("text", lastMessage.content)
            })

            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })
        } else if (lastMessage != null) {
            // 最后一条是 assistant 消息
            messages.put(JSONObject().apply {
                put("role", "assistant")
                put("content", lastMessage.content)
            })
        }

        Log.d(TAG, "[PhoneCall] buildMessages 完成，总消息数: ${messages.length()}")
        return messages
    }
}
