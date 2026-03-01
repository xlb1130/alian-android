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
 * MAI-UI 专用客户端
 * 实现 MAI-UI 的特定 prompt 格式和对话历史管理
 */
class MAIUIClient(
    private val baseUrl: String = "http://localhost:8000/v1",
    private val model: String = "MAI-UI-2B",
    private val historyN: Int = 3  // 保留的历史图片数量
) {
    companion object {
        private const val SCALE_FACTOR = 999
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    // 对话历史
    private val historyImages = mutableListOf<String>()  // Base64 encoded images
    private val historyResponses = mutableListOf<String>()  // Assistant responses

    // 可用应用列表 (会在运行时更新)
    private var availableApps: List<String> = emptyList()

    /**
     * 系统提示词 (参考 MAI-UI 官方实现)
     */
    private fun getSystemPrompt(): String = """
You are a GUI agent. You are given a task and your action history, with screenshots. You need to perform the next action to complete the task.

## Output Format
For each function call, return the thinking process in <thinking> </thinking> tags, and a json object with function name and arguments within <tool_call></tool_call> XML tags:
```
<thinking>
...
</thinking>
<tool_call>
{"name": "mobile_use", "arguments": <args-json-object>}
</tool_call>
```

## Action Space

{"action": "click", "coordinate": [x, y]}
{"action": "long_press", "coordinate": [x, y]}
{"action": "type", "text": ""}
{"action": "swipe", "direction": "up or down or left or right", "coordinate": [x, y]}
{"action": "open", "text": "app_name"}
{"action": "drag", "start_coordinate": [x1, y1], "end_coordinate": [x2, y2]}
{"action": "system_button", "button": "button_name"}
{"action": "wait"}
{"action": "terminate", "status": "success or fail"}
{"action": "answer", "text": "xxx"}
{"action": "ask_user", "text": "xxx"}

## Note
- Write a small plan and finally summarize your next action (with its target element) in one sentence in <thinking></thinking> part.
- Available Apps: `${if (availableApps.isNotEmpty()) availableApps.toString() else "[请通过open动作打开应用]"}`.
- You should use the `open` action to open the app as possible as you can, because it is the fast way to open the app.
- You must follow the Action Space strictly, and return the correct json object within <thinking> </thinking> and <tool_call></tool_call> XML tags.
    """.trimIndent()

    /**
     * 设置可用应用列表
     */
    fun setAvailableApps(apps: List<String>) {
        availableApps = apps
    }

    /**
     * 重置对话历史
     */
    fun reset() {
        historyImages.clear()
        historyResponses.clear()
    }

    /**
     * 预测下一步动作
     * @param instruction 用户指令
     * @param screenshot 当前截图
     * @return Result<MAIUIResponse>
     */
    suspend fun predict(
        instruction: String,
        screenshot: Bitmap
    ): Result<MAIUIResponse> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        // 编码当前截图
        val currentImageBase64 = bitmapToBase64(screenshot)

        for (attempt in 1..MAX_RETRIES) {
            try {
                // 构建消息
                val messages = buildMessages(instruction, currentImageBase64)

                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", 2048)
                    put("temperature", 0.0)
                    put("top_p", 1.0)
                }

                val request = Request.Builder()
                    .url("${normalizeUrl(baseUrl)}/chat/completions")
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
                        val content = message.getString("content")

                        println("[MAIUIClient] Raw response: $content")

                        // 解析响应
                        val parsed = parseResponse(content)

                        // 保存到历史
                        historyImages.add(currentImageBase64)
                        historyResponses.add(content)

                        // 限制历史数量
                        while (historyImages.size > historyN) {
                            historyImages.removeAt(0)
                            historyResponses.removeAt(0)
                        }

                        return@withContext Result.success(parsed)
                    } else {
                        lastException = Exception("No response from model")
                    }
                } else {
                    lastException = Exception("API error: ${response.code} - $responseBody")
                }
            } catch (e: Exception) {
                println("[MAIUIClient] Error on attempt $attempt: ${e.message}")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * 构建消息列表 (参考 MAI-UI 官方实现)
     */
    private fun buildMessages(instruction: String, currentImageBase64: String): JSONArray {
        val messages = JSONArray()

        // 1. System message
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", getSystemPrompt())
                })
            })
        })

        // 2. User instruction
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", instruction)
                })
            })
        })

        // 3. History (image + assistant response pairs)
        val startIdx = maxOf(0, historyImages.size - (historyN - 1))
        for (i in startIdx until historyImages.size) {
            // User message with image
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,${historyImages[i]}")
                        })
                    })
                })
            })

            // Assistant response
            messages.put(JSONObject().apply {
                put("role", "assistant")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", historyResponses[i])
                    })
                })
            })
        }

        // 4. Current image
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$currentImageBase64")
                    })
                })
            })
        })

        return messages
    }

    /**
     * 解析 MAI-UI 响应
     */
    private fun parseResponse(text: String): MAIUIResponse {
        var processedText = text.trim()

        // 处理 thinking model 输出格式 (</think> instead of </thinking>)
        if (processedText.contains("</think>") && !processedText.contains("</thinking>")) {
            processedText = processedText.replace("</think>", "</thinking>")
            processedText = "<thinking>$processedText"
        }

        // 提取 thinking
        val thinkingRegex = Regex("<thinking>(.*?)</thinking>", RegexOption.DOT_MATCHES_ALL)
        val thinking = thinkingRegex.find(processedText)?.groupValues?.get(1)?.trim() ?: ""

        // 提取 tool_call
        val toolCallRegex = Regex("<tool_call>\\s*(.+?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
        val toolCallMatch = toolCallRegex.find(processedText)

        var action: MAIUIAction? = null
        if (toolCallMatch != null) {
            try {
                val toolCallJson = JSONObject(toolCallMatch.groupValues[1].trim())
                val arguments = toolCallJson.optJSONObject("arguments")
                if (arguments != null) {
                    action = parseAction(arguments)
                }
            } catch (e: Exception) {
                println("[MAIUIClient] Failed to parse tool_call: ${e.message}")
            }
        }

        return MAIUIResponse(
            thinking = thinking,
            action = action,
            rawResponse = text
        )
    }

    /**
     * 解析动作
     */
    private fun parseAction(arguments: JSONObject): MAIUIAction {
        val actionType = arguments.optString("action", "")

        // 解析坐标 (0-999 归一化)
        val coordinate = arguments.optJSONArray("coordinate")
        var x: Float? = null
        var y: Float? = null
        if (coordinate != null && coordinate.length() >= 2) {
            x = coordinate.getDouble(0).toFloat() / SCALE_FACTOR
            y = coordinate.getDouble(1).toFloat() / SCALE_FACTOR
        }

        // 解析 drag 坐标
        val startCoord = arguments.optJSONArray("start_coordinate")
        val endCoord = arguments.optJSONArray("end_coordinate")
        var startX: Float? = null
        var startY: Float? = null
        var endX: Float? = null
        var endY: Float? = null
        if (startCoord != null && endCoord != null) {
            startX = startCoord.getDouble(0).toFloat() / SCALE_FACTOR
            startY = startCoord.getDouble(1).toFloat() / SCALE_FACTOR
            endX = endCoord.getDouble(0).toFloat() / SCALE_FACTOR
            endY = endCoord.getDouble(1).toFloat() / SCALE_FACTOR
        }

        return MAIUIAction(
            type = actionType,
            x = x,
            y = y,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            text = arguments.optString("text", null),
            button = arguments.optString("button", null),
            direction = arguments.optString("direction", null),
            status = arguments.optString("status", null),
            tellUser = arguments.optString("tellUser", null)
        )
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val bytes = outputStream.toByteArray()
        println("[MAIUIClient] Image compressed: ${bitmap.width}x${bitmap.height}, ${bytes.size / 1024}KB")
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun normalizeUrl(url: String): String {
        var normalized = url.trim().removeSuffix("/")
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://$normalized"
        }
        return normalized
    }
}

