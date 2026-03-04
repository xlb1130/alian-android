package com.alian.assistant.core.agent.improve

import android.content.Context
import android.util.Log
import com.alian.assistant.core.flow.FlowAssistant
import com.alian.assistant.core.flow.model.*
import com.alian.assistant.core.flow.service.ExecutionStrategy
import com.alian.assistant.data.ExecutionStep
import com.alian.assistant.data.ExecutionRecord
import com.alian.assistant.data.ExecutionStatus
import com.alian.assistant.infrastructure.device.accessibility.AlianAccessibilityService
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Flow 融合执行器
 * 
 * 负责将 Flow 模板建议与 MobileAgentImprove 的传统执行相融合
 */
class FlowIntegration(
    private val context: Context,
    private val controller: IDeviceController
) {
    companion object {
        private const val TAG = "FlowIntegration"
    }
    
    // Flow 辅助器
    private val flowAssistant: FlowAssistant = FlowAssistant(context)
    
    // 模板仓库（延迟初始化）
    private var templateRepository: com.alian.assistant.data.repository.FlowTemplateRepository? = null
    
    /**
     * 当前会话状态
     */
    var currentSession: FlowSession? = null
        private set
    
    /**
     * 当前步骤索引
     */
    var currentStepIndex: Int = 0
        private set
    
    /**
     * 是否启用 Flow 模式
     */
    var flowEnabled: Boolean = true
    
    /**
     * 初始化仓库
     */
    fun initRepository() {
        if (templateRepository == null) {
            templateRepository = com.alian.assistant.data.repository.FlowTemplateRepository.getInstance(context)
            flowAssistant.setTemplateRepository(templateRepository!!)
        }
    }
    
    /**
     * 开始 Flow 会话
     */
    suspend fun startSession(instruction: String): FlowSession {
        if (!flowEnabled) {
            Log.d(TAG, "Flow 模式已禁用，跳过会话启动")
            return FlowSession(instruction, null, 0f)
        }
        
        initRepository()
        currentStepIndex = 0
        currentSession = flowAssistant.startSession(instruction)
        
        Log.d(TAG, "Flow 会话已启动: 模板=${currentSession?.matchedTemplate?.name ?: "无"}, " +
                "置信度=${currentSession?.matchConfidence ?: 0f}")
        
        return currentSession!!
    }
    
    /**
     * 结束 Flow 会话
     */
    suspend fun endSession(success: Boolean) {
        currentSession?.let {
            flowAssistant.endSession(success)
            Log.d(TAG, "Flow 会话已结束: success=$success, " +
                    "跳过截图=${it.stats.screenshotsSkipped}, " +
                    "跳过VLM=${it.stats.vlmCallsSkipped}")
        }
        currentSession = null
        currentStepIndex = 0
    }
    
    /**
     * 获取下一步执行建议
     */
    suspend fun getStepAdvice(): FlowStepAdvice {
        if (!flowEnabled || currentSession == null) {
            return FlowStepAdvice(useTraditionalMode = true)
        }
        
        val template = currentSession!!.matchedTemplate
        if (template == null) {
            return FlowStepAdvice(useTraditionalMode = true)
        }
        
        // 获取当前屏幕信息
        val currentPackage = controller.getCurrentPackage() ?: ""
        val (elements, texts) = extractAccessibilityInfo()
        
        // 查询 Flow 建议
        val advice = flowAssistant.queryStepAdvice(
            currentStepIndex = currentStepIndex,
            currentPackage = currentPackage,
            currentElements = elements,
            currentTexts = texts
        )
        
        return FlowStepAdvice(
            useTraditionalMode = advice.shouldUseTraditionalMode,
            canSkipScreenshot = advice.canSkipScreenshot,
            nextStep = advice.nextStep,
            confidence = advice.confidence,
            nodeMatched = advice.nodeMatched
        )
    }
    
    /**
     * 执行 Flow 建议的步骤
     */
    suspend fun executeFlowStep(step: FlowStep): FlowStepResult {
        val startTime = System.currentTimeMillis()
        
        Log.d(TAG, "执行 Flow 步骤: ${step.action.type}, 置信度=${step.successRate}")
        
        // 按优先级尝试定位策略
        for (strategy in step.locateStrategies.sortedBy { it.priority }) {
            val result = executeWithStrategy(step.action, strategy)
            if (result.success) {
                val executionTime = System.currentTimeMillis() - startTime
                
                // 报告成功
                reportStepResult(
                    stepIndex = currentStepIndex,
                    action = step.action,
                    success = true,
                    locateStrategy = strategy.type,
                    executionTime = executionTime,
                    screenshotSkipped = true,
                    vlmSkipped = true
                )
                
                return FlowStepResult(
                    success = true,
                    locateStrategy = strategy.type,
                    executionTime = executionTime
                )
            }
        }
        
        // 所有策略都失败
        val executionTime = System.currentTimeMillis() - startTime
        reportStepResult(
            stepIndex = currentStepIndex,
            action = step.action,
            success = false,
            locateStrategy = null,
            executionTime = executionTime,
            screenshotSkipped = false,
            vlmSkipped = false
        )
        
        return FlowStepResult(
            success = false,
            locateStrategy = null,
            executionTime = executionTime
        )
    }
    
    /**
     * 使用指定策略执行动作
     */
    private suspend fun executeWithStrategy(
        action: StepAction,
        strategy: LocateStrategy
    ): FlowStepResult = withContext(Dispatchers.IO) {
        val accessibilityService = AlianAccessibilityService.getInstance()
        
        if (accessibilityService == null) {
            Log.w(TAG, "无障碍服务不可用，无法执行 Flow 步骤")
            return@withContext FlowStepResult(success = false)
        }
        
        val success = when (strategy.type) {
            LocateType.RESOURCE_ID -> {
                // 使用无障碍服务的查找和点击方法
                try {
                    val node = accessibilityService.findByResourceId(strategy.value)
                    if (node != null) {
                        accessibilityService.clickNode(node)
                    } else {
                        Log.w(TAG, "未找到资源ID: ${strategy.value}")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "点击失败: ${e.message}")
                    false
                }
            }
            LocateType.TEXT_EXACT -> {
                try {
                    val node = accessibilityService.findFirstByText(strategy.value, exactMatch = true)
                    if (node != null) {
                        accessibilityService.clickNode(node)
                    } else {
                        Log.w(TAG, "未找到精确文本: ${strategy.value}")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "点击失败: ${e.message}")
                    false
                }
            }
            LocateType.TEXT_FUZZY -> {
                try {
                    val node = accessibilityService.findFirstByText(strategy.value, exactMatch = false)
                    if (node != null) {
                        accessibilityService.clickNode(node)
                    } else {
                        Log.w(TAG, "未找到模糊文本: ${strategy.value}")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "点击失败: ${e.message}")
                    false
                }
            }
            LocateType.CONTENT_DESC -> {
                try {
                    val node = accessibilityService.findByContentDescription(strategy.value)
                    if (node != null) {
                        accessibilityService.clickNode(node)
                    } else {
                        Log.w(TAG, "未找到内容描述: ${strategy.value}")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "点击失败: ${e.message}")
                    false
                }
            }
            LocateType.COORDINATE -> {
                val parts = strategy.value.split(",")
                if (parts.size == 2) {
                    val x = parts[0].trim().toIntOrNull() ?: 0
                    val y = parts[1].trim().toIntOrNull() ?: 0
                    controller.tap(x, y)
                    true
                } else {
                    false
                }
            }
        }
        
        FlowStepResult(
            success = success,
            locateStrategy = strategy.type
        )
    }
    
    /**
     * 报告步骤执行结果
     */
    suspend fun reportStepResult(
        stepIndex: Int,
        action: StepAction?,
        success: Boolean,
        locateStrategy: LocateType?,
        executionTime: Long,
        screenshotSkipped: Boolean = false,
        vlmSkipped: Boolean = false
    ) {
        flowAssistant.reportExecutionResult(
            stepIndex = stepIndex,
            action = action,
            success = success,
            actualLocateStrategy = locateStrategy,
            executionTime = executionTime,
            screenshotSkipped = screenshotSkipped,
            vlmSkipped = vlmSkipped
        )
        
        if (success) {
            currentStepIndex++
        }
    }
    
    /**
     * 从传统执行中学习
     */
    suspend fun learnFromTraditionalExecution(
        instruction: String,
        executionSteps: List<ExecutionStep>
    ): FlowTemplate? {
        if (!flowEnabled) return null
        
        // 转换为执行记录
        val currentTime = System.currentTimeMillis()
        val record = ExecutionRecord(
            title = instruction,
            instruction = instruction,
            startTime = currentTime,
            status = ExecutionStatus.COMPLETED,
            steps = executionSteps
        )
        
        return flowAssistant.learnFromTraditionalExecution(instruction, record)
    }
    
    /**
     * 提取无障碍信息
     */
    private fun extractAccessibilityInfo(): Pair<List<ElementSignature>, List<String>> {
        val accessibilityService = AlianAccessibilityService.getInstance()
        
        if (accessibilityService == null) {
            return Pair(emptyList(), emptyList())
        }
        
        // 获取当前屏幕的节点信息
        val nodes = try {
            accessibilityService.getRootInActiveWindow()?.let { rootNode ->
                extractNodes(rootNode)
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "提取节点失败: ${e.message}")
            emptyList()
        }
        
        val elements = nodes.mapNotNull { node ->
            ElementSignature(
                resourceId = node.resourceId,
                text = node.text,
                contentDesc = node.contentDesc,
                className = node.className
            ).takeIf { 
                it.resourceId != null || it.text != null || it.contentDesc != null 
            }
        }
        
        val texts = nodes.mapNotNull { 
            it.text?.takeIf { it.isNotBlank() } 
        }.distinct()
        
        return Pair(elements, texts)
    }
    
    /**
     * 递归提取节点信息
     */
    private fun extractNodes(node: android.view.accessibility.AccessibilityNodeInfo): List<NodeInfo> {
        val result = mutableListOf<NodeInfo>()
        
        fun traverse(n: android.view.accessibility.AccessibilityNodeInfo) {
            result.add(NodeInfo(
                resourceId = n.viewIdResourceName,
                text = n.text?.toString(),
                contentDesc = n.contentDescription?.toString(),
                className = n.className?.toString()
            ))
            
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { traverse(it) }
            }
        }
        
        traverse(node)
        return result
    }
    
    /**
     * 检查是否应该使用 Flow 模式
     */
    fun shouldUseFlowMode(): Boolean {
        return flowEnabled && 
               currentSession?.hasTemplate == true &&
               currentSession!!.matchConfidence >= 0.5f
    }
    
    /**
     * 获取执行策略
     */
    fun getExecutionStrategy(): ExecutionStrategy {
        return flowAssistant.getExecutionStrategy(
            currentStepIndex = currentStepIndex,
            currentPackage = controller.getCurrentPackage() ?: ""
        )
    }
    
    /**
     * 获取会话统计
     */
    fun getSessionStats(): SessionStats? {
        return currentSession?.stats
    }
}

/**
 * 节点信息
 */
private data class NodeInfo(
    val resourceId: String?,
    val text: String?,
    val contentDesc: String?,
    val className: String?
)

/**
 * Flow 步骤建议
 */
data class FlowStepAdvice(
    val useTraditionalMode: Boolean = true,
    val canSkipScreenshot: Boolean = false,
    val nextStep: FlowStep? = null,
    val confidence: Float = 0f,
    val nodeMatched: Boolean = false
)

/**
 * Flow 步骤执行结果
 */
data class FlowStepResult(
    val success: Boolean,
    val locateStrategy: LocateType? = null,
    val executionTime: Long = 0
)