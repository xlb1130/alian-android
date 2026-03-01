package com.alian.assistant.infrastructure.ai.llm

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * GUI-Owl API 客户端
 * 专用于阿里云 GUI Agent 服务（非 OpenAI 兼容格式）
 *
 * API 特点：
 * - 专用端点: https://dashscope.aliyuncs.com/api/v2/apps/gui-owl/gui_agent_server
 * - 返回直接的操作指令: Click(x,y), Swipe(x1,y1,x2,y2) 等
 * - 支持 session_id 管理多轮操作
 */
class GUIOwlClient(
    private val apiKey: String,
    private val model: String = "pre-gui_owl_7b",
    private val deviceType: String = "mobile",
    private val thoughtLanguage: String = "chinese"
) {
    companion object {
        private const val TAG = "GUIOwlClient"
        private const val ENDPOINT = "https://dashscope.aliyuncs.com/api/v2/apps/gui-owl/gui_agent_server"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    // 会话 ID（用于关联多轮操作）
    private var sessionId: String = ""

    /**
     * GUI-Owl 响应结果
     */
    data class GUIOwlResponse(
        val thought: String,      // 思考过程
        val operation: String,    // 操作指令: Click (x, y, x, y) / Swipe (x1, y1, x2, y2) 等
        val explanation: String,  // 操作说明
        val sessionId: String,    // 会话 ID
        val rawResponse: String   // 原始响应
    )

    /**
     * 解析操作指令为 Action
     */
    data class ParsedAction(
        val type: String,  // click, swipe, type, etc.
        val x: Int? = null,
        val y: Int? = null,
        val x2: Int? = null,
        val y2: Int? = null,
        val text: String? = null
    )

    /**
     * 调用 GUI-Owl 进行界面理解和操作推理
     *
     * @param instruction 用户指令
     * @param imageUrl 截图 URL（支持 http/https 或 data:image/... base64）
     * @param addInfo 额外的操作提示信息
     * @return GUIOwlResponse
     */
    suspend fun predict(
        instruction: String,
        imageUrl: String,
        addInfo: String = ""
    ): Result<GUIOwlResponse> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                val messagesArray = JSONArray().apply {
                    put(JSONObject().put("image", imageUrl))
                    put(JSONObject().put("instruction", instruction))
                    put(JSONObject().put("session_id", sessionId))
                    put(JSONObject().put("device_type", deviceType))
                    put(JSONObject().put("pipeline_type", "agent"))
                    put(JSONObject().put("model_name", model))
                    put(JSONObject().put("thought_language", thoughtLanguage))
                    put(JSONObject().put("param_list", JSONArray().apply {
                        put(JSONObject().put("add_info", addInfo))
                    }))
                }

                val dataObj = JSONObject().apply {
                    put("messages", messagesArray)
                }

                val contentArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "data")
                        put("data", dataObj)
                    })
                }

                val inputArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", contentArray)
                    })
                }

                val requestBody = JSONObject().apply {
                    put("app_id", "gui-owl")
                    put("input", inputArray)
                }

                val request = Request.Builder()
                    .url(ENDPOINT)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                println("[$TAG] 请求: instruction=$instruction")
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)

                    // 更新 session_id
                    val newSessionId = json.optString("session_id", "")
                    if (newSessionId.isNotEmpty()) {
                        sessionId = newSessionId
                    }

                    // 解析 output
                    val outputArray = json.optJSONArray("output")
                    if (outputArray != null && outputArray.length() > 0) {
                        val output = outputArray.getJSONObject(0)
                        val contentArr = output.optJSONArray("content")

                        if (contentArr != null && contentArr.length() > 0) {
                            val content = contentArr.getJSONObject(0)
                            val data = content.optJSONObject("data")

                            if (data != null) {
                                val result = GUIOwlResponse(
                                    thought = data.optString("Thought", ""),
                                    operation = data.optString("Operation", ""),
                                    explanation = data.optString("Explanation", ""),
                                    sessionId = sessionId,
                                    rawResponse = responseBody
                                )
                                println("[$TAG] 响应: operation=${result.operation}")
                                return@withContext Result.success(result)
                            }
                        }
                    }

                    lastException = Exception("Invalid response format: $responseBody")
                } else {
                    lastException = Exception("API error: ${response.code} - $responseBody")
                }
            } catch (e: Exception) {
                println("[$TAG] 请求失败 (attempt $attempt): ${e.message}")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * 使用 Bitmap 调用 GUI-Owl
     * 自动将 Bitmap 转换为 base64 data URL
     */
    suspend fun predict(
        instruction: String,
        image: Bitmap,
        addInfo: String = ""
    ): Result<GUIOwlResponse> {
        val imageUrl = bitmapToDataUrl(image)
        return predict(instruction, imageUrl, addInfo)
    }

    /**
     * 解析操作指令字符串为 ParsedAction
     *
     * 支持的格式:
     * - Click (x, y, x, y) 或 Click (x, y)
     * - Swipe (x1, y1, x2, y2)
     * - Type (text)
     * - Long_press (x, y)
     * - Scroll (direction)
     */
    fun parseOperation(operation: String): ParsedAction? {
        val trimmed = operation.trim()

        // Click (x, y, x, y) 或 Click (x, y)
        val clickPattern = Regex("""Click\s*\(\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*\d+\s*,\s*\d+)?\s*\)""", RegexOption.IGNORE_CASE)
        clickPattern.find(trimmed)?.let { match ->
            val x = match.groupValues[1].toIntOrNull() ?: return null
            val y = match.groupValues[2].toIntOrNull() ?: return null
            return ParsedAction(type = "click", x = x, y = y)
        }

        // Swipe (x1, y1, x2, y2)
        val swipePattern = Regex("""Swipe\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)
        swipePattern.find(trimmed)?.let { match ->
            val x1 = match.groupValues[1].toIntOrNull() ?: return null
            val y1 = match.groupValues[2].toIntOrNull() ?: return null
            val x2 = match.groupValues[3].toIntOrNull() ?: return null
            val y2 = match.groupValues[4].toIntOrNull() ?: return null
            return ParsedAction(type = "swipe", x = x1, y = y1, x2 = x2, y2 = y2)
        }

        // Long_press (x, y) 或 LongPress (x, y)
        val longPressPattern = Regex("""Long[_\s]?[Pp]ress\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)
        longPressPattern.find(trimmed)?.let { match ->
            val x = match.groupValues[1].toIntOrNull() ?: return null
            val y = match.groupValues[2].toIntOrNull() ?: return null
            return ParsedAction(type = "long_press", x = x, y = y)
        }

        // Type (text) 或 Input (text) 或 Type: "text" 或 Type text
        val typePattern = Regex("""(?:Type|Input)\s*[:\(]\s*["']?(.+?)["']?\s*\)?""", RegexOption.IGNORE_CASE)
        typePattern.find(trimmed)?.let { match ->
            val text = match.groupValues[1].trim()
            return ParsedAction(type = "type", text = text)
        }

        // Scroll (direction) 或 Scroll_down / Scroll_up
        val scrollPattern = Regex("""Scroll[_\s]?(up|down|left|right)?""", RegexOption.IGNORE_CASE)
        scrollPattern.find(trimmed)?.let { match ->
            val direction = match.groupValues.getOrNull(1)?.lowercase() ?: "down"
            return ParsedAction(type = "scroll", text = direction)
        }

        // Back
        if (trimmed.contains("Back", ignoreCase = true)) {
            return ParsedAction(type = "system_button", text = "Back")
        }

        // Home
        if (trimmed.contains("Home", ignoreCase = true)) {
            return ParsedAction(type = "system_button", text = "Home")
        }

        // FINISH / DONE / COMPLETE
        if (trimmed.contains(Regex("FINISH|DONE|COMPLETE|Finished", RegexOption.IGNORE_CASE))) {
            return ParsedAction(type = "finish")
        }

        println("[$TAG] 无法解析操作: $operation")
        return null
    }

    /**
     * 重置会话（开始新任务时调用）
     */
    fun resetSession() {
        sessionId = ""
    }

    /**
     * 获取当前会话 ID
     */
    fun getSessionId(): String = sessionId

    /**
     * Bitmap 转换为 data URL
     */
    private fun bitmapToDataUrl(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val bytes = outputStream.toByteArray()
        println("[$TAG] 图片压缩: ${bitmap.width}x${bitmap.height}, ${bytes.size / 1024}KB")
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }
}
