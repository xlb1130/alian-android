package com.alian.assistant.core.flow.model

import kotlinx.serialization.*

// ========== ID 值对象 ==========

/**
 * 模板 ID 生成器
 */
object TemplateId {
    fun generate(): String = "tpl_${System.currentTimeMillis()}_${(0..9999).random().toString().padStart(4, '0')}"
}

/**
 * 步骤 ID 生成器
 */
object StepId {
    fun generate(): String = "step_${System.currentTimeMillis()}_${(0..9999).random().toString().padStart(4, '0')}"
}

/**
 * 节点 ID 生成器
 */
object NodeId {
    fun generate(): String = "node_${System.currentTimeMillis()}_${(0..9999).random().toString().padStart(4, '0')}"
}

// ========== 匹配规则 ==========

/**
 * 匹配规则 DTO（可序列化格式）
 * 将 Regex 转换为 String 存储
 */
@Serializable
data class MatchingRuleDto(
    @SerialName("keywords")
    val keywords: List<String>,              // 触发关键词
    
    @SerialName("intentPatterns")
    val intentPatterns: List<String>,        // 意图匹配正则（字符串形式）
    
    @SerialName("semanticEmbedding")
    val semanticEmbedding: List<Float>? = null  // 语义向量（使用 List 替代 FloatArray）
) {
    /**
     * 转换为领域对象
     */
    fun toDomain(): MatchingRule {
        return MatchingRule(
            keywords = keywords,
            intentPatterns = intentPatterns.map { Regex(it, RegexOption.IGNORE_CASE) },
            semanticEmbedding = semanticEmbedding?.toFloatArray()
        )
    }
    
    companion object {
        /**
         * 从领域对象创建 DTO
         */
        fun fromDomain(rule: MatchingRule): MatchingRuleDto {
            return MatchingRuleDto(
                keywords = rule.keywords,
                intentPatterns = rule.intentPatterns.map { it.pattern },
                semanticEmbedding = rule.semanticEmbedding?.toList()
            )
        }
    }
}

/**
 * 匹配规则领域对象（内部使用，不序列化）
 */
