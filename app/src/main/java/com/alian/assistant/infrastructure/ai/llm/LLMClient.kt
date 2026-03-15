package com.alian.assistant.infrastructure.ai.llm

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
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * LLM (Large Language Model) API 客户端
 * 支持 OpenAI 兼容接口 (GPT-4, Qwen, Claude, etc.)
 * 用于纯文本对话，不包含视觉能力
 */
class LLMClient(
    private val apiKey: String,
    baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    private val model: String = "qwen-plus"
) {
    // 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠
    private val baseUrl: String = normalizeUrl(baseUrl)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L

        /** 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠 */
        private fun normalizeUrl(url: String): String {
            var normalized = url.trim().removeSuffix("/")
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://$normalized"
            }
            return normalized
        }
    }

    /**
     * 调用 LLM 进行文本对话 (使用完整对话历史)
     * @param messagesJson OpenAI 兼容的 messages JSON 数组
     * @param systemPrompt 系统提示词（可选，默认为空）
     * @return Result<String> AI 响应内容
     */
    suspend fun predictWithContext(
        messagesJson: JSONArray,
        systemPrompt: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                // 构建完整的消息列表
                val fullMessages = JSONArray()
                
                // 如果有 systemPrompt，添加 system 消息
                if (systemPrompt.isNotBlank()) {
                    fullMessages.put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                
                // 添加用户提供的消息
                for (i in 0 until messagesJson.length()) {
                    fullMessages.put(messagesJson.getJSONObject(i))
                }

                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", fullMessages)
                    put("max_tokens", 4096)
                    put("temperature", 0.7)
                }

                println("[LLMClient] 发送请求: model=$model, messages=${messagesJson.length()}")

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
                    val json = JSONObject(responseBody)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val message = choices.getJSONObject(0).getJSONObject("message")
                        val responseContent = message.getString("content")
                        println("[LLMClient] 请求成功，响应长度: ${responseContent.length}")
                        return@withContext Result.success(responseContent)
                    } else {
                        lastException = Exception("No response from model")
                    }
                } else {
                    lastException = Exception("API error: ${response.code} - $responseBody")
                }
            } catch (e: UnknownHostException) {
                println("[LLMClient] DNS 解析失败，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: SocketTimeoutException) {
                println("[LLMClient] 请求超时，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: IOException) {
                println("[LLMClient] IO 错误: ${e.message}，重试 $attempt/$MAX_RETRIES...")
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
     * 调用 LLM 进行流式文本对话
     * @param messagesJson OpenAI 兼容的 messages JSON 数组
     * @return Flow<String> 流式响应
     */
    fun predictWithContextStream(
        messagesJson: JSONArray
    ): Flow<String> = flow {
        // 构建完整的消息列表，包含 system prompt
        val fullMessages = JSONArray()
        // 添加 system prompt
        fullMessages.put(JSONObject().apply {
            put("role", "system")
            put("content", "你是一个视频通话助手，能够通过语音识别和图像理解与用户进行自然对话。视频信息会通过用户消息中的【视觉信息】标签提供。请用简洁、友好的语气回应，每次回答不超过100字。")
        })
        // 添加用户提供的消息
        for (i in 0 until messagesJson.length()) {
            fullMessages.put(messagesJson.getJSONObject(i))
        }

        println("[LLMClient] 发送流式请求: model=$model, messages=${fullMessages.length()}")

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", fullMessages)
            put("max_tokens", 4096)
            put("temperature", 0.7)
            put("stream", true)
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
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                throw Exception("API error: ${response.code} - $responseBody")
            }

            // 解析 SSE 流式响应
            responseBody.lines().forEach { line ->
                if (line.startsWith("data: ")) {
                    val data = line.substring(6).trim()
                    if (data == "[DONE]") {
                        return@forEach
                    }

                    try {
                        val json = JSONObject(data)
                        val choices = json.getJSONArray("choices")
                        if (choices.length() > 0) {
                            val delta = choices.getJSONObject(0).getJSONObject("delta")
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                emit(content)
                            }
                        }
                    } catch (e: Exception) {
                        println("[LLMClient] 解析流式响应失败: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("[LLMClient] 流式请求失败: ${e.message}")
            throw e
        }
    }.flowOn(Dispatchers.IO)
}
