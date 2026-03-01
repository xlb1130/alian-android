package com.alian.assistant.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP 请求工具
 *
 * 用于调用外部 API，如：
 * - 调用 AI 服务
 * - 获取天气信息
 * - 查询数据
 */
class HttpTool : Tool {

    override val name = "http_request"
    override val displayName = "HTTP 请求"
    override val description = "发送 HTTP 请求调用外部 API"

    override val params = listOf(
        ToolParam(
            name = "url",
            type = "string",
            description = "请求 URL",
            required = true
        ),
        ToolParam(
            name = "method",
            type = "string",
            description = "HTTP 方法（GET/POST/PUT/DELETE）",
            required = false,
            defaultValue = "GET"
        ),
        ToolParam(
            name = "headers",
            type = "object",
            description = "请求头（JSON 格式）",
            required = false
        ),
        ToolParam(
            name = "body",
            type = "string",
            description = "请求体（POST/PUT 时使用）",
            required = false
        ),
        ToolParam(
            name = "timeout",
            type = "int",
            description = "超时时间（毫秒）",
            required = false,
            defaultValue = 30000
        )
    )

    override suspend fun execute(params: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val urlStr = params["url"] as? String
            ?: return@withContext ToolResult.Error("缺少 url 参数")

        val method = (params["method"] as? String)?.uppercase() ?: "GET"
        val timeout = (params["timeout"] as? Number)?.toInt() ?: 30000
        val body = params["body"] as? String

        @Suppress("UNCHECKED_CAST")
        val headers = params["headers"] as? Map<String, String> ?: emptyMap()

        try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = method
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.doInput = true

            // 设置请求头
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            // 默认 Content-Type
            if (body != null && !headers.containsKey("Content-Type")) {
                connection.setRequestProperty("Content-Type", "application/json")
            }

            // 发送请求体
            if (body != null && (method == "POST" || method == "PUT")) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }

            // 读取响应
            val responseCode = connection.responseCode
            val inputStream = if (responseCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }

            val response = BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.readText()
            }

            connection.disconnect()

            if (responseCode >= 400) {
                return@withContext ToolResult.Error(
                    "HTTP $responseCode: $response",
                    code = responseCode
                )
            }

            ToolResult.Success(
                data = mapOf(
                    "status_code" to responseCode,
                    "body" to response
                ),
                message = "请求成功 (HTTP $responseCode)"
            )
        } catch (e: Exception) {
            ToolResult.Error("请求失败: ${e.message}")
        }
    }

    /**
     * 简化的 GET 请求
     */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): ToolResult {
        return execute(mapOf(
            "url" to url,
            "method" to "GET",
            "headers" to headers
        ))
    }

    /**
     * 简化的 POST 请求
     */
    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): ToolResult {
        return execute(mapOf(
            "url" to url,
            "method" to "POST",
            "body" to body,
            "headers" to headers
        ))
    }

    /**
     * POST JSON 请求
     */
    suspend fun postJson(url: String, json: JSONObject, headers: Map<String, String> = emptyMap()): ToolResult {
        val allHeaders = headers.toMutableMap()
        allHeaders["Content-Type"] = "application/json"
        return post(url, json.toString(), allHeaders)
    }
}
