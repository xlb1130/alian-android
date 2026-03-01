package com.alian.assistant.core.skills

import kotlin.collections.iterator

/**
 * 执行类型
 */
enum class ExecutionType {
    /** 委托：通过 DeepLink 打开 App */
    DELEGATION,
    /** GUI 自动化：通过截图-操作循环 */
    GUI_AUTOMATION
}

/**
 * 关联应用配置
 */
data class RelatedApp(
    val packageName: String,
    val name: String,
    val type: ExecutionType,
    val deepLink: String? = null,
    val steps: List<String>? = null,
    val priority: Int = 0,
    val description: String? = null
)

/**
 * Skill 参数定义
 */
data class SkillParam(
    val name: String,
    val type: String,           // string, int, boolean
    val description: String,
    val required: Boolean = false,
    val defaultValue: Any? = null,
    val examples: List<String> = emptyList()
)

/**
 * Skill 配置（意图定义）
 */
data class SkillConfig(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val keywords: List<String>,
    val params: List<SkillParam>,
    val relatedApps: List<RelatedApp>,
    val promptHint: String? = null  // 提示词约束，如"内容不超过100字"
)

/**
 * Skill 执行计划
 *
 * 根据用户意图和本地已安装 App 生成的执行方案
 */
data class ExecutionPlan(
    val skillId: String,
    val skillName: String,
    val app: RelatedApp,
    val params: Map<String, Any?>,
    val isInstalled: Boolean,
    val promptHint: String? = null  // 提示词约束
) {
    /**
     * 生成给 Agent 的上下文信息
     */
    fun toAgentContext(): String {
        return buildString {
            append("【任务】${skillName}\n")
            append("【目标应用】${app.name} (${app.packageName})\n")
            append("【执行方式】${if (app.type == ExecutionType.DELEGATION) "快捷跳转" else "GUI 自动化"}\n")

            if (!promptHint.isNullOrBlank()) {
                append("【重要提示】⚠️ $promptHint\n")
            }

            if (!app.steps.isNullOrEmpty()) {
                append("【操作步骤】\n")
                app.steps.forEachIndexed { index, step ->
                    append("  ${index + 1}. $step\n")
                }
            }

            if (params.isNotEmpty()) {
                append("【参数】\n")
                params.forEach { (key, value) ->
                    if (key != "_raw_query" && value != null) {
                        append("  $key: $value\n")
                    }
                }
            }
        }
    }
}

/**
 * Skill 执行结果
 */
sealed class SkillResult {
    /**
     * 委托成功：已通过 DeepLink 跳转
     */
    data class Delegated(
        val app: RelatedApp,
        val deepLink: String,
        val message: String
    ) : SkillResult()

    /**
     * GUI 自动化：返回执行计划给 Agent
     */
    data class NeedAutomation(
        val plan: ExecutionPlan,
        val message: String
    ) : SkillResult()

    /**
     * 失败
     */
    data class Failed(
        val error: String,
        val suggestion: String? = null
    ) : SkillResult()

    /**
     * 无可用应用
     */
    data class NoAvailableApp(
        val skillName: String,
        val requiredApps: List<String>
    ) : SkillResult()
}

/**
 * Skill 意图匹配器
 */
class Skill(val config: SkillConfig) {

    /**
     * 计算与用户查询的匹配分数
     * @return 0-1 之间的分数
     */
    fun matchScore(query: String): Float {
        val lowerQuery = query.lowercase()

        // 精确匹配关键词（最高分）
        for (keyword in config.keywords) {
            if (lowerQuery.contains(keyword.lowercase())) {
                return 0.9f
            }
        }

        // 匹配 Skill 名称
        if (lowerQuery.contains(config.name.lowercase())) {
            return 0.8f
        }

        // 模糊匹配描述
        val descWords = config.description.split(" ", "，", "、", "/")
        val matchedWords = descWords.count { lowerQuery.contains(it.lowercase()) }
        if (matchedWords > 0) {
            return (0.3f + 0.3f * matchedWords / descWords.size).coerceAtMost(0.7f)
        }

        return 0f
    }

    /**
     * 从查询中提取参数
     */
    fun extractParams(query: String): Map<String, Any?> {
        val params = mutableMapOf<String, Any?>()

        for (param in config.params) {
            when (param.name) {
                "food", "item", "song", "book", "keyword" -> {
                    // 提取关键内容（去掉意图关键词后的部分）
                    var content = query
                    for (kw in config.keywords) {
                        content = content.replace(kw, "", ignoreCase = true)
                    }
                    content = content.trim()
                    if (content.isNotEmpty()) {
                        params[param.name] = content
                    }
                }
                "destination", "address", "location" -> {
                    // 提取目的地
                    val patterns = listOf(
                        "去(.+?)$",
                        "到(.+?)$",
                        "导航(.+?)$",
                        "(.+?)怎么走"
                    )
                    for (pattern in patterns) {
                        val match = Regex(pattern).find(query)
                        if (match != null) {
                            params[param.name] = match.groupValues[1].trim()
                            break
                        }
                    }
                }
                "contact" -> {
                    // 提取联系人
                    val patterns = listOf(
                        "给(.+?)发",
                        "跟(.+?)说",
                        "告诉(.+?)"
                    )
                    for (pattern in patterns) {
                        val match = Regex(pattern).find(query)
                        if (match != null) {
                            params[param.name] = match.groupValues[1].trim()
                            break
                        }
                    }
                }
                "message", "content", "prompt" -> {
                    // 保存原始查询作为内容
                    params[param.name] = query
                }
                "time" -> {
                    // 提取时间
                    val patterns = listOf(
                        "(\\d{1,2}[点:：]\\d{0,2})",
                        "(\\d{1,2}点)",
                        "(早上|上午|中午|下午|晚上|明天).{0,5}(\\d{1,2}[点:：]?\\d{0,2}?)"
                    )
                    for (pattern in patterns) {
                        val match = Regex(pattern).find(query)
                        if (match != null) {
                            params[param.name] = match.value
                            break
                        }
                    }
                }
            }

            // 设置默认值
            if (!params.containsKey(param.name) && param.defaultValue != null) {
                params[param.name] = param.defaultValue
            }
        }

        // 保存原始查询
        params["_raw_query"] = query

        return params
    }

    /**
     * 生成 DeepLink（替换参数）
     */
    fun generateDeepLink(app: RelatedApp, params: Map<String, Any?>): String {
        var deepLink = app.deepLink ?: return ""

        for ((key, value) in params) {
            if (value != null && key != "_raw_query") {
                deepLink = deepLink.replace("{$key}", value.toString())
            }
        }

        // 清理未替换的占位符
        deepLink = deepLink.replace(Regex("\\{[^}]+\\}"), "")

        return deepLink
    }
}

/**
 * Skill 匹配结果
 */
data class SkillMatch(
    val skill: Skill,
    val score: Float,
    val params: Map<String, Any?>
)

/**
 * 可用应用匹配结果
 */
data class AvailableAppMatch(
    val skill: Skill,
    val app: RelatedApp,
    val params: Map<String, Any?>,
    val score: Float
)

/**
 * LLM 意图匹配结果
 */
data class LLMIntentMatch(
    val skillId: String,
    val confidence: Float,
    val reasoning: String
)
