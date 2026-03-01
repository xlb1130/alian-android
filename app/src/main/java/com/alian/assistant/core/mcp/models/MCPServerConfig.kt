package com.alian.assistant.core.mcp.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.util.UUID

/**
 * MCP Server 配置
 * 存储 MCP Server 的连接信息和状态
 */
@Serializable
data class MCPServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String? = null,
    val url: String,
    val transport: MCPTransport,
    val enabled: Boolean = true,
    val apiKey: String? = null,
    val headers: Map<String, String>? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * 从 JSON 字符串解析配置
         * 支持两种格式：
         * 1. 标准格式: {"mcpServers": {"server-name": {...}}}
         * 2. 简化格式: {"name": "My Server", "url": "...", ...}
         */
        fun fromJson(jsonStr: String): Result<MCPServerConfig> {
            return try {
                android.util.Log.d("MCPServerConfig", "开始解析 JSON: $jsonStr")
                val json = JSONObject(jsonStr)

                // 检查是否是标准格式
                val mcpServers = json.optJSONObject("mcpServers")
                if (mcpServers != null) {
                    android.util.Log.d("MCPServerConfig", "检测到标准格式，mcpServers: $mcpServers")
                    // 取第一个 server
                    val keys = mcpServers.keys()
                    if (keys.hasNext()) {
                        val serverKey = keys.next()
                        android.util.Log.d("MCPServerConfig", "解析 server: $serverKey")
                        val serverJson = mcpServers.getJSONObject(serverKey)
                        android.util.Log.d("MCPServerConfig", "serverJson: $serverJson")
                        val config = parseServerConfig(serverJson, serverKey)
                        android.util.Log.d("MCPServerConfig", "解析成功: ${config.name}")
                        return Result.success(config)
                    } else {
                        android.util.Log.e("MCPServerConfig", "mcpServers is empty")
                        return Result.failure(Exception("mcpServers is empty"))
                    }
                }

                // 否则按简化格式解析
                android.util.Log.d("MCPServerConfig", "使用简化格式解析")
                val config = parseServerConfig(json, null)
                android.util.Log.d("MCPServerConfig", "解析成功: ${config.name}")
                Result.success(config)
            } catch (e: Exception) {
                android.util.Log.e("MCPServerConfig", "JSON 解析失败: ${e.message}", e)
                Result.failure(e)
            }
        }

        private fun parseServerConfig(json: JSONObject, fallbackName: String?): MCPServerConfig {
            val name = json.optString("name", fallbackName ?: "MCP Server")

            // 检查必需字段
            if (!json.has("url")) {
                throw IllegalArgumentException("Missing required field: url")
            }
            val url = json.getString("url")

            // 验证 URL 不为空
            if (url.isBlank()) {
                throw IllegalArgumentException("URL cannot be blank")
            }
            
            // 支持 type 和 transport 两种字段名
            val transportStr = json.optString("type", null) ?: json.optString("transport", "http")
            val transport = when (transportStr.lowercase()) {
                "websocket", "ws", "streamable_http" -> MCPTransport.HTTP
                "sse" -> MCPTransport.SSE
                else -> MCPTransport.HTTP
            }

            val apiKey = json.optString("apiKey", null)?.takeIf { it.isNotBlank() }

            // 解析 headers
            val headers = mutableMapOf<String, String>()
            try {
                val headersJson = json.optJSONObject("headers")
                if (headersJson != null) {
                    val keys = headersJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        headers[key] = headersJson.getString(key)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MCPServerConfig", "Failed to parse headers", e)
            }

            return MCPServerConfig(
                name = name,
                description = json.optString("description", null)?.takeIf { it.isNotBlank() },
                url = url,
                transport = transport,
                apiKey = apiKey,
                headers = headers.takeIf { it.isNotEmpty() }
            )
        }
    }

    /**
     * 转换为 JSON 字符串
     */
    fun toJson(): String {
        return Json.encodeToString(this)
    }

    /**
     * 验证配置是否有效
     */
    fun isValid(): Boolean {
        return name.isNotBlank() && url.isNotBlank()
    }

    /**
     * 获取请求头（包含 API Key）
     */
    fun getRequestHeaders(): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // 添加 API Key
        apiKey?.let {
            result["X-API-Key"] = it
        }

        // 添加自定义 headers
        headers?.let {
            result.putAll(it)
        }

        return result
    }
}

/**
 * MCP 传输协议类型
 */
@Serializable
enum class MCPTransport {
    WEBSOCKET,  // WebSocket 长连接
    HTTP,       // HTTP 请求
    SSE         // Server-Sent Events
}