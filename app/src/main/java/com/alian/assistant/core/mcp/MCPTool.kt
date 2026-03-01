package com.alian.assistant.core.mcp

import com.alian.assistant.core.mcp.models.MCPToolDefinition
import com.alian.assistant.core.tools.Tool
import com.alian.assistant.core.tools.ToolParam
import com.alian.assistant.core.tools.ToolResult
import org.json.JSONObject

/**
 * MCP 工具封装
 * 将 MCP 工具封装为应用内的 Tool 接口，便于与现有 ToolManager 集成
 */
class MCPTool(
    private val definition: MCPToolDefinition,
    private val mcpManager: MCPManager
) : Tool {
    
    override val name: String = "mcp_${definition.serverId}_${definition.name}"
    
    override val displayName: String = definition.name
    
    override val description: String = definition.description
    
    override val params: List<ToolParam> = parseParamsFromSchema(definition.inputSchema)
    
    /**
     * 从 JSON Schema 解析参数定义
     */
    private fun parseParamsFromSchema(schema: JSONObject): List<ToolParam> {
        val params = mutableListOf<ToolParam>()
        
        try {
            val properties = schema.optJSONObject("properties")
            if (properties != null) {
                val required = schema.optJSONArray("required")
                val requiredSet = mutableSetOf<String>()
                if (required != null) {
                    for (i in 0 until required.length()) {
                        requiredSet.add(required.getString(i))
                    }
                }
                
                properties.keys().forEach { key ->
                    val prop = properties.getJSONObject(key)
                    val type = prop.optString("type", "string")
                    val description = prop.optString("description", "")
                    val isRequired = key in requiredSet
                    val defaultValue = prop.opt("default")
                    
                    params.add(ToolParam(
                        name = key,
                        type = type,
                        description = description,
                        required = isRequired,
                        defaultValue = defaultValue
                    ))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MCPTool", "Failed to parse params from schema", e)
        }
        
        return params
    }
    
    /**
     * 执行工具
     */
    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        return try {
            val result = mcpManager.executeTool(definition.name, params)
            
            if (result.isSuccess) {
                ToolResult.Success(
                    data = result.getDataOrNull(),
                    message = "工具执行成功"
                )
            } else {
                val error = (result as? ToolResult.Error)?.error ?: "未知错误"
                ToolResult.Error(error)
            }
        } catch (e: Exception) {
            android.util.Log.e("MCPTool", "Failed to execute tool: ${definition.name}", e)
            ToolResult.Error("工具执行失败: ${e.message}")
        }
    }
    
    /**
     * 获取工具定义
     */
    fun getDefinition(): MCPToolDefinition {
        return definition
    }
    
    /**
     * 获取所属 Server ID
     */
    fun getServerId(): String {
        return definition.serverId
    }
}