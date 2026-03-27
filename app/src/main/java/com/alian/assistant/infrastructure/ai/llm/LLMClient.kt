package com.alian.assistant.infrastructure.ai.llm

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
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
import java.util.concurrent.atomic.AtomicLong

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
    companion object {
        private const val TAG = "LLMClient"
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

    // 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠
    private val baseUrl: String = normalizeUrl(baseUrl)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    private val requestCounter = AtomicLong(0L)

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var activeRequestId: Long = 0L

    fun cancelActiveRequest() {
        val call = activeCall ?: return
        val requestId = activeRequestId
        Log.d(TAG, "取消当前 LLM 请求: requestId=$requestId")
        call.cancel()
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
            var call: Call? = null
            val requestId = nextRequestId()
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

                call = client.newCall(request)
                registerActiveCall(call, requestId)

                call.execute().use { response ->
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
                if (isRequestCancelled(e, call)) {
                    return@withContext Result.failure(CancellationException("LLM 请求已取消"))
                }
                println("[LLMClient] IO 错误: ${e.message}，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: Exception) {
                if (isRequestCancelled(e, call)) {
                    return@withContext Result.failure(CancellationException("LLM 请求已取消"))
                }
                return@withContext Result.failure(e)
            } finally {
                clearActiveCall(call)
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

        var call: Call? = null
        val requestId = nextRequestId()

        try {
            currentCoroutineContext().ensureActive()
            call = client.newCall(request)
            registerActiveCall(call, requestId)

            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    throw Exception("API error: ${response.code} - $responseBody")
                }

                val inputStream = response.body?.byteStream()
                    ?: throw Exception("API 响应流为空")

                val reader = inputStream.bufferedReader()
                var currentContent = ""

                reader.useLines { lines ->
                    for (line in lines) {
                        currentCoroutineContext().ensureActive()

                        if (!line.startsWith("data: ")) {
                            continue
                        }

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
                            val choices = json.optJSONArray("choices") ?: continue
                            if (choices.length() == 0) {
                                continue
                            }
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            val content = delta?.optString("content")
                            if (!content.isNullOrEmpty() && !"null".equals(content, ignoreCase = true)) {
                                currentContent += content
                                if (
                                    content.endsWith("。") ||
                                    content.endsWith("！") ||
                                    content.endsWith("？") ||
                                    content.endsWith(".") ||
                                    content.endsWith("!") ||
                                    content.endsWith("?") ||
                                    content.endsWith("\n")
                                ) {
                                    emit(currentContent)
                                    currentContent = ""
                                }
                            }
                        } catch (e: Exception) {
                            println("[LLMClient] 解析流式响应失败: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (isRequestCancelled(e, call)) {
                throw CancellationException("LLM 流式请求已取消")
            }
            println("[LLMClient] 流式请求失败: ${e.message}")
            throw e
        } catch (e: CancellationException) {
            call?.cancel()
            throw e
        } catch (e: Exception) {
            if (isRequestCancelled(e, call)) {
                throw CancellationException("LLM 流式请求已取消")
            }
            println("[LLMClient] 流式请求失败: ${e.message}")
            throw e
        } finally {
            clearActiveCall(call)
        }
    }.flowOn(Dispatchers.IO)

    private fun nextRequestId(): Long = requestCounter.incrementAndGet()

    @Synchronized
    private fun registerActiveCall(call: Call?, requestId: Long) {
        val previous = activeCall
        if (previous != null && previous !== call) {
            previous.cancel()
        }
        activeCall = call
        activeRequestId = requestId
        Log.d(TAG, "注册活动中的 LLM 请求: requestId=$requestId")
    }

    @Synchronized
    private fun clearActiveCall(call: Call?) {
        if (call != null && activeCall === call) {
            Log.d(TAG, "清理活动中的 LLM 请求: requestId=$activeRequestId")
            activeCall = null
            activeRequestId = 0L
        }
    }

    private fun isRequestCancelled(error: Throwable?, call: Call?): Boolean {
        if (error is CancellationException) {
            return true
        }
        if (call?.isCanceled() == true) {
            return true
        }
        val message = error?.message?.lowercase() ?: return false
        return message.contains("canceled") || message.contains("cancelled")
    }
}
