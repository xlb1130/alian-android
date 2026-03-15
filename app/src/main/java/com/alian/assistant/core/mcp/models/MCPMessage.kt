package com.alian.assistant.core.mcp.models

import org.json.JSONObject

/**
 * MCP 协议消息
 * 基于 JSON-RPC 2.0 规范
 */
sealed class MCPMessage {
    /**
     * 请求消息
     */
    data class Request(
        val jsonrpc: String = "2.0",
        val id: Int,
        val method: String,
        val params: JSONObject = JSONObject()
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("jsonrpc", jsonrpc)
                put("id", id)
                put("method", method)
                // 始终包含 params 字段（即使是空对象），符合 JSON-RPC 2.0 规范
                put("params", params)
            }
        }
    }

    /**
     * 响应消息
     */
    data class Response(
        val jsonrpc: String = "2.0",
        val id: Int,
        val result: Any? = null,
        val error: MCPError? = null
    ) {
        companion object {
            fun fromJson(json: JSONObject): Response {
                val errorJson = json.optJSONObject("error")
                val error = if (errorJson != null) {
                    MCPError(
                        code = errorJson.optInt("code", -1),
                        message = errorJson.optString("message", ""),
                        data = errorJson.opt("data")
                    )
                } else {
                    null
                }

                return Response(
                    id = json.getInt("id"),
                    result = json.opt("result"),
                    error = error
                )
            }
        }

        val isError: Boolean get() = error != null
    }

    /**
     * 错误信息
     */
    data class MCPError(
        val code: Int,
        val message: String,
        val data: Any? = null
    ) {
        override fun toString(): String {
            return "Error $code: $message${data?.let { " - $it" } ?: ""}"
        }
    }

    /**
     * 通知消息（无响应）
     */
    data class Notification(
        val jsonrpc: String = "2.0",
        val method: String,
        val params: JSONObject = JSONObject()
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("jsonrpc", jsonrpc)
                put("method", method)
                if (params.length() > 0) {
                    put("params", params)
                }
            }
        }
    }
}

/**
 * MCP 协议方法常量
 */
object MCPMethod {
    const val INITIALIZE = "initialize"
    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"
    const val RESOURCES_LIST = "resources/list"
    const val RESOURCES_READ = "resources/read"
    const val PROMPTS_LIST = "prompts/list"
    const val PROMPTS_GET = "prompts/get"
    const val NOTIFICATIONS_INITIALIZED = "notifications/initialized"
}
