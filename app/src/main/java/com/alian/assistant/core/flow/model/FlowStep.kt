package com.alian.assistant.core.flow.model

import kotlinx.serialization.*

/**
 * 流程步骤实体（不可变）
 * 属于 FlowTemplate 聚合的一部分
 */
@Serializable
data class FlowStep(
    // 标识
    @SerialName("id")
    val id: String,
    
    // 顺序
    @SerialName("order")
    val order: Int,
    
    // 动作定义
    @SerialName("action")
    val action: StepAction,                  // 值对象：动作定义
    
    // 节点类型
    @SerialName("nodeType")
    val nodeType: NodeType,
    
    // 定位策略（优先级排序）
    @SerialName("locateStrategies")
    val locateStrategies: List<LocateStrategy>,
    
    // 验证条件（可选）
    @SerialName("verification")
    val verification: VerificationCondition? = null,
    
    // 失败处理策略
    @SerialName("failureHandling")
    val failureHandling: FailureHandling = FailureHandling.CONTINUE,
    
    // 屏幕描述（用于调试和日志）
    @SerialName("screenDescription")
    val screenDescription: String? = null,
    
    // 执行统计（不可变）
    @SerialName("successCount")
    val successCount: Int = 0,
    
    @SerialName("failureCount")
    val failureCount: Int = 0,
    
    @SerialName("totalExecutionTime")
    val totalExecutionTime: Long = 0
) {
    /**
     * 记录执行结果（返回新实例）
     */
    fun withExecutionResult(success: Boolean, executionTime: Long): FlowStep {
        return copy(
            successCount = if (success) successCount + 1 else successCount,
            failureCount = if (!success) failureCount + 1 else failureCount,
            totalExecutionTime = totalExecutionTime + executionTime
        )
    }
    
    /**
     * 更新定位策略置信度（返回新实例）
     */
    fun withUpdatedStrategyConfidence(locateType: LocateType, newConfidence: Float): FlowStep {
        return copy(
            locateStrategies = locateStrategies.map { strategy ->
                if (strategy.type == locateType) {
                    strategy.copy(confidence = newConfidence)
                } else {
                    strategy
                }
            }
        )
    }
    
    /**
     * 成功率
     */
    val successRate: Float
        get() = if (successCount + failureCount > 0)
            successCount.toFloat() / (successCount + failureCount)
        else 0f
    
    /**
     * 平均执行时间
     */
    val avgExecutionTime: Long
        get() = if (successCount + failureCount > 0)
            totalExecutionTime / (successCount + failureCount)
        else 0
    
    /**
     * 最佳定位策略
     */
    val bestLocateStrategy: LocateStrategy?
        get() = locateStrategies.maxByOrNull { it.confidence }
    
    /**
     * 是否是关键节点（需要验证）
     */
    val isKeyNode: Boolean
        get() = nodeType == NodeType.ENTRY || 
                nodeType == NodeType.BRANCH || 
                nodeType == NodeType.KEY_OPERATION ||
                nodeType == NodeType.COMPLETION
    
    /**
     * 是否可以跳过截图
     */
    val canSkipScreenshot: Boolean
        get() = nodeType == NodeType.INTERMEDIATE && successRate >= 0.8f
    
    /**
     * 总执行次数
     */
    val totalExecutions: Int
        get() = successCount + failureCount
}
