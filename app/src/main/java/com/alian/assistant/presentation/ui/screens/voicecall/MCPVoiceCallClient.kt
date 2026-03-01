package com.alian.assistant.presentation.ui.screens.voicecall

import android.util.Log
import com.alian.assistant.core.mcp.MCPManager
import com.alian.assistant.presentation.viewmodel.VoiceCallMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 语音通话客户端（支持 MCP 工具调用）
 * 基于 VoiceCallClient，增加工具调用支持
 */
class MCPVoiceCallClient(
    private val apiKey: String,
    private val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    private val model: String = "qwen-plus",
    private val systemPrompt: String
) {
    companion object {
        private const val TAG = "MCPVoiceCallClient"
        private const val CHAT_ENDPOINT = "/chat/completions"
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * 发送消息（支持工具调用）
     */
    suspend fun sendMessage(
        message: String,
        conversationHistory: List<VoiceCallMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        var currentHistory = conversationHistory.toMutableList()
        var maxIterations = 5

        // 添加用户消息
        currentHistory.add(VoiceCallMessage(
            id = System.currentTimeMillis().toString(),
            content = message,
            isUser = true,
            timestamp = System.currentTimeMillis()
        ))

        while (maxIterations > 0) {
            maxIterations--

            try {
                Log.d(TAG, "=== 发送消息（尝试 ${5 - maxIterations}/5） ===")
                Log.d(TAG, "消息内容: $message")

                // 构建请求体（包含 MCP 工具）
                val requestBody = buildRequestBody(message, currentHistory, stream = false)
                Log.d(TAG, "请求体: $requestBody")

                // 创建请求
                val request = Request.Builder()
                    .url("$baseUrl$CHAT_ENDPOINT")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody(jsonMediaType))
                    .build()

                // 发送请求
                val response: Response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorMsg = "API 请求失败: ${response.code} ${response.message}"
                    Log.e(TAG, errorMsg)
                    lastException = Exception(errorMsg)
                    break
                }

                // 解析响应
                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    val errorMsg = "API 响应为空"
                    Log.e(TAG, errorMsg)
                    lastException = Exception(errorMsg)
                    break
                }

                Log.d(TAG, "API 响应: $responseBody")

                // 解析响应（检查是否有工具调用）
                val parsed = parseResponse(responseBody)

                when (parsed) {
                    is ParsedResponse.Text -> {
                        // 普通文本响应，直接返回
                        return@withContext Result.success(parsed.content)
                    }
                    is ParsedResponse.ToolCalls -> {
                        // 工具调用，需要继续对话
                        Log.d(TAG, "处理 ${parsed.calls.size} 个工具调用")

                        // 执行工具调用
                        val toolResults = mutableListOf<ToolCallResult>()
                        for (call in parsed.calls) {
                            val result = executeMCPTool(call.name, call.arguments)
                            toolResults.add(ToolCallResult(call.id, call.name, result))

                            // 添加工具调用结果到历史
                                                    currentHistory.add(VoiceCallMessage(
                                                        id = call.id,
                                                        content = "[工具调用: ${call.name}] 结果: $result",
                                                        isUser = false,
                                                        timestamp = System.currentTimeMillis()
                                                    ))                        }

                        // 继续循环，让模型根据工具结果生成最终响应
                        continue
                    }
                    is ParsedResponse.Error -> {
                        lastException = Exception(parsed.message)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送消息失败", e)
                lastException = e
                break
            }
        }

        Result.failure(lastException ?: Exception("未知错误"))
    }

    /**
     * 发送消息（流式，支持工具调用）
     */
    fun sendMessageStream(
        message: String,
        conversationHistory: List<VoiceCallMessage>
    ): Flow<String> = flow {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "=== 流式消息开始 ===")
        Log.d(TAG, "消息内容: $message")

        var currentHistory = conversationHistory.toMutableList()
        var maxIterations = 5

        // 添加用户消息
        currentHistory.add(VoiceCallMessage(
            id = System.currentTimeMillis().toString(),
            content = message,
            isUser = true,
            timestamp = System.currentTimeMillis()
        ))

        while (maxIterations > 0) {
            maxIterations--

            var response: Response? = null
            var toolCallsBuffer = StringBuffer()
            var currentToolCallId = ""
            var currentToolCallFunctionName = ""
            var currentToolCallArguments = StringBuffer()

            try {
                // 构建请求体（包含 MCP 工具）
                val requestBody = buildRequestBodyWithHistory(currentHistory, stream = true)
                Log.d(TAG, "流式请求体: $requestBody")

                // 创建请求
                val request = Request.Builder()
                    .url("$baseUrl$CHAT_ENDPOINT")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody(jsonMediaType))
                    .build()

                // 发送请求
                Log.d(TAG, "开始发送 HTTP 请求...")
                response = client.newCall(request).execute()
                Log.d(TAG, "HTTP 请求完成，响应码: ${response.code}")

                if (!response.isSuccessful) {
                    val errorMsg = "API 请求失败: ${response.code} ${response.message}"
                    Log.e(TAG, errorMsg)
                    throw Exception(errorMsg)
                }

                // 使用 byteStream 真正流式读取响应
                val inputStream = response.body?.byteStream()
                if (inputStream == null) {
                    Log.e(TAG, "API 响应流为空")
                    throw Exception("API 响应流为空")
                }

                Log.d(TAG, "开始流式读取 SSE 响应")

                // 逐行读取 SSE 响应
                val reader = inputStream.bufferedReader()
                var currentContent = ""
                var hasToolCalls = false
                val collectedToolCalls = mutableListOf<StreamToolCall>()

                reader.useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("data: ")) {
                            val data = line.substring(6).trim()

                            // 检查是否是结束标记
                            if (data == "[DONE]") {
                                Log.d(TAG, "流式响应结束")
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
                                    // 只有当 content 不为 null 且不为空时才处理，避免输出 "null" 给 TTS
                                    if (content != null && content.isNotEmpty() && !"null".equals(content)) {
                                        Log.d(TAG, "流式文本块: $content")
                                        currentContent += content

                                        // 按句子累积：当遇到句子结束标点时 emit
                                        if (content.endsWith("。") || content.endsWith("！") || content.endsWith("？") ||
                                            content.endsWith(".") || content.endsWith("!") || content.endsWith("?") ||
                                            content.endsWith("\n")) {
                                            emit(currentContent)
                                            currentContent = ""
                                        }
                                    }

                                    // 处理工具调用（流式）
                                    val toolCalls = delta?.optJSONArray("tool_calls")
                                    if (toolCalls != null && toolCalls.length() > 0) {
                                        hasToolCalls = true
                                        Log.d(TAG, "检测到流式工具调用")

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
                                Log.e(TAG, "解析流式数据块失败", e)
                            }
                        }
                    }
                }

                // 发送剩余的内容
                if (currentContent.isNotEmpty()) {
                    emit(currentContent)
                }

                // 如果有工具调用，执行它们
                if (hasToolCalls && collectedToolCalls.isNotEmpty()) {
                    Log.d(TAG, "处理 ${collectedToolCalls.size} 个工具调用")

                    // 执行工具调用
                    for (call in collectedToolCalls) {
                        if (call.functionName.isNotEmpty()) {
                            Log.d(TAG, "执行工具: ${call.functionName}, 参数: ${call.arguments}")
                            val result = executeMCPTool(call.functionName, call.arguments)

                            // 添加工具调用结果到历史
                            currentHistory.add(VoiceCallMessage(
                                id = call.id,
                                content = "[工具调用: ${call.functionName}] 结果: $result",
                                isUser = false,
                                timestamp = System.currentTimeMillis()
                            ))
                        }
                    }

                    // 继续循环，让模型根据工具结果生成最终响应
                    continue
                }

                // 如果没有工具调用，正常结束
                val endTime = System.currentTimeMillis()
                Log.d(TAG, "=== 流式消息结束，总耗时: ${endTime - startTime}ms ===")
                break

            } catch (e: Exception) {
                val endTime = System.currentTimeMillis()
                Log.e(TAG, "=== 流式消息失败，总耗时: ${endTime - startTime}ms ===", e)
                throw e
            } finally {
                response?.close()
            }
        }
    }.flowOn(Dispatchers.IO)


    /**
     * 构建请求体（支持 MCP 工具）
     */
    private suspend fun buildRequestBody(
        message: String,
        conversationHistory: List<VoiceCallMessage>,
        stream: Boolean = false
    ): String {
        val messagesArray = JSONArray()

        // 添加系统提示词
        messagesArray.put(
            JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            }
        )

        // 添加对话历史
        conversationHistory.forEach { msg ->
            messagesArray.put(
                JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "assistant")
                    put("content", msg.content)
                }
            )
        }

        // 添加当前消息
        messagesArray.put(
            JSONObject().apply {
                put("role", "user")
                put("content", message)
            }
        )

        // 构建请求体
        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("temperature", 0.7)
            put("max_tokens", 500)
            put("stream", stream)

            // 添加 MCP 工具定义
            val tools = buildMCPToolsPayload()
            if (tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }

        return requestJson.toString()
    }

    /**
     * 构建请求体（使用当前历史记录，用于流式工具调用循环）
     */
    private suspend fun buildRequestBodyWithHistory(
        conversationHistory: List<VoiceCallMessage>,
        stream: Boolean = false
    ): String {
        val messagesArray = JSONArray()

        // 添加系统提示词
        messagesArray.put(
            JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            }
        )

        // 添加对话历史
        conversationHistory.forEach { msg ->
            messagesArray.put(
                JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "assistant")
                    put("content", msg.content)
                }
            )
        }

        // 构建请求体
        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("temperature", 0.7)
            put("max_tokens", 500)
            put("stream", stream)

            // 添加 MCP 工具定义
            val tools = buildMCPToolsPayload()
            if (tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }

        return requestJson.toString()
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
            Log.d(TAG, "已添加 ${mcpTools.size} 个 MCP 工具")
        } catch (e: Exception) {
            Log.e(TAG, "获取 MCP 工具失败", e)
        }

        return tools
    }

    /**
     * 解析响应（支持工具调用）
     */
    private suspend fun parseResponse(responseBody: String): ParsedResponse {
        return try {
            val jsonObject = JSONObject(responseBody)
            val choices = jsonObject.optJSONArray("choices")

            if (choices == null || choices.length() == 0) {
                return ParsedResponse.Error("API 响应中没有 choices")
            }

            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.optJSONObject("message") ?: return ParsedResponse.Error("无法解析 message")

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

                    Log.d(TAG, "检测到工具调用: $functionName, 参数: $arguments")

                    callResults.add(ToolCallInfo(toolCallId, functionName, arguments))
                }

                return ParsedResponse.ToolCalls(callResults)
            }

            // 普通文本响应
            val content = message.optString("content", "")
            ParsedResponse.Text(content)
        } catch (e: Exception) {
            Log.e(TAG, "解析响应失败", e)
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
            Log.e(TAG, "执行 MCP 工具失败", e)
            "工具执行错误: ${e.message}"
        }
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

    /**
     * 流式工具调用信息
     */
    data class StreamToolCall(
        var id: String = "",
        var functionName: String = "",
        var arguments: String = ""
    )
}