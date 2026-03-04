package com.alian.assistant.core.flow.model

import kotlinx.serialization.*

/**
 * 流程模板聚合根
 * 沉淀后的操作流程，可复用执行
 * 
 * 设计特点：
 * 1. 不可变：所有字段使用 val，状态更新通过 copy() 返回新实例
 * 2. 可序列化：使用 @Serializable 支持直接 JSON 序列化
 * 3. 领域行为与数据分离：领域行为方法不参与序列化
 * 
 * 聚合边界：
 * - FlowTemplate 是聚合根
 * - FlowStep 是内部实体（不可变）
 * - KeyNode 是值对象
 */
@Serializable
data class FlowTemplate(
    // 标识
    @SerialName("id")
    val id: String,                          // 模板唯一ID
    
    @SerialName("name")
    val name: String,                        // 模板名称，如 "肯德基点餐"
    
    @SerialName("description")
    val description: String,                 // 模板描述
    
    // 分类
    @SerialName("category")
    val category: FlowCategory,              // 分类：导航、点餐、购物等
    
    // 匹配规则（DTO 格式，解决 Regex 序列化问题）
    @SerialName("matchingRule")
    val matchingRule: MatchingRuleDto,       // 匹配规则
    
    // 目标应用
    @SerialName("targetApp")
    val targetApp: TargetApp,                // 目标应用信息
    
    // 步骤集合（不可变列表）
    @SerialName("steps")
    val steps: List<FlowStep>,               // 步骤列表
    
    // 关键节点集合
    @SerialName("keyNodes")
    val keyNodes: List<KeyNode>,             // 关键节点列表
    
    // 统计信息
    @SerialName("statistics")
    val statistics: FlowStatistics,          // 执行统计
    
    // 来源标记
    @SerialName("source")
    val source: TemplateSource = TemplateSource.LEARNED,  // 模板来源
    
    // 版本控制
    @SerialName("version")
    val version: Int = 1,
    
    @SerialName("createdAt")
    val createdAt: Long = System.currentTimeMillis(),
    
    @SerialName("updatedAt")
    val updatedAt: Long = System.currentTimeMillis()
) {
    // ========== 领域行为（返回新实例，不参与序列化）==========
    
    /**
     * 添加步骤（返回新实例）
     */
    fun withStep(step: FlowStep): FlowTemplate {
        return copy(
            steps = steps + step,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 更新步骤（返回新实例）
     */
    fun withUpdatedStep(stepId: String, update: (FlowStep) -> FlowStep): FlowTemplate {
        return copy(
            steps = steps.map { step ->
                if (step.id == stepId) update(step) else step
            },
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 更新步骤执行结果（返回新实例）
     */
    fun withStepResult(stepId: String, success: Boolean, executionTime: Long): FlowTemplate {
        val updatedSteps = steps.map { step ->
            if (step.id == stepId) {
                step.withExecutionResult(success, executionTime)
            } else {
                step
            }
        }
        return copy(
            steps = updatedSteps,
            statistics = statistics.withExecution(success, executionTime),
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 更新统计数据（返回新实例）
     */
    fun withStatistics(update: (FlowStatistics) -> FlowStatistics): FlowTemplate {
        return copy(
            statistics = update(statistics),
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 添加关键节点（返回新实例）
     */
    fun withKeyNode(keyNode: KeyNode): FlowTemplate {
        return copy(
            keyNodes = keyNodes + keyNode,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 获取下一步建议
     */
    fun suggestNextStep(currentStepIndex: Int): FlowStep? {
        return steps.getOrNull(currentStepIndex + 1)
    }
    
    /**
     * 获取当前步骤
     */
    fun getCurrentStep(currentStepIndex: Int): FlowStep? {
        return steps.getOrNull(currentStepIndex)
    }
    
    /**
     * 匹配指令
     */
    fun matchesInstruction(instruction: String): MatchResult {
        return matchingRule.toDomain().match(instruction)
    }
    
    /**
     * 检查是否匹配目标应用
     */
    fun matchesApp(packageName: String): Boolean {
        return targetApp.packageName == packageName
    }
    
    /**
     * 是否可信
     */
    val isTrusted: Boolean 
        get() = statistics.trustLevel >= TrustLevel.TRUSTED
    
    /**
     * 是否高度可信
     */
    val isHighlyTrusted: Boolean
        get() = statistics.trustLevel == TrustLevel.HIGHLY_TRUSTED
    
    /**
     * 步骤数量
     */
    val stepCount: Int
        get() = steps.size
    
    /**
     * 总执行次数
     */
    val totalExecutions: Int
        get() = statistics.totalExecutions
    
    /**
     * 成功率
     */
    val successRate: Float
        get() = statistics.successRate
    
    /**
     * 关键节点数量
     */
    val keyNodeCount: Int
        get() = keyNodes.size
    
    /**
     * 获取入口节点
     */
    val entryNode: KeyNode?
        get() = keyNodes.find { it.name == "ENTRY" }
    
    /**
     * 获取完成节点
     */
    val completionNode: KeyNode?
        get() = keyNodes.find { it.name == "COMPLETION" }
}

/**
 * 模板来源
 */
@Serializable
enum class TemplateSource {
    PRESET,     // 预置模板
    LEARNED,    // 系统学习
    USER,       // 用户创建
    IMPORTED    // 导入
}

/**
 * 模板匹配结果
 */
data class TemplateMatchResult(
    val template: FlowTemplate,
    val confidence: Float
) {
    val isHighConfidence: Boolean
        get() = confidence >= 0.8f
    
    val isLowConfidence: Boolean
        get() = confidence < 0.5f
}

// ========== 索引结构 ==========

/**
 * 模板索引（用于快速查询）
 */
@Serializable
data class TemplateIndex(
    @SerialName("userTemplates")
    val userTemplates: List<String>,
    
    @SerialName("learnedTemplates")
    val learnedTemplates: List<String>,
    
    @SerialName("updatedAt")
    val updatedAt: Long
)

/**
 * 预置模板索引
 */
@Serializable
data class PresetIndex(
    @SerialName("templates")
    val templates: List<String>
)

// ========== 执行会话 ==========

/**
 * 执行会话（不可变）
 * 每次状态更新返回新实例
 */
data class FlowSession(
    val instruction: String,
    val matchedTemplate: FlowTemplate?,
    val matchConfidence: Float,
    val startTime: Long = System.currentTimeMillis(),
    
    // 运行时状态（不可变）
    val stepResults: List<StepResult> = emptyList(),
    val stats: SessionStats = SessionStats()
) {
    /**
     * 记录步骤结果（返回新实例）
     */
    fun withStepResult(stepIndex: Int, success: Boolean, executionTime: Long): FlowSession {
        return copy(
            stepResults = stepResults + StepResult(stepIndex, success, executionTime),
            stats = stats.withStep(success)
        )
    }
    
    /**
     * 记录优化（返回新实例）
     */
    fun withOptimization(screenshotSkipped: Boolean, vlmSkipped: Boolean, tokensSaved: Long = 0): FlowSession {
        return copy(
            stats = stats.withOptimization(screenshotSkipped, vlmSkipped, tokensSaved)
        )
    }
    
    /**
     * 总执行时间
     */
    val totalExecutionTime: Long
        get() = System.currentTimeMillis() - startTime
    
    /**
     * 总步骤数
     */
    val totalSteps: Int
        get() = stepResults.size
    
    /**
     * 成功步骤数
     */
    val successfulSteps: Int
        get() = stepResults.count { it.success }
    
    /**
     * 失败步骤数
     */
    val failedSteps: Int
        get() = stepResults.count { !it.success }
    
    /**
     * 是否有模板匹配
     */
    val hasTemplate: Boolean
        get() = matchedTemplate != null
}

/**
 * 步骤执行结果
 */
data class StepResult(
    val stepIndex: Int,
    val success: Boolean,
    val executionTime: Long,
    val action: StepAction? = null,
    val locateStrategy: LocateType? = null
)

/**
 * 步骤建议
 */
data class StepAdvice(
    val hasAdvice: Boolean,
    val nextStep: FlowStep? = null,
    val canSkipScreenshot: Boolean = false,
    val nodeMatched: Boolean = false,
    val confidence: Float = 0f,
    val shouldUseTraditionalMode: Boolean = false
) {
    companion object {
        fun noAdvice() = StepAdvice(hasAdvice = false)
        fun completed() = StepAdvice(hasAdvice = false)
    }
}
