package com.alian.assistant.presentation.ui.screens.videocall

import android.text.format.DateFormat
import android.util.Log
import com.alian.assistant.infrastructure.ai.llm.LLMClient
import com.alian.assistant.presentation.viewmodel.ImageRecognitionResult
import com.alian.assistant.presentation.viewmodel.VideoCallMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 视频通话客户端
 * 调用文本大模型进行对话
 * 图像识别结果已作为文本上下文传入，不需要调用视觉大模型
 */
class VideoCallClient(
    private val apiKey: String,
    baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    model: String = "qwen-plus"
) {
    companion object {
        private const val TAG = "VideoCallClient"
    }

    // 使用 LLMClient 进行实际的 API 调用
    private val llmClient = LLMClient(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model
    )

    /**
     * 发送消息（非流式）
     * @param message 用户消息
     * @param conversationHistory 对话历史
     * @param imageHistory 图像识别历史（可选）
     * @return Result<String> AI 响应内容
     */
    suspend fun sendMessage(
        message: String,
        conversationHistory: List<VideoCallMessage> = emptyList(),
        imageHistory: List<ImageRecognitionResult> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "[DEBUG] sendMessage 开始")
        Log.d(TAG, "[DEBUG] 用户消息: $message")
        Log.d(TAG, "[DEBUG] 对话历史数量: ${conversationHistory.size}")
        conversationHistory.forEachIndexed { index, msg ->
            val role = if (msg.isUser) "user" else "assistant"
            Log.d(TAG, "[DEBUG] 对话历史 #$index [$role]: ${msg.content}")
        }
        Log.d(TAG, "[DEBUG] 图像识别历史数量: ${imageHistory.size}")
        imageHistory.forEachIndexed { index, result ->
            val timeStr = DateFormat.format("HH:mm:ss", result.timestamp)
            Log.d(TAG, "[DEBUG] 图像识别历史 #$index [$timeStr]: ${result.description}")
        }

        val startTime = System.currentTimeMillis()

        // 构建消息列表
        val messagesJson = buildMessages(message, conversationHistory, imageHistory)
        Log.d(TAG, "[DEBUG] 消息构建完成，总消息数: ${messagesJson.length()}")

        // 打印完整的输入信息
        Log.d(TAG, "[DEBUG] 完整输入信息:")
        for (i in 0 until messagesJson.length()) {
            val msgObj = messagesJson.getJSONObject(i)
            val role = msgObj.getString("role")
            val content = msgObj.getString("content")
            Log.d(TAG, "[DEBUG] 消息 #$i [$role]: $content")
        }

        // 使用 LLMClient 的 predictWithContext 方法
        Log.d(TAG, "[DEBUG] 开始调用 LLMClient...")
        val result = llmClient.predictWithContext(messagesJson)

        val elapsed = System.currentTimeMillis() - startTime

        if (result.isSuccess) {
            val content = result.getOrNull() ?: ""
            Log.d(TAG, "[DEBUG] sendMessage 成功，总耗时: ${elapsed}ms")
            Log.d(TAG, "[DEBUG] 响应内容: $content")
        } else {
            Log.d(TAG, "[DEBUG] sendMessage 失败，总耗时: ${elapsed}ms, 错误: ${result.exceptionOrNull()?.message}")
        }

        result
    }

    /**
     * 发送消息（流式）
     * @param message 用户消息
     * @param conversationHistory 对话历史
     * @param imageHistory 图像识别历史（可选）
     * @return Flow<String> 流式响应
     */
    fun sendMessageStream(
        message: String,
        conversationHistory: List<VideoCallMessage> = emptyList(),
        imageHistory: List<ImageRecognitionResult> = emptyList()
    ): Flow<String> = flow {
        Log.d(TAG, "[DEBUG] sendMessageStream 开始")
        Log.d(TAG, "[DEBUG] 用户消息: $message")
        Log.d(TAG, "[DEBUG] 对话历史数量: ${conversationHistory.size}")
        conversationHistory.forEachIndexed { index, msg ->
            val role = if (msg.isUser) "user" else "assistant"
            Log.d(TAG, "[DEBUG] 对话历史 #$index [$role]: ${msg.content}")
        }
        Log.d(TAG, "[DEBUG] 图像识别历史数量: ${imageHistory.size}")
        imageHistory.forEachIndexed { index, result ->
            val timeStr = DateFormat.format("HH:mm:ss", result.timestamp)
            Log.d(TAG, "[DEBUG] 图像识别历史 #$index [$timeStr]: ${result.description}")
        }

        // 构建消息列表
        val messagesJson = buildMessages(message, conversationHistory, imageHistory)
        Log.d(TAG, "[DEBUG] 消息构建完成，总消息数: ${messagesJson.length()}")

        // 打印完整的输入信息
        Log.d(TAG, "[DEBUG] 完整输入信息:")
        for (i in 0 until messagesJson.length()) {
            val msgObj = messagesJson.getJSONObject(i)
            val role = msgObj.getString("role")
            val content = msgObj.getString("content")
            Log.d(TAG, "[DEBUG] 消息 #$i [$role]: $content")
        }

        // 调用 LLMClient 进行流式请求
        Log.d(TAG, "[DEBUG] 开始调用 LLMClient 流式接口...")
        llmClient.predictWithContextStream(messagesJson).collect { chunk ->
            emit(chunk)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 构建消息列表
     */
    private fun buildMessages(
        message: String,
        conversationHistory: List<VideoCallMessage>,
        imageHistory: List<ImageRecognitionResult>
    ): JSONArray {
        Log.d(TAG, "[DEBUG] buildMessages 开始")

        val messages = JSONArray()

        // 添加对话历史（限制最近 5 条）
        val recentHistory = conversationHistory.takeLast(5)
        Log.d(TAG, "[DEBUG] 添加对话历史: ${recentHistory.size} 条")

        for (msg in recentHistory) {
            val role = if (msg.isUser) "user" else "assistant"
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", msg.content)
            })
        }

        // 构建当前用户消息
        var fullMessage = message

        // 添加图像识别历史作为上下文
        if (imageHistory.isNotEmpty()) {
            Log.d(TAG, "[DEBUG] 添加图像识别历史: ${imageHistory.size} 条")
            fullMessage += "\n\n【视觉信息】\n"
            imageHistory.forEachIndexed { index, result ->
                val timeStr = DateFormat.format("HH:mm:ss", result.timestamp)
                fullMessage += "[$timeStr] ${result.description}\n"
            }
        }

        Log.d(TAG, "[DEBUG] 完整消息长度: ${fullMessage.length}")
        Log.d(TAG, "[DEBUG] 完整消息内容: $fullMessage")

        // 添加当前用户消息
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", fullMessage)
        })

        Log.d(TAG, "[DEBUG] buildMessages 完成，总消息数: ${messages.length()}")
        return messages
    }
}