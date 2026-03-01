package com.alian.assistant.core.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.alian.assistant.App
import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.core.agent.components.Executor
import com.alian.assistant.core.agent.memory.InfoPool
import com.alian.assistant.core.agent.components.Manager
import com.alian.assistant.core.agent.components.Notetaker
import com.alian.assistant.data.model.ChatSession
import com.alian.assistant.core.agent.components.ActionReflector
import com.alian.assistant.core.agent.components.ReflectorResult
import com.alian.assistant.data.ExecutionStep
import com.alian.assistant.core.skills.SkillManager
import com.alian.assistant.infrastructure.device.AppScanner
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.presentation.ui.overlay.OverlayService
import com.alian.assistant.infrastructure.ai.llm.GUIOwlClient
import com.alian.assistant.infrastructure.ai.llm.MAIUIAction
import com.alian.assistant.infrastructure.ai.llm.MAIUIClient
import com.alian.assistant.infrastructure.ai.llm.VLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Mobile Agent 主循环 - 移植自 MobileAgent-v3
 *
 * 新增 Skill 层支持：
 * - 快速路径：高置信度 delegation Skill 直接执行
 * - 增强模式：GUI 自动化 Skill 提供上下文指导
 *
 * 支持三种模式：
 * - OpenAI 兼容模式：使用 VLMClient (Manager → Executor → Reflector)
 * - GUI-Owl 模式：使用 GUIOwlClient (直接返回操作指令)
 * - MAI-UI 模式：使用 MAIUIClient (专用 prompt 和对话历史)
 */
