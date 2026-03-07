package com.alian.assistant.core.agent

import android.graphics.Bitmap
import android.util.Log
import com.alian.assistant.core.agent.improve.ExecutorImprove
import com.alian.assistant.core.agent.improve.InfoPoolImprove
import com.alian.assistant.core.agent.improve.ManagerImprove
import com.alian.assistant.core.agent.improve.ManagerMode
import com.alian.assistant.core.agent.improve.MobileAgentImprove
import com.alian.assistant.core.agent.improve.ReflectorImprove
import com.alian.assistant.core.agent.memory.Action as AgentAction
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.ai.llm.VLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 手机通话 Agent - 核心逻辑层
 * 
 * 职责：
 * - 分析用户意图（纯对话 vs 需要操作）
 * - 决定是否需要操作手机
 * - 使用 Manager→Executor→执行→Reflector 循环执行多步操作
 * - 协调对话和操作的执行顺序
 */
class PhoneCallAgent(
    private val vlmClient: VLMClient,
    private val controller: IDeviceController,
    private val onActionSpeak: (suspend (text: String) -> Unit)? = null,
    private val onHideFloatingWindow: (suspend () -> Unit)? = null,
    private val onShowFloatingWindow: (suspend () -> Unit)? = null
) {
    companion object {
        private const val TAG = "PhoneCallAgent"
    }

    private val intentParser = IntentParser(vlmClient)
    private val actionDecider = ActionDecider()
    private val orchestrator = Orchestrator(vlmClient, controller, onActionSpeak, onHideFloatingWindow, onShowFloatingWindow)

    /**
     * 处理用户输入
     * @param userText 用户输入文本
     * @param currentScreen 当前屏幕截图（可选）
     * @param conversationHistory 对话历史
     * @return 处理结果
     */
    suspend fun processUserInput(
        userText: String,
        screenHistory: List<Bitmap> = emptyList(),
        conversationHistory: List<PhoneCallMessage> = emptyList()
    ): PhoneCallAgentResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== 开始处理用户输入 ===")
        Log.d(TAG, "用户输入: $userText")
        Log.d(TAG, "屏幕截图数量: ${screenHistory.size}")
        Log.d(TAG, "对话历史大小: ${conversationHistory.size}")

        val latestScreen = screenHistory.lastOrNull()

        try {
            // 1. 解析用户意图（使用最新截图）
            Log.d(TAG, "步骤1: 解析用户意图")
            val intentResult = intentParser.parseIntent(userText, latestScreen, conversationHistory)
            Log.d(TAG, "意图解析结果: type=${intentResult.type}, confidence=${intentResult.confidence}, needsScreenshot=${intentResult.needsScreenshot}")

            // 2. 决定是否需要操作
            Log.d(TAG, "步骤2: 决定是否需要操作")
            val decision = actionDecider.decideAction(intentResult, conversationHistory)
            Log.d(TAG, "决策结果: shouldOperate=${decision.shouldOperate}, operationType=${decision.operationType}, operationDescription=${decision.operationDescription}")

            // 3. 协调执行（传入多帧截图）
            Log.d(TAG, "步骤3: 协调执行")
            val orchestratorResult = orchestrator.orchestrate(
                userText = userText,
                intent = intentResult,
                decision = decision,
                screenHistory = screenHistory,
                conversationHistory = conversationHistory
            )

            Log.d(TAG, "=== 处理完成 ===")
            Log.d(TAG, "AI 回应: ${orchestratorResult.response}")
            Log.d(TAG, "执行的操作: ${orchestratorResult.actions.size} 条")
            Log.d(TAG, "最终状态: ${orchestratorResult.finalState}")

            PhoneCallAgentResult(
                response = orchestratorResult.response,
                actions = orchestratorResult.actions,
                finalState = orchestratorResult.finalState,
                intentType = intentResult.type,
                shouldOperate = decision.shouldOperate
            )
        } catch (e: Exception) {
            Log.e(TAG, "处理用户输入失败", e)
            PhoneCallAgentResult(
                response = "抱歉，处理您的请求时出错了：${e.message}",
                actions = emptyList(),
                finalState = "处理失败",
                intentType = IntentType.QA,
                shouldOperate = false,
                error = e.message
            )
        }
    }

    /**
     * 意图解析器
     * 
     * 解析用户意图，判断是纯对话还是需要操作手机
     */
    class IntentParser(private val vlmClient: VLMClient) {
        companion object {
            private const val TAG = "PhoneCallAgent.IntentParser"
        }

        /**
         * 解析用户意图
         * @param userText 用户输入文本
         * @param currentScreen 当前屏幕截图
         * @param conversationHistory 对话历史
         * @return 意图解析结果
         */
        suspend fun parseIntent(
            userText: String,
            currentScreen: Bitmap?,
            conversationHistory: List<PhoneCallMessage>
        ): IntentResult = withContext(Dispatchers.IO) {
            Log.d(TAG, "开始解析意图: $userText")

            // 快速判断：检查是否包含操作关键词
            val operationKeywords = listOf(
                "打开", "点击", "输入", "滑动", "搜索", "返回", "主页", "启动",
                "open", "click", "tap", "type", "swipe", "search", "back", "home", "launch"
            )

            val hasOperationKeyword = operationKeywords.any { keyword ->
                userText.contains(keyword, ignoreCase = true)
            }

            // 如果包含操作关键词，倾向于判断为 OPERATE 或 MIXED
            if (hasOperationKeyword) {
                Log.d(TAG, "检测到操作关键词，倾向于需要操作")
                
                // 使用 VLM 进一步确认
                val prompt = buildIntentPrompt(userText, conversationHistory)
                val images = if (currentScreen != null) listOf(currentScreen) else emptyList()
                
                val result = vlmClient.predict(prompt, images)
                if (result.isSuccess) {
                    val response = result.getOrNull() ?: ""
                    Log.d(TAG, "VLM 意图分析结果: $response")
                    
                    // 解析 VLM 响应
                    val intentType = parseIntentTypeFromResponse(response)
                    val extractedTask = extractTaskFromResponse(response)
                    
                    IntentResult(
                        type = intentType,
                        confidence = 0.8f,
                        needsScreenshot = intentType != IntentType.QA,
                        extractedTask = extractedTask
                    )
                } else {
                    // VLM 调用失败，使用关键词判断
                    IntentResult(
                        type = IntentType.OPERATE,
                        confidence = 0.6f,
                        needsScreenshot = true,
                        extractedTask = userText
                    )
                }
            } else {
                // 没有操作关键词，倾向于纯对话
                // 但仍需要截图来回答视觉相关问题（如"桌面上有什么应用"）
                Log.d(TAG, "未检测到操作关键词，倾向于纯对话")
                IntentResult(
                    type = IntentType.QA,
                    confidence = 0.7f,
                    needsScreenshot = true,
                    extractedTask = null
                )
            }
        }

        /**
         * 构建意图分析 Prompt
         */
        private fun buildIntentPrompt(
            userText: String,
            conversationHistory: List<PhoneCallMessage>
        ): String {
            val historyText = if (conversationHistory.isNotEmpty()) {
                conversationHistory.takeLast(3).joinToString("\n") { msg ->
                    "${if (msg.isUser) "用户" else "AI"}: ${msg.content}"
                }
            } else {
                "（无对话历史）"
            }

            return """
你是一个意图分析助手。请分析用户的输入，判断用户的意图类型。

对话历史：
$historyText

用户输入：$userText

请判断用户的意图类型，并按以下格式输出：
意图类型：[QA/OPERATE/MIXED]
- QA：纯问答，不需要操作手机
- OPERATE：需要操作手机
- MIXED：混合类型，需要对话和操作

任务描述：[如果需要操作，简要描述任务内容；否则输出"无"]

置信度：[0-1之间的数字]

示例：
用户输入：今天天气怎么样？
意图类型：QA
任务描述：无
置信度：0.9

用户输入：帮我打开微信
意图类型：OPERATE
任务描述：打开微信
置信度：0.95

用户输入：帮我查一下明天的天气，然后告诉我
意图类型：MIXED
任务描述：打开天气，查询明天天气
置信度：0.85
""".trimIndent()
        }

        /**
         * 从 VLM 响应中解析意图类型
         */
        private fun parseIntentTypeFromResponse(response: String): IntentType {
            val lowerResponse = response.lowercase()
            return when {
                lowerResponse.contains("operate") || lowerResponse.contains("操作") -> IntentType.OPERATE
                lowerResponse.contains("mix") || lowerResponse.contains("混合") -> IntentType.MIXED
                else -> IntentType.QA
            }
        }

        /**
         * 从 VLM 响应中提取任务描述
         */
        private fun extractTaskFromResponse(response: String): String? {
            val lines = response.lines()
            for (line in lines) {
                if (line.contains("任务描述") || line.contains("任务")) {
                    val parts = line.split("：", ":")
                    if (parts.size >= 2) {
                        val task = parts[1].trim()
                        if (task != "无" && task != "None" && task.isNotEmpty()) {
                            return task
                        }
                    }
                }
            }
            return null
        }
    }

    /**
     * 操作决策器
     * 
     * 根据意图和对话历史决定是否执行操作
     */
    class ActionDecider {
        companion object {
            private const val TAG = "PhoneCallAgent.ActionDecider"
        }

        /**
         * 决定是否需要执行操作
         * @param intent 意图解析结果
         * @param conversationHistory 对话历史
         * @return 决策结果
         */
        fun decideAction(
            intent: IntentResult,
            conversationHistory: List<PhoneCallMessage>
        ): Decision {
            Log.d(TAG, "开始决策: intentType=${intent.type}, confidence=${intent.confidence}")

            // 根据意图类型直接决策
            val shouldOperate = when (intent.type) {
                IntentType.QA -> false
                IntentType.OPERATE -> true
                IntentType.MIXED -> true
            }

            // 确定操作类型
            val operationType = if (shouldOperate) {
                determineOperationType(intent.extractedTask ?: "")
            } else {
                null
            }

            // 估算操作步数
            val estimatedSteps = if (shouldOperate) {
                estimateSteps(intent.extractedTask ?: "")
            } else {
                0
            }

            return Decision(
                shouldOperate = shouldOperate,
                operationType = operationType,
                operationDescription = intent.extractedTask,
                estimatedSteps = estimatedSteps
            )
        }

        /**
         * 确定操作类型
         */
        private fun determineOperationType(task: String): OperationType {
            val lowerTask = task.lowercase()
            return when {
                lowerTask.contains("打开") || lowerTask.contains("启动") || lowerTask.contains("open") || lowerTask.contains("launch") -> OperationType.OPEN_APP
                lowerTask.contains("点击") || lowerTask.contains("tap") || lowerTask.contains("click") -> OperationType.CLICK
                lowerTask.contains("输入") || lowerTask.contains("type") || lowerTask.contains("输入文本") -> OperationType.TYPE
                lowerTask.contains("滑动") || lowerTask.contains("swipe") -> OperationType.SWIPE
                lowerTask.contains("搜索") || lowerTask.contains("search") -> OperationType.SEARCH
                lowerTask.contains("返回") || lowerTask.contains("back") -> OperationType.NAVIGATE
                else -> OperationType.CUSTOM
            }
        }

        /**
         * 估算操作步数
         */
        private fun estimateSteps(task: String): Int {
            val lowerTask = task.lowercase()
            return when {
                lowerTask.contains("打开") || lowerTask.contains("启动") -> 1
                lowerTask.contains("搜索") -> 3
                lowerTask.contains("点击") -> 1
                lowerTask.contains("滑动") -> 1
                lowerTask.contains("输入") -> 2
                lowerTask.contains("返回") -> 1
                else -> 2
            }
        }
    }

    /**
     * 执行协调器
     * 
     * 使用 Manager→Executor→执行→Reflector 循环执行多步操作
     * 参考 MobileAgentImprove 的执行链路实现
     */
    class Orchestrator(
        private val vlmClient: VLMClient,
        private val controller: IDeviceController,
        private val onActionSpeak: (suspend (text: String) -> Unit)? = null,
        private val onHideFloatingWindow: (suspend () -> Unit)? = null,
        private val onShowFloatingWindow: (suspend () -> Unit)? = null
    ) {
        companion object {
            private const val TAG = "PhoneCallAgent.Orchestrator"
            private const val MAX_OPERATION_STEPS = 10
            private const val MAX_STEPS_WITHOUT_MANAGER_UPDATE = 5
            private const val MIN_SUCCESSFUL_ACTIONS_FOR_SUBGOAL = 2
        }

        private val manager = ManagerImprove()
        private val executor = ExecutorImprove()
        private val reflector = ReflectorImprove(controller)

        private suspend fun safeHideFloatingWindow() {
            runCatching { onHideFloatingWindow?.invoke() }
                .onFailure { Log.w(TAG, "隐藏悬浮窗失败，继续执行自动化: ${it.message}") }
        }

        private suspend fun safeShowFloatingWindow() {
            runCatching { onShowFloatingWindow?.invoke() }
                .onFailure { Log.w(TAG, "恢复悬浮窗失败，继续执行自动化: ${it.message}") }
        }

        /**
         * 协调对话和操作的执行
         * @param userText 用户输入
         * @param intent 意图解析结果
         * @param decision 决策结果
         * @param screenHistory 屏幕截图历史
         * @param conversationHistory 对话历史
         * @return 协调执行结果
         */
        suspend fun orchestrate(
            userText: String,
            intent: IntentResult,
            decision: Decision,
            screenHistory: List<Bitmap> = emptyList(),
            conversationHistory: List<PhoneCallMessage>
        ): OrchestratorResult = withContext(Dispatchers.IO) {
            Log.d(TAG, "开始协调执行")

            val latestScreen = screenHistory.lastOrNull()
            // 1. 生成对话回应
            val response = generateResponse(userText, intent, decision, latestScreen, conversationHistory)
            Log.d(TAG, "生成回应: $response")

            // 2. 如果需要操作，执行多步操作循环
            val executedActions = if (decision.shouldOperate) {
                Log.d(TAG, "开始多步操作循环")
                executeOperationLoop(
                    instruction = decision.operationDescription ?: userText,
                    initialScreen = latestScreen
                )
            } else {
                Log.d(TAG, "无需操作")
                emptyList()
            }

            // 3. 确定最终状态
            val finalState = when {
                executedActions.isEmpty() -> "对话完成"
                executedActions.any { it.type == "ERROR" } -> "操作部分失败"
                executedActions.any { it.type == "answer" } -> "已回答"
                else -> "操作完成"
            }

            OrchestratorResult(
                response = response,
                actions = executedActions,
                finalState = finalState
            )
        }
        /**
         * 多步操作循环：Manager→Executor→执行→Reflector
         * 参考 MobileAgentImprove.runInstruction() 的核心循环
         */
        private suspend fun executeOperationLoop(
            instruction: String,
            initialScreen: Bitmap?
        ): List<PhoneCallAction> = withContext(Dispatchers.IO) {
            val executedActions = mutableListOf<PhoneCallAction>()

            // 初始化 InfoPoolImprove
            val infoPool = InfoPoolImprove(instruction = instruction)
            infoPool.managerMode = ManagerMode.INITIAL

            // 获取屏幕尺寸
            val (screenWidth, screenHeight) = controller.getScreenSize()
            infoPool.screenWidth = screenWidth
            infoPool.screenHeight = screenHeight
            Log.d(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}")

            for (step in 0 until MAX_OPERATION_STEPS) {
                Log.d(TAG, "\n========== 操作步骤 ${step + 1}/$MAX_OPERATION_STEPS ==========")
                infoPool.totalSteps = step + 1

                // 每一步开始时强制截图（参考 MobileAgentImprove）
                // 截图前隐藏悬浮窗
                Log.d(TAG, "截图前隐藏悬浮窗")
                safeHideFloatingWindow()
                delay(100) // 等待悬浮窗完全隐藏

                Log.d(TAG, "获取当前屏幕截图...")
                val screenshotResult = controller.screenshotWithFallback()
                var currentScreenshot = screenshotResult.bitmap
                if (screenshotResult.isFallback) {
                    Log.w(TAG, "截图失败，使用黑屏占位图")
                }
                Log.d(TAG, "截图完成: ${if (currentScreenshot != null) "${currentScreenshot.width}x${currentScreenshot.height}" else "null"}")

                // 截图后恢复悬浮窗显示
                Log.d(TAG, "截图后恢复悬浮窗")
                safeShowFloatingWindow()

                // === 1. 检查错误升级 ===
                checkErrorEscalation(infoPool)

                // === 2. 判断是否需要调用 Manager ===
                val shouldCallManager = shouldCallManager(infoPool, step)

                if (shouldCallManager) {
                    Log.d(TAG, "Manager 规划中... (模式: ${infoPool.managerMode})")
                    val planPrompt = manager.getPrompt(infoPool)
                    val planResponse = vlmClient.predict(
                        planPrompt,
                        if (currentScreenshot != null) listOf(currentScreenshot!!) else emptyList(),
                        MobileAgentImprove.SYSTEM_PROMPT
                    )

                    if (planResponse.isFailure) {
                        Log.e(TAG, "Manager 调用失败: ${planResponse.exceptionOrNull()?.message}")
                        continue
                    }

                    val planResult = manager.parseResponse(planResponse.getOrThrow())
                    infoPool.completedPlan = planResult.completedSubgoal
                    infoPool.plan = planResult.plan
                    infoPool.lastPlanUpdateStep = step
                    infoPool.managerCalls++

                    Log.d(TAG, "计划: ${planResult.plan.take(100)}...")

                    // 检查是否完成
                    if (planResult.plan.contains("Finished") && planResult.plan.length < 20) {
                        Log.d(TAG, "Manager 判断任务完成")
                        break
                    }

                    // 检查是否遇到敏感页面
                    if (planResult.plan.contains("STOP_SENSITIVE")) {
                        Log.w(TAG, "检测到敏感页面，停止执行")
                        executedActions.add(PhoneCallAction(
                            type = "STOP",
                            description = "检测到敏感页面，已安全停止",
                            timestamp = System.currentTimeMillis()
                        ))
                        break
                    }
                } else {
                    Log.d(TAG, "跳过 Manager，复用现有计划")
                    infoPool.managerSkips++
                }

                // === 3. Executor 决定动作 ===
                Log.d(TAG, "Executor 决策中...")
                val actionPrompt = executor.getPrompt(infoPool, enableBatchExecution = false)
                val actionResponse = vlmClient.predict(
                    actionPrompt,
                    if (currentScreenshot != null) listOf(currentScreenshot!!) else emptyList(),
                    MobileAgentImprove.SYSTEM_PROMPT
                )

                if (actionResponse.isFailure) {
                    Log.e(TAG, "Executor 调用失败: ${actionResponse.exceptionOrNull()?.message}")
                    continue
                }

                val responseText = actionResponse.getOrThrow()
                Log.d(TAG, "VLM 原始响应:\n$responseText")
                val executorResult = executor.parseResponse(responseText, enableBatchExecution = false)

                Log.d(TAG, "思考: ${executorResult.thought.take(80)}...")
                Log.d(TAG, "置信度: ${executorResult.confidence}")
                Log.d(TAG, "描述: ${executorResult.description}")

                infoPool.lastExecutorConfidence = executorResult.confidence
                infoPool.lastActionThought = executorResult.thought
                infoPool.lastSummary = executorResult.description

                val actions = executorResult.actionBatch.actions
                if (actions.isEmpty() || actions.first().type == "invalid") {
                    Log.w(TAG, "动作解析失败")
                    infoPool.actionHistory.add(AgentAction(type = "invalid"))
                    infoPool.summaryHistory.add(executorResult.description)
                    infoPool.actionOutcomes.add("C")
                    infoPool.errorDescriptions.add("Invalid action format")
                    continue
                }

                // === 4. 执行动作 ===
                for (action in actions) {
                    Log.d(TAG, "执行动作: ${action.type}")

                    // 特殊处理: answer 动作（回答问题）
                    if (action.type == "answer") {
                        Log.d(TAG, "回答: ${action.text}")
                        executedActions.add(PhoneCallAction(
                            type = "answer",
                            description = action.text ?: "回答问题",
                            timestamp = System.currentTimeMillis(),
                            text = action.text
                        ))
                        infoPool.actionHistory.add(action)
                        infoPool.summaryHistory.add("回答: ${action.text?.take(30)}")
                        infoPool.actionOutcomes.add("A")
                        infoPool.errorDescriptions.add("None")
                        return@withContext executedActions
                    }

                    // 特殊处理: terminate 动作
                    if (action.type == "terminate") {
                        Log.d(TAG, "任务结束: ${action.status}")
                        executedActions.add(PhoneCallAction(
                            type = "terminate",
                            description = "任务${if (action.status == "success") "完成" else "失败"}",
                            timestamp = System.currentTimeMillis()
                        ))
                        return@withContext executedActions
                    }

                    // 截图（动作前）
                    val beforeScreenshot = currentScreenshot

                    // 播放 TTS 告诉用户正在做什么
                    val tellUserText = if (!action.tellUser.isNullOrEmpty()) {
                        action.tellUser
                    } else {
                        "正在${executorResult.description.take(20)}"
                    }
                    Log.d(TAG, "播报: $tellUserText")
                    onActionSpeak?.invoke(tellUserText)

                    // 执行动作
                    executeAgentAction(action, infoPool)
                    infoPool.lastAction = action

                    // 记录到返回结果
                    executedActions.add(convertToPhoneCallAction(action, executorResult.description))

                    // 等待动作生效
                    delay(if (step == 0) 2000 else 500)

                    // === 5. 截图（动作后）===
                    // 截图前隐藏悬浮窗
                    Log.d(TAG, "动作后截图前隐藏悬浮窗")
                    safeHideFloatingWindow()
                    delay(100) // 等待悬浮窗完全隐藏

                    val afterScreenshotResult = controller.screenshotWithFallback()
                    val afterScreenshot = afterScreenshotResult.bitmap
                    if (afterScreenshotResult.isFallback) {
                        Log.w(TAG, "动作后截图失败，使用黑屏占位图")
                    }

                    // 截图后恢复悬浮窗显示
                    Log.d(TAG, "动作后截图后恢复悬浮窗")
                    safeShowFloatingWindow()

                    // === 6. Reflector 验证 ===
                    if (beforeScreenshot != null && afterScreenshot != null) {
                        val verificationResult = reflector.verify(
                            action = action,
                            executorResult = executorResult,
                            infoPool = infoPool,
                            vlmClient = vlmClient,
                            beforeScreenshot = beforeScreenshot,
                            afterScreenshot = afterScreenshot,
                            systemPrompt = MobileAgentImprove.SYSTEM_PROMPT
                        )

                        Log.d(TAG, "验证结果: ${verificationResult.outcome} (${verificationResult.method})")

                        infoPool.actionHistory.add(action)
                        infoPool.summaryHistory.add(executorResult.description)
                        infoPool.actionOutcomes.add(verificationResult.outcome)
                        infoPool.errorDescriptions.add(verificationResult.errorDescription)

                        if (verificationResult.verified) {
                            infoPool.reflectorCalls++
                            infoPool.consecutiveReflectorSkips = 0
                        } else {
                            infoPool.reflectorSkips++
                            infoPool.consecutiveReflectorSkips++
                            infoPool.assumedSuccessCount++
                        }
                    } else {
                        // 无法验证，假设成功
                        infoPool.actionHistory.add(action)
                        infoPool.summaryHistory.add(executorResult.description)
                        infoPool.actionOutcomes.add("D")
                        infoPool.errorDescriptions.add("No screenshot for verification")
                        infoPool.reflectorSkips++
                        infoPool.consecutiveReflectorSkips++
                    }

                    // 更新当前截图
                    currentScreenshot = afterScreenshot
                }
            }

            Log.d(TAG, "操作循环结束，共执行 ${executedActions.size} 个动作")
            executedActions
        }

        /**
         * 执行 Agent 动作（参考 MobileAgentImprove.executeAction）
         */
        private suspend fun executeAgentAction(
            action: AgentAction,
            infoPool: InfoPoolImprove
        ) = withContext(Dispatchers.IO) {
            val (screenWidth, screenHeight) = controller.getScreenSize()

            when (action.type) {
                "click" -> {
                    val (x, y) = mapClickCoordinates(action, screenWidth, screenHeight)
                    Log.d(TAG, "点击: ($x, $y)")
                    controller.tap(x, y)
                }
                "double_tap" -> {
                    val (x, y) = mapClickCoordinates(action, screenWidth, screenHeight)
                    Log.d(TAG, "双击: ($x, $y)")
                    controller.doubleTap(x, y)
                }
                "long_press" -> {
                    val (x, y) = mapClickCoordinates(action, screenWidth, screenHeight)
                    Log.d(TAG, "长按: ($x, $y)")
                    controller.longPress(x, y)
                }
                "swipe" -> {
                    val velocity = com.alian.assistant.App.getInstance().settingsManager.settings.value.swipeVelocity
                    if (action.direction != null) {
                        val centerX = action.x?.let { mapCoordinate(it, screenWidth) } ?: (screenWidth / 2)
                        val centerY = action.y?.let { mapCoordinate(it, screenHeight) } ?: (screenHeight / 2)
                        val distance = 400
                        Log.d(TAG, "方向滑动: ${action.direction}, 中心: ($centerX, $centerY), 力度: $velocity")
                        when (action.direction.lowercase()) {
                            "up" -> controller.swipe(centerX, centerY + distance, centerX, centerY - distance, velocity = velocity)
                            "down" -> controller.swipe(centerX, centerY - distance, centerX, centerY + distance, velocity = velocity)
                            "left" -> controller.swipe(centerX + distance, centerY, centerX - distance, centerY, velocity = velocity)
                            "right" -> controller.swipe(centerX - distance, centerY, centerX + distance, centerY, velocity = velocity)
                            else -> Log.w(TAG, "未知滑动方向: ${action.direction}")
                        }
                    } else {
                        val x1 = mapCoordinate(action.x ?: 0, screenWidth)
                        val y1 = mapCoordinate(action.y ?: 0, screenHeight)
                        val x2 = mapCoordinate(action.x2 ?: 0, screenWidth)
                        val y2 = mapCoordinate(action.y2 ?: 0, screenHeight)
                        Log.d(TAG, "坐标滑动: ($x1,$y1) -> ($x2,$y2), 力度: $velocity")
                        controller.swipe(x1, y1, x2, y2, velocity = velocity)
                    }
                }
                "type" -> {
                    Log.d(TAG, "输入文本: ${action.text}")
                    action.text?.let { controller.type(it) }
                }
                "system_button" -> {
                    Log.d(TAG, "系统按键: ${action.button}")
                    when (action.button) {
                        "Back", "back" -> controller.back()
                        "Home", "home" -> controller.home()
                        "Enter", "enter" -> controller.enter()
                        else -> Log.w(TAG, "未知系统按钮: ${action.button}")
                    }
                }
                "open_app" -> {
                    val appName = action.text ?: ""
                    Log.d(TAG, "打开应用: $appName")
                    controller.openApp(appName)
                    delay(1500)
                }
                "wait" -> {
                    val duration = (action.duration ?: 3).coerceIn(1, 10)
                    Log.d(TAG, "等待 ${duration} 秒")
                    delay(duration * 1000L)
                }
                else -> {
                    Log.w(TAG, "未知动作类型: ${action.type}")
                }
            }
        }

        /**
         * 坐标映射 - 智能判断坐标类型并映射
         * 归一化坐标(0-999) → 映射到实际屏幕尺寸
         * 像素坐标(>=1000) → 直接使用（限制在屏幕范围内）
         */
        private fun mapCoordinate(value: Int, screenMax: Int): Int {
            if (value <= 0) return 0
            return if (value < 1000) {
                (value * screenMax / 999)
            } else {
                value.coerceAtMost(screenMax)
            }
        }

        /**
         * 计算点击坐标
         */
        private fun mapClickCoordinates(
            action: AgentAction,
            screenWidth: Int,
            screenHeight: Int
        ): Pair<Int, Int> {
            val x = mapCoordinate(action.x ?: 0, screenWidth)
            val y = mapCoordinate(action.y ?: 0, screenHeight)
            return Pair(x, y)
        }

        /**
         * 将 AgentAction 转换为 PhoneCallAction
         */
        private fun convertToPhoneCallAction(action: AgentAction, description: String): PhoneCallAction {
            return PhoneCallAction(
                type = action.type,
                description = action.tellUser ?: description,
                timestamp = System.currentTimeMillis(),
                x = action.x,
                y = action.y,
                x1 = action.x,
                y1 = action.y,
                x2 = action.x2,
                y2 = action.y2,
                text = action.text
            )
        }

        /**
         * 生成对话回应
         */
        private suspend fun generateResponse(
            userText: String,
            intent: IntentResult,
            decision: Decision,
            currentScreen: Bitmap?,
            conversationHistory: List<PhoneCallMessage>
        ): String {
            Log.d(TAG, "生成对话回应")

            // 构建对话 Prompt
            val prompt = buildResponsePrompt(userText, intent, decision, conversationHistory)
            val images = if (currentScreen != null && intent.needsScreenshot) listOf(currentScreen) else emptyList()

            val result = vlmClient.predict(prompt, images)
            return if (result.isSuccess) {
                result.getOrNull() ?: "好的，我明白了。"
            } else {
                "好的，我明白了。"
            }
        }

        /**
         * 构建回应 Prompt
         */
        private fun buildResponsePrompt(
            userText: String,
            intent: IntentResult,
            decision: Decision,
            conversationHistory: List<PhoneCallMessage>
        ): String {
            val historyText = if (conversationHistory.isNotEmpty()) {
                conversationHistory.takeLast(3).joinToString("\n") { msg ->
                    "${if (msg.isUser) "用户" else "AI"}: ${msg.content}"
                }
            } else {
                "（无对话历史）"
            }

            val actionText = if (decision.shouldOperate) {
                "我将执行以下操作：${decision.operationDescription}"
            } else {
                ""
            }

            return """
你叫艾莲，是一个温柔体贴的 AI 助手。

对话历史：
$historyText

用户输入：$userText

$actionText

请用简洁、友好的语气回应用户。回应控制在 1-3 句话之间。
如果是问答，直接回答问题。
如果是操作，先说明你要做什么，然后执行操作。
""".trimIndent()
        }

        /**
         * 判断是否需要调用 Manager
         */
        private fun shouldCallManager(infoPool: InfoPoolImprove, currentStep: Int): Boolean {
            // 初始规划：第一次必须调用
            if (currentStep == 0) {
                infoPool.managerMode = ManagerMode.INITIAL
                return true
            }

            // 强制更新
            if (infoPool.forceManagerUpdate) {
                infoPool.managerMode = ManagerMode.REPLANNING
                infoPool.forceManagerUpdate = false
                return true
            }

            // 错误升级：连续失败时必须调用
            if (infoPool.errorFlagPlan) {
                infoPool.managerMode = ManagerMode.RECOVERY
                return true
            }

            // 子目标完成后需要更新计划
            if (isSubgoalCompleted(infoPool)) {
                infoPool.managerMode = ManagerMode.NORMAL
                return true
            }

            // 长时间未更新
            val stepsSinceLastUpdate = currentStep - infoPool.lastPlanUpdateStep
            if (stepsSinceLastUpdate >= MAX_STEPS_WITHOUT_MANAGER_UPDATE) {
                infoPool.managerMode = ManagerMode.NORMAL
                return true
            }

            return false
        }

        /**
         * 判断子目标是否完成
         */
        private fun isSubgoalCompleted(infoPool: InfoPoolImprove): Boolean {
            val recentSuccessfulActions = infoPool.actionOutcomes.takeLast(3)
                .count { it == "A" || it == "D" }

            if (recentSuccessfulActions >= MIN_SUCCESSFUL_ACTIONS_FOR_SUBGOAL) {
                infoPool.currentSubgoalIndex++
                Log.d(TAG, "子目标 ${infoPool.currentSubgoalIndex} 完成")
                return true
            }
            return false
        }

        /**
         * 检查错误升级
         */
        private fun checkErrorEscalation(infoPool: InfoPoolImprove) {
            infoPool.errorFlagPlan = false
            val thresh = infoPool.errToManagerThresh

            if (infoPool.actionOutcomes.size >= thresh) {
                val recentOutcomes = infoPool.actionOutcomes.takeLast(thresh)
                val failCount = recentOutcomes.count { it in listOf("B", "C") }
                if (failCount == thresh) {
                    infoPool.errorFlagPlan = true
                    Log.w(TAG, "连续 $thresh 次失败，触发错误恢复")
                }
            }
        }
    }
}