/**
 * MAI-UI 响应
 */
data class MAIUIResponse(
    val thinking: String,
    val action: MAIUIAction?,
    val rawResponse: String
)

/**
 * MAI-UI 动作 (坐标已归一化为 0-1)
 */
data class MAIUIAction(
    val type: String,
    val x: Float? = null,           // 归一化坐标 0-1
    val y: Float? = null,
    val startX: Float? = null,      // drag 起点
    val startY: Float? = null,
    val endX: Float? = null,        // drag 终点
    val endY: Float? = null,
    val text: String? = null,
    val button: String? = null,
    val direction: String? = null,  // swipe 方向: up, down, left, right
    val status: String? = null,     // terminate 状态: success, fail
    val tellUser: String? = null    // 需要向用户传达的消息
) {
    /**
     * 转换为屏幕像素坐标
     */
    fun toScreenCoordinates(screenWidth: Int, screenHeight: Int): MAIUIAction {
        return copy(
            x = x?.let { it * screenWidth },
            y = y?.let { it * screenHeight },
            startX = startX?.let { it * screenWidth },
            startY = startY?.let { it * screenHeight },
            endX = endX?.let { it * screenWidth },
            endY = endY?.let { it * screenHeight },
            text = text,
            button = button,
            direction = direction,
            status = status,
            tellUser = tellUser
        )
    }
}