class MobileAgent(
    private val vlmClient: VLMClient?,
    private val controller: IDeviceController,
    private val context: Context,
    private val guiOwlClient: GUIOwlClient? = null,  // GUI-Owl 专用客户端
    private val maiuiClient: MAIUIClient? = null     // MAI-UI 专用客户端
) : Agent {
    companion object {
        private const val TAG = "MobileAgent"
    }

    // 是否使用 GUI-Owl 模式
    private val useGUIOwlMode: Boolean = guiOwlClient != null
    // 是否使用 MAI-UI 模式
    private val useMAIUIMode: Boolean = maiuiClient != null
    // App 扫描器 (使用 App 单例中的实例)
    private val appScanner: AppScanner = App.getInstance().appScanner
    private val manager = Manager()
    private val executor = Executor()
    private val reflector = ActionReflector()
    private val notetaker = Notetaker()

    // Skill 管理器
    private val skillManager: SkillManager? = try {
        SkillManager.getInstance().also {
            Log.d(TAG, "SkillManager 已加载，共 ${it.getAllSkills().size} 个 Skills")
            // 设置 VLM 客户端用于意图匹配（仅在 OpenAI 兼容模式下）
            vlmClient?.let { client -> it.setVLMClient(client) }
        }
    } catch (e: Exception) {
        Log.e(TAG, "SkillManager 加载失败: ${e.message}")
        null
    }

    // 状态流
    private val _state = MutableStateFlow(AgentState())
    override val state: StateFlow<AgentState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs

    // 对话会话（用于语音打断功能）
    private var chatSession: ChatSession? = null

    /**
     * 执行指令
     * @param enableBatchExecution 是否启用批量执行模式
     */
    override suspend fun runInstruction(
        instruction: String,
        maxSteps: Int,
        useNotetaker: Boolean,
        enableBatchExecution: Boolean
    ): AgentResult {
        log("开始执行: $instruction")

        // 根据模式选择执行路径
        if (useGUIOwlMode && guiOwlClient != null) {
            log("使用 GUI-Owl 模式")
            return runInstructionWithGUIOwl(instruction, maxSteps)
        }

        if (useMAIUIMode && maiuiClient != null) {
            log("使用 MAI-UI 模式")
            return runInstructionWithMAIUI(instruction, maxSteps)
        }

        // OpenAI 兼容模式需要 VLMClient
        if (vlmClient == null) {
            log("错误: VLMClient 未初始化")
            return AgentResult(success = false, message = "VLMClient 未初始化")
        }

        log("使用 OpenAI 兼容模式")

        // 使用 LLM 匹配 Skill，生成上下文信息给 Agent（不执行任何操作）
        log("正在分析意图...")
        val skillContext = skillManager?.generateAgentContextWithLLM(instruction)

        val infoPool = InfoPool(instruction = instruction)

        // 初始化 Executor 的对话记忆
        // 注释掉：现在 getPrompt 已经包含了历史信息，不需要 ConversationMemory
        // val executorSystemPrompt = buildString {
        //     append("You are an agent who can operate an Android phone. ")
        //     append("Decide the next action based on the current state.\n\n")
        //     append("User Request: $instruction\n")
        // }
        // infoPool.executorMemory = ConversationMemory.withSystemPrompt(executorSystemPrompt)
        // log("已初始化对话记忆")

        // 如果有 Skill 上下文，添加到 InfoPool，让 Manager 知道可用的工具
        if (!skillContext.isNullOrEmpty() && skillContext != "未找到相关技能或可用应用，请使用通用 GUI 自动化完成任务。") {
            infoPool.skillContext = skillContext
            log("已匹配到可用技能:\n$skillContext")
        } else {
            log("未匹配到特定技能，使用通用 GUI 自动化")
        }

        // 获取屏幕尺寸
        val (width, height) = controller.getScreenSize()
        infoPool.screenWidth = width
        infoPool.screenHeight = height
        log("屏幕尺寸: ${width}x${height}")

        // 获取已安装应用列表（格式：应用名 (包名)）
        val apps = appScanner.getApps()
            .filter { !it.isSystem }
            .map { "${it.appName} (${it.packageName})" }
        infoPool.installedApps = apps.joinToString(", ")
        log("已加载 ${apps.size} 个应用")

        // 显示悬浮窗 (带停止按钮)
        OverlayService.show(context, "开始执行...") {
            // 停止回调 - 设置状态为停止
            // 注意：协程取消需要在 MainActivity 中处理
            updateState { copy(isRunning = false) }
            // 调用 stop() 方法确保清理
            stop()
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                // 检查协程是否被取消
                coroutineContext.ensureActive()

                // 检查是否被用户停止
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} ==========")
                OverlayService.updateStep("Step ${step + 1}/$maxSteps - 正在分析当前屏幕...", "")

                // 1. 截图 (先隐藏悬浮窗避免被识别)
                log("截图中...")
                OverlayService.setVisible(false)
                delay(100) // 等待悬浮窗隐藏
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                var screenshot = screenshotResult.bitmap

                // 处理敏感页面（截图被系统阻止）
                if (screenshotResult.isSensitive) {
                    log("⚠️ 检测到敏感页面（截图被阻止），请求人工接管")
                    val confirmed = withContext(Dispatchers.Main) {
                        waitForUserConfirm("检测到敏感页面，是否继续执行？")
                    }
                    if (!confirmed) {
                        log("用户取消，任务终止")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "敏感页面，用户取消")
                    }
                    log("用户确认继续（使用黑屏占位图）")
                } else if (screenshotResult.isFallback) {
                    log("⚠️ 截图失败，使用黑屏占位图继续")
                }

                // 再次检查停止状态（截图后）
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                // 2. 检查错误升级
                checkErrorEscalation(infoPool)

                // 3. 跳过 Manager 的情况
                val skipManager = !infoPool.errorFlagPlan &&
                        infoPool.actionHistory.isNotEmpty() &&
                        infoPool.actionHistory.last().type == "invalid"

                // 4. Manager 规划
                if (!skipManager) {
                    log("Manager 规划中...")

                    // 检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }

                    val planPrompt = manager.getPrompt(infoPool)
                    log("Manager 提示词: $planPrompt")
                    log("Manager 估算 token: ${planPrompt.length / 3}")
                    val planResponse = vlmClient.predict(planPrompt, listOf(screenshot))
                    log("Manager 响应: $planResponse")

                    // VLM 调用后检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }

                    if (planResponse.isFailure) {
                        log("Manager 调用失败: ${planResponse.exceptionOrNull()?.message}")
                        continue
                    }

                    val planResult = manager.parseResponse(planResponse.getOrThrow())
                    infoPool.completedPlan = planResult.completedSubgoal
                    infoPool.plan = planResult.plan

                    log("计划: ${planResult.plan.take(100)}...")

                    // 播放 Manager 的 tellUser
                    if (planResult.tellUser.isNotEmpty()) {
                        OverlayService.updateStep("Step ${step + 1}/$maxSteps", planResult.tellUser)
                    }

                    // 检查是否遇到敏感页面
                    if (planResult.plan.contains("STOP_SENSITIVE")) {
                        log("检测到敏感页面（支付/密码等），已停止执行")
                        OverlayService.update("敏感页面，已停止")
                        delay(2000)
                        OverlayService.hide(context)
                        updateState { copy(isRunning = false, isCompleted = false) }
                        bringAppToFront()
                        return AgentResult(success = false, message = "检测到敏感页面（支付/密码），已安全停止")
                    }

                    // 检查是否完成
                    if (planResult.plan.contains("Finished") && planResult.plan.length < 20) {
                        log("任务完成!")
                        OverlayService.update("完成!")
                        delay(1500)
                        OverlayService.hideAfterTTS(context)
                        updateState { copy(isRunning = false, isCompleted = true) }
                        bringAppToFront()
                        return AgentResult(success = true, message = "任务完成")
                    }
                }

                // 5. Executor 决定动作 (使用上下文记忆)
                log("Executor 决策中...")

                // 检查停止状态
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                val actionPrompt = executor.getPrompt(infoPool, enableBatchExecution)
                log("Executor 提示词: $actionPrompt")
                // 注释掉：现在 getPrompt 已经包含了历史信息，不需要 ConversationMemory
                // // 使用上下文记忆调用 VLM
                // val memory = infoPool.executorMemory
                // val actionResponse = if (memory != null) {
                //     // 添加用户消息（带截图）
                //     memory.addUserMessage(actionPrompt, screenshot)
                //     log("记忆消息数: ${memory.size()}, 估算 token: ${memory.estimateTokens()}")
                //
                //     // 调用 VLM
                //     val response = vlmClient.predictWithContext(memory.toMessagesJson())
                //
                //     // 删除图片节省 token
                //     memory.stripLastUserImage()
                //
                //     response
                // } else {
                //     // 降级：使用普通方式
                //     vlmClient.predict(actionPrompt, listOf(screenshot))
                // }
                log("Executor 估算 token: ${actionPrompt.length / 3}")
                // 直接调用 VLM
                val actionResponse = vlmClient.predict(actionPrompt, listOf(screenshot))
                // VLM 调用后检查停止状态
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                if (actionResponse.isFailure) {
                    log("Executor 调用失败: ${actionResponse.exceptionOrNull()?.message}")
                    continue
                }

                val responseText = actionResponse.getOrThrow()
                val executorResult = executor.parseResponse(responseText, enableBatchExecution)

                log("思考: ${executorResult.thought.take(80)}...")
                log("动作: ${executorResult.actionStr}")
                log("描述: ${executorResult.description}")
                // // 将助手响应添加到记忆
                // memory?.addAssistantMessage(responseText)
                val actionBatch = executorResult.actionBatch
                val actions = actionBatch.actions

                log("批量执行 ${actions.size} 个动作")

                infoPool.lastActionThought = executorResult.thought
                infoPool.lastSummary = executorResult.description

                if (actions.isEmpty()) {
                    log("动作解析失败")
                    infoPool.actionHistory.add(Action(type = "invalid"))
                    infoPool.summaryHistory.add(executorResult.description)
                    infoPool.actionOutcomes.add("C")
                    infoPool.errorDescriptions.add("Invalid action format")
                    continue
                }

                // 循环处理 actionBatch 中的所有 actions
                for ((actionIndex, action) in actions.withIndex()) {
                    log("\n--- 执行动作 ${actionIndex + 1}/${actions.size} ---")

                    // 特殊处理: answer 动作
                    if (action.type == "answer") {
                        log("回答: ${action.text}")
                        OverlayService.update("${action.text?.take(20)}...")
                        // 播放回答的 TTS
                        if (action.text != null && action.text.isNotEmpty()) {
                            log("播放回答 TTS: ${action.text}")
                            suspendCancellableCoroutine<Unit> { continuation ->
                                OverlayService.playTTSWithCallback(action.text) {
                                    continuation.resume(Unit)
                                }
                            }
                        }
                        OverlayService.hide(context)
                        updateState { copy(isRunning = false, isCompleted = true, answer = action.text) }
                        bringAppToFront()
                        return AgentResult(success = true, message = "回答: ${action.text}")
                    }

                    // 特殊处理: terminate 动作 (MAI-UI)
                    if (action.type == "terminate") {
                        val success = action.status == "success"
                        log("任务${if (success) "完成" else "失败"}")
                        OverlayService.update(if (success) "完成!" else "失败")
                        delay(1500)
                        OverlayService.hideAfterTTS(context)
                        updateState { copy(isRunning = false, isCompleted = success) }
                        bringAppToFront()
                        return AgentResult(success = success, message = if (success) "任务完成" else "任务失败")
                    }

                    // 6. 敏感操作确认
                    if (action.needConfirm || action.message != null && action.type in listOf("click", "double_tap", "long_press")) {
                        val confirmMessage = action.message ?: "确认执行此操作？"
                        log("⚠️ 敏感操作: $confirmMessage")

                        val confirmed = withContext(Dispatchers.Main) {
                            waitForUserConfirm(confirmMessage)
                        }

                        if (!confirmed) {
                            log("❌ 用户取消操作")
                            infoPool.actionHistory.add(action)
                            val actionDesc = if (!action.tellUser.isNullOrEmpty()) action.tellUser else actionBatch.description
                            infoPool.summaryHistory.add("用户取消: $actionDesc")
                            infoPool.actionOutcomes.add("C")
                            infoPool.errorDescriptions.add("User cancelled")
                            break // 用户取消后，跳过后续动作
                        }
                        log("✅ 用户确认，继续执行")
                    }

                    // 7. 执行动作
                    log("执行动作: ${action.type}")
                    val actionDesc = if (!action.tellUser.isNullOrEmpty()) action.tellUser else actionBatch.description
                    val tellUserText = if (!action.tellUser.isNullOrEmpty()) action.tellUser else "正在帮你${actionBatch.description.take(30)}"
                    OverlayService.updateStep("Step ${step + 1}/$maxSteps - 动作 ${actionIndex + 1}/${actions.size}", tellUserText)
                    executeAction(action, infoPool)
                    infoPool.lastAction = action

                    // 立即记录执行步骤（outcome 暂时为 "?" 表示进行中）
                    val currentStepIndex = _state.value.executionSteps.size
                    val stepStartTime = System.currentTimeMillis()
                    val executionStep = ExecutionStep(
                        stepNumber = step + 1,
                        timestamp = stepStartTime,
                        action = action.type,
                        description = actionDesc,
                        thought = executorResult.thought,
                        outcome = "?", // 进行中
                        icon = getActionIcon(action.type),
                        actionText = action.text,
                        actionButton = action.button,
                        actionDuration = action.duration,
                        actionMessage = action.message
                    )
                    updateState { copy(executionSteps = executionSteps + executionStep) }

                    // 等待动作生效
                    delay(if (step == 0 && actionIndex == 0) 5000 else 200)

                    // 检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }

                    // 8. 截图 (动作后，隐藏悬浮窗)
                    OverlayService.setVisible(false)
                    delay(100)
                    val afterScreenshotResult = controller.screenshotWithFallback()
                    OverlayService.setVisible(true)
                    val afterScreenshot = afterScreenshotResult.bitmap
                    if (afterScreenshotResult.isFallback) {
                        log("动作后截图失败，使用黑屏占位图")
                    }

                    // 9. Reflector 反思
                    log("Reflector 反思中...")

                    // 检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }

                    val reflectPrompt = reflector.getPrompt(infoPool)
                    log("Reflector 提示词: $reflectPrompt")
                    log("Reflector 估算 token: ${reflectPrompt.length / 3}")
                    val reflectResponse = vlmClient.predict(reflectPrompt, listOf(screenshot, afterScreenshot))
                    log("Reflector 响应: $reflectResponse")
                    val reflectResult = if (reflectResponse.isSuccess) {
                        reflector.parseResponse(reflectResponse.getOrThrow())
                    } else {
                        ReflectorResult("C", "Failed to call reflector")
                    }

                    log("结果: ${reflectResult.outcome} - ${reflectResult.errorDescription.take(50)}")

                    // 更新历史
                    infoPool.actionHistory.add(action)
                    infoPool.summaryHistory.add(actionDesc)
                    infoPool.actionOutcomes.add(reflectResult.outcome)
                    infoPool.errorDescriptions.add(reflectResult.errorDescription)
                    infoPool.progressStatus = infoPool.completedPlan

                    // 更新执行步骤的 outcome（之前添加的步骤 outcome 是 "?"）
                    val stepEndTime = System.currentTimeMillis()
                    val stepDuration = stepEndTime - stepStartTime
                    updateState {
                        val updatedSteps = executionSteps.toMutableList()
                        if (currentStepIndex < updatedSteps.size) {
                            updatedSteps[currentStepIndex] = updatedSteps[currentStepIndex].copy(
                                outcome = reflectResult.outcome,
                                duration = stepDuration
                            )
                        }
                        copy(executionSteps = updatedSteps)
                    }

                    // 10. Notetaker (可选)
                    if (useNotetaker && reflectResult.outcome == "A" && action.type != "answer") {
                        log("Notetaker 记录中...")

                        // 检查停止状态
                        if (!_state.value.isRunning) {
                            log("用户停止执行")
                            OverlayService.hide(context)
                            bringAppToFront()
                            return AgentResult(success = false, message = "用户停止")
                        }

                        val notePrompt = notetaker.getPrompt(infoPool)
                        val noteResponse = vlmClient.predict(notePrompt, listOf(afterScreenshot))
                        if (noteResponse.isSuccess) {
                            infoPool.importantNotes = notetaker.parseResponse(noteResponse.getOrThrow())
                        }
                    }

                    // 更新 screenshot 为 afterScreenshot，供下一个 action 使用
                    screenshot = afterScreenshot
                }
            }
        } catch (e: CancellationException) {
            log("任务被取消")
            OverlayService.hide(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.hideAfterTTS(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * GUI-Owl 模式执行指令
     * 简化流程：截图 → GUI-Owl API → 解析操作 → 执行
     */
    private suspend fun runInstructionWithGUIOwl(
        instruction: String,
        maxSteps: Int
    ): AgentResult {
        val client = guiOwlClient ?: return AgentResult(false, "GUIOwlClient 未初始化")

        // 重置会话
        client.resetSession()
        log("GUI-Owl 会话已重置")

        // 获取屏幕尺寸
        val (screenWidth, screenHeight) = controller.getScreenSize()
        log("屏幕尺寸: ${screenWidth}x${screenHeight}")

        // 显示悬浮窗
        OverlayService.show(context, "GUI-Owl 模式") {
            updateState { copy(isRunning = false) }
            stop()
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                coroutineContext.ensureActive()

                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} (GUI-Owl) ==========")
                OverlayService.updateStep("Step ${step + 1}/$maxSteps - 正在分析屏幕...", "")

                // 1. 截图
                log("截图中...")
                OverlayService.setVisible(false)
                delay(100)
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val screenshot = screenshotResult.bitmap

                if (screenshotResult.isSensitive) {
                    log("⚠️ 检测到敏感页面")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "敏感页面，已停止")
                }

                // 2. 调用 GUI-Owl API
                log("调用 GUI-Owl API...")
                val response = client.predict(instruction, screenshot)

                if (response.isFailure) {
                    log("GUI-Owl 调用失败: ${response.exceptionOrNull()?.message}")
                    continue
                }

                val result = response.getOrThrow()
                log("思考: ${result.thought.take(100)}...")
                log("操作: ${result.operation}")
                log("说明: ${result.explanation}")

                // 3. 解析操作指令
                val parsedAction = client.parseOperation(result.operation)
                if (parsedAction == null) {
                    log("无法解析操作: ${result.operation}")
                    continue
                }

                // 记录执行步骤
                val guiOwlStepStartTime = System.currentTimeMillis()
                val currentStepIndex = _state.value.executionSteps.size
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = guiOwlStepStartTime,
                    action = parsedAction.type,
                    description = result.explanation,
                    thought = result.thought,
                    outcome = "?",
                    icon = getActionIcon(parsedAction.type),
                    actionText = parsedAction.text,
                    actionButton = if (parsedAction.type == "system_button") parsedAction.text else null,
                    actionDuration = null,
                    actionMessage = null
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // 检查是否完成
                if (parsedAction.type == "finish") {
                    log("任务完成!")
                    OverlayService.update("完成!")
                    delay(1500)
                    OverlayService.hideAfterTTS(context)
                    updateState { copy(isRunning = false, isCompleted = true) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "任务完成")
                }

                // 4. 执行动作
                log("执行动作: ${parsedAction.type}")
                val explanation = result.explanation.take(60)
                OverlayService.updateStep("Step ${step + 1}/$maxSteps", "正在帮你$explanation")
                executeGUIOwlAction(parsedAction, screenWidth, screenHeight)

                // 更新步骤状态
                val guiOwlStepEndTime = System.currentTimeMillis()
                val guiOwlStepDuration = guiOwlStepEndTime - guiOwlStepStartTime
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (currentStepIndex < updatedSteps.size) {
                        updatedSteps[currentStepIndex] = updatedSteps[currentStepIndex].copy(
                            outcome = "A",
                            duration = guiOwlStepDuration
                        )
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 等待动作生效
                delay(if (step == 0) 3000 else 1500)
            }
        } catch (e: CancellationException) {
            log("任务被取消")
            OverlayService.hide(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.hideAfterTTS(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * 执行 GUI-Owl 解析的动作
     */
    private suspend fun executeGUIOwlAction(
        action: GUIOwlClient.ParsedAction,
        screenWidth: Int,
        screenHeight: Int
    ) = withContext(Dispatchers.IO) {
        when (action.type) {
            "click" -> {
                val x = action.x ?: return@withContext
                val y = action.y ?: return@withContext
                log("点击: ($x, $y)")
                executeWithOverlayProtection(action.type, x, y) {
                    controller.tap(x, y)
                }
            }
            "swipe" -> {
                val x1 = action.x ?: return@withContext
                val y1 = action.y ?: return@withContext
                val x2 = action.x2 ?: return@withContext
                val y2 = action.y2 ?: return@withContext
                val velocity = com.alian.assistant.App.getInstance().settingsManager.settings.value.swipeVelocity

                log("滑动: ($x1, $y1) -> ($x2, $y2), 力度: $velocity")
                executeWithOverlayProtection(action.type, x1, y1, x2, y2) {
                    controller.swipe(x1, y1, x2, y2, velocity = velocity)
                }
            }
            "long_press" -> {
                val x = action.x ?: return@withContext
                val y = action.y ?: return@withContext
                log("长按: ($x, $y)")
                executeWithOverlayProtection(action.type, x, y) {
                    controller.longPress(x, y)
                }
            }
            "type" -> {
                val text = action.text ?: return@withContext
                log("输入: $text")
                controller.type(text)
            }
            "scroll" -> {
                val direction = action.text ?: "down"
                val centerX = screenWidth / 2
                val centerY = screenHeight / 2
                val velocity = com.alian.assistant.App.getInstance().settingsManager.settings.value.swipeVelocity

                log("滚动: $direction, 力度: $velocity")
                when (direction) {
                    "up" -> executeWithOverlayProtection(action.type, centerX, centerY + 300, centerX, centerY - 300) {
                        controller.swipe(centerX, centerY + 300, centerX, centerY - 300, velocity = velocity)
                    }
                    "down" -> executeWithOverlayProtection(action.type, centerX, centerY - 300, centerX, centerY + 300) {
                        controller.swipe(centerX, centerY - 300, centerX, centerY + 300, velocity = velocity)
                    }
                    "left" -> executeWithOverlayProtection(action.type, centerX + 300, centerY, centerX - 300, centerY) {
                        controller.swipe(centerX + 300, centerY, centerX - 300, centerY, velocity = velocity)
                    }
                    "right" -> executeWithOverlayProtection(action.type, centerX - 300, centerY, centerX + 300, centerY) {
                        controller.swipe(centerX - 300, centerY, centerX + 300, centerY, velocity = velocity)
                    }
                }
            }
            "system_button" -> {
                when (action.text?.lowercase()) {
                    "back" -> {
                        log("按返回键")
                        controller.back()
                    }
                    "home" -> {
                        log("按 Home 键")
                        controller.home()
                    }
                }
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
    }

    /**
     * MAI-UI 模式执行指令
     * 使用专用的 MAI-UI prompt 和对话历史管理
     */
    private suspend fun runInstructionWithMAIUI(
        instruction: String,
        maxSteps: Int
    ): AgentResult {
        val client = maiuiClient ?: return AgentResult(false, "MAIUIClient 未初始化")

        // 重置会话
        client.reset()
        log("MAI-UI 会话已重置")

        // 获取屏幕尺寸
        val (screenWidth, screenHeight) = controller.getScreenSize()
        log("屏幕尺寸: ${screenWidth}x${screenHeight}")

        // 设置可用应用列表
        val installedApps = appScanner.getApps().map { it.appName }
        client.setAvailableApps(installedApps)
        log("已加载 ${installedApps.size} 个应用")

        // 显示悬浮窗
        OverlayService.show(context, "MAI-UI 模式") {
            updateState { copy(isRunning = false) }
            stop()
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                coroutineContext.ensureActive()

                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} (MAI-UI) ==========")
                OverlayService.updateStep("Step ${step + 1}/$maxSteps - 正在分析屏幕...", "")

                // 1. 截图
                log("截图中...")
                OverlayService.setVisible(false)
                delay(100)
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val screenshot = screenshotResult.bitmap

                if (screenshotResult.isSensitive) {
                    log("⚠️ 检测到敏感页面")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "敏感页面，已停止")
                }

                // 2. 调用 MAI-UI API
                log("调用 MAI-UI API...")
                val response = client.predict(instruction, screenshot)

                if (response.isFailure) {
                    log("MAI-UI 调用失败: ${response.exceptionOrNull()?.message}")
                    continue
                }

                val result = response.getOrThrow()
                log("思考: ${result.thinking.take(150)}...")

                val action = result.action
                if (action == null) {
                    log("无法解析动作")
                    continue
                }

                log("动作: ${action.type}")

                // 记录执行步骤
                val maiuiStepStartTime = System.currentTimeMillis()
                val currentStepIndex = _state.value.executionSteps.size
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = maiuiStepStartTime,
                    action = action.type,
                    description = result.thinking.take(50),
                    thought = result.thinking,
                    outcome = "?",
                    icon = getActionIcon(action.type),
                    actionText = action.text,
                    actionButton = action.button,
                    actionDuration = null,
                    actionMessage = if (action.type == "ask_user" || action.type == "terminate") action.tellUser else null
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // 检查是否完成
                if (action.type == "terminate") {
                    val success = action.status == "success"
                    log(if (success) "任务完成!" else "任务失败")
                    OverlayService.update(if (success) "完成!" else "失败")

                    // 播放完成消息的TTS（如果有）
                    val tellUser = action.tellUser
                    if (success && tellUser != null && tellUser.isNotEmpty()) {
                        log("播放完成消息TTS: $tellUser")
                        // 使用suspendCancellableCoroutine等待TTS播放完成
                        suspendCancellableCoroutine<Unit> { continuation ->
                            OverlayService.playTTSWithCallback(tellUser) {
                                continuation.resume(Unit)
                            }
                        }
                    }

                    OverlayService.hideAfterTTS(context)
                    updateState { copy(isRunning = false, isCompleted = success) }
                    bringAppToFront()
                    return AgentResult(success = success, message = if (success) "任务完成" else "任务失败")
                }

                // 检查是否需要人工接管
                if (action.type == "ask_user") {
                    log("请求用户介入: ${action.text}")
                    OverlayService.update("请手动操作: ${action.text?.take(20)}")
                    // 等待用户操作后继续
                    delay(5000)
                    continue
                }

                // 检查是否是回答
                if (action.type == "answer") {
                    log("回答: ${action.text}")
                    OverlayService.update("答案: ${action.text?.take(30)}")
                    // 播放回答的 TTS
                    if (action.text != null && action.text.isNotEmpty()) {
                        log("播放回答 TTS: ${action.text}")
                        suspendCancellableCoroutine<Unit> { continuation ->
                            OverlayService.playTTSWithCallback(action.text) {
                                continuation.resume(Unit)
                            }
                        }
                    }
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = true) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "回答: ${action.text}")
                }

                // 3. 执行动作
                log("执行动作: ${action.type}")
                val tellUserText = if (action.tellUser != null && action.tellUser.isNotEmpty()) {
                    action.tellUser
                } else {
                    "正在帮你${action.type}"
                }
                OverlayService.updateStep("Step ${step + 1}/$maxSteps", tellUserText)
                executeMAIUIAction(action, screenWidth, screenHeight)

                // 更新步骤状态
                val maiuiStepEndTime = System.currentTimeMillis()
                val maiuiStepDuration = maiuiStepEndTime - maiuiStepStartTime
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (currentStepIndex < updatedSteps.size) {
                        updatedSteps[currentStepIndex] = updatedSteps[currentStepIndex].copy(
                            outcome = "A",
                            duration = maiuiStepDuration
                        )
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 等待动作生效
                delay(if (step == 0) 2000 else 1000)
            }
        } catch (e: CancellationException) {
            log("任务被取消")
            OverlayService.hide(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.hideAfterTTS(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * 执行 MAI-UI 解析的动作
     */
    private suspend fun executeMAIUIAction(
        action: MAIUIAction,
        screenWidth: Int,
        screenHeight: Int
    ) = withContext(Dispatchers.IO) {
        // 转换归一化坐标到屏幕像素
        val screenAction = action.toScreenCoordinates(screenWidth, screenHeight)

        when (action.type) {
            "click" -> {
                val x = screenAction.x?.toInt() ?: return@withContext
                val y = screenAction.y?.toInt() ?: return@withContext
                log("点击: ($x, $y)")
                controller.tap(x, y)
            }
            "long_press" -> {
                val x = screenAction.x?.toInt() ?: return@withContext
                val y = screenAction.y?.toInt() ?: return@withContext
                log("长按: ($x, $y)")
                controller.longPress(x, y)
            }
            "double_click" -> {
                val x = screenAction.x?.toInt() ?: return@withContext
                val y = screenAction.y?.toInt() ?: return@withContext
                log("双击: ($x, $y)")
                controller.tap(x, y)
                delay(100)
                controller.tap(x, y)
            }
            "type" -> {
                val text = action.text ?: return@withContext
                log("输入: $text")
                controller.type(text)
            }
            "swipe" -> {
                // 支持方向式滑动和坐标式滑动
                val velocity = com.alian.assistant.App.getInstance().settingsManager.settings.value.swipeVelocity

                if (action.direction != null) {
                    val centerX = screenWidth / 2
                    val centerY = screenHeight / 2
                    val distance = screenHeight / 3
                    log("滑动: ${action.direction}, 力度: $velocity")
                    when (action.direction.lowercase()) {
                        "up" -> controller.swipe(centerX, centerY + distance, centerX, centerY - distance, velocity = velocity)
                        "down" -> controller.swipe(centerX, centerY - distance, centerX, centerY + distance, velocity = velocity)
                        "left" -> controller.swipe(centerX + distance, centerY, centerX - distance, centerY, velocity = velocity)
                        "right" -> controller.swipe(centerX - distance, centerY, centerX + distance, centerY, velocity = velocity)
                    }
                } else if (screenAction.x != null && screenAction.y != null) {
                    // 从指定位置滑动
                    val startX = screenAction.x.toInt()
                    val startY = screenAction.y.toInt()
                    val distance = screenHeight / 3
                    val direction = action.direction ?: "up"
                    log("滑动: $direction, 起点: ($startX, $startY), 力度: $velocity")

                    when (direction.lowercase()) {
                        "up" -> controller.swipe(startX, startY, startX, startY - distance, velocity = velocity)
                        "down" -> controller.swipe(startX, startY, startX, startY + distance, velocity = velocity)
                        "left" -> controller.swipe(startX, startY, startX - distance, startY, velocity = velocity)
                        "right" -> controller.swipe(startX, startY, startX + distance, startY, velocity = velocity)
                    }
                }
            }
            "drag" -> {
                val x1 = screenAction.startX?.toInt() ?: return@withContext
                val y1 = screenAction.startY?.toInt() ?: return@withContext
                val x2 = screenAction.endX?.toInt() ?: return@withContext
                val y2 = screenAction.endY?.toInt() ?: return@withContext
                val velocity = com.alian.assistant.App.getInstance().settingsManager.settings.value.swipeVelocity

                log("拖拽: ($x1, $y1) -> ($x2, $y2), 力度: $velocity")
                controller.swipe(x1, y1, x2, y2, 500, velocity)
            }
            "open" -> {
                val appName = action.text ?: return@withContext
                log("打开应用: $appName")
                controller.openApp(appName)
            }
            "system_button" -> {
                when (action.button?.lowercase()) {
                    "back" -> {
                        log("按返回键")
                        controller.back()
                    }
                    "home" -> {
                        log("按 Home 键")
                        controller.home()
                    }
                    "menu" -> {
                        log("按菜单键")
                        // 通过 keyevent 模拟菜单键
                        controller.back()  // 大多数场景用返回代替
                    }
                    "enter" -> {
                        log("按回车键")
                        controller.enter()
                    }
                }
            }
            "wait" -> {
                log("等待...")
                delay(2000)
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
    }

    /**
     * 执行具体动作 (在 IO 线程执行，避免 ANR)
     */
    private suspend fun executeAction(action: Action, infoPool: InfoPool) = withContext(Dispatchers.IO) {
        // 动态获取屏幕尺寸（处理横竖屏切换）
        val (screenWidth, screenHeight) = controller.getScreenSize()

        when (action.type) {
            "click" -> {
                val x = mapCoordinate(action.x ?: 0, screenWidth)
                val y = mapCoordinate(action.y ?: 0, screenHeight)
                executeWithOverlayProtection(action.type, x, y) {
                    controller.tap(x, y)
                }
            }
            "double_tap" -> {
                val x = mapCoordinate(action.x ?: 0, screenWidth)
                val y = mapCoordinate(action.y ?: 0, screenHeight)
                executeWithOverlayProtection(action.type, x, y) {
                    controller.doubleTap(x, y)
                }
            }
            "long_press" -> {
                val x = mapCoordinate(action.x ?: 0, screenWidth)
                val y = mapCoordinate(action.y ?: 0, screenHeight)
                executeWithOverlayProtection(action.type, x, y) {
                    controller.longPress(x, y)
                }
            }
            "swipe" -> {
                // 支持两种 swipe 方式:
                // 1. 坐标方式: coordinate + coordinate2
                // 2. 方向方式: direction (up/down/left/right) + optional coordinate
                val velocity = com.alian.assistant.App.getInstance().settingsManager.settings.value.swipeVelocity

                if (action.direction != null) {
                    // 方向方式 (MAI-UI 格式)
                    val centerX = action.x?.let { mapCoordinate(it, screenWidth) } ?: (screenWidth / 2)
                    val centerY = action.y?.let { mapCoordinate(it, screenHeight) } ?: (screenHeight / 2)
                    val distance = 400  // 滑动距离
                    log("方向滑动: ${action.direction}, 中心点: ($centerX, $centerY), 力度: $velocity")
                    when (action.direction.lowercase()) {
                        "up" -> executeWithOverlayProtection(action.type, centerX, centerY + distance, centerX, centerY - distance) {
                            controller.swipe(centerX, centerY + distance, centerX, centerY - distance, velocity = velocity)
                        }
                        "down" -> executeWithOverlayProtection(action.type, centerX, centerY - distance, centerX, centerY + distance) {
                            controller.swipe(centerX, centerY - distance, centerX, centerY + distance, velocity = velocity)
                        }
                        "left" -> executeWithOverlayProtection(action.type, centerX + distance, centerY, centerX - distance, centerY) {
                            controller.swipe(centerX + distance, centerY, centerX - distance, centerY, velocity = velocity)
                        }
                        "right" -> executeWithOverlayProtection(action.type, centerX - distance, centerY, centerX + distance, centerY) {
                            controller.swipe(centerX - distance, centerY, centerX + distance, centerY, velocity = velocity)
                        }
                        else -> log("未知滑动方向: ${action.direction}")
                    }
                } else {
                    // 坐标方式
                    val x1 = mapCoordinate(action.x ?: 0, screenWidth)
                    val y1 = mapCoordinate(action.y ?: 0, screenHeight)
                    val x2 = mapCoordinate(action.x2 ?: 0, screenWidth)
                    val y2 = mapCoordinate(action.y2 ?: 0, screenHeight)
                    log("坐标滑动: ($x1, $y1) -> ($x2, $y2), 力度: $velocity")

                    executeWithOverlayProtection(action.type, x1, y1, x2, y2) {
                        controller.swipe(x1, y1, x2, y2, velocity = velocity)
                    }
                }
            }
            "type" -> {
                action.text?.let { controller.type(it) }
            }
            "system_button" -> {
                when (action.button) {
                    "Back", "back" -> controller.back()
                    "Home", "home" -> controller.home()
                    "Enter", "enter" -> controller.enter()
                    else -> log("未知系统按钮: ${action.button}")
                }
            }
            "open_app" -> {
                action.text?.let { appName ->
                    // 智能匹配包名 (客户端模糊搜索，省 token)
                    val packageName = appScanner.findPackage(appName)
                    if (packageName != null) {
                        log("找到应用: $appName -> $packageName")
                        controller.openApp(packageName)
                    } else {
                        log("未找到应用: $appName，尝试直接打开")
                        controller.openApp(appName)
                    }
                }
            }
            "wait" -> {
                // 智能等待：模型决定等待时长
                val duration = (action.duration ?: 3).coerceIn(1, 10)
                log("等待 ${duration} 秒...")
                delay(duration * 1000L)
            }
            "take_over" -> {
                // 人机协作：暂停等待用户手动完成操作
                val message = action.message ?: "请完成操作后点击继续"
                log("🖐 人机协作: $message")
                withContext(Dispatchers.Main) {
                    waitForUserTakeOver(message)
                }
                log("✅ 用户已完成，继续执行")
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
    }

    /**
     * 等待用户完成手动操作（人机协作）
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserTakeOver(message: String) = suspendCancellableCoroutine<Unit> { continuation ->
        OverlayService.showTakeOver(message) {
            if (continuation.isActive) {
                continuation.resume(Unit) {}
            }
        }
    }

    /**
     * 等待用户确认敏感操作
     * @return true = 用户确认，false = 用户取消
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserConfirm(message: String) = suspendCancellableCoroutine<Boolean> { continuation ->
        OverlayService.showConfirm(message) { confirmed ->
            if (continuation.isActive) {
                continuation.resume(confirmed) {}
            }
        }
    }

    /**
     * 坐标映射 - 支持相对坐标和绝对坐标
     *
     * 坐标格式判断:
     * - 0-999: Qwen-VL 相对坐标 (0-999 映射到屏幕)
     * - >= 1000: 绝对像素坐标，直接使用
     *
     * @param value 模型输出的坐标值
     * @param screenMax 屏幕实际尺寸
     */
    private fun mapCoordinate(value: Int, screenMax: Int): Int {
        return if (value < 1000) {
            // 相对坐标 (0-999) -> 绝对像素
            (value * screenMax / 999)
        } else {
            // 绝对坐标，限制在屏幕范围内
            value.coerceAtMost(screenMax)
        }
    }

    /**
     * 检查错误升级
     */
    private fun checkErrorEscalation(infoPool: InfoPool) {
        infoPool.errorFlagPlan = false
        val thresh = infoPool.errToManagerThresh

        if (infoPool.actionOutcomes.size >= thresh) {
            val recentOutcomes = infoPool.actionOutcomes.takeLast(thresh)
            val failCount = recentOutcomes.count { it in listOf("B", "C") }
            if (failCount == thresh) {
                infoPool.errorFlagPlan = true
            }
        }
    }

    // 停止回调（由 MainActivity 设置，用于取消协程）
    override var onStopRequested: (() -> Unit)? = null

    /**
     * 停止执行
     */
    override fun stop() {
        OverlayService.hide(context)
        updateState { copy(isRunning = false) }
        // 通知 MainActivity 取消协程
        onStopRequested?.invoke()
    }

    /**
     * 清空日志
     */
    override fun clearLogs() {
        _logs.value = emptyList()
        updateState { copy(executionSteps = emptyList()) }
    }

    /**
     * 获取对话会话
     */
    override fun getChatSession(): ChatSession? {
        return chatSession
    }

    /**
     * 设置对话会话（用于从历史记录恢复）
     */
    fun setChatSession(session: ChatSession) {
        chatSession = session
    }

    /**
     * 返回艾莲App
     */
    private fun bringAppToFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
        } catch (e: Exception) {
            log("返回App失败: ${e.message}")
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        _logs.value = _logs.value + message
    }

    /**
     * 安全执行操作 - 自动处理悬浮窗隐藏/恢复
     * @param action 操作类型
     * @param x 操作点 X 坐标（可选）
     * @param y 操作点 Y 坐标（可选）
     * @param x2 第二个操作点 X 坐标（用于滑动，可选）
     * @param y2 第二个操作点 Y 坐标（用于滑动，可选）
     * @param proximityThreshold 附近距离阈值（像素），默认为 100
     * @param operation 要执行的操作
     */
    private suspend fun executeWithOverlayProtection(
        action: String,
        x: Int? = null,
        y: Int? = null,
        x2: Int? = null,
        y2: Int? = null,
        proximityThreshold: Int = 100,
        operation: suspend () -> Unit
    ) {
        val shouldHideOverlay = when (action) {
            "click", "double_tap", "long_press", "swipe", "drag" -> {
                x != null && y != null && OverlayService.isActionNearOverlay(x, y, proximityThreshold)
            }
            else -> false
        }

        if (shouldHideOverlay) {
            log("操作点 ($x, $y) 在悬浮窗附近，临时隐藏悬浮窗")
            OverlayService.setVisible(false)
            delay(100) // 等待悬浮窗完全隐藏
        }

        try {
            operation()
        } finally {
            if (shouldHideOverlay) {
                delay(50) // 等待操作完成
                OverlayService.setVisible(true)
                log("恢复悬浮窗显示")
            }
        }
    }

    /**
     * 根据动作类型获取图标
     */
    private fun getActionIcon(actionType: String): String {
        return when (actionType) {
            "click" -> "👆"
            "double_tap" -> "👆👆"
            "long_press" -> "👇"
            "swipe", "drag" -> "👋"
            "type" -> "⌨️"
            "open_app", "open" -> "📱"
            "scroll" -> "📜"
            "answer" -> "💬"
            "terminate" -> "✅"
            "take_over", "ask_user" -> "🤝"
            "wait" -> "⏳"
            "back" -> "⬅️"
            "home" -> "🏠"
            "system_button" -> "🔘"
            else -> "⚙️"
        }
    }

    /**
     * 获取执行结果的文本和颜色
     */
    private fun getOutcomeInfo(outcome: String): Pair<String, String> {
        return when (outcome) {
            "A" -> "成功" to "#10B981"
            "B" -> "部分成功" to "#F59E0B"
            "C" -> "失败" to "#EF4444"
            "?" -> "进行中" to "#3B82F6"
            else -> "未知" to "#9CA3AF"
        }
    }

    private fun updateState(update: AgentState.() -> AgentState) {
        _state.value = _state.value.update()
    }

    /**
     * 加载历史执行步骤
     */
    override fun loadExecutionSteps(steps: List<ExecutionStep>) {
        Log.d("MobileAgent", "loadExecutionSteps 被调用，步骤数量: ${steps.size}")
        updateState {
            val newState = copy(
                executionSteps = steps,
                currentStep = if (steps.isNotEmpty()) steps.size else 0
            )
            Log.d("MobileAgent", "状态已更新 - executionSteps 数量: ${newState.executionSteps.size}, currentStep: ${newState.currentStep}")
            newState
        }
        // 将步骤信息添加到日志中
        steps.forEach { step ->
            log("历史步骤 ${step.stepNumber}: ${step.description} - ${step.outcome}")
        }
    }

    /**
     * 加载历史执行记录（包含步骤、指令和答案）
     */
    override fun loadExecutionRecord(steps: List<ExecutionStep>, instruction: String, answer: String?) {
        Log.d("MobileAgent", "loadExecutionRecord 被调用 - 步骤数量: ${steps.size}, instruction: $instruction, answer: $answer")
        updateState {
            copy(
                executionSteps = steps,
                currentStep = if (steps.isNotEmpty()) steps.size else 0,
                instruction = instruction,
                answer = answer
            )
        }
        // 将步骤信息添加到日志中
        steps.forEach { step ->
            log("历史步骤 ${step.stepNumber}: ${step.description} - ${step.outcome}")
        }
    }
}

data class AgentState(
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val currentStep: Int = 0,
    val instruction: String = "",
    val answer: String? = null,
    val executionSteps: List<ExecutionStep> = emptyList()
)

data class AgentResult(
    val success: Boolean,
    val message: String
)
