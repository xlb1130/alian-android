package com.alian.assistant.core.flow.model

import kotlinx.serialization.*

/**
 * 执行统计（不可变值对象）
 * 所有状态更新通过 withXxx() 方法返回新实例
 */
@Serializable
data class FlowStatistics(
    @SerialName("totalExecutions")
    val totalExecutions: Int = 0,
    
    @SerialName("successfulExecutions")
    val successfulExecutions: Int = 0,
    
    @SerialName("failedExecutions")
    val failedExecutions: Int = 0,
    
    @SerialName("totalExecutionTime")
    val totalExecutionTime: Long = 0,
    
    // 优化统计
    @SerialName("screenshotsSkipped")
    val screenshotsSkipped: Int = 0,
    
    @SerialName("vlmCallsSkipped")
    val vlmCallsSkipped: Int = 0,
    
    @SerialName("tokensSaved")
    val tokensSaved: Long = 0,
    
    @SerialName("lastExecutionTime")
    val lastExecutionTime: Long = 0
) {
    /**
     * 记录执行（返回新实例）
     */
    fun withExecution(success: Boolean, executionTime: Long): FlowStatistics {
        return copy(
            totalExecutions = totalExecutions + 1,
            successfulExecutions = if (success) successfulExecutions + 1 else successfulExecutions,
            failedExecutions = if (!success) failedExecutions + 1 else failedExecutions,
            totalExecutionTime = totalExecutionTime + executionTime,
            lastExecutionTime = System.currentTimeMillis()
        )
    }
    
    /**
     * 记录优化统计（返回新实例）
     */
    fun withOptimization(screenshotsSkipped: Int, vlmCallsSkipped: Int, tokensSaved: Long): FlowStatistics {
        return copy(
            screenshotsSkipped = this.screenshotsSkipped + screenshotsSkipped,
            vlmCallsSkipped = this.vlmCallsSkipped + vlmCallsSkipped,
            tokensSaved = this.tokensSaved + tokensSaved
        )
    }
    
    /**
     * 成功率
     */
    val successRate: Float
        get() = if (totalExecutions > 0)
            successfulExecutions.toFloat() / totalExecutions
        else 0f
    
    /**
     * 平均执行时间（毫秒）
     */
    val avgExecutionTime: Long
        get() = if (totalExecutions > 0)
            totalExecutionTime / totalExecutions
        else 0
    
    /**
     * 可信度等级
     */
    val trustLevel: TrustLevel
        get() = when {
            totalExecutions < 3 -> TrustLevel.NEW
            successRate >= 0.9f -> TrustLevel.HIGHLY_TRUSTED
            successRate >= 0.7f -> TrustLevel.TRUSTED
            successRate >= 0.5f -> TrustLevel.PROBATIONARY
            else -> TrustLevel.UNRELIABLE
        }
    
    /**
     * 是否可信（可用于跳过截图决策）
     */
    val isTrusted: Boolean
        get() = trustLevel >= TrustLevel.TRUSTED
    
    /**
     * 节省的时间估算（秒）
     * 假设每次截图 + VLM 调用需要 3 秒
     */
    val savedTimeSeconds: Long
        get() = (screenshotsSkipped + vlmCallsSkipped) * 3L
    
    /**
     * 节省的成本估算（元）
     * 假设每次 VLM 调用成本 0.1 元
     */
    val savedCostYuan: Float
        get() = vlmCallsSkipped * 0.1f
}

/**
 * 会话统计（不可变）
 */
@Serializable
data class SessionStats(
    @SerialName("successfulSteps")
    val successfulSteps: Int = 0,
    
    @SerialName("failedSteps")
    val failedSteps: Int = 0,
    
    @SerialName("screenshotsSkipped")
    val screenshotsSkipped: Int = 0,
    
    @SerialName("vlmCallsSkipped")
    val vlmCallsSkipped: Int = 0,
    
    @SerialName("tokensSaved")
    val tokensSaved: Long = 0
) {
    /**
     * 记录步骤（返回新实例）
     */
    fun withStep(success: Boolean): SessionStats {
        return copy(
            successfulSteps = if (success) successfulSteps + 1 else successfulSteps,
            failedSteps = if (!success) failedSteps + 1 else failedSteps
        )
    }
    
    /**
     * 记录优化（返回新实例）
     */
    fun withOptimization(screenshotSkipped: Boolean, vlmSkipped: Boolean, tokensSaved: Long = 0): SessionStats {
        return copy(
            screenshotsSkipped = if (screenshotSkipped) screenshotsSkipped + 1 else screenshotsSkipped,
            vlmCallsSkipped = if (vlmSkipped) vlmCallsSkipped + 1 else vlmCallsSkipped,
            tokensSaved = this.tokensSaved + tokensSaved
        )
    }
    
    /**
     * 总步骤数
     */
    val totalSteps: Int
        get() = successfulSteps + failedSteps
    
    /**
     * 成功率
     */
    val successRate: Float
        get() = if (totalSteps > 0) successfulSteps.toFloat() / totalSteps else 0f
}
