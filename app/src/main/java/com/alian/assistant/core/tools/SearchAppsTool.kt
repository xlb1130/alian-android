package com.alian.assistant.core.tools

import com.alian.assistant.infrastructure.device.AppScanner

/**
 * 搜索应用工具
 *
 * 支持：
 * - 应用名搜索
 * - 拼音搜索
 * - 语义搜索（如"点外卖"会匹配外卖类应用）
 * - 分类搜索
 */
class SearchAppsTool(private val appScanner: AppScanner) : Tool {

    override val name = "search_apps"
    override val displayName = "搜索应用"
    override val description = "在已安装应用中搜索，支持应用名、拼音、语义描述等多种方式"

    override val params = listOf(
        ToolParam(
            name = "query",
            type = "string",
            description = "搜索词（应用名/拼音/描述，如：微信、weixin、聊天）",
            required = true
        ),
        ToolParam(
            name = "top_k",
            type = "int",
            description = "返回结果数量",
            required = false,
            defaultValue = 5
        ),
        ToolParam(
            name = "include_system",
            type = "boolean",
            description = "是否包含系统应用",
            required = false,
            defaultValue = true
        )
    )

    override suspend fun execute(params: Map<String, Any?>): ToolResult {
        val query = params["query"] as? String
            ?: return ToolResult.Error("缺少 query 参数")

        val topK = (params["top_k"] as? Number)?.toInt() ?: 5
        val includeSystem = params["include_system"] as? Boolean ?: true

        val results = appScanner.searchApps(query, topK, includeSystem)

        if (results.isEmpty()) {
            return ToolResult.Success(
                data = emptyList<Map<String, Any>>(),
                message = "未找到匹配\"$query\"的应用"
            )
        }

        val data = results.map { result ->
            mapOf(
                "package_name" to result.app.packageName,
                "app_name" to result.app.appName,
                "category" to (result.app.category ?: ""),
                "score" to result.score,
                "match_type" to result.matchType,
                "is_system" to result.app.isSystem
            )
        }

        return ToolResult.Success(
            data = data,
            message = "找到 ${results.size} 个匹配的应用"
        )
    }

    /**
     * 快捷方法：直接获取最佳匹配的包名
     */
    fun findBestMatch(query: String): String? {
        val results = appScanner.searchApps(query, topK = 1)
        return results.firstOrNull()?.app?.packageName
    }
}
