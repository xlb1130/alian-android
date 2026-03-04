package com.alian.assistant.core.flow.service

import com.alian.assistant.core.flow.model.*

/**
 * 步骤预测服务
 * 预测是否可以跳过截图和 VLM 调用
 */
class StepPredictor {
    
    // 置信度阈值
    companion object {
        const val MIN_CONFIDENCE_FOR_SKIP = 0.8f      // 跳过截图的最低置信度
        const val MIN_SUCCESS_RATE_FOR_SKIP = 0.8f   // 跳过截图的最低成功率
        val MIN_TEMPLATE_TRUST: TrustLevel = TrustLevel.TRUSTED  // 跳过截图所需的模板可信度
    }
    
    /**
     * 判断是否可以跳过截图
     * @param template 流程模板
     * @param step 当前步骤
     * @param nodeMatched 节点是否匹配
     * @param sessionStats 会话统计
     * @return 是否可以跳过截图
     */
    fun canSkipScreenshot(
        template: FlowTemplate,
        step: FlowStep,
        nodeMatched: Boolean,
        sessionStats: SessionStats
    ): Boolean {
        // 1. 模板必须可信
        if (template.statistics.trustLevel < MIN_TEMPLATE_TRUST) {
            return false
        }
        
        // 2. 节点必须匹配
        if (!nodeMatched) {
            return false
        }
        
        // 3. 步骤成功率必须足够高
        if (step.successRate < MIN_SUCCESS_RATE_FOR_SKIP) {
            return false
        }
        
        // 4. 关键节点必须验证
        if (step.isKeyNode) {
            return false
        }
        
        // 5. 会话中没有连续失败
        if (hasRecentFailures(sessionStats)) {
            return false
        }
        
        return true
    }
    
    /**
     * 计算跳过截图的置信度
     */
    fun calculateSkipConfidence(
        template: FlowTemplate,
        step: FlowStep,
        nodeMatched: Boolean
    ): Float {
        var confidence = 0f
        
        // 模板可信度贡献 (0-0.3)
        confidence += when (template.statistics.trustLevel) {
            TrustLevel.HIGHLY_TRUSTED -> 0.3f
            TrustLevel.TRUSTED -> 0.25f
            TrustLevel.PROBATIONARY -> 0.1f
            TrustLevel.NEW -> 0.05f
            TrustLevel.UNRELIABLE -> 0f
        }
        
        // 步骤成功率贡献 (0-0.3)
        confidence += step.successRate * 0.3f
        
        // 节点匹配贡献 (0-0.2)
        if (nodeMatched) {
            confidence += 0.2f
        }
        
        // 步骤类型贡献 (0-0.2)
        confidence += when (step.nodeType) {
            NodeType.INTERMEDIATE -> 0.2f
            NodeType.ENTRY -> 0.1f
            NodeType.COMPLETION -> 0.1f
            NodeType.BRANCH -> 0.05f
            NodeType.KEY_OPERATION -> 0f
            NodeType.RECOVERY -> 0f
        }
        
        return confidence.coerceIn(0f, 1f)
    }
    
    /**
     * 预测下一步动作
     */
    fun predictNextAction(
        template: FlowTemplate,
        currentStepIndex: Int
    ): StepPrediction? {
        val currentStep = template.steps.getOrNull(currentStepIndex) ?: return null
        val nextStep = template.steps.getOrNull(currentStepIndex + 1) ?: return null
        
        // 计算预测置信度
        val confidence = calculatePredictionConfidence(template, currentStepIndex)
        
        return StepPrediction(
            nextStep = nextStep,
            confidence = confidence,
            canSkipVerification = confidence >= MIN_CONFIDENCE_FOR_SKIP && !nextStep.isKeyNode
        )
    }
    
    /**
     * 计算预测置信度
     */
    private fun calculatePredictionConfidence(
        template: FlowTemplate,
        currentStepIndex: Int
    ): Float {
        // 基于历史执行成功率
        val completedSteps = template.steps.take(currentStepIndex + 1)
        val avgSuccessRate = if (completedSteps.isNotEmpty()) {
            completedSteps.map { it.successRate }.average().toFloat()
        } else {
            0.5f
        }
        
        // 基于模板可信度
        val trustBonus = when (template.statistics.trustLevel) {
            TrustLevel.HIGHLY_TRUSTED -> 0.2f
            TrustLevel.TRUSTED -> 0.15f
            TrustLevel.PROBATIONARY -> 0.05f
            else -> 0f
        }
        
        return (avgSuccessRate + trustBonus).coerceIn(0f, 1f)
    }
    
    /**
     * 检查是否有最近的失败
     */
    private fun hasRecentFailures(sessionStats: SessionStats): Boolean {
        // 如果失败步骤数超过成功步骤数的一半，认为有连续失败
        return sessionStats.failedSteps > 0 && 
               sessionStats.failedSteps > sessionStats.successfulSteps / 2
    }
    
    /**
     * 判断是否应该使用传统模式
     */
    fun shouldUseTraditionalMode(
        template: FlowTemplate?,
        matchConfidence: Float,
        sessionStats: SessionStats
    ): Boolean {
        // 无匹配模板
        if (template == null) {
            return true
        }
        
        // 匹配置信度过低
        if (matchConfidence < 0.5f) {
            return true
        }
        
        // 模板不可靠
        if (template.statistics.trustLevel == TrustLevel.UNRELIABLE) {
            return true
        }
        
        // 会话失败率过高
        if (sessionStats.totalSteps >= 3 && sessionStats.successRate < 0.5f) {
            return true
        }
        
        return false
    }
    
    /**
     * 判断是否需要截图验证
     */
    fun needsVerification(
        template: FlowTemplate,
        step: FlowStep,
        skipConfidence: Float
    ): Boolean {
        // 关键节点必须验证
        if (step.isKeyNode) {
            return true
        }
        
        // 置信度不足需要验证
        if (skipConfidence < MIN_CONFIDENCE_FOR_SKIP) {
            return true
        }
        
        // 模板不够可信
        if (template.statistics.trustLevel < TrustLevel.TRUSTED) {
            return true
        }
        
        return false
    }
    
    /**
     * 获取建议的执行策略
     */
    fun getExecutionStrategy(
        template: FlowTemplate?,
        step: FlowStep?,
        matchConfidence: Float,
        sessionStats: SessionStats
    ): ExecutionStrategy {
        if (template == null || step == null) {
            return ExecutionStrategy.TRADITIONAL
        }
        
        val canSkip = canSkipScreenshot(template, step, true, sessionStats)
        val needsVerify = needsVerification(template, step, matchConfidence)
        
        return when {
            canSkip && !needsVerify -> ExecutionStrategy.SKIP_SCREENSHOT
            needsVerify -> ExecutionStrategy.VERIFY_THEN_EXECUTE
            shouldUseTraditionalMode(template, matchConfidence, sessionStats) -> 
                ExecutionStrategy.TRADITIONAL
            else -> ExecutionStrategy.FLOW_GUIDED
        }
    }
}

/**
 * 步骤预测结果
 */
data class StepPrediction(
    val nextStep: FlowStep,
    val confidence: Float,
    val canSkipVerification: Boolean
)

/**
 * 执行策略
 */
enum class ExecutionStrategy {
    TRADITIONAL,        // 传统 VLM 模式
    FLOW_GUIDED,        // Flow 引导模式
    SKIP_SCREENSHOT,    // 跳过截图直接执行
    VERIFY_THEN_EXECUTE // 先验证再执行
}
