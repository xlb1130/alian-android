package com.alian.assistant.core.flow

import android.content.Context
import android.util.Log
import com.alian.assistant.core.flow.model.*
import com.alian.assistant.core.flow.service.*
import com.alian.assistant.data.ExecutionRecord
import com.alian.assistant.data.repository.FlowTemplateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FlowAssistant 领域服务
 * 辅助 MobileAgentImprove 进行执行决策
 * 
 * 职责：
 * - 匹配流程模板
 * - 预测下一步动作
 * - 判断是否可以跳过截图
 * - 同步执行结果
 */
class FlowAssistant(
    private val context: Context,
    private val nodeMatcher: NodeMatcher = NodeMatcher(),
    private val stepPredictor: StepPredictor = StepPredictor(),
    private val flowLearner: FlowLearner = FlowLearner(context)
) {
    companion object {
        private const val TAG = "FlowAssistant"
    }
    
    // Repository（延迟初始化，避免循环依赖）
    private var templateRepository: FlowTemplateRepository? = null
    
    /**
     * 设置模板仓库
     */
    fun setTemplateRepository(repository: FlowTemplateRepository) {
        templateRepository = repository
    }
    
    /**
     * 当前会话
     */
    private var currentSession: FlowSession? = null
    
    /**
     * 开始新会话
     */
    suspend fun startSession(instruction: String): FlowSession = withContext(Dispatchers.Default) {
        Log.d(TAG, "开始会话: $instruction")
        
        // 尝试匹配模板
        val matchResult = templateRepository?.findMatchingTemplate(instruction)
        
        val session = FlowSession(
            instruction = instruction,
            matchedTemplate = matchResult?.template,
            matchConfidence = matchResult?.confidence ?: 0f
        )
        
        currentSession = session
        
        Log.d(TAG, "会话匹配结果: 模板=${matchResult?.template?.name ?: "无"}, 置信度=${matchResult?.confidence ?: 0f}")
        
        session
    }
    
    /**
     * 查询当前步骤建议
     * 由 MobileAgentImprove 在每步开始时调用
     */
    suspend fun queryStepAdvice(
        currentStepIndex: Int,
        currentPackage: String,
        currentElements: List<ElementSignature>,
        currentTexts: List<String> = emptyList()
    ): StepAdvice = withContext(Dispatchers.Default) {
        val session = currentSession ?: return@withContext StepAdvice.noAdvice()
        val template = session.matchedTemplate ?: return@withContext StepAdvice.noAdvice()
        
        // 获取下一步骤
        val nextStep = template.getCurrentStep(currentStepIndex)
            ?: return@withContext StepAdvice.completed()
        
        // 检查当前节点是否匹配
        val nodeMatchResult = nodeMatcher.matchCurrentNode(
            template = template,
            stepIndex = currentStepIndex,
            currentPackage = currentPackage,
            currentElements = currentElements,
            currentTexts = currentTexts
        )
        
        // 计算跳过截图的置信度
        val skipConfidence = stepPredictor.calculateSkipConfidence(
            template = template,
            step = nextStep,
            nodeMatched = nodeMatchResult.matched
        )
        
        // 预测是否可以跳过截图
        val canSkipScreenshot = stepPredictor.canSkipScreenshot(
            template = template,
            step = nextStep,
            nodeMatched = nodeMatchResult.matched,
            sessionStats = session.stats
        )
        
        // 判断是否应该使用传统模式
        val shouldUseTraditionalMode = stepPredictor.shouldUseTraditionalMode(
            template = template,
            matchConfidence = nodeMatchResult.confidence,
            sessionStats = session.stats
        )
        
        Log.d(TAG, "步骤建议: stepIndex=$currentStepIndex, canSkip=$canSkipScreenshot, " +
                "nodeMatched=${nodeMatchResult.matched}, confidence=${nodeMatchResult.confidence}")
        
        StepAdvice(
            hasAdvice = true,
            nextStep = nextStep,
            canSkipScreenshot = canSkipScreenshot,
            nodeMatched = nodeMatchResult.matched,
            confidence = skipConfidence,
            shouldUseTraditionalMode = shouldUseTraditionalMode
        )
    }
    
    /**
     * 报告执行结果
     * 由 MobileAgentImprove 在每步完成后调用
     */
    suspend fun reportExecutionResult(
        stepIndex: Int,
        action: StepAction?,
        success: Boolean,
        actualLocateStrategy: LocateType?,
        executionTime: Long,
        screenshotSkipped: Boolean = false,
        vlmSkipped: Boolean = false
    ) = withContext(Dispatchers.Default) {
        val session = currentSession ?: return@withContext
        
        Log.d(TAG, "报告执行结果: stepIndex=$stepIndex, success=$success, " +
                "screenshotSkipped=$screenshotSkipped, vlmSkipped=$vlmSkipped")
        
        // 更新会话统计
        currentSession = session
            .withStepResult(stepIndex, success, executionTime)
            .withOptimization(screenshotSkipped, vlmSkipped)
        
        // 如果使用了模板建议，更新模板统计
        session.matchedTemplate?.let { template ->
            template.steps.getOrNull(stepIndex)?.let { step ->
                val updatedTemplate = flowLearner.learnFromStep(
                    template = template,
                    step = step,
                    success = success,
                    actualLocateStrategy = actualLocateStrategy,
                    executionTime = executionTime
                )
                
                // 保存更新后的模板
                templateRepository?.save(updatedTemplate)
            }
        }
    }
    
    /**
     * 结束会话
     */
    suspend fun endSession(success: Boolean) = withContext(Dispatchers.Default) {
        currentSession?.let { session ->
            Log.d(TAG, "结束会话: success=$success, totalSteps=${session.totalSteps}, " +
                    "screenshotsSkipped=${session.stats.screenshotsSkipped}, " +
                    "vlmCallsSkipped=${session.stats.vlmCallsSkipped}")
            
            // 如果有匹配的模板，更新模板统计
            session.matchedTemplate?.let { template ->
                val updatedTemplate = flowLearner.learnFromSession(
                    template = template,
                    session = session,
                    success = success
                )
                
                // 保存更新后的模板
                templateRepository?.save(updatedTemplate)
            }
        }
        
        currentSession = null
    }
    
    /**
     * 从传统执行学习（创建新模板）
     */
    suspend fun learnFromTraditionalExecution(
        instruction: String,
        executionRecord: ExecutionRecord
    ): FlowTemplate? = withContext(Dispatchers.Default) {
        val template = flowLearner.learnFromTraditionalExecution(instruction, executionRecord)
        
        if (template != null) {
            Log.d(TAG, "从传统执行学习到新模板: ${template.name}")
            templateRepository?.save(template)
        }
        
        template
    }
    
    /**
     * 获取当前会话
     */
    fun getCurrentSession(): FlowSession? = currentSession
    
    /**
     * 获取当前匹配的模板
     */
    fun getCurrentTemplate(): FlowTemplate? = currentSession?.matchedTemplate
    
    /**
     * 获取当前匹配置信度
     */
    fun getCurrentMatchConfidence(): Float = currentSession?.matchConfidence ?: 0f
    
    /**
     * 是否有活跃的会话
     */
    fun hasActiveSession(): Boolean = currentSession != null
    
    /**
     * 是否有匹配的模板
     */
    fun hasMatchedTemplate(): Boolean = currentSession?.hasTemplate ?: false
    
    /**
     * 获取执行策略
     */
    fun getExecutionStrategy(
        currentStepIndex: Int,
        currentPackage: String
    ): ExecutionStrategy {
        val session = currentSession ?: return ExecutionStrategy.TRADITIONAL
        val template = session.matchedTemplate ?: return ExecutionStrategy.TRADITIONAL
        val step = template.getCurrentStep(currentStepIndex) ?: return ExecutionStrategy.TRADITIONAL
        
        return stepPredictor.getExecutionStrategy(
            template = template,
            step = step,
            matchConfidence = session.matchConfidence,
            sessionStats = session.stats
        )
    }
    
    /**
     * 提取无障碍元素签名
     */
    fun extractElementSignatures(accessibilityNodes: List<Map<String, Any?>>): List<ElementSignature> {
        return nodeMatcher.extractElementSignatures(accessibilityNodes)
    }
    
    /**
     * 提取文本列表
     */
    fun extractTexts(accessibilityNodes: List<Map<String, Any?>>): List<String> {
        return nodeMatcher.extractTexts(accessibilityNodes)
    }
}
