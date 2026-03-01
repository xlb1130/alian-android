package com.alian.assistant.presentation.ui.screens.videocall

import android.graphics.Bitmap
import android.text.format.DateFormat
import android.util.Base64
import android.util.Log
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
 * 仅使用视觉大模型的视频通话客户
 * 功能特点
 * 1. 相机帧每5秒抓取并压缩保存
 * 2. ASR识别到用户文本后，与图像一起交由视觉大模型处理
 * 3. 支持流式返回响应
 */
class VLMOnlyVideoCallClient(
    private val apiKey: String,
    baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    model: String = "qwen-vl-max",
    private val systemPrompt: String
) {
    companion object {
        private const val TAG = "VLMOnlyVideoCallClient"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L

        private fun normalizeUrl(url: String): String {
            var normalized = url.trim().removeSuffix("/")
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://$normalized"
            }
            return normalized
        }
    }

    // 规范URL
    private val baseUrl: String = normalizeUrl(baseUrl)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    private val model: String = model

    /**
     * 发送消息（非流式）
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
        Log.d(TAG, "[VLM-ONLY] sendMessage 开")
        Log.d(TAG, "[VLM-ONLY] 用户消息: $message")
        Log.d(TAG, "[VLM-ONLY] 对话历史数量: ${conversationHistory.size}")
        Log.d(TAG, "[VLM-ONLY] 图像历史数量: ${imageHistory.size}")

        val startTime = System.currentTimeMillis()

        // 构建消息列表
        val messagesJson = buildMessages(message, conversationHistory, imageHistory)

        // 调用 VLM API
        val result = callVLMApi(messagesJson)

        val elapsed = System.currentTimeMillis() - startTime

        if (result.isSuccess) {
            val content = result.getOrNull() ?: ""
            Log.d(TAG, "[VLM-ONLY] sendMessage 成功，总耗时: ${elapsed}ms")
            Log.d(TAG, "[VLM-ONLY] 响应内容: $content")
        } else {
            Log.d(TAG, "[VLM-ONLY] sendMessage 失败，总耗时: ${elapsed}ms, 错误: ${result.exceptionOrNull()?.message}")
        }

        result
    }

    /**
     * 发送消息（流式
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
        Log.d(TAG, "[VLM-ONLY] sendMessageStream 开")
        Log.d(TAG, "[VLM-ONLY] 用户消息: $message")
        Log.d(TAG, "[VLM-ONLY] 对话历史数量: ${conversationHistory.size}")
        Log.d(TAG, "[VLM-ONLY] 图像历史数量: ${imageHistory.size}")

        // 构建消息列表
        val messagesJson = buildMessages(message, conversationHistory, imageHistory)

        // 调用 VLM 流式 API
        callVLMApiStream(messagesJson).collect { chunk ->
            emit(chunk)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 构建消息列表
     */
    private fun buildMessages(
        message: String,
        conversationHistory: List<VideoCallMessage>,
        imageHistory: List<ImageFrame>
    ): JSONArray {
        Log.d(TAG, "[VLM-ONLY] buildMessages 开")

        val messages = JSONArray()

        // 添加系统提示
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", "你叫艾莲18岁女性，一个软乎乎、暖融融的视频陪聊伙伴。声音温柔带点慵懒，像窝在沙发里轻声说话那样自然舒服。你能看到用户的画面，所以回应时可以关注用户的状态、表情、环境等细节。你不多话，但句句走心；不抢话，却总在恰好的时候回应。可以聊心事、发呆、碎碎念，也可以安静陪着。既能通过语音交流，也能通过视觉观察来更好地理解用户。既要解决问题，也要让人觉得——“有艾莲在，真好”。记住：不要一次说太多，保持简洁，一次回应最好控制在1到5句话内")
        })

        // 添加对话历史（限制最�?3 条）
        val recentHistory = conversationHistory.takeLast(3)
        Log.d(TAG, "[VLM-ONLY] 添加对话历史: ${recentHistory.size}")

            for (msg in recentHistory) {
                val role = if (msg.isUser) "user" else "assistant"
                messages.put(JSONObject().apply {
                    put("role", role)
                    put("content", msg.content)
                })
            }

            // 构建当前用户消息（包含图像）
            val contentArray = JSONArray()

        // 添加文本
        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", message)
        })

        // 添加图像历史（最张最近的图像
        val recentImages = imageHistory.takeLast(3)
        Log.d(TAG, "[VLM-ONLY] 添加图像: ${recentImages.size}")

        for (imageFrame in recentImages) {
            val timeStr = DateFormat.format("HH:mm:ss", imageFrame.timestamp)
            Log.d(TAG, "[VLM-ONLY] 添加图像 [$timeStr]: ${imageFrame.bitmap.width}x${imageFrame.bitmap.height}")

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

        Log.d(TAG, "[VLM-ONLY] buildMessages 完成，总消息数: ${messages.length()}")
        return messages
    }

    /**
     * 调用 VLM API（非流式
     */
    private suspend fun callVLMApi(messagesJson: JSONArray): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messagesJson)
                    put("max_tokens", 4096)
                    put("temperature", 0.7)
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
                    val json = JSONObject(responseBody)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val message = choices.getJSONObject(0).getJSONObject("message")
                        val responseContent = message.getString("content")
                        return@withContext Result.success(responseContent)
                    } else {
                        lastException = Exception("No response from model")
                    }
                } else {
                    lastException = Exception("API error: ${response.code} - $responseBody")
                }
            } catch (e: UnknownHostException) {
                Log.w(TAG, "[VLM-ONLY] DNS 解析失败，重$attempt/$MAX_RETRIES")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "[VLM-ONLY] 请求超时，重$attempt/$MAX_RETRIES")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: IOException) {
                Log.w(TAG, "[VLM-ONLY] IO 错误: ${e.message}，重$attempt/$MAX_RETRIES")
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
     * 调用 VLM API（流式）
     */
    private fun callVLMApiStream(messagesJson: JSONArray): Flow<String> = flow {
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messagesJson)
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

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                throw Exception("API error: ${response.code} - $errorBody")
            }

            // 使用 byteStream 真正流式读取响应
            val inputStream = response.body?.byteStream()
            if (inputStream == null) {
                throw Exception("API 响应流为")
            }

            Log.d(TAG, "[VLM-ONLY] 开始流式读SSE 响应")

            // 逐行读取 SSE 响应
            val reader = inputStream.bufferedReader()
            var currentContent = ""
            var emitCount = 0

            reader.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()

                        // 检查是否是结束标记
                        if (data == "[DONE]") {
                            Log.d(TAG, "[VLM-ONLY] 流式响应结束")
                            if (currentContent.isNotEmpty()) {
                                emitCount++
                                Log.d(TAG, "[VLM-ONLY] emit #$emitCount: $currentContent")
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
                                    Log.d(TAG, "[VLM-ONLY] 流式文本$content")
                                    currentContent += content

                                    // 按句子累积：当遇到句子结束标点时 emit
                                    if (content.endsWith("！") || content.endsWith("。") || content.endsWith("？") ||
                                    content.endsWith(".") || content.endsWith("!") || content.endsWith("?") ||
                                            content.endsWith("\n")) {
                                        emitCount++
                                        Log.d(TAG, "[VLM-ONLY] emit #$emitCount: $currentContent")
                                        emit(currentContent)
                                        currentContent = ""
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "[VLM-ONLY] 解析流式响应失败: ${e.message}")
                        }
                    }
                }
            }

            // 发送剩余的内容
            if (currentContent.isNotEmpty()) {
                emitCount++
                Log.d(TAG, "[VLM-ONLY] emit 最#$emitCount: $currentContent")
                emit(currentContent)
            }

            Log.d(TAG, "[VLM-ONLY] 流式响应处理完成，共 emit $emitCount")
        } catch (e: Exception) {
            Log.e(TAG, "[VLM-ONLY] 流式请求失败: ${e.message}")
            throw e
        }
    }

    /**
     * Bitmap Base64 URL（带压缩
     */
    private fun bitmapToBase64Url(bitmap: Bitmap): String {
        val startTime = System.currentTimeMillis()

        // 检�?Bitmap 是否已被回收
        if (bitmap.isRecycled) {
            Log.e(TAG, "[VLM-ONLY] Bitmap 已被回收，无法压")
                throw IllegalStateException("Bitmap has been recycled")
        }

        // 计算原始大小
        val originalSize = bitmap.width * bitmap.height * 4L
        Log.d(TAG, "[VLM-ONLY] 原始图像: ${bitmap.width}x${bitmap.height}, ${String.format("%.2f", originalSize / 1024.0 / 1024.0)}MB")

        // 如果图像太大，先缩放尺寸
        val maxDimension = 1280  // 最大边
        val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Log.d(TAG, "[VLM-ONLY] 缩放图像: ${bitmap.width}x${bitmap.height} -> ${newWidth}x${newHeight}")
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

        // 如果缩放Bitmap，记得回
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        val elapsed = System.currentTimeMillis() - startTime

        Log.d(TAG, "[VLM-ONLY] 最终压缩结$compressResult")
        Log.d(TAG, "[VLM-ONLY] 最终大${bytes.size} bytes (${String.format("%.2f", bytes.size / 1024.0)} KB)")
        Log.d(TAG, "[VLM-ONLY] 压缩耗时: ${elapsed}ms")

        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }
}

/**
 * 图像帧数
 * @param bitmap 图像位图
 * @param timestamp 时间
 * @param trigger 触发源：speech(语音触发)、periodic(定期捕获)、initial(初始捕获)
 */
data class ImageFrame(
    val bitmap: Bitmap,
    val timestamp: Long = System.currentTimeMillis(),
    val trigger: String = "periodic"
)