/**
 * 意图类型
 */
enum class IntentType {
    QA,       // 纯问答，不需要操作
    OPERATE,  // 需要操作手机
    MIXED     // 混合类型，先对话后操作
}

/**
 * 操作类型
 */
enum class OperationType {
    OPEN_APP,      // 打开应用
    CLICK,         // 点击
    TYPE,          // 输入
    SWIPE,         // 滑动
    SEARCH,        // 搜索
    NAVIGATE,      // 导航
    CUSTOM         // 自定义操作
}

/**
 * 意图解析结果
 */
data class IntentResult(
    val type: IntentType,
    val confidence: Float,
    val needsScreenshot: Boolean,
    val extractedTask: String?
)

/**
 * 决策结果
 */
data class Decision(
    val shouldOperate: Boolean,
    val operationType: OperationType?,
    val operationDescription: String?,
    val estimatedSteps: Int
)

/**
 * 手机通话操作（PhoneCallAgent 的返回动作类型）
 * 与 com.alian.assistant.core.agent.memory.Action 区分
 */
data class PhoneCallAction(
    val type: String,
    val description: String,
    val timestamp: Long,
    val x: Int? = null,
    val y: Int? = null,
    val x1: Int? = null,
    val y1: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val text: String? = null
)

/**
 * 协调执行结果
 */
data class OrchestratorResult(
    val response: String,
    val actions: List<PhoneCallAction>,
    val finalState: String
)

/**
 * 手机通话消息
 */
data class PhoneCallMessage(
    val id: String = "",
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val actions: List<PhoneCallAction>? = null
)

/**
 * PhoneCallAgent 处理结果
 */
data class PhoneCallAgentResult(
    val response: String,
    val actions: List<PhoneCallAction>,
    val finalState: String,
    val intentType: IntentType,
    val shouldOperate: Boolean,
    val error: String? = null
)
