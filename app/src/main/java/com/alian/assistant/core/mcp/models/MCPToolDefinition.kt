package com.alian.assistant.core.mcp.models

import org.json.JSONObject

/**
 * MCP 工具定义
 * 描述一个 MCP 工具的接口信息
 */
data class MCPToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val serverId: String
) {
    /**
     * 转换为 OpenAI Function Calling 格式
     */
    fun toOpenAIFunction(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", inputSchema)
            })
        }
    }

    /**
     * 获取必填参数列表
     */
    fun getRequiredParams(): List<String> {
        val required = inputSchema.optJSONArray("required")
        if (required != null) {
            return (0 until required.length()).map { required.getString(it) }
        }
        return emptyList()
    }
}