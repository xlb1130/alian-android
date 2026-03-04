package com.alian.assistant.core.flow.service

import android.content.Context
import com.alian.assistant.core.flow.model.*
import com.alian.assistant.data.ExecutionRecord
import com.alian.assistant.data.ExecutionStep
import com.alian.assistant.data.ExecutionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 流程学习服务
 * 从执行结果中学习，更新模板
 */
class FlowLearner(
    private val context: Context
) {
    // 学习参数
    companion object {
        const val MIN_EXECUTIONS_FOR_LEARNING = 2      // 最少执行次数才开始学习
        const val STRATEGY_CONFIDENCE_BOOST = 0.1f     // 成功时策略置信度增加
        const val STRATEGY_CONFIDENCE_DECAY = 0.05f    // 失败时策略置信度减少
        const val MIN_STRATEGY_CONFIDENCE = 0.3f       // 最小策略置信度
        const val MAX_STRATEGY_CONFIDENCE = 1.0f       // 最大策略置信度
    }
    
    /**
     * 从单步执行学习
     */
    suspend fun learnFromStep(
        template: FlowTemplate,
        step: FlowStep,
        success: Boolean,
        actualLocateStrategy: LocateType?,
        executionTime: Long
    ): FlowTemplate = withContext(Dispatchers.Default) {
        // 更新步骤统计
        var updatedTemplate = template.withStepResult(step.id, success, executionTime)
        
        // 更新定位策略置信度
        if (actualLocateStrategy != null) {
            updatedTemplate = updateLocateStrategyConfidence(
                updatedTemplate, 
                step.id, 
                actualLocateStrategy, 
                success
            )
        }
        
        updatedTemplate
    }
    
    /**
     * 从会话学习
     */
    suspend fun learnFromSession(
        template: FlowTemplate,
        session: FlowSession,
        success: Boolean
    ): FlowTemplate = withContext(Dispatchers.Default) {
        // 更新模板统计
        val executionTime = session.totalExecutionTime
        val updatedStats = template.statistics.withExecution(success, executionTime)
        
        // 更新优化统计
        val finalStats = updatedStats.withOptimization(
            screenshotsSkipped = session.stats.screenshotsSkipped,
            vlmCallsSkipped = session.stats.vlmCallsSkipped,
            tokensSaved = session.stats.tokensSaved
        )
        
        template.copy(
            statistics = finalStats,
            updatedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * 从传统执行中学习（创建新模板）
     */
    suspend fun learnFromTraditionalExecution(
        instruction: String,
        executionRecord: ExecutionRecord
    ): FlowTemplate? = withContext(Dispatchers.Default) {
        // 只从成功执行中学习
        if (executionRecord.status != ExecutionStatus.COMPLETED) {
            return@withContext null
        }
        
        // 检查是否有足够的步骤
        if (executionRecord.steps.size < MIN_EXECUTIONS_FOR_LEARNING) {
            return@withContext null
        }
        
        // 创建新模板
        createTemplateFromExecution(instruction, executionRecord)
    }
    
    /**
     * 更新定位策略置信度
     */
    private fun updateLocateStrategyConfidence(
        template: FlowTemplate,
        stepId: String,
        locateType: LocateType,
        success: Boolean
    ): FlowTemplate {
        return template.withUpdatedStep(stepId) { step ->
            val updatedStrategies = step.locateStrategies.map { strategy ->
                if (strategy.type == locateType) {
                    val newConfidence = if (success) {
                        (strategy.confidence + STRATEGY_CONFIDENCE_BOOST).coerceAtMost(MAX_STRATEGY_CONFIDENCE)
                    } else {
                        (strategy.confidence - STRATEGY_CONFIDENCE_DECAY).coerceAtLeast(MIN_STRATEGY_CONFIDENCE)
                    }
                    strategy.copy(confidence = newConfidence)
                } else {
                    strategy
                }
            }
            step.copy(locateStrategies = updatedStrategies)
        }
    }
    
    /**
     * 从执行记录创建模板
     */
    private fun createTemplateFromExecution(
        instruction: String,
        record: ExecutionRecord
    ): FlowTemplate {
        // 从执行记录中提取步骤
        val steps = record.steps.mapIndexed { index, executionStep ->
            FlowStep(
                id = StepId.generate(),
                order = index,
                action = parseAction(executionStep.action),
                nodeType = determineNodeType(executionStep, index, record.steps.size),
                locateStrategies = extractLocateStrategies(executionStep),
                screenDescription = executionStep.description
            )
        }
        
        // 提取关键词
        val keywords = extractKeywords(instruction)
        
        // 检测目标应用
        val targetApp = detectTargetApp(record)
        
        return FlowTemplate(
            id = TemplateId.generate(),
            name = extractTemplateName(instruction),
            description = instruction,
            category = categorizeInstruction(instruction),
            matchingRule = MatchingRuleDto(
                keywords = keywords,
                intentPatterns = listOf(".*${keywords.firstOrNull() ?: ""}.*")
            ),
            targetApp = targetApp,
            steps = steps,
            keyNodes = extractKeyNodes(steps, record),
            statistics = FlowStatistics(),
            source = TemplateSource.LEARNED
        )
    }
    
    /**
     * 解析动作字符串为 StepAction
     */
    private fun parseAction(actionStr: String): StepAction {
        val parts = actionStr.split("(", ")", ",").filter { it.isNotBlank() }
        val type = parts.firstOrNull() ?: "unknown"
        
        return StepAction(
            type = ActionType.fromString(type),
            target = parts.getOrNull(1),
            coordinate = null,
            inputValue = null,
            swipeDirection = null
        )
    }
    
    /**
     * 确定节点类型
     */
    private fun determineNodeType(
        step: ExecutionStep,
        index: Int,
        totalSteps: Int
    ): NodeType {
        // 判断是否为敏感操作
        val isSensitive = step.action in listOf("click", "long_press") && 
                          step.actionMessage != null
        
        return when {
            index == 0 -> NodeType.ENTRY
            index == totalSteps - 1 -> NodeType.COMPLETION
            isSensitive -> NodeType.KEY_OPERATION
            else -> NodeType.INTERMEDIATE
        }
    }
    
    /**
     * 提取定位策略
     */
    private fun extractLocateStrategies(step: ExecutionStep): List<LocateStrategy> {
        val strategies = mutableListOf<LocateStrategy>()
        
        // 从描述中提取可能的定位信息
        val description = step.description
        
        // 尝试从描述中提取资源ID
        val resourceIdPattern = Regex("resourceId[:\\s]+([^,\\s]+)")
        resourceIdPattern.find(description)?.let {
            strategies.add(LocateStrategy(LocateType.RESOURCE_ID, 1, it.groupValues[1], 1.0f))
        }
        
        // 尝试从描述中提取文本
        val textPattern = Regex("text[:\\s]+\"([^\"]+)\"")
        textPattern.find(description)?.let {
            strategies.add(LocateStrategy(LocateType.TEXT_EXACT, 2, it.groupValues[1], 0.9f))
            strategies.add(LocateStrategy(LocateType.TEXT_FUZZY, 3, it.groupValues[1], 0.7f))
        }
        
        // 尝试从描述中提取内容描述
        val descPattern = Regex("contentDesc[:\\s]+\"([^\"]+)\"")
        descPattern.find(description)?.let {
            strategies.add(LocateStrategy(LocateType.CONTENT_DESC, 4, it.groupValues[1], 0.8f))
        }
        
        // 如果有坐标信息
        if (step.action == "tap" || step.action == "click") {
            val coordPattern = Regex("\\((\\d+),(\\d+)\\)")
            coordPattern.find(description)?.let {
                strategies.add(LocateStrategy(LocateType.COORDINATE, 5, "${it.groupValues[1]},${it.groupValues[2]}", 0.5f))
            }
        }
        
        return strategies.ifEmpty {
            // 默认策略
            listOf(LocateStrategy(LocateType.TEXT_FUZZY, 1, step.description.take(20), 0.5f))
        }
    }
    
    /**
     * 提取关键词
     */
    private fun extractKeywords(instruction: String): List<String> {
        // 简单的关键词提取：分词并过滤停用词
        val stopWords = setOf("帮", "我", "请", "的", "了", "在", "是", "有", "和", "就", "不", "都", "要", "会")
        
        return instruction.split(Regex("\\s+|[，。！？、]"))
            .filter { it.isNotBlank() && it !in stopWords && it.length >= 2 }
            .distinct()
            .take(5)
    }
    
    /**
     * 提取模板名称
     */
    private fun extractTemplateName(instruction: String): String {
        // 提取核心动词和对象
        val keywords = extractKeywords(instruction)
        return keywords.take(3).joinToString("") + "流程"
    }
    
    /**
     * 分类指令
     */
    private fun categorizeInstruction(instruction: String): FlowCategory {
        val categoryKeywords = mapOf(
            FlowCategory.NAVIGATION to listOf("导航", "路线", "去", "到", "地图"),
            FlowCategory.FOOD_ORDERING to listOf("点餐", "外卖", "订餐", "点菜", "肯德基", "麦当劳", "美团"),
            FlowCategory.SHOPPING to listOf("购物", "买", "下单", "淘宝", "京东", "拼多多"),
            FlowCategory.TRANSPORTATION to listOf("打车", "叫车", "滴滴", "出行", "公交", "地铁"),
            FlowCategory.ENTERTAINMENT to listOf("游戏", "音乐", "视频", "电影"),
            FlowCategory.UTILITIES to listOf("设置", "闹钟", "日历", "计算器", "翻译")
        )
        
        for ((category, keywords) in categoryKeywords) {
            if (keywords.any { instruction.contains(it, ignoreCase = true) }) {
                return category
            }
        }
        
        return FlowCategory.GENERAL
    }
    
    /**
     * 检测目标应用
     */
    private fun detectTargetApp(record: ExecutionRecord): TargetApp {
        // 从执行记录中提取目标应用信息
        return TargetApp(
            packageName = "unknown",
            appName = "未知应用",
            launchActivity = null
        )
    }
    
    /**
     * 提取关键节点
     */
    private fun extractKeyNodes(steps: List<FlowStep>, record: ExecutionRecord): List<KeyNode> {
        val keyNodes = mutableListOf<KeyNode>()
        
        // 入口节点
        steps.firstOrNull()?.let { step ->
            if (step.nodeType == NodeType.ENTRY) {
                keyNodes.add(KeyNode(
                    id = NodeId.generate(),
                    name = "ENTRY",
                    stepIndex = 0,
                    screenSignature = ScreenSignature(
                        textPatterns = emptyList(),
                        elementSignatures = emptyList()
                    ),
                    verificationMethod = VerificationMethod.ACCESSIBILITY_CHECK
                ))
            }
        }
        
        // 完成节点
        steps.lastOrNull()?.let { step ->
            if (step.nodeType == NodeType.COMPLETION) {
                keyNodes.add(KeyNode(
                    id = NodeId.generate(),
                    name = "COMPLETION",
                    stepIndex = steps.size - 1,
                    screenSignature = ScreenSignature(
                        textPatterns = listOf("完成", "成功"),
                        elementSignatures = emptyList()
                    ),
                    verificationMethod = VerificationMethod.ACCESSIBILITY_CHECK
                ))
            }
        }
        
        return keyNodes
    }
}