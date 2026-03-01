package com.alian.assistant.presentation.ui.screens.videocall

import android.text.format.DateFormat
import android.util.Log
import com.alian.assistant.core.mcp.MCPManager
import com.alian.assistant.presentation.viewmodel.ImageRecognitionResult
import com.alian.assistant.presentation.viewmodel.VideoCallMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 视频通话客户端（支持 MCP 工具调用）
 * 基于 VideoCallClient，增加工具调用支持
 */
class MCPVideoCallClient(
    private val apiKey: String,
    baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    model: String = "qwen-plus"
) {
    companion object {
        private const val TAG = "MCPVideoCallClient"
    }

    private val baseUrl: String = normalizeUrl(baseUrl)
    private val model: String = model

    /**
     * 发送消息（非流式，支持工具调用）
     */
    suspend fun sendMessage(
        message: String,
        conversationHistory: List<VideoCallMessage> = emptyList(),
        imageHistory: List<ImageRecognitionResult> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "[DEBUG] sendMessage 开始")
        Log.d(TAG, "[DEBUG] 用户消息: $message")
        Log.d(TAG, "[DEBUG] 对话历史数量: ${conversationHistory.size}")

        val startTime = System.currentTimeMillis()

        // 构建消息列表
        val messagesJson = buildMessages(message, conversationHistory, imageHistory)
        Log.d(TAG, "[DEBUG] 消息构建完成，总消息数: ${messagesJson.length()}")

        // 调用带工具支持的 LLM 方法
        val result = predictWithTools(messagesJson)

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
     * 注意：流式模式暂不支持工具调用
     */
    fun sendMessageStream(
        message: String,
        conversationHistory: List<VideoCallMessage> = emptyList(),
        imageHistory: List<ImageRecognitionResult> = emptyList()
    ): Flow<String> = flow {
        Log.d(TAG, "[DEBUG] sendMessageStream 开始")
        Log.d(TAG, "[DEBUG] 用户消息: $message")

        // 构建消息列表
        val messagesJson = buildMessages(message, conversationHistory, imageHistory)
        Log.d(TAG, "[DEBUG] 消息构建完成，总消息数: ${messagesJson.length()}")

        // 使用 HTTP 客户端发送流式请求
        val result = sendStreamRequest(messagesJson)

        result.collect { chunk ->
            emit(chunk)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 调用 LLM（支持工具调用）
     */
    private suspend fun predictWithTools(
        messagesJson: JSONArray
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        var currentMessages = copyMessages(messagesJson)
        var maxIterations = 5

        while (maxIterations > 0) {
            maxIterations--

            try {
                // 构建请求
                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", currentMessages)
                    put("max_tokens", 4096)
                    put("temperature", 0.7)

                    // 添加 MCP 工具定义
                    val tools = buildMCPToolsPayload()
                    if (tools.length() > 0) {
                        put("tools", tools)
                        put("tool_choice", "auto")
                    }
                }

                // 发送请求
                val response = sendHTTPRequest(requestBody.toString())

                if (response.isFailure) {
                    lastException = response.exceptionOrNull() as? Exception
                    break
                }

                val responseBody = response.getOrNull() ?: ""
                val parsed = parseResponse(responseBody)

                when (parsed) {
                    is ParsedResponse.Text -> {
                        // 普通文本响应，直接返回
                        return@withContext Result.success(parsed.content)
                    }
                    is ParsedResponse.ToolCalls -> {
                        // 工具调用，需要继续对话
                        Log.d(TAG, "[DEBUG] 处理 ${parsed.calls.size} 个工具调用")

                        // 执行工具调用
                        val toolResults = mutableListOf<ToolCallResult>()
                        for (call in parsed.calls) {
                            val result = executeMCPTool(call.name, call.arguments)
                            toolResults.add(ToolCallResult(call.id, call.name, result))
                        }

                        // 将工具调用和结果添加到消息历史
                        currentMessages = appendToolCallsToMessages(
                            currentMessages,
                            parsed.calls,
                            toolResults
                        )

                        // 继续循环
                        continue
                    }
                    is ParsedResponse.Error -> {
                        lastException = Exception(parsed.message)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[DEBUG] predictWithTools 失败", e)
                lastException = e
                break
            }
        }

        Result.failure(lastException ?: Exception("未知错误"))
    }

    /**
     * 发送 HTTP 请求
     */
    private suspend fun sendHTTPRequest(requestBody: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient()

            val request = okhttp3.Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("API error: ${response.code} - ${response.message}")
                )
            }

            val responseBody = response.body?.string() ?: ""
            Result.success(responseBody)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 发送流式请求
     */
    private fun sendStreamRequest(messagesJson: JSONArray): Flow<String> = flow {
        try {
            val client = okhttp3.OkHttpClient()

            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", messagesJson)
                put("max_tokens", 4096)
                put("temperature", 0.7)
                put("stream", true)
            }

            val request = okhttp3.Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("API error: ${response.code} - ${response.message}")
            }

            // 解析 SSE 流式响应
            val inputStream = response.body?.byteStream()
            if (inputStream == null) {
                throw Exception("API 响应流为空")
            }

            val reader = inputStream.bufferedReader()
            var currentContent = ""

            reader.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()

                        if (data == "[DONE]") {
                            if (currentContent.isNotEmpty()) {
                                emit(currentContent)
                                currentContent = ""
                            }
                            break
                        }

                        try {
                            val json = JSONObject(data)
                            val choices = json.getJSONArray("choices")
                            if (choices.length() > 0) {
                                val delta = choices.getJSONObject(0).getJSONObject("delta")
                                val content = delta.optString("content", "")
                                if (content.isNotEmpty()) {
                                    currentContent += content

                                    // 按句子累积
                                    if (content.endsWith("。") || content.endsWith("！") || content.endsWith("？") ||
                                        content.endsWith(".") || content.endsWith("!") || content.endsWith("?") ||
                                        content.endsWith("\n")) {
                                        emit(currentContent)
                                        currentContent = ""
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[DEBUG] 解析流式数据块失败", e)
                        }
                    }
                }
            }

            if (currentContent.isNotEmpty()) {
                emit(currentContent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] sendStreamRequest 失败", e)
            throw e
        }
    }

    /**
     * 构建 MCP 工具定义
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
            Log.d(TAG, "[DEBUG] 已添加 ${mcpTools.size} 个 MCP 工具")
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] 获取 MCP 工具失败", e)
        }

        return tools
    }

    /**
     * 解析响应
     */
    private suspend fun parseResponse(responseBody: String): ParsedResponse {
        return try {
            val json = JSONObject(responseBody)
            val choices = json.getJSONArray("choices")

            if (choices.length() == 0) {
                return ParsedResponse.Error("No response from model")
            }

            val message = choices.getJSONObject(0).getJSONObject("message")

            // 检查是否有工具调用
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val calls = mutableListOf<ToolCallInfo>()
                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.getJSONObject(i)
                    calls.add(ToolCallInfo(
                        id = tc.getString("id"),
                        name = tc.getJSONObject("function").getString("name"),
                        arguments = tc.getJSONObject("function").getString("arguments")
                    ))
                }
                return ParsedResponse.ToolCalls(calls)
            }

            // 普通文本响应
            val content = message.getString("content")
            ParsedResponse.Text(content)
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] 解析响应失败", e)
            ParsedResponse.Error("解析响应失败: ${e.message}")
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
            Log.e(TAG, "[DEBUG] 执行 MCP 工具失败", e)
            "工具执行错误: ${e.message}"
        }
    }

    /**
     * 将工具调用结果添加到消息历史
     */
    private fun appendToolCallsToMessages(
        messages: JSONArray,
        toolCalls: List<ToolCallInfo>,
        toolResults: List<ToolCallResult>
    ): JSONArray {
        val newMessages = JSONArray()

        // 复制原有消息
        for (i in 0 until messages.length()) {
            newMessages.put(messages.getJSONObject(i))
        }

        // 添加 assistant 的工具调用消息
        val toolCallsJson = JSONArray()
        toolCalls.forEach { call ->
            toolCallsJson.put(JSONObject().apply {
                put("id", call.id)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", call.name)
                    put("arguments", call.arguments)
                })
            })
        }

        newMessages.put(JSONObject().apply {
            put("role", "assistant")
            put("tool_calls", toolCallsJson)
        })

        // 添加工具结果
        toolResults.forEach { result ->
            newMessages.put(JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", result.id)
                put("content", result.result)
            })
        }

        return newMessages
    }

    /**
     * 复制消息数组
     */
    private fun copyMessages(messages: JSONArray): JSONArray {
        val newMessages = JSONArray()
        for (i in 0 until messages.length()) {
            newMessages.put(messages.getJSONObject(i))
        }
        return newMessages
    }

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

    /**
     * 规范化 URL
     */
    private fun normalizeUrl(url: String): String {
        var normalized = url.trim().removeSuffix("/")
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized
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
     * 工具调用结果
     */
    data class ToolCallResult(
        val id: String,
        val name: String,
        val result: String
    )
}