data class MatchingRule(
    val keywords: List<String>,
    val intentPatterns: List<Regex>,
    val semanticEmbedding: FloatArray? = null
) {
    fun match(instruction: String): MatchResult {
        // 关键词匹配
        val keywordScore = if (keywords.isNotEmpty()) {
            keywords.count { keyword ->
                instruction.contains(keyword, ignoreCase = true)
            }.toFloat() / keywords.size
        } else {
            0f
        }
        
        // 正则匹配
        val patternMatch = intentPatterns.any { it.matches(instruction) }
        
        val confidence = if (patternMatch) 0.9f else keywordScore * 0.8f
        
        return MatchResult(
            matched = confidence > 0.5f,
            confidence = confidence
        )
    }
    
    // FloatArray 需要手动实现 equals/hashCode
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MatchingRule) return false
        return keywords == other.keywords &&
               intentPatterns == other.intentPatterns &&
               semanticEmbedding?.contentEquals(other.semanticEmbedding) == true
    }
    
    override fun hashCode(): Int {
        var result = keywords.hashCode()
        result = 31 * result + intentPatterns.hashCode()
        result = 31 * result + (semanticEmbedding?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * 匹配结果
 */
data class MatchResult(
    val matched: Boolean,
    val confidence: Float
)

// ========== 目标应用 ==========

@Serializable
data class TargetApp(
    @SerialName("packageName")
    val packageName: String,
    
    @SerialName("appName")
    val appName: String,
    
    @SerialName("launchActivity")
    val launchActivity: String? = null
)

// ========== 分类 ==========

@Serializable
enum class FlowCategory {
    NAVIGATION,      // 导航
    FOOD_ORDERING,   // 点餐
    SHOPPING,        // 购物
    TRANSPORTATION,  // 出行
    ENTERTAINMENT,   // 娱乐
    UTILITIES,       // 工具
    GENERAL          // 通用
}

// ========== 可信度等级 ==========

@Serializable
enum class TrustLevel {
    NEW,              // 新模板，需要验证
    PROBATIONARY,     // 见习期
    TRUSTED,          // 可信
    HIGHLY_TRUSTED,   // 高度可信
    UNRELIABLE        // 不可靠
}

// ========== 步骤动作 ==========

@Serializable
data class StepAction(
    @SerialName("type")
    val type: ActionType,
    
    @SerialName("target")
    val target: String? = null,              // 目标元素文本/ID
    
    @SerialName("coordinate")
    val coordinate: Coordinate? = null,      // 坐标（降级方案）
    
    @SerialName("inputValue")
    val inputValue: String? = null,          // 输入内容（type 动作）
    
    @SerialName("swipeDirection")
    val swipeDirection: SwipeDirection? = null  // 滑动方向
)

@Serializable
enum class ActionType {
    TAP, LONG_TAP, SWIPE, TYPE, BACK, HOME, OPEN_APP, WAIT, SCROLL_TO_FIND, UNKNOWN;
    
    companion object {
        fun fromString(value: String): ActionType {
            return when (value.lowercase()) {
                "tap", "click" -> TAP
                "long_tap", "long_press", "longtap" -> LONG_TAP
                "swipe" -> SWIPE
                "type", "input" -> TYPE
                "back" -> BACK
                "home" -> HOME
                "open_app", "openapp" -> OPEN_APP
                "wait" -> WAIT
                "scroll_to_find", "scroll" -> SCROLL_TO_FIND
                else -> UNKNOWN
            }
        }
    }
}

@Serializable
data class Coordinate(
    @SerialName("x") val x: Int, 
    @SerialName("y") val y: Int
)

@Serializable
enum class SwipeDirection {
    UP, DOWN, LEFT, RIGHT
}

// ========== 节点类型 ==========

@Serializable
enum class NodeType {
    ENTRY,            // 入口节点：流程开始
    BRANCH,           // 分支节点：需要选择路径
    KEY_OPERATION,    // 关键操作：敏感/重要操作
    INTERMEDIATE,     // 中间节点：普通操作，可跳过验证
    RECOVERY,         // 恢复节点：失败后恢复
    COMPLETION        // 完成节点：流程结束
}

// ========== 定位策略 ==========

@Serializable
data class LocateStrategy(
    @SerialName("type")
    val type: LocateType,
    
    @SerialName("priority")
    val priority: Int,                       // 优先级，数字越小越优先
    
    @SerialName("value")
    val value: String,                       // 定位值
    
    @SerialName("confidence")
    val confidence: Float = 1.0f             // 该策略的置信度
)

@Serializable
enum class LocateType {
    RESOURCE_ID,      // 资源ID（最稳定）
    TEXT_EXACT,       // 精确文本
    TEXT_FUZZY,       // 模糊文本
    CONTENT_DESC,     // 内容描述
    COORDINATE        // 坐标（降级）
}

// ========== 验证条件 ==========

@Serializable
data class VerificationCondition(
    @SerialName("type")
    val type: VerificationType,
    
    @SerialName("timeoutMs")
    val timeoutMs: Long = 5000,
    
    @SerialName("retryCount")
    val retryCount: Int = 2,
    
    @SerialName("expectedElements")
    val expectedElements: List<String> = emptyList()  // 期望出现的元素
)

@Serializable
enum class VerificationType {
    SCREEN_STATE,     // 屏幕状态匹配
    ELEMENT_PRESENCE, // 元素存在性
    PACKAGE_NAME      // 包名验证（工程化）
}

@Serializable
enum class FailureHandling {
    CONTINUE,         // 继续执行下一步
    RETRY,            // 重试当前步骤
    ABORT,            // 终止流程
    FALLBACK          // 回退到传统模式
}

// ========== 元素签名 ==========

/**
 * 元素签名（值对象）
 */
@Serializable
data class ElementSignature(
    @SerialName("resourceId")
    val resourceId: String? = null,
    
    @SerialName("text")
    val text: String? = null,
    
    @SerialName("contentDesc")
    val contentDesc: String? = null,
    
    @SerialName("className")
    val className: String? = null
) {
    fun matches(other: ElementSignature): Boolean {
        return when {
            resourceId != null && other.resourceId != null -> 
                resourceId == other.resourceId
            text != null && other.text != null -> 
                text.equals(other.text, ignoreCase = true)
            contentDesc != null && other.contentDesc != null -> 
                contentDesc.equals(other.contentDesc, ignoreCase = true)
            else -> false
        }
    }
}

// ========== 验证方法 ==========

@Serializable
enum class VerificationMethod {
    ACCESSIBILITY_CHECK,  // 无障碍服务检查（低成本）
    VLM_ANALYSIS,         // VLM 分析（高精度）
    IMAGE_COMPARE         // 图像比对
}
