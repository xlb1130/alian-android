package com.alian.assistant.presentation.ui.screens.voicecall

import android.util.Log
import com.alian.assistant.presentation.viewmodel.VoiceCallMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 语音通话客户端
 * 负责调用 qwen-plus API 进行对话
 */
class VoiceCallClient(
    private val apiKey: String,
    private val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    private val model: String = "qwen-plus",
    private val systemPrompt: String
) {
    companion object {
        private const val TAG = "VoiceCallClient"
        private const val CHAT_ENDPOINT = "/chat/completions"
        private const val MAX_RETRY_COUNT = 3  // 最大重试次数
        private const val RETRY_DELAY_MS = 1000L  // 重试延迟（毫秒）
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)  // 增加读取超时时间
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * 发送消息（非流式）
     * @param message 用户消息
     * @param conversationHistory 对话历史
     * @return Result<String> API 响应结果
     */
    suspend fun sendMessage(
        message: String,
        conversationHistory: List<VoiceCallMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        // 重试机制
        repeat(MAX_RETRY_COUNT) { retryCount ->
            val startTime = System.currentTimeMillis()

            try {
                Log.d(TAG, "=== 大模型调用开始 (非流式) ===")
                Log.d(TAG, "开始时间: $startTime")
                Log.d(TAG, "发送消息 (尝试 ${retryCount + 1}/$MAX_RETRY_COUNT): $message")
                Log.d(TAG, "对话历史大小: ${conversationHistory.size}")

                // 构建请求体
                val requestBody = buildRequestBody(message, conversationHistory, stream = false)
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

                    // 检查是否是认证错误（401）
                    if (response.code == 401) {
                        return@withContext Result.failure(Exception("API Key 无效或已过期，请检查设置"))
                    }

                    // 检查是否是限流错误（429）
                    if (response.code == 429) {
                        Log.w(TAG, "API 限流，等待后重试")
                        delay(RETRY_DELAY_MS * (retryCount + 1))
                        return@repeat  // 继续重试
                    }

                    lastException = Exception(errorMsg)
                    if (retryCount < MAX_RETRY_COUNT - 1) {
                        Log.d(TAG, "等待 $RETRY_DELAY_MS ms 后重试...")
                        delay(RETRY_DELAY_MS)
                        return@repeat  // 继续重试
                    }
                    return@withContext Result.failure(lastException ?: Exception(errorMsg))
                }

                // 解析响应
                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    val errorMsg = "API 响应为空"
                    Log.e(TAG, errorMsg)
                    lastException = Exception(errorMsg)
                    if (retryCount < MAX_RETRY_COUNT - 1) {
                        Log.d(TAG, "等待 $RETRY_DELAY_MS ms 后重试...")
                        delay(RETRY_DELAY_MS)
                        return@repeat  // 继续重试
                    }
                    return@withContext Result.failure(lastException ?: Exception(errorMsg))
                }

                Log.d(TAG, "API 响应: $responseBody")

                val content = parseResponse(responseBody)
                if (content.isNullOrBlank()) {
                    val errorMsg = "无法解析 API 响应"
                    Log.e(TAG, errorMsg)
                    lastException = Exception(errorMsg)
                    if (retryCount < MAX_RETRY_COUNT - 1) {
                        Log.d(TAG, "等待 $RETRY_DELAY_MS ms 后重试...")
                        delay(RETRY_DELAY_MS)
                        return@repeat  // 继续重试
                    }
                    return@withContext Result.failure(lastException ?: Exception(errorMsg))
                }

                val endTime = System.currentTimeMillis()
                val totalTime = endTime - startTime
                Log.d(TAG, "=== 大模型调用结束 (非流式) ===")
                Log.d(TAG, "结束时间: $endTime")
                Log.d(TAG, "总耗时: ${totalTime}ms")
                Log.d(TAG, "解析后的内容: $content")
                return@withContext Result.success(content)

            } catch (e: Exception) {
                val endTime = System.currentTimeMillis()
                val totalTime = endTime - startTime
                Log.e(TAG, "=== 大模型调用失败 (非流式) ===")
                Log.e(TAG, "结束时间: $endTime")
                Log.e(TAG, "总耗时: ${totalTime}ms")
                Log.e(TAG, "发送消息失败 (尝试 ${retryCount + 1}/$MAX_RETRY_COUNT)", e)
                lastException = e

                // 检查是否是网络错误
                if (e is UnknownHostException || e is SocketTimeoutException || e is IOException) {
                    Log.w(TAG, "网络错误，等待后重试")
                    if (retryCount < MAX_RETRY_COUNT - 1) {
                        delay(RETRY_DELAY_MS * (retryCount + 1))
                        return@repeat  // 继续重试
                    }
                }

                // 其他错误直接返回
                return@withContext Result.failure(e)
            }
        }

        // 所有重试都失败
        Result.failure(lastException ?: Exception("未知错误"))
    }

    /**
     * 发送消息（流式）
     * @param message 用户消息
     * @param conversationHistory 对话历史
     * @return Flow<String> 流式返回 LLM 生成的文本块
     */
    fun sendMessageStream(
        message: String,
        conversationHistory: List<VoiceCallMessage>
    ): Flow<String> = flow {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "=== 大模型调用开始 ===")
        Log.d(TAG, "开始时间: $startTime")
        Log.d(TAG, "发送流式消息: $message")
        Log.d(TAG, "对话历史大小: ${conversationHistory.size}")

        var response: Response? = null

        try {
            // 构建请求体
            val requestBody = buildRequestBody(message, conversationHistory, stream = true)
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
            var emitCount = 0
            var firstTokenTime: Long? = null
            var totalBytesRead = 0L

            reader.useLines { lines ->
                for (line in lines) {
                    totalBytesRead += line.toByteArray().size

                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()

                        // 检查是否是结束标记
                        if (data == "[DONE]") {
                            Log.d(TAG, "流式响应结束")
                            if (currentContent.isNotEmpty()) {
                                emitCount++
                                Log.d(TAG, "emit #$emitCount: $currentContent")
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
                                val content = delta?.optString("content")

                                if (!content.isNullOrBlank()) {
                                    // 记录首 token 时间（真正的首 token 到达时间）
                                    if (firstTokenTime == null) {
                                        firstTokenTime = System.currentTimeMillis()
                                        val timeToFirstToken = firstTokenTime!! - startTime
                                        Log.d(TAG, "=== 首 token 到达 ===")
                                        Log.d(TAG, "首 token 时间: $firstTokenTime")
                                        Log.d(TAG, "首 token 延迟: ${timeToFirstToken}ms")
                                        Log.d(TAG, "首 token 内容: $content")
                                        Log.d(TAG, "已读取字节数: ${totalBytesRead}")
                                    }

                                    Log.d(TAG, "流式文本块: $content")
                                    currentContent += content

                                    // 按句子累积：当遇到句子结束标点时 emit
                                    if (content.endsWith("。") || content.endsWith("！") || content.endsWith("？") ||
                                        content.endsWith(".") || content.endsWith("!") || content.endsWith("?") ||
                                        content.endsWith("\n")) {
                                        emitCount++
                                        Log.d(TAG, "emit #$emitCount: $currentContent")
                                        emit(currentContent)
                                        currentContent = ""
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
                emitCount++
                Log.d(TAG, "emit #$emitCount (剩余): $currentContent")
                emit(currentContent)
            }

            val endTime = System.currentTimeMillis()
            val totalTime = endTime - startTime
            Log.d(TAG, "=== 大模型调用结束 ===")
            Log.d(TAG, "结束时间: $endTime")
            Log.d(TAG, "总耗时: ${totalTime}ms")
            Log.d(TAG, "共 emit $emitCount 次")
            Log.d(TAG, "总共读取字节数: ${totalBytesRead}")

        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            val totalTime = endTime - startTime
            Log.e(TAG, "=== 大模型调用失败 ===")
            Log.e(TAG, "结束时间: $endTime")
            Log.e(TAG, "总耗时: ${totalTime}ms", e)
            throw e
        } finally {
            response?.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 构建请求体
     */
    private fun buildRequestBody(
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

        return JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("temperature", 0.7)
            put("max_tokens", 500)
            put("stream", stream)
        }.toString()
    }

    /**
     * 解析响应（非流式）
     */
    private fun parseResponse(responseBody: String): String? {
        return try {
            Log.d(TAG, "开始解析 API 响应: $responseBody")
            val jsonObject = JSONObject(responseBody)
            val choices = jsonObject.optJSONArray("choices")

            if (choices != null && choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.optJSONObject("message")
                val content = message?.optString("content")
                Log.d(TAG, "解析成功: content=$content")
                content
            } else {
                Log.w(TAG, "API 响应中没有 choices 数组")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析响应失败", e)
            null
        }
    }

    /**
     * 解析流式响应（SSE 格式）- 内联版本
     * @param responseBody SSE 格式的响应体
     * @param emitCallback 回调函数，用于发射文本块
     */
    private fun parseStreamResponseInline(responseBody: String, emitCallback: (String) -> Unit) {
        Log.d(TAG, "开始解析流式响应")

        // SSE 格式: data: {...}\n\ndata: {...}\n\n
        val lines = responseBody.split("\n")
        var currentContent = ""

        for (line in lines) {
            if (line.startsWith("data: ")) {
                val data = line.substring(6).trim()

                // 检查是否是结束标记
                if (data == "[DONE]") {
                    Log.d(TAG, "流式响应结束")
                    if (currentContent.isNotEmpty()) {
                        emitCallback(currentContent)
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
                        val content = delta?.optString("content")

                        if (!content.isNullOrBlank()) {
                            Log.d(TAG, "流式文本块: $content")
                            currentContent += content

                            // 累积到一定程度后发出（比如每累积一个句子或每10个字符）
                            if (currentContent.length >= 10 || content.endsWith("。") || content.endsWith("！") || content.endsWith("？") || content.endsWith(".")) {
                                emitCallback(currentContent)
                                currentContent = ""
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析流式数据块失败", e)
                }
            }
        }

        // 发送剩余的内容
        if (currentContent.isNotEmpty()) {
            emitCallback(currentContent)
        }
    }
}