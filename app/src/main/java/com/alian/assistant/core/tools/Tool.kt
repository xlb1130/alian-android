package com.alian.assistant.core.tools

import org.json.JSONObject

/**
 * Tool 执行结果
 */
sealed class ToolResult {
    data class Success(val data: Any?, val message: String = "") : ToolResult()
    data class Error(val error: String, val code: Int = -1) : ToolResult()

    val isSuccess: Boolean get() = this is Success

    fun getDataOrNull(): Any? = (this as? Success)?.data

    fun toJson(): JSONObject = JSONObject().apply {
        when (this@ToolResult) {
            is Success -> {
                put("success", true)
                put("data", data?.toString() ?: "")
                put("message", message)
            }
            is Error -> {
                put("success", false)
                put("error", error)
                put("code", code)
            }
        }
    }
}

/**
 * Tool 参数定义
 */
data class ToolParam(
    val name: String,
    val type: String,           // string, int, boolean, list
    val description: String,
    val required: Boolean = true,
    val defaultValue: Any? = null
)

/**
 * Tool 接口 - 所有工具的基类
 *
 * Tool 是原子能力层，提供单一、可复用的功能
 * - 每个 Tool 完成一个独立的操作
 * - Tool 之间可以组合使用
 * - Tool 的输入输出是结构化的
 */
interface Tool {
    /** 工具名称（唯一标识） */
    val name: String

    /** 工具显示名称 */
    val displayName: String

    /** 工具描述 */
    val description: String

    /** 参数定义 */
    val params: List<ToolParam>

    /**
     * 执行工具
     * @param params 参数 Map
     * @return 执行结果
     */
    suspend fun execute(params: Map<String, Any?>): ToolResult

    /**
     * 验证参数
     */
    fun validateParams(params: Map<String, Any?>): List<String> {
        val errors = mutableListOf<String>()
        for (param in this.params) {
            if (param.required && !params.containsKey(param.name)) {
                errors.add("缺少必填参数: ${param.name}")
            }
        }
        return errors
    }

    /**
     * 生成给 LLM 的工具描述
     */
    fun toLLMDescription(): String {
        val paramsDesc = params.joinToString("\n") { p ->
            val required = if (p.required) "(必填)" else "(可选)"
            "  - ${p.name}: ${p.type} $required - ${p.description}"
        }
        return """
            |工具: $name
            |说明: $description
            |参数:
            |$paramsDesc
        """.trimMargin()
    }
}

/**
 * Tool 注册表 - 管理所有可用工具
 */
object ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
        println("[ToolRegistry] 注册工具: ${tool.name}")
    }

    fun get(name: String): Tool? = tools[name]

    fun getAll(): List<Tool> = tools.values.toList()

    fun getNames(): List<String> = tools.keys.toList()

    fun contains(name: String): Boolean = tools.containsKey(name)

    /**
     * 生成所有工具的描述（给 LLM）
     */
    fun getAllDescriptions(): String {
        return tools.values.joinToString("\n\n") { it.toLLMDescription() }
    }

    /**
     * 根据名称执行工具
     */
    suspend fun execute(toolName: String, params: Map<String, Any?>): ToolResult {
        val tool = tools[toolName] ?: return ToolResult.Error("未找到工具: $toolName")

        val errors = tool.validateParams(params)
        if (errors.isNotEmpty()) {
            return ToolResult.Error("参数错误: ${errors.joinToString(", ")}")
        }

        return try {
            tool.execute(params)
        } catch (e: Exception) {
            ToolResult.Error("执行失败: ${e.message}")
        }
    }
}
