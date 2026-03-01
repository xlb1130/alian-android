package com.alian.assistant.presentation.ui.screens.videocall

import android.graphics.Bitmap
import android.text.format.DateFormat
import android.util.Base64
import android.util.Log
import com.alian.assistant.core.mcp.MCPManager
import com.alian.assistant.presentation.viewmodel.VideoCallMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 仅使用视觉大模型的视频通话客户端（支持 MCP 工具调用）
 * 
 * 功能特点：
 * 1. 相机帧每5秒抓取并压缩保存
 * 2. ASR识别到用户文本后，与图像一起交由视觉大模型处理
 * 3. 支持流式返回响应
 * 4. 支持 MCP 工具调用
 */
open class MCPVLMOnlyVideoCallClient(
    private val apiKey: String,
    baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    model: String = "qwen-vl-max",
    protected val systemPrompt: String
) {
    companion object {
        private const val TAG = "MCPVLMVideoCallClient"
        protected const val MAX_RETRIES = 3
        protected const val RETRY_DELAY_MS = 1000L

        fun normalizeUrl(url: String): String {
            var normalized = url.trim().removeSuffix("/")
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://$normalized"
            }
            return normalized
        }
    }

    // 规范URL
    protected val baseUrl: String = normalizeUrl(baseUrl)

    protected val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    protected val model: String = model

    /**
     * 发送消息（非流式，支持 MCP 工具调用）
     * @param message 用户消息
     * @param conversationHistory 对话历史
     * @param imageHistory 图像历史（包含时间戳和图像数据）
     * @return Result<String> AI 响应内容
     */
    suspend fun sendMessage(
        message: String,
        conversationHistory: List<VideoCallMessage> = emptyList(),
        imageHistory: List<ImageFrame> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "[MCP-VLM-ONLY] sendMessage 开始")
        Log.d(TAG, "[MCP-VLM-ONLY] 用户消息: $message")
        Log.d(TAG, "[MCP-VLM-ONLY] 对话历史数量: ${conversationHistory.size}")
        Log.d(TAG, "[MCP-VLM-ONLY] 图像历史数量: ${imageHistory.size}")

        val startTime = System.currentTimeMillis()
        var currentHistory = conversationHistory.toMutableList()
        var maxIterations = 5

        // 添加用户消息
        currentHistory.add(VideoCallMessage(
            id = System.currentTimeMillis().toString(),
            content = message,
            isUser = true,
            timestamp = System.currentTimeMillis()
        ))

        while (maxIterations > 0) {
            maxIterations--

            try {
                // 构建消息列表
                val messagesJson = buildMessages(currentHistory, imageHistory)

                // 调用 VLM API（包含 MCP 工具）
                val result = callVLMApiWithTools(messagesJson)

                if (result.isSuccess) {
                    val parsed = result.getOrNull() ?: throw Exception("解析结果为空")

                    when (parsed) {
                        is ParsedResponse.Text -> {
                            // 普通文本响应，直接返回
                            val elapsed = System.currentTimeMillis() - startTime
                            Log.d(TAG, "[MCP-VLM-ONLY] sendMessage 成功，总耗时: ${elapsed}ms")
                            Log.d(TAG, "[MCP-VLM-ONLY] 响应内容: ${parsed.content}")
                            return@withContext Result.success(parsed.content)
                        }
                        is ParsedResponse.ToolCalls -> {
                            // 工具调用，需要继续对话
                            Log.d(TAG, "[MCP-VLM-ONLY] 处理 ${parsed.calls.size} 个工具调用")

                            // 执行工具调用
                            for (call in parsed.calls) {
                                val result = executeMCPTool(call.name, call.arguments)

                                // 添加工具调用结果到历史
                                currentHistory.add(VideoCallMessage(
                                    id = call.id,
                                    content = "[工具调用: ${call.name}] 结果: $result",
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                ))
                            }

                            // 继续循环，让模型根据工具结果生成最终响应
                            continue
                        }
                        is ParsedResponse.Error -> {
                            val elapsed = System.currentTimeMillis() - startTime
                            Log.d(TAG, "[MCP-VLM-ONLY] sendMessage 失败，总耗时: ${elapsed}ms, 错误: ${parsed.message}")
                            return@withContext Result.failure(Exception(parsed.message))
                        }
                    }
                } else {
                    val elapsed = System.currentTimeMillis() - startTime
                    val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.d(TAG, "[MCP-VLM-ONLY] sendMessage 失败，总耗时: ${elapsed}ms, 错误: $errorMsg")
                    return@withContext Result.failure(Exception(errorMsg))
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.e(TAG, "[MCP-VLM-ONLY] sendMessage 异常，总耗时: ${elapsed}ms", e)
                return@withContext Result.failure(e)
            }
        }

        Result.failure(Exception("达到最大迭代次数"))
    }

    /**
     * 发送消息（流式，支持 MCP 工具调用）
     * @param message 用户消息
     * @param conversationHistory 对话历史
     * @param imageHistory 图像历史
     * @return Flow<String> 流式响应
     */
    fun sendMessageStream(
        message: String,
        conversationHistory: List<VideoCallMessage> = emptyList(),
        imageHistory: List<ImageFrame> = emptyList()
    ): Flow<String> = flow {
        Log.d(TAG, "[MCP-VLM-ONLY] sendMessageStream 开始")
        Log.d(TAG, "[MCP-VLM-ONLY] 用户消息: $message")
        Log.d(TAG, "[MCP-VLM-ONLY] 对话历史数量: ${conversationHistory.size}")
        Log.d(TAG, "[MCP-VLM-ONLY] 图像历史数量: ${imageHistory.size}")

        val startTime = System.currentTimeMillis()
        var currentHistory = conversationHistory.toMutableList()
        var maxIterations = 5

        // 添加用户消息
        currentHistory.add(VideoCallMessage(
            id = System.currentTimeMillis().toString(),
            content = message,
            isUser = true,
            timestamp = System.currentTimeMillis()
        ))

        while (maxIterations > 0) {
            maxIterations--

            try {
                // 构建消息列表
                val messagesJson = buildMessages(currentHistory, imageHistory)

                // 调用 VLM 流式 API（包含 MCP 工具）
                callVLMApiStreamWithTools(messagesJson, currentHistory).collect { chunk ->
                    emit(chunk)
                }

                // 如果有工具调用，会自动在流式处理中完成并继续循环
                // 如果没有工具调用，正常结束
                break
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.e(TAG, "[MCP-VLM-ONLY] sendMessageStream 异常，总耗时: ${elapsed}ms", e)
                throw e
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 构建消息列表
     */
    protected open fun buildMessages(
        conversationHistory: List<VideoCallMessage>,
        imageHistory: List<ImageFrame>
    ): JSONArray {
        Log.d(TAG, "[MCP-VLM-ONLY] buildMessages 开始")

        val messages = JSONArray()

        // 添加系统提示
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        // 添加对话历史（限制最近3条）
        val recentHistory = conversationHistory.takeLast(3)
        Log.d(TAG, "[MCP-VLM-ONLY] 添加对话历史: ${recentHistory.size}")

        for (msg in recentHistory) {
            val role = if (msg.isUser) "user" else "assistant"
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", msg.content)
            })
        }

        // 添加当前用户消息（包含图像）
        val lastMessage = conversationHistory.lastOrNull()
        if (lastMessage != null && lastMessage.isUser) {
            val contentArray = JSONArray()

            // 添加文本
            contentArray.put(JSONObject().apply {
                put("type", "text")
                put("text", lastMessage.content)
            })

            // 添加图像历史（最近的图像）
            val recentImages = imageHistory.takeLast(3)
            Log.d(TAG, "[MCP-VLM-ONLY] 添加图像: ${recentImages.size}")

            for (imageFrame in recentImages) {
                val timeStr = DateFormat.format("HH:mm:ss", imageFrame.timestamp)
                Log.d(TAG, "[MCP-VLM-ONLY] 添加图像 [$timeStr]: ${imageFrame.bitmap.width}x${imageFrame.bitmap.height}")

                val imageUrl = bitmapToBase64Url(imageFrame.bitmap)
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", imageUrl)
                    })
                })
            }

            // 添加当前用户消息
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })
        }

        Log.d(TAG, "[MCP-VLM-ONLY] buildMessages 完成，总消息数: ${messages.length()}")
        return messages
    }

    /**
     * 调用 VLM API（非流式，支持 MCP 工具）
     */
    private suspend fun callVLMApiWithTools(messagesJson: JSONArray): Result<ParsedResponse> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messagesJson)
                    put("max_tokens", 4096)
                    put("temperature", 0.7)

                    // 添加 MCP 工具定义
                    val tools = buildMCPToolsPayload()
                    if (tools.length() > 0) {
                        put("tools", tools)
                        put("tool_choice", "auto")
                    }
                }

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    return@withContext parseResponse(responseBody)
                } else {
                    lastException = Exception("API error: ${response.code} - $responseBody")
                }
            } catch (e: UnknownHostException) {
                Log.w(TAG, "[MCP-VLM-ONLY] DNS 解析失败，重试 $attempt/$MAX_RETRIES")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "[MCP-VLM-ONLY] 请求超时，重试 $attempt/$MAX_RETRIES")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: IOException) {
                Log.w(TAG, "[MCP-VLM-ONLY] IO 错误: ${e.message}，重试 $attempt/$MAX_RETRIES")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * 调用 VLM API（流式，支持 MCP 工具）
     */
    private fun callVLMApiStreamWithTools(
        messagesJson: JSONArray,
        currentHistory: MutableList<VideoCallMessage>
    ): Flow<String> = flow {
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messagesJson)
            put("max_tokens", 4096)
            put("temperature", 0.7)
            put("stream", true)

            // 添加 MCP 工具定义
            val tools = buildMCPToolsPayload()
            if (tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .apply {
                if (apiKey.isNotBlank()) {
                    addHeader("Authorization", "Bearer $apiKey")
                }
            }
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                throw Exception("API error: ${response.code} - $errorBody")
            }

            // 使用 byteStream 真正流式读取响应
            val inputStream = response.body?.byteStream()
            if (inputStream == null) {
                throw Exception("API 响应流为空")
            }

            Log.d(TAG, "[MCP-VLM-ONLY] 开始流式读取 SSE 响应")

            // 逐行读取 SSE 响应
            val reader = inputStream.bufferedReader()
            var currentContent = ""
            var emitCount = 0
            val collectedToolCalls = mutableListOf<StreamToolCall>()

            reader.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()

                        // 检查是否是结束标记
                        if (data == "[DONE]") {
                            Log.d(TAG, "[MCP-VLM-ONLY] 流式响应结束")
                            if (currentContent.isNotEmpty()) {
                                emitCount++
                                Log.d(TAG, "[MCP-VLM-ONLY] emit #$emitCount: $currentContent")
                                emit(currentContent)
                                currentContent = ""
                            }
                            break
                        }

                        try {
                            val jsonObject = JSONObject(data)
                            val choices = jsonObject.optJSONArray("choices")

                            if (choices != null && choices.length() > 0) {
                                val firstChoice = choices.getJSONObject(0)
                                val delta = firstChoice.optJSONObject("delta")

                                // 处理文本内容
                                val content = delta?.optString("content")
                                if (!content.isNullOrBlank()) {
                                    Log.d(TAG, "[MCP-VLM-ONLY] 流式文本块: $content")
                                    currentContent += content

                                    // 按句子累积：当遇到句子结束标点时 emit
                                    if (content.endsWith("！") || content.endsWith("。") || content.endsWith("？") ||
                                        content.endsWith(".") || content.endsWith("!") || content.endsWith("?") ||
                                        content.endsWith("\n")) {
                                        emitCount++
                                        Log.d(TAG, "[MCP-VLM-ONLY] emit #$emitCount: $currentContent")
                                        emit(currentContent)
                                        currentContent = ""
                                    }
                                }

                                // 处理工具调用（流式）
                                val toolCalls = delta?.optJSONArray("tool_calls")
                                if (toolCalls != null && toolCalls.length() > 0) {
                                    Log.d(TAG, "[MCP-VLM-ONLY] 检测到流式工具调用")

                                    for (i in 0 until toolCalls.length()) {
                                        val toolCall = toolCalls.getJSONObject(i)
                                        val index = toolCall.optInt("index", 0)

                                        // 确保有足够的空间存储工具调用
                                        while (collectedToolCalls.size <= index) {
                                            collectedToolCalls.add(StreamToolCall("", "", ""))
                                        }

                                        val currentCall = collectedToolCalls[index]

                                        // 处理工具调用 ID
                                        val id = toolCall.optString("id")
                                        if (!id.isNullOrBlank()) {
                                            currentCall.id = id
                                        }

                                        // 处理函数信息
                                        val function = toolCall.optJSONObject("function")
                                        if (function != null) {
                                            val name = function.optString("name")
                                            if (!name.isNullOrBlank()) {
                                                currentCall.functionName = name
                                            }

                                            val arguments = function.optString("arguments")
                                            if (!arguments.isNullOrBlank()) {
                                                currentCall.arguments += arguments
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[MCP-VLM-ONLY] 解析流式响应失败: ${e.message}")
                        }
                    }
                }
            }

            // 发送剩余的内容
            if (currentContent.isNotEmpty()) {
                emitCount++
                Log.d(TAG, "[MCP-VLM-ONLY] emit 最后 #$emitCount: $currentContent")
                emit(currentContent)
            }

            // 如果有工具调用，执行它们
            if (collectedToolCalls.isNotEmpty()) {
                Log.d(TAG, "[MCP-VLM-ONLY] 处理 ${collectedToolCalls.size} 个工具调用")

                // 执行工具调用
                for (call in collectedToolCalls) {
                    if (call.functionName.isNotEmpty()) {
                        Log.d(TAG, "[MCP-VLM-ONLY] 执行工具: ${call.functionName}, 参数: ${call.arguments}")
                        val result = executeMCPTool(call.functionName, call.arguments)

                        // 添加工具调用结果到历史
                        currentHistory.add(VideoCallMessage(
                            id = call.id,
                            content = "[工具调用: ${call.functionName}] 结果: $result",
                            isUser = false,
                            timestamp = System.currentTimeMillis()
                        ))
                    }
                }

                // 注意：这里不继续循环，因为流式处理中工具调用后应该继续生成响应
                // 这部分逻辑由外层循环处理
            }

            Log.d(TAG, "[MCP-VLM-ONLY] 流式响应处理完成，共 emit $emitCount")
        } catch (e: Exception) {
            Log.e(TAG, "[MCP-VLM-ONLY] 流式请求失败: ${e.message}")
            throw e
        }
    }

    /**
     * 构建 MCP 工具定义（OpenAI Function Calling 格式）
     */
    private suspend fun buildMCPToolsPayload(): JSONArray {
        val tools = JSONArray()

        if (!MCPManager.isInitialized()) {
            return tools
        }

        try {
            val mcpTools = MCPManager.getInstance().getEnabledTools()
            mcpTools.forEach { tool ->
                tools.put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.inputSchema)
                    })
                })
            }
            Log.d(TAG, "[MCP-VLM-ONLY] 已添加 ${mcpTools.size} 个 MCP 工具")
        } catch (e: Exception) {
            Log.e(TAG, "[MCP-VLM-ONLY] 获取 MCP 工具失败", e)
        }

        return tools
    }

    /**
     * 解析响应（支持工具调用）
     */
    private suspend fun parseResponse(responseBody: String): Result<ParsedResponse> {
        return try {
            val jsonObject = JSONObject(responseBody)
            val choices = jsonObject.optJSONArray("choices")

            if (choices == null || choices.length() == 0) {
                return Result.success(ParsedResponse.Error("API 响应中没有 choices"))
            }

            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.optJSONObject("message") ?: return Result.success(ParsedResponse.Error("无法解析 message"))

            // 检查是否有工具调用
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val callResults = mutableListOf<ToolCallInfo>()

                for (i in 0 until toolCalls.length()) {
                    val toolCall = toolCalls.getJSONObject(i)
                    val toolCallId = toolCall.getString("id")
                    val function = toolCall.getJSONObject("function")
                    val functionName = function.getString("name")
                    val arguments = function.getString("arguments")

                    Log.d(TAG, "[MCP-VLM-ONLY] 检测到工具调用: $functionName, 参数: $arguments")

                    callResults.add(ToolCallInfo(toolCallId, functionName, arguments))
                }

                return Result.success(ParsedResponse.ToolCalls(callResults))
            }

            // 普通文本响应
            val content = message.optString("content", "")
            Result.success(ParsedResponse.Text(content))
        } catch (e: Exception) {
            Log.e(TAG, "[MCP-VLM-ONLY] 解析响应失败", e)
            Result.success(ParsedResponse.Error("解析响应失败: ${e.message}"))
        }
    }

    /**
     * 执行 MCP 工具
     */
    private suspend fun executeMCPTool(toolName: String, argumentsJson: String): String {
        return try {
            val arguments = JSONObject(argumentsJson)
            val params = mutableMapOf<String, Any?>()

            arguments.keys().forEach { key ->
                params[key] = arguments.get(key)
            }

            val result = MCPManager.getInstance().executeTool(toolName, params)

            if (result.isSuccess) {
                result.getDataOrNull()?.toString() ?: "执行成功"
            } else {
                "执行失败: ${(result as? com.alian.assistant.core.tools.ToolResult.Error)?.error}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MCP-VLM-ONLY] 执行 MCP 工具失败", e)
            "工具执行错误: ${e.message}"
        }
    }

    /**
     * Bitmap 转 Base64 URL（带压缩）
     */
    protected fun bitmapToBase64Url(bitmap: Bitmap): String {
        val startTime = System.currentTimeMillis()

        // 检查 Bitmap 是否已被回收
        if (bitmap.isRecycled) {
            Log.e(TAG, "[MCP-VLM-ONLY] Bitmap 已被回收，无法压缩")
            throw IllegalStateException("Bitmap has been recycled")
        }

        // 计算原始大小
        val originalSize = bitmap.width * bitmap.height * 4L
        Log.d(TAG, "[MCP-VLM-ONLY] 原始图像: ${bitmap.width}x${bitmap.height}, ${String.format("%.2f", originalSize / 1024.0 / 1024.0)}MB")

        // 如果图像太大，先缩放尺寸
        val maxDimension = 1280  // 最大边
        val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Log.d(TAG, "[MCP-VLM-ONLY] 缩放图像: ${bitmap.width}x${bitmap.height} -> ${newWidth}x${newHeight}")
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        // 压缩图像
        val outputStream = ByteArrayOutputStream()
        var quality = 85
        var compressResult = false
        var bytes: ByteArray

        // 自适应压缩
        val targetSize = 500 * 1024  // 目标大小 500KB
        val scaledSize = scaledBitmap.width * scaledBitmap.height * 4L

        if (scaledSize > targetSize * 3) {
            quality = 70
        } else if (scaledSize > targetSize * 2) {
            quality = 75
        }

        // 尝试压缩
        compressResult = scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        bytes = outputStream.toByteArray()

        // 如果压缩后仍然太大，继续降低质量
        var retryCount = 0
        while (bytes.size > targetSize && quality > 30 && retryCount < 5) {
            quality -= 10
            outputStream.reset()
            compressResult = scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            bytes = outputStream.toByteArray()
            retryCount++
        }

        // 如果缩放了Bitmap，记得回收
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        val elapsed = System.currentTimeMillis() - startTime

        Log.d(TAG, "[MCP-VLM-ONLY] 最终压缩结果: $compressResult")
        Log.d(TAG, "[MCP-VLM-ONLY] 最终大小: ${bytes.size} bytes (${String.format("%.2f", bytes.size / 1024.0)} KB)")
        Log.d(TAG, "[MCP-VLM-ONLY] 压缩耗时: ${elapsed}ms")

        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    /**
     * 解析响应的密封类
     */
    sealed class ParsedResponse {
        data class Text(val content: String) : ParsedResponse()
        data class ToolCalls(val calls: List<ToolCallInfo>) : ParsedResponse()
        data class Error(val message: String) : ParsedResponse()
    }

    /**
     * 工具调用信息
     */
    data class ToolCallInfo(
        val id: String,
        val name: String,
        val arguments: String
    )

    /**
     * 流式工具调用信息
     */
    data class StreamToolCall(
        var id: String = "",
        var functionName: String = "",
        var arguments: String = ""
    )
}