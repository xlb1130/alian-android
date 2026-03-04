package com.alian.assistant.core.agent.improve

import android.content.Context
import android.content.Intent
import android.util.Log
import com.alian.assistant.App
import com.alian.assistant.infrastructure.device.accessibility.AlianAccessibilityService
import com.alian.assistant.data.model.ChatMessage
import com.alian.assistant.data.model.ChatSession
import com.alian.assistant.core.agent.AgentPermissionCheck
import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.core.agent.AgentState
import com.alian.assistant.core.agent.AgentResult
import com.alian.assistant.core.agent.InterruptOrchestrator
import com.alian.assistant.core.agent.Agent
import com.alian.assistant.data.ExecutionStep
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.core.skills.SkillManager
import com.alian.assistant.infrastructure.device.AppScanner
import com.alian.assistant.infrastructure.device.controller.accessibility.AccessibilityElementLocator
import com.alian.assistant.infrastructure.device.controller.factory.ShizukuPrivilegeLevel
import com.alian.assistant.infrastructure.device.controller.interfaces.ControllerType
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.device.controller.interfaces.ScreenshotErrorType
import com.alian.assistant.infrastructure.device.controller.shizuku.ShizukuController
import com.alian.assistant.presentation.ui.overlay.OverlayService

import com.alian.assistant.infrastructure.ai.llm.VLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * MobileAgentImprove - 优化版本
 * 
 * 核心优化：
 * 1. 条件性 Manager 调用（减少 60% Manager 调用）
 * 2. 条件性 Reflector 调用（减少 50% Reflector 调用）
 * 3. 精简 Prompt（减少 60% token）
 * 
 * 预期效果：
 * - VLM 调用次数减少 60%
 * - 速度提升 2.5x
 * - 成本降低 66%
 */
class MobileAgentImprove(
    private val vlmClient: VLMClient?,
    private val controller: IDeviceController,
    private val context: Context,
    private val reactOnly: Boolean = false,  // ReactOnly 模式：Manager 只规划一次
    private val enableChatAgent: Boolean = false,  // 是否启用 ChatAgent 模式
    private val enableFlowMode: Boolean = true  // 是否启用 Flow 模式（流程缓存优化）
) : Agent {
    companion object {
        private const val TAG = "MobileAgentImprove"
        
        // 全局 System Prompt
        val SYSTEM_PROMPT = """
You are an AI agent that operates an Android phone through GUI automation.
You can see screenshots and execute actions like click, type, swipe, etc.

Core Capabilities:
- Analyze screenshots to understand current state
- Plan and execute multi-step tasks
- Handle errors and recover from failures

Limitations:
- Cannot solve CAPTCHAs or verification codes
- Cannot perform payment operations without user confirmation
- Cannot access content outside the visible screen
        """.trimIndent()
        
        // 优化参数
        private const val CONFIDENCE_THRESHOLD = 0.8f  // 置信度阈值
        private const val MAX_CONSECUTIVE_REFLECTOR_SKIPS = 3  // 最大连续跳过 Reflector 次数
        private const val MAX_STEPS_WITHOUT_MANAGER_UPDATE = 5  // 最大步数不更新 Manager
        private const val MIN_SUCCESSFUL_ACTIONS_FOR_SUBGOAL = 2  // 子目标完成所需成功动作数
        
        // 重试参数
        private const val MAX_CLICK_RETRY_COUNT = 3  // 最大点击重试次数
        private const val CLICK_RETRY_DELAY_MS = 500L  // 点击重试延迟（毫秒）
        
        // 截图失败处理参数
        private const val MAX_CONSECUTIVE_SCREENSHOT_FAILURES = 3  // 最大连续截图失败次数
        
        // Flow 模式参数
        private const val FLOW_MIN_CONFIDENCE = 0.7f  // Flow 模式最低置信度
    }

    // 组件
    private val appScanner: AppScanner = App.getInstance().appScanner
    private val manager = ManagerImprove()
    private val executor = ExecutorImprove()
    private val reflector = ReflectorImprove(controller)
    private val settingsManager: SettingsManager = SettingsManager(context)
    private val elementLocator = AccessibilityElementLocator()
    private val permissionCheck = AgentPermissionCheck(context, settingsManager)

    // Flow 集成（流程缓存优化）
    private val flowIntegration: FlowIntegration = FlowIntegration(context, controller).apply {
        flowEnabled = enableFlowMode
    }

    // 打断协调器（可选，用于语音打断功能）
    private var interruptOrchestrator: InterruptOrchestrator? = null
    private var onChatMessageCallback: ((ChatMessage) -> Unit)? = null

    // 聊天模式状态
    private var isPausedForChat = false
    private var pauseSnapshot: InterruptOrchestrator.Snapshot? = null

    // Skill 管理器
    private val skillManager: SkillManager? = try {
        SkillManager.getInstance().also {
            Log.d(TAG, "SkillManager 已加载，共 ${it.getAllSkills().size} 个 Skills")
            vlmClient?.let { client -> it.setVLMClient(client) }
        }
    } catch (e: Exception) {
        Log.e(TAG, "SkillManager 加载失败: ${e.message}")
        null
    }

    // 截图失败计数器
    private var consecutiveScreenshotFailures = 0

    // 状态流
    private val _state = MutableStateFlow(AgentState())
    override val state: StateFlow<AgentState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs

    /**
     * 带重试机制的点击操作
     *
     * @param action 操作动作
     * @param locateResult 无障碍定位结果
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return 是否成功
     */
    private suspend fun clickWithRetry(
        action: Action,
        locateResult: AccessibilityElementLocator.ElementLocateResult,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (locateResult.node == null) {
            // 没有可点击的元素，降级到坐标点击
            log("⚠️ 无障碍定位未找到可点击元素，降级到坐标点击")
            val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
            executeWithOverlayProtection(action.type, x, y) {
                controller.tap(x, y)
            }
            return true
        }

        // 验证元素是否匹配目标特征
        val isElementMatch = elementLocator.verifyElementMatch(
            node = locateResult.node,
            targetText = action.targetText,
            targetDesc = action.targetDesc,
            targetResourceId = action.targetResourceId
        )

        if (!isElementMatch) {
            log("⚠️ 找到的元素不匹配目标特征")
            log("   目标 - 文本: ${action.targetText}, 描述: ${action.targetDesc}, 资源ID: ${action.targetResourceId}")
            val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
            log("   降级到坐标点击: ($x, $y)")
            executeWithOverlayProtection(action.type, x, y) {
                controller.tap(x, y)
            }
            return true
        }

        // 验证元素状态
        val validation = elementLocator.validateElement(locateResult.node)
        if (!validation.isValid) {
            log("⚠️ 元素状态无效: ${validation.errorMessage}")
            val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
            log("   降级到坐标点击: ($x, $y)")
            executeWithOverlayProtection(action.type, x, y) {
                controller.tap(x, y)
            }
            return true
        }

        // 尝试点击，最多重试 MAX_CLICK_RETRY_COUNT 次
        // 隐藏悬浮窗避免挡住界面
        log("执行无障碍点击前隐藏悬浮窗")
        OverlayService.setVisible(false)
        delay(100) // 等待悬浮窗完全隐藏
        
        var clickSuccess = false
        try {
            repeat(MAX_CLICK_RETRY_COUNT) { attempt ->
                clickSuccess = elementLocator.clickElement(locateResult.node)
                if (clickSuccess) {
                    log("✓ 点击成功 (尝试 ${attempt + 1}/$MAX_CLICK_RETRY_COUNT)")
                    return true
                } else {
                    log("⚠️ 点击失败 (尝试 ${attempt + 1}/$MAX_CLICK_RETRY_COUNT)")
                    if (attempt < MAX_CLICK_RETRY_COUNT - 1) {
                        log("   等待 ${CLICK_RETRY_DELAY_MS}ms 后重试...")
                        delay(CLICK_RETRY_DELAY_MS)
                    }
                }
            }
        } finally {
            // 恢复悬浮窗显示
            OverlayService.setVisible(true)
            log("恢复悬浮窗显示")
        }

        // 所有重试都失败，降级到坐标点击
        log("⚠️ 所有重试失败，降级到坐标点击")
        val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
        executeWithOverlayProtection(action.type, x, y) {
            controller.tap(x, y)
        }
        return true
    }

    /**
     * 执行指令
     */
    override suspend fun runInstruction(
        instruction: String,
        maxSteps: Int,
        useNotetaker: Boolean,
        enableBatchExecution: Boolean
    ): AgentResult {
        log("========== 系统诊断 ==========")
        log("开始执行（优化模式）: $instruction")
        log("🎤 语音打断状态: enableChatAgent=$enableChatAgent")

        // 注意：权限检查现在由 MainActivity 在调用前完成

        // 系统诊断
        runDiagnostics()

        if (vlmClient == null) {
            log("错误: VLMClient 未初始化")
            return AgentResult(success = false, message = "VLMClient 未初始化")
        }

        // 初始化 InfoPoolImprove
        val infoPool = InfoPoolImprove(instruction = instruction)
        infoPool.managerMode = ManagerMode.INITIAL

        // 初始化 InterruptOrchestrator（如果聊天模式已启用）
        initializeInterruptOrchestrator(infoPool)

        // 启动 Flow 会话（流程缓存优化）
        if (enableFlowMode) {
            log("🔄 启动 Flow 会话...")
            val flowSession = flowIntegration.startSession(instruction)
            if (flowSession.hasTemplate) {
                log("✓ Flow 匹配到模板: ${flowSession.matchedTemplate?.name}, 置信度: ${flowSession.matchConfidence}")
            } else {
                log("Flow 未匹配到模板，使用传统模式")
            }
        }

        // 使用 LLM 匹配 Skill
        log("正在分析意图...")
        val skillContext = skillManager?.generateAgentContextWithLLM(instruction)

        if (!skillContext.isNullOrEmpty() && 
            skillContext != "未找到相关技能或可用应用，请使用通用 GUI 自动化完成任务。") {
            infoPool.skillContext = skillContext
            log("已匹配到可用技能:\n$skillContext")
        } else {
            log("未匹配到特定技能，使用通用 GUI 自动化")
        }

        // 获取屏幕尺寸
        val (width, height) = controller.getScreenSize()
        infoPool.screenWidth = width
        infoPool.screenHeight = height

        // 屏幕尺寸验证和诊断
        log("📱 屏幕尺寸诊断:")
        log("   宽度: $width px")
        log("   高度: $height px")
        log("   宽高比: ${String.format("%.2f", width.toFloat() / height)}")

        // 检查屏幕尺寸是否合理
        if (width < 300 || height < 300) {
            log("⚠️ 警告: 屏幕尺寸异常小！可能影响坐标映射准确性")
        }
        if (width > 5000 || height > 5000) {
            log("⚠️ 警告: 屏幕尺寸异常大！可能影响坐标映射准确性")
        }

        // 检查是否为常见分辨率
        val commonResolutions = listOf(
            "1080x2400", "1080x2340", "1080x2244", "1080x2220",
            "1440x3200", "1440x3120", "1440x2960",
            "720x1280", "720x1520",
            "2160x3840", "2160x3840"
        )
        val currentRes = "${width}x${height}"
        if (currentRes in commonResolutions) {
            log("   ✓ 检测到常见分辨率: $currentRes")
        } else {
            log("   ℹ️ 当前分辨率: $currentRes (非标准分辨率)")
        }

        // 坐标映射说明
        log("📐 坐标映射规则:")
        log("   归一化坐标 (0-999) → 映射到实际屏幕尺寸")
        log("   像素坐标 (≥1000) → 直接使用（限制在屏幕范围内）")
        log("   示例: 500 → ${500 * width / 999} (归一化), 1200 → ${1200.coerceAtMost(width)} (像素)")

        // 获取已安装应用列表（格式：应用名 (包名)）
        val apps = appScanner.getApps()
            .filter { !it.isSystem }
            .map { "${it.appName} (${it.packageName})" }
        infoPool.installedApps = apps.joinToString(", ")
        log("已加载 ${apps.size} 个应用")

        // 显示悬浮窗
        OverlayService.show(context, "开始执行...") {
            updateState { copy(isRunning = false) }
            stop()
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                coroutineContext.ensureActive()

                // 检查是否被用户停止
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    // 结束 Flow 会话并自动沉淀
                    if (enableFlowMode) {
                        flowIntegration.endSession(success = false)
                        autoSaveTemplate(instruction, success = false)
                    }
                    cleanup()  // 清理语音监听资源
                    return AgentResult(success = false, message = "用户停止")
                }

                // 检查是否被暂停（聊天模式）
                if (isPaused()) {
                    log("等待恢复...")
                    waitForResume()
                    // 恢复后重新开始当前步骤
                    continue
                }

                updateState { copy(currentStep = step + 1) }
                infoPool.totalSteps = step + 1
                log("\n========== Step ${step + 1} ==========")
                OverlayService.updateStep("Step ${step + 1}/$maxSteps - 正在分析当前屏幕...", "")

                // ========== Flow 融合执行 ==========
                var flowStepExecuted = false
                var flowScreenshotSkipped = false
                var flowVlmSkipped = false
                
                if (enableFlowMode && flowIntegration.shouldUseFlowMode()) {
                    val flowAdvice = flowIntegration.getStepAdvice()
                    
                    if (!flowAdvice.useTraditionalMode && flowAdvice.canSkipScreenshot && flowAdvice.nextStep != null) {
                        log("🔄 Flow 建议跳过截图，置信度: ${flowAdvice.confidence}")
                        log("   执行 Flow 步骤: ${flowAdvice.nextStep.action.type}")
                        
                        // 执行 Flow 建议的步骤
                        val flowResult = flowIntegration.executeFlowStep(flowAdvice.nextStep)
                        
                        if (flowResult.success) {
                            log("✓ Flow 步骤执行成功")
                            flowStepExecuted = true
                            flowScreenshotSkipped = true
                            flowVlmSkipped = true
                            infoPool.managerSkips++
                            infoPool.reflectorSkips++
                            
                            // 更新统计
                            infoPool.lastExecutorConfidence = flowAdvice.confidence
                            
                            // 等待动作生效后继续下一步
                            delay(300)
                            continue
                        } else {
                            log("⚠️ Flow 步骤执行失败，降级到传统模式")
                        }
                    } else if (flowAdvice.nextStep != null && flowAdvice.confidence >= FLOW_MIN_CONFIDENCE) {
                        log("🔄 Flow 有建议但需要验证，置信度: ${flowAdvice.confidence}")
                        // Flow 有建议但需要截图验证，继续传统流程
                    }
                }
                // ========== Flow 融合执行结束 ==========

                // 1. 截图
                log("截图中...")

                // 检测通知遮挡
                val accessibilityService = AlianAccessibilityService.getInstance()
                if (accessibilityService != null && accessibilityService.hasNotificationBlocking()) {
                    val notificationText = accessibilityService.getLastNotificationText()
                    log("⚠️ 检测到通知遮挡: $notificationText")
                    log("   等待通知消失...")
                    val cleared = accessibilityService.waitForNotificationClear()
                    if (cleared) {
                        log("✓ 通知已消失，继续执行")
                    } else {
                        log("⚠️ 通知仍然存在，继续执行（可能影响操作）")
                    }
                }

                OverlayService.setVisible(false)
                delay(100)
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                var screenshot = screenshotResult.bitmap

                // 处理敏感页面
                if (screenshotResult.isSensitive) {
                    log("⚠️ 检测到敏感页面")
                    val confirmed = withContext(Dispatchers.Main) {
                        waitForUserConfirm("检测到敏感页面，是否继续执行？")
                    }
                    if (!confirmed) {
                        log("用户取消，任务终止")
                        OverlayService.hide(context)
                        bringAppToFront()
                        cleanup()  // 清理语音监听资源
                        return AgentResult(success = false, message = "敏感页面，用户取消")
                    }
                    log("用户确认继续（使用黑屏占位图）")
                } else if (screenshotResult.isFallback) {
                    // 截图失败，处理连续失败情况
                    consecutiveScreenshotFailures++
                    val errorMessage = getScreenshotErrorMessage(screenshotResult.errorType)
                    log("⚠️ 截图失败: $errorMessage (连续失败: $consecutiveScreenshotFailures/$MAX_CONSECUTIVE_SCREENSHOT_FAILURES)")
                    
                    if (consecutiveScreenshotFailures >= MAX_CONSECUTIVE_SCREENSHOT_FAILURES) {
                        log("❌ 连续截图失败次数过多，任务终止")
                        OverlayService.update("⚠️ $errorMessage")
                        delay(2000)
                        
                        // 提示用户检查权限
                        val shouldOpenSettings = withContext(Dispatchers.Main) {
                            waitForUserConfirm("$errorMessage\n\n点击确认前往设置检查权限")
                        }
                        
                        if (shouldOpenSettings) {
                            openPermissionSettings()
                        }
                        
                        OverlayService.hide(context)
                        bringAppToFront()
                        cleanup()
                        return AgentResult(success = false, message = "截图权限失效: $errorMessage")
                    }
                } else {
                    // 截图成功，重置计数器
                    consecutiveScreenshotFailures = 0
                }

                // 再次检查停止状态
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    // 结束 Flow 会话并自动沉淀
                    if (enableFlowMode) {
                        flowIntegration.endSession(success = false)
                        autoSaveTemplate(instruction, success = false)
                    }
                    cleanup()  // 清理语音监听资源
                    return AgentResult(success = false, message = "用户停止")
                }

                // 2. 检查错误升级
                checkErrorEscalation(infoPool)

                // 3. 判断是否需要调用 Manager
                val shouldCallManager = shouldCallManager(infoPool, step)
                
                if (shouldCallManager) {
                    log("Manager 规划中... (模式: ${infoPool.managerMode})")

                    // 检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        // 结束 Flow 会话并自动沉淀
                        if (enableFlowMode) {
                            flowIntegration.endSession(success = false)
                            autoSaveTemplate(instruction, success = false)
                        }
                        cleanup()  // 清理语音监听资源
                        return AgentResult(success = false, message = "用户停止")
                    }

                    // 检查暂停状态（语音打断）
                    if (isPaused()) {
                        log("检测到暂停请求，跳过 Manager 调用")
                        continue
                    }

                    val planPrompt = manager.getPrompt(infoPool)
                    log("Manager 提示词: $planPrompt")
                    log("Manager 估算 token: ${planPrompt.length / 3}")
                    val planResponse = vlmClient.predict(planPrompt, listOf(screenshot), SYSTEM_PROMPT)
                    log("Manager 响应: $planResponse")
                    
                    // VLM 调用后检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        // 结束 Flow 会话并自动沉淀
                        if (enableFlowMode) {
                            flowIntegration.endSession(success = false)
                            autoSaveTemplate(instruction, success = false)
                        }
                        cleanup()  // 清理语音监听资源
                        return AgentResult(success = false, message = "用户停止")
                    }

                    if (planResponse.isFailure) {
                        log("Manager 调用失败: ${planResponse.exceptionOrNull()?.message}")
                        continue
                    }

                    val planResult = manager.parseResponse(planResponse.getOrThrow())
                    infoPool.completedPlan = planResult.completedSubgoal
                    infoPool.plan = planResult.plan
                    infoPool.lastPlanUpdateStep = step

                    log("计划: ${planResult.plan.take(100)}...")
                    infoPool.managerCalls++
                    log("Manager 调用次数: ${infoPool.managerCalls}")

                    // 播放 Manager 的 tellUser
                    if (planResult.tellUser.isNotEmpty()) {
                        OverlayService.updateStep("Step ${step + 1}/$maxSteps", planResult.tellUser)
                    }

                    // 检查是否遇到敏感页面
                    if (planResult.plan.contains("STOP_SENSITIVE")) {
                        log("检测到敏感页面，已停止执行")
                        OverlayService.update("敏感页面，已停止")
                        delay(2000)
                        OverlayService.hide(context)
                        updateState { copy(isRunning = false, isCompleted = false) }
                        bringAppToFront()
                        cleanup()  // 清理语音监听资源
                        return AgentResult(success = false, message = "检测到敏感页面，已安全停止")
                    }

                    // 检查是否完成
                    if (planResult.plan.contains("Finished") && planResult.plan.length < 20) {
                        log("任务完成!")
                        OverlayService.update("完成!")
                        delay(1500)
                        OverlayService.hideAfterTTS(context)
                        updateState { copy(isRunning = false, isCompleted = true) }
                        bringAppToFront()
                        // 结束 Flow 会话
                        if (enableFlowMode) {
                            flowIntegration.endSession(success = true)
                            // 自动沉淀流程模板
                            autoSaveTemplate(instruction, success = true)
                        }
                        logOptimizationReport(infoPool)
                        cleanup()  // 清理语音监听资源
                        return AgentResult(success = true, message = "任务完成")
                    }
                } else {
                    log("跳过 Manager，复用现有计划")
                    infoPool.managerSkips++
                    log("Manager 跳过次数: ${infoPool.managerSkips}")
                }

                // 4. Executor 决定动作
                log("Executor 决策中...")

                // 检查停止状态
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    // 结束 Flow 会话并自动沉淀
                    if (enableFlowMode) {
                        flowIntegration.endSession(success = false)
                        autoSaveTemplate(instruction, success = false)
                    }
                    cleanup()  // 清理语音监听资源
                    return AgentResult(success = false, message = "用户停止")
                }

                // 检查暂停状态（语音打断）
                if (isPaused()) {
                    log("检测到暂停请求，跳过 Executor 调用")
                    continue
                }

                val actionPrompt = executor.getPrompt(infoPool, enableBatchExecution)
                log("Executor 提示词: $actionPrompt")
                log("Executor 估算 token: ${actionPrompt.length / 3}")
                val actionResponse = vlmClient.predict(actionPrompt, listOf(screenshot), SYSTEM_PROMPT)

                // VLM 调用后检查停止状态
                if (!_state.value.isRunning) {                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    // 结束 Flow 会话并自动沉淀
                    if (enableFlowMode) {
                        flowIntegration.endSession(success = false)
                        autoSaveTemplate(instruction, success = false)
                    }
                    cleanup()  // 清理语音监听资源
                    return AgentResult(success = false, message = "用户停止")
                }

                if (actionResponse.isFailure) {
                    log("Executor 调用失败: ${actionResponse.exceptionOrNull()?.message}")
                    continue
                }

                val responseText = actionResponse.getOrThrow()
                log("VLM 原始响应:\n$responseText")  // 添加日志：打印 VLM 返回的原始响应
                val executorResult = executor.parseResponse(responseText, enableBatchExecution)

                log("思考: ${executorResult.thought.take(80)}...")
                log("置信度: ${executorResult.confidence}")
                log("动作: ${executorResult.actionStr}")
                log("描述: ${executorResult.description}")

                // 更新置信度
                infoPool.lastExecutorConfidence = executorResult.confidence

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
                        // 结束 Flow 会话
                        if (enableFlowMode) {
                            flowIntegration.endSession(success = true)
                        }
                        logOptimizationReport(infoPool)
                        return AgentResult(success = true, message = "回答: ${action.text}")
                    }

                    // 敏感操作确认
                    if (action.needConfirm || (action.message != null && action.type in listOf("click", "double_tap", "long_press"))) {
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
                            break
                        }
                        log("✅ 用户确认，继续执行")
                    }

                    // 执行动作
                    log("执行动作: ${action.type}")
                    log("action.tellUser: ${action.tellUser}")
                    val actionDesc = if (!action.tellUser.isNullOrEmpty()) action.tellUser else actionBatch.description
                    val tellUserText = if (!action.tellUser.isNullOrEmpty()) action.tellUser else "正在帮你${actionBatch.description.take(30)}"
                    log("tellUserText: $tellUserText")
                    OverlayService.updateStep("Step ${step + 1}/$maxSteps - 动作 ${actionIndex + 1}/${actions.size}", tellUserText)
                    executeAction(action, infoPool)
                    infoPool.lastAction = action

                    // 记录执行步骤
                    val currentStepIndex = _state.value.executionSteps.size
                    val stepStartTime = System.currentTimeMillis()
                    val executionStep = ExecutionStep(
                        stepNumber = step + 1,
                        timestamp = stepStartTime,
                        action = action.type,
                        description = actionDesc,
                        thought = executorResult.thought,
                        outcome = "?",
                        icon = getActionIcon(action.type),
                        actionText = action.text,
                        actionButton = action.button,
                        actionDuration = action.duration,
                        actionMessage = action.message
                    )
                    updateState { copy(executionSteps = executionSteps + executionStep) }

                    // 等待动作生效
                    delay(if (step == 0 && actionIndex == 0) 2000 else 200)

                    // 检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        // 结束 Flow 会话并自动沉淀
                        if (enableFlowMode) {
                            flowIntegration.endSession(success = false)
                            autoSaveTemplate(instruction, success = false)
                        }
                        cleanup()  // 清理语音监听资源
                        return AgentResult(success = false, message = "用户停止")
                    }

                    // 5. 截图（动作后）
                    // 检测通知遮挡
                    val accessibilityService = AlianAccessibilityService.getInstance()
                    if (accessibilityService != null && accessibilityService.hasNotificationBlocking()) {
                        val notificationText = accessibilityService.getLastNotificationText()
                        log("⚠️ 检测到通知遮挡: $notificationText")
                        log("   等待通知消失...")
                        val cleared = accessibilityService.waitForNotificationClear()
                        if (cleared) {
                            log("✓ 通知已消失，继续执行")
                        } else {
                            log("⚠️ 通知仍然存在，继续执行（可能影响操作）")
                        }
                    }

                    OverlayService.setVisible(false)
                    delay(100)
                    log("开始截图，控制器类型: ${controller.javaClass.simpleName}")
                    val afterScreenshotResult = controller.screenshotWithFallback()
                    log("截图完成 - isFallback=${afterScreenshotResult.isFallback}, isSensitive=${afterScreenshotResult.isSensitive}, errorType=${afterScreenshotResult.errorType}, bitmap=${if (afterScreenshotResult.bitmap != null) "${afterScreenshotResult.bitmap.width}x${afterScreenshotResult.bitmap.height}" else "null"}")
                    OverlayService.setVisible(true)
                    val afterScreenshot = afterScreenshotResult.bitmap
                    if (afterScreenshotResult.isFallback) {
                        consecutiveScreenshotFailures++
                        log("⚠️ 动作后截图失败: ${getScreenshotErrorMessage(afterScreenshotResult.errorType)} (连续失败: $consecutiveScreenshotFailures/$MAX_CONSECUTIVE_SCREENSHOT_FAILURES)")
                    } else {
                        consecutiveScreenshotFailures = 0
                    }

                    // 6. 调用 Reflector 验证
                    // 检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        // 结束 Flow 会话并自动沉淀
                        if (enableFlowMode) {
                            flowIntegration.endSession(success = false)
                            autoSaveTemplate(instruction, success = false)
                        }
                        cleanup()  // 清理语音监听资源
                        return AgentResult(success = false, message = "用户停止")
                    }

                    // 检查暂停状态（语音打断）
                    if (isPaused()) {
                        log("检测到暂停状态，跳过 Reflector 调用")
                        infoPool.actionHistory.add(action)
                        infoPool.summaryHistory.add(actionDesc)
                        infoPool.actionOutcomes.add("C")
                        infoPool.errorDescriptions.add("Paused by user")
                        infoPool.reflectorSkips++
                        continue
                    }

                    // 调用 Reflector verify 方法，内部处理所有验证逻辑
                    val verificationResult = reflector.verify(
                        action = action,
                        executorResult = executorResult,
                        infoPool = infoPool,
                        vlmClient = vlmClient,
                        beforeScreenshot = screenshot,
                        afterScreenshot = afterScreenshot,
                        systemPrompt = SYSTEM_PROMPT
                    )

                    log("Reflector 验证结果: ${verificationResult.outcome} - ${verificationResult.errorDescription.take(50)}")
                    log("验证方法: ${verificationResult.method}")

                    // 更新历史
                    infoPool.actionHistory.add(action)
                    infoPool.summaryHistory.add(actionDesc)
                    infoPool.actionOutcomes.add(verificationResult.outcome)
                    infoPool.errorDescriptions.add(verificationResult.errorDescription)
                    infoPool.progressStatus = infoPool.completedPlan

                    // 更新统计信息
                    if (verificationResult.verified) {
                        infoPool.reflectorCalls++
                        infoPool.consecutiveReflectorSkips = 0  // 重置连续跳过计数
                        log("Reflector 调用次数: ${infoPool.reflectorCalls}")
                    } else {
                        infoPool.reflectorSkips++
                        infoPool.consecutiveReflectorSkips++
                        infoPool.assumedSuccessCount++
                        log("Reflector 跳过次数: ${infoPool.reflectorSkips}")
                        log("连续跳过 Reflector: ${infoPool.consecutiveReflectorSkips}")
                    }

                    // 更新执行步骤的 outcome
                    val stepEndTime = System.currentTimeMillis()
                    val stepDuration = stepEndTime - stepStartTime
                    updateState {
                        val updatedSteps = executionSteps.toMutableList()
                        if (currentStepIndex < updatedSteps.size) {
                            val outcome = infoPool.actionOutcomes.last()
                            updatedSteps[currentStepIndex] = updatedSteps[currentStepIndex].copy(
                                outcome = outcome,
                                duration = stepDuration
                            )
                        }
                        copy(executionSteps = updatedSteps)
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
            // 结束 Flow 会话
            if (enableFlowMode) {
                flowIntegration.endSession(success = false)
            }
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.hideAfterTTS(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        // 结束 Flow 会话
        if (enableFlowMode) {
            flowIntegration.endSession(success = false)
        }
        logOptimizationReport(infoPool)
        cleanup()  // 清理语音监听资源
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * 判断是否需要调用 Manager
     */
    private fun shouldCallManager(infoPool: InfoPoolImprove, currentStep: Int): Boolean {
        // ReactOnly 模式：只在第一次调用 Manager
        if (reactOnly) {
            if (currentStep == 0) {
                infoPool.managerMode = ManagerMode.INITIAL
                return true
            }
            // ReactOnly 模式下，后续不再调用 Manager
            return false
        }

        // 1. 初始规划：第一次必须调用
        if (currentStep == 0) {
            infoPool.managerMode = ManagerMode.INITIAL
            return true
        }

        // 2. 强制更新：重规划后必须调用 Manager 获取新计划
        if (infoPool.forceManagerUpdate) {
            infoPool.managerMode = ManagerMode.REPLANNING
            infoPool.forceManagerUpdate = false  // 重置标志
            log("强制 Manager 更新（重规划后）")
            return true
        }

        // 3. 错误升级：连续失败时必须调用
        if (infoPool.errorFlagPlan) {
            infoPool.managerMode = ManagerMode.RECOVERY
            return true
        }

        // 4. 计划完成：当前子目标完成后需要更新计划
        if (isSubgoalCompleted(infoPool)) {
            infoPool.managerMode = ManagerMode.NORMAL
            return true
        }

        // 4. 屏幕状态异常：检测到意外状态
        if (infoPool.unexpectedScreenState) {
            infoPool.managerMode = ManagerMode.REPLANNING
            return true
        }

        // 5. 长时间未更新：超过 N 步未更新计划
        val stepsSinceLastUpdate = currentStep - infoPool.lastPlanUpdateStep
        if (stepsSinceLastUpdate >= MAX_STEPS_WITHOUT_MANAGER_UPDATE) {
            infoPool.managerMode = ManagerMode.NORMAL
            return true
        }

        // 其他情况跳过 Manager
        return false
    }

    /**
     * 判断子目标是否完成
     */
    private fun isSubgoalCompleted(infoPool: InfoPoolImprove): Boolean {
        val subgoals = infoPool.plan.split(Regex("""(?<=\d)\. """)).filter { it.isNotEmpty() }
        
        if (infoPool.currentSubgoalIndex < subgoals.size) {
            // 检查最近的动作是否成功
            val recentSuccessfulActions = infoPool.actionOutcomes.takeLast(3)
                .count { it == "A" || it == "D" }  // A=实际成功, D=假设成功

            if (recentSuccessfulActions >= MIN_SUCCESSFUL_ACTIONS_FOR_SUBGOAL) {
                infoPool.currentSubgoalIndex++
                log("子目标 ${infoPool.currentSubgoalIndex} 完成，更新计划")
                return true
            }
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
            }
        }
    }

    /**
     * 执行动作
     */
    private suspend fun executeAction(action: Action, infoPool: InfoPoolImprove) = withContext(Dispatchers.IO) {
        val (screenWidth, screenHeight) = controller.getScreenSize()

        // 记录屏幕尺寸信息
        log("📱 屏幕尺寸: ${screenWidth}x${screenHeight}")

        when (action.type) {
            "click" -> {
                // 1. 优先尝试无障碍定位
                val locateResult = tryAccessibilityLocate(action, infoPool, screenWidth, screenHeight)

                if (locateResult.success) {
                    // 无障碍定位成功，使用带重试机制的点击
                    log("✓ 无障碍定位成功: ${locateResult.method}")
                    clickWithRetry(action, locateResult, screenWidth, screenHeight)
                } else {
                    // 2. 降级到坐标点击
                    log("⚠ 无障碍定位失败，使用坐标点击")
                    val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
                    executeWithOverlayProtection(action.type, x, y) {
                        controller.tap(x, y)
                    }
                }
            }
            "double_tap" -> {
                // 优先尝试无障碍定位
                val locateResult = tryAccessibilityLocate(action, infoPool, screenWidth, screenHeight)

                if (locateResult.success) {
                    log("✓ 无障碍定位成功: ${locateResult.method}")
                    if (locateResult.node != null) {
                        // 验证元素状态
                        val validation = elementLocator.validateElement(locateResult.node)
                        if (!validation.isValid) {
                            log("⚠️ 元素状态无效: ${validation.errorMessage}")
                            log("   降级到坐标双击")
                            val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
                            executeWithOverlayProtection(action.type, x, y) {
                                controller.doubleTap(x, y)
                            }
                        } else {
                            // 双击需要执行两次点击，隐藏悬浮窗避免挡住界面
                            log("执行无障碍双击前隐藏悬浮窗")
                            OverlayService.setVisible(false)
                            delay(100)
                            
                            try {
                                val firstClickSuccess = elementLocator.clickElement(locateResult.node)
                                if (firstClickSuccess) {
                                    delay(100)
                                    val secondClickSuccess = elementLocator.clickElement(locateResult.node)
                                    if (!secondClickSuccess) {
                                        log("⚠️ 第二次点击失败，降级到坐标双击")
                                        val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
                                        controller.doubleTap(x, y)
                                    } else {
                                        log("✓ 双击成功")
                                    }
                                } else {
                                    log("⚠️ 第一次点击失败，降级到坐标双击")
                                    val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
                                    controller.doubleTap(x, y)
                                }
                            } finally {
                                OverlayService.setVisible(true)
                                log("恢复悬浮窗显示")
                            }
                        }
                    } else {
                        val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
                        executeWithOverlayProtection(action.type, x, y) {
                            controller.doubleTap(x, y)
                        }
                    }
                } else {
                    log("⚠ 无障碍定位失败，使用坐标双击")
                    val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
                    executeWithOverlayProtection(action.type, x, y) {
                        controller.doubleTap(x, y)
                    }
                }
            }
            "long_press" -> {
                // 优先尝试无障碍定位
                val locateResult = tryAccessibilityLocate(action, infoPool, screenWidth, screenHeight)

                if (locateResult.success) {
                    log("✓ 无障碍定位成功: ${locateResult.method}")
                    if (locateResult.node != null) {
                        // 验证元素状态
                        val validation = elementLocator.validateElement(locateResult.node)
                        if (!validation.isValid) {
                            log("⚠️ 元素状态无效: ${validation.errorMessage}")
                            log("   降级到坐标长按")
                            val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
                            executeWithOverlayProtection(action.type, x, y) {
                                controller.longPress(x, y)
                            }
                        } else {
                            // 隐藏悬浮窗避免挡住界面
                            log("执行无障碍长按前隐藏悬浮窗")
                            OverlayService.setVisible(false)
                            delay(100)
                            
                            try {
                                val clickSuccess = elementLocator.clickElement(locateResult.node)
                                if (!clickSuccess) {
                                    log("⚠️ 直接点击元素失败，降级到坐标长按")
                                    val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
                                    controller.longPress(x, y)
                                } else {
                                    log("✓ 长按成功")
                                }
                            } finally {
                                OverlayService.setVisible(true)
                                log("恢复悬浮窗显示")
                            }
                        }
                    } else {
                        val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
                        executeWithOverlayProtection(action.type, x, y) {
                            controller.longPress(x, y)
                        }
                    }
                } else {
                    log("⚠ 无障碍定位失败，使用坐标长按")
                    val (x, y) = calculateClickCoordinates(action, screenWidth, screenHeight)
                    executeWithOverlayProtection(action.type, x, y) {
                        controller.longPress(x, y)
                    }
                }
            }
            "swipe" -> {
                val velocity = settingsManager.settings.value.swipeVelocity
                if (action.direction != null) {
                    val origX = action.x
                    val origY = action.y
                    val centerX = origX?.let { mapCoordinate(it, screenWidth) } ?: (screenWidth / 2)
                    val centerY = origY?.let { mapCoordinate(it, screenHeight) } ?: (screenHeight / 2)
                    val distance = 400

                    log("🎯 方向滑动: ${action.direction}, 中心点: ($centerX, $centerY), 距离: $distance, 力度: $velocity")

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
                    val origX1 = action.x ?: 0
                    val origY1 = action.y ?: 0
                    val origX2 = action.x2 ?: 0
                    val origY2 = action.y2 ?: 0
                    val x1 = mapCoordinate(origX1, screenWidth)
                    val y1 = mapCoordinate(origY1, screenHeight)
                    val x2 = mapCoordinate(origX2, screenWidth)
                    val y2 = mapCoordinate(origY2, screenHeight)

                    log("🎯 坐标滑动:")
                    log("   起点: ($origX1, $origY1) -> ($x1, $y1) [${if (origX1 < 1000 && origY1 < 1000) "归一化" else "像素"}]")
                    log("   终点: ($origX2, $origY2) -> ($x2, $y2) [${if (origX2 < 1000 && origY2 < 1000) "归一化" else "像素"}]")

                    executeWithOverlayProtection(action.type, x1, y1, x2, y2) {
                        controller.swipe(x1, y1, x2, y2)
                    }
                }
            }
            "type" -> {
                // 1. 优先查找可编辑元素
                val editableNode = elementLocator.findFocusedEditable()
                    ?: if (action.x != null && action.y != null) {
                        val origX = action.x ?: 0
                        val origY = action.y ?: 0
                        val x = mapCoordinate(origX, screenWidth)
                        val y = mapCoordinate(origY, screenHeight)
                        elementLocator.findNearestEditable(x, y)
                    } else {
                        null
                    }

                if (editableNode != null) {
                    log("✓ 找到输入框，直接输入")
                    elementLocator.typeInElement(editableNode, action.text ?: "")
                } else {
                    // 2. 降级到坐标点击 + 输入
                    log("⚠ 未找到输入框，使用坐标方式")
                    action.text?.let { controller.type(it) }
                }
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
                    val packageName = appScanner.findPackage(appName)
                    val targetPackage = packageName ?: appName
                    
                    log("找到应用: $appName -> $targetPackage")
                    controller.openApp(targetPackage)
                    
                    // 等待应用启动
                    delay(1500)
                    
                    log("已尝试打开应用: $appName，等待 Reflector 验证")
                    infoPool.lastSummary = "尝试打开应用: $appName"
                }
            }
            "wait" -> {
                val duration = (action.duration ?: 3).coerceIn(1, 10)
                log("等待 ${duration} 秒...")
                delay(duration * 1000L)
            }
            "take_over" -> {
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
     * 尝试无障碍元素定位
     *
     * @param action 动作
     * @param infoPool 信息池
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return 定位结果
     */
    private fun tryAccessibilityLocate(
        action: Action,
        infoPool: InfoPoolImprove,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityElementLocator.ElementLocateResult {
        // 检查是否使用无障碍控制器
        val controllerType = controller.getControllerType()
        if (controllerType != ControllerType.ACCESSIBILITY && controllerType != ControllerType.HYBRID) {
            log("当前控制器类型: $controllerType，跳过无障碍定位")
            return AccessibilityElementLocator.ElementLocateResult(success = false)
        }

        // 检查无障碍服务是否可用
        if (!elementLocator.isServiceAvailable()) {
            log("无障碍服务未连接，跳过无障碍定位")
            return AccessibilityElementLocator.ElementLocateResult(success = false)
        }

        infoPool.accessibilityLocateAttempts++

        // 策略1: 通过目标文本定位
        action.targetText?.let { text ->
            val result = elementLocator.locateByText(text)
            if (result.success) {
                infoPool.accessibilityLocateSuccess++
                infoPool.accessibilityLocateByMethod["text"] = (infoPool.accessibilityLocateByMethod["text"] ?: 0) + 1
                return result
            }
        }

        // 策略2: 通过目标描述定位
        action.targetDesc?.let { desc ->
            val result = elementLocator.locateByDescription(desc)
            if (result.success) {
                infoPool.accessibilityLocateSuccess++
                infoPool.accessibilityLocateByMethod["contentDesc"] = (infoPool.accessibilityLocateByMethod["contentDesc"] ?: 0) + 1
                return result
            }
        }

        // 策略3: 通过资源ID定位
        action.targetResourceId?.let { resourceId ->
            val result = elementLocator.locateByResourceId(resourceId)
            if (result.success) {
                infoPool.accessibilityLocateSuccess++
                infoPool.accessibilityLocateByMethod["resourceId"] = (infoPool.accessibilityLocateByMethod["resourceId"] ?: 0) + 1
                return result
            }
        }

        // 策略4: 在坐标附近查找可点击元素
        if (action.x != null && action.y != null) {
            val origX = action.x ?: 0
            val origY = action.y ?: 0
            val x = mapCoordinate(origX, screenWidth)
            val y = mapCoordinate(origY, screenHeight)

            val result = elementLocator.locateNearestClickable(x, y)
            if (result.success) {
                infoPool.accessibilityLocateSuccess++
                infoPool.accessibilityLocateByMethod["bounds"] = (infoPool.accessibilityLocateByMethod["bounds"] ?: 0) + 1
                return result
            }
        }

        return AccessibilityElementLocator.ElementLocateResult(success = false)
    }

    /**
     * 坐标映射 - 智能判断坐标类型并映射
     *
     * 坐标类型判断逻辑:
     * - 归一化坐标: 0-999 范围，需要映射到实际屏幕尺寸
     * - 像素坐标: >= 1000，直接使用（限制在屏幕范围内）
     *
     * @param value 原始坐标值
     * @param screenMax 屏幕最大尺寸（宽度或高度）
     * @return 映射后的坐标值
     */
    /**
     * 系统诊断 - 检查关键组件状态
     */
    private fun runDiagnostics() {
        log("🔍 系统诊断:")

        // 1. 检查控制器类型和可用性
        val controllerType = controller.getControllerType()
        log("   控制器类型: $controllerType")
        
        // 2. 检查 Shizuku 权限（仅当使用 Shizuku 控制器时）
        if (controllerType == ControllerType.SHIZUKU ||
            controllerType == ControllerType.HYBRID) {
            if (controller is ShizukuController) {
                val privilegeLevel = controller.getShizukuPrivilegeLevel()
                log("   Shizuku 权限: $privilegeLevel")
                when (privilegeLevel) {
                    ShizukuPrivilegeLevel.NONE -> {
                        log("   ❌ 警告: Shizuku 未授权，点击操作可能无效！")
                        log("   请在设置中授予 Shizuku 权限")
                    }
                    ShizukuPrivilegeLevel.ADB -> {
                        log("   ✓ ADB 模式 (UID 2000)")
                        log("   ℹ️ 提示: ADB 模式下某些系统应用可能无法点击")
                    }
                    ShizukuPrivilegeLevel.ROOT -> {
                        log("   ✓ ROOT 模式 (UID 0)")
                        log("   ✓ 完全权限，所有应用均可操作")
                    }
                }
            }
        }

        // 3. 检查服务可用性
        val serviceAvailable = controller.isAvailable()
        log("   服务状态: ${if (serviceAvailable) "✓ 已连接" else "❌ 未连接"}")

        // 4. 测试命令执行（仅对 Shizuku/DeviceController）
        try {
            val execMethod = controller.javaClass.getDeclaredMethod("exec", String::class.java)
            execMethod.isAccessible = true
            val testResult = execMethod.invoke(controller, "echo test") as String
            log("   命令执行: ✓ 正常")
        } catch (e: NoSuchMethodException) {
            // AccessibilityController 没有 exec 方法，这是正常的
            log("   命令执行: ⏭️ 跳过（无障碍服务无需 exec 方法）")
        } catch (e: Exception) {
            log("   命令执行: ❌ 异常 - ${e.message}")
        }

        log("==============================")
    }

    private fun mapCoordinate(value: Int, screenMax: Int): Int {
        if (value <= 0) {
            log("⚠️ 坐标值无效: $value，使用默认值 0")
            return 0
        }

        val isNormalized = value < 1000
        val mappedValue = if (isNormalized) {
            // 归一化坐标映射: 0-999 -> 0-screenMax
            val result = (value * screenMax / 999)
            log("   映射计算: $value * $screenMax / 999 = $result")
            result
        } else {
            // 像素坐标直接使用，限制在屏幕范围内
            val result = value.coerceAtMost(screenMax)
            if (value > screenMax) {
                log("   坐标超限: $value > $screenMax，限制为 $result")
            }
            result
        }

        return mappedValue
    }

    /**
     * 计算点击坐标（统一方法）
     *
     * 坐标类型判断逻辑:
     * - 归一化坐标: 0-999 范围，需要映射到实际屏幕尺寸
     * - 像素坐标: >= 1000，直接使用（限制在屏幕范围内）
     *
     * @param action 操作动作（包含 x, y 坐标）
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return 映射后的坐标对 (x, y)
     */
    private fun calculateClickCoordinates(
        action: Action,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Int, Int> {
        val origX = action.x ?: 0
        val origY = action.y ?: 0

        val x = mapCoordinate(origX, screenWidth)
        val y = mapCoordinate(origY, screenHeight)

        val coordinateType = if (origX < 1000 && origY < 1000) "归一化坐标 (0-999)" else "像素坐标"

        log("🎯 坐标映射:")
        log("   原始坐标: ($origX, $origY)")
        log("   坐标类型: $coordinateType")
        log("   映射后坐标: ($x, $y)")
        log("   坐标范围检查: X∈[0,$screenWidth], Y∈[0,$screenHeight]")

        // 坐标验证
        val validationError = validateCoordinate(x, y, screenWidth, screenHeight)
        if (validationError != null) {
            log("⚠️ 坐标验证警告: $validationError")
        }

        // 检查坐标是否越界
        if (x < 0 || x > screenWidth || y < 0 || y > screenHeight) {
            log("❌ 严重错误: 坐标越界! ($x, $y) 超出屏幕范围 [0,$screenWidth]x[0,$screenHeight]")
            log("   建议检查 VLM 返回的坐标格式是否正确")
        }

        return Pair(x, y)
    }

    /**
     * 验证坐标是否在有效范围内
     * @param x X 坐标
     * @param y Y 坐标
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return 验证结果消息，如果有效返回 null
     */
    private fun validateCoordinate(x: Int, y: Int, screenWidth: Int, screenHeight: Int): String? {
        val issues = mutableListOf<String>()

        if (x < 0) issues.add("X 坐标为负数")
        if (y < 0) issues.add("Y 坐标为负数")
        if (x > screenWidth) issues.add("X 坐标超出屏幕宽度 ($x > $screenWidth)")
        if (y > screenHeight) issues.add("Y 坐标超出屏幕高度 ($y > $screenHeight)")

        // 检查是否在屏幕边缘（可能导致点击无效）
        if (x < 10) issues.add("X 坐标过于靠近左边缘")
        if (y < 10) issues.add("Y 坐标过于靠近上边缘")
        if (x > screenWidth - 10) issues.add("X 坐标过于靠近右边缘")
        if (y > screenHeight - 10) issues.add("Y 坐标过于靠近下边缘")

        return if (issues.isNotEmpty()) issues.joinToString("; ") else null
    }

    /**
     * 等待用户完成手动操作
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
     * 清理资源（任务结束时调用）
     */
    private fun cleanup() {
        // 停止 InterruptOrchestrator 和底层的 AEC 录音监听
        Log.d(TAG, "cleanup: 销毁 InterruptOrchestrator，停止语音监听")
        interruptOrchestrator?.destroy()
        interruptOrchestrator = null
        isPausedForChat = false
        pauseSnapshot = null
    }

    /**
     * 停止执行
     */
    override fun stop() {
        OverlayService.hide(context)
        updateState { copy(isRunning = false) }
        cleanup()
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

    /**
     * 记录优化报告
     */
    private fun logOptimizationReport(infoPool: InfoPoolImprove) {
        log(infoPool.getOptimizationReport())
        
        // 添加 Flow 统计
        if (enableFlowMode) {
            val flowStats = flowIntegration.getSessionStats()
            if (flowStats != null && (flowStats.screenshotsSkipped > 0 || flowStats.vlmCallsSkipped > 0)) {
                log("\n========== Flow 优化统计 ==========")
                log("跳过截图: ${flowStats.screenshotsSkipped} 次")
                log("跳过 VLM: ${flowStats.vlmCallsSkipped} 次")
                log("节省 Token: ~${flowStats.tokensSaved}")
                log("节省时间: ~${flowStats.screenshotsSkipped * 3 + flowStats.vlmCallsSkipped * 3} 秒")
                log("=====================================")
            }
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        _logs.value = _logs.value + message
    }

    /**
     * 获取截图失败的错误消息
     */
    private fun getScreenshotErrorMessage(errorType: ScreenshotErrorType): String {
        return when (errorType) {
            ScreenshotErrorType.SERVICE_NOT_RUNNING ->
                "截图服务未运行，请检查无障碍服务是否开启"
            ScreenshotErrorType.MEDIA_PROJECTION_NULL ->
                "屏幕录制权限未授权或已失效，请重新授权"
            ScreenshotErrorType.IMAGE_READER_NULL ->
                "截图组件初始化失败，请重启应用"
            ScreenshotErrorType.VIRTUAL_DISPLAY_NULL ->
                "虚拟显示未创建，请重启应用"
            ScreenshotErrorType.NO_IMAGE_AVAILABLE ->
                "无法获取屏幕图像，请检查屏幕录制权限"
            ScreenshotErrorType.SENSITIVE_PAGE ->
                "检测到敏感页面，截图被系统阻止"
            ScreenshotErrorType.SHELL_COMMAND_FAILED ->
                "截图命令执行失败，请检查 Shizuku 权限"
            ScreenshotErrorType.FILE_NOT_ACCESSIBLE ->
                "截图文件无法访问，请检查存储权限"
            ScreenshotErrorType.NONE ->
                "截图成功"
            ScreenshotErrorType.UNKNOWN ->
                "截图失败，原因未知"
        }
    }

    /**
     * 打开权限设置页面
     */
    private fun openPermissionSettings() {
        try {
            // 首先尝试打开无障碍服务设置
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            log("已打开无障碍服务设置页面")
        } catch (e: Exception) {
            log("打开设置页面失败: ${e.message}")
            // 如果失败，尝试打开应用详情页
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                log("打开应用详情页也失败: ${e2.message}")
            }
        }
    }

    /**
     * 执行操作时保护悬浮窗
     * 在所有屏幕操作前都隐藏悬浮窗，操作完成后恢复显示
     * 这样可以确保悬浮窗不会挡住任何按钮
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
            "click", "double_tap", "long_press", "swipe", "drag" -> true
            else -> false
        }

        if (shouldHideOverlay) {
            log("执行屏幕操作前隐藏悬浮窗")
            OverlayService.setVisible(false)
            delay(100) // 等待悬浮窗完全隐藏
        }

        try {
            operation()
        } finally {
            if (shouldHideOverlay) {
                delay(800) // 等待操作完成后再显示悬浮窗，避免遮挡导致点击无效
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

    private fun updateState(update: AgentState.() -> AgentState) {
        _state.value = _state.value.update()
    }

    /**
     * 加载历史执行步骤
     */
    override fun loadExecutionSteps(steps: List<ExecutionStep>) {
        updateState {
            copy(
                executionSteps = steps,
                currentStep = if (steps.isNotEmpty()) steps.size else 0
            )
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

    /**
     * 获取对话会话
     */
    override fun getChatSession(): ChatSession? {
        return interruptOrchestrator?.getCurrentChatSession()
    }

    /**
     * 设置聊天消息回调（用于 UI 实时渲染）
     */
    fun setOnChatMessageCallback(callback: ((ChatMessage) -> Unit)?) {
        onChatMessageCallback = callback
        interruptOrchestrator?.setOnMessageCallback(callback)
    }

    /**
     * 设置对话会话（用于从历史记录恢复）
     */
    fun setChatSession(session: ChatSession) {
        interruptOrchestrator?.setChatSession(session)
    }

    // 停止回调
    override var onStopRequested: (() -> Unit)? = null

    // ========== InterruptOrchestrator 集成 ==========

    /**
     * 获取当前聊天会话
     */
    fun getCurrentChatSession(): ChatSession? {
        return interruptOrchestrator?.getCurrentChatSession()
    }

    /**
     * 在 runInstruction 中初始化 InterruptOrchestrator
     */
    private fun initializeInterruptOrchestrator(infoPool: InfoPoolImprove) {
        Log.d(TAG, "========== 语音打断初始化诊断 ==========")
        Log.d(TAG, "enableChatAgent: $enableChatAgent")
        Log.d(TAG, "interruptOrchestrator: ${interruptOrchestrator != null}")
        Log.d(TAG, "vlmClient: ${vlmClient != null}")
        Log.d(TAG, "context: $context")

        // 检查是否启用了 ChatAgent 模式
        if (!enableChatAgent) {
            Log.e(TAG, "❌ ChatAgent 模式未启用！请在设置中开启 enableChatAgent")
            return
        }

        // 销毁旧的 InterruptOrchestrator（如果存在），确保每次任务开始时都是全新状态
        if (interruptOrchestrator != null) {
            Log.d(TAG, "销毁旧的 InterruptOrchestrator...")
            interruptOrchestrator?.destroy()
            interruptOrchestrator = null
        }

        // 获取设置
        val settings = settingsManager.settings.value
        if (settings.apiKey.isEmpty()) {
            Log.e(TAG, "❌ API Key 为空，无法初始化 InterruptOrchestrator")
            return
        }

        // 实例化新的 interruptOrchestrator（使用 AEC 音频管理器）
        if (vlmClient != null) {
            Log.d(TAG, "实例化 InterruptOrchestrator（使用 AEC 音频管理器）...")
            interruptOrchestrator = InterruptOrchestrator(
                context = context,
                vlmClient = vlmClient,
                settingsManager = settingsManager,
                controller = controller
            )
            interruptOrchestrator?.setOnMessageCallback(onChatMessageCallback)
            Log.d(TAG, "✓ InterruptOrchestrator 实例化成功")
        } else {
            Log.e(TAG, "❌ vlmClient 为 null，无法实例化 InterruptOrchestrator")
            return
        }

        Log.d(TAG, "✓ 所有组件已准备就绪，开始初始化...")
        Log.d(TAG, "========================================")

        Log.d(TAG, "ChatAgent 模式已启用，初始化 InterruptOrchestrator")

        Log.d(TAG, "调用 interruptOrchestrator.initialize()...")
        interruptOrchestrator?.initialize(
            infoPool = infoPool,
            onPause = { callback ->
                Log.d(TAG, "🔔 收到暂停回调")
                // 暂停回调：保存快照并调用 callback
                val snapshot = createSnapshot(infoPool)
                pauseSnapshot = snapshot
                isPausedForChat = true
                log("已暂停，进入聊天模式")
                
                // 立即更新 UI（不阻塞 callback）
                CoroutineScope(Dispatchers.Main).launch {
                    OverlayService.showChatMode {
                        Log.d(TAG, "用户点击了问答按钮")
                    }
                    // 立即调用 callback，让它挂起等待
                    // 主执行循环会在下一个检查点检测到 isPausedForChat = true 并进入等待状态
                    callback(snapshot)
                }
            },
            onResume = { callback ->
                Log.d(TAG, "🔔 收到恢复回调")
                // 恢复回调：恢复快照并调用 callback
                // 注意：不恢复 plan，因为重规划后已经有了新计划
                if (pauseSnapshot != null) {
                    restoreSnapshot(infoPool, pauseSnapshot!!, restorePlan = false)
                    pauseSnapshot = null
                }
                isPausedForChat = false
                log("恢复执行")
                CoroutineScope(Dispatchers.Main).launch {
                    // 退出问答模式，恢复正常模式（按钮文字从"问答"变为"停止"）
                    OverlayService.exitChatMode()
                    callback()
                }
            },
            onStop = { callback ->
                Log.d(TAG, "🔔 收到停止回调")
                // 停止回调
                updateState { copy(isRunning = false) }
                OverlayService.hide(context)
                CoroutineScope(Dispatchers.Main).launch {
                    callback()
                }
            },
            onReplan = { newIntent, chatSession, onComplete ->
                Log.d(TAG, "🔔 收到重规划回调: $newIntent")
                // 重规划回调：使用新意图重新规划
                CoroutineScope(Dispatchers.Main).launch {
                    replanWithNewIntent(infoPool, newIntent, chatSession)
                    onComplete()
                }
            }
        )
        Log.d(TAG, "✓ interruptOrchestrator.initialize() 完成")

        // 启动执行（开始监听语音）
        Log.d(TAG, "调用 interruptOrchestrator.startExecution()...")
        interruptOrchestrator?.startExecution()
        Log.d(TAG, "✓ interruptOrchestrator.startExecution() 完成，语音监听已启动")
        Log.d(TAG, "💡 提示：说'暂停'、'等等'、'停'等关键词可以打断执行")
    }

    /**
     * 创建暂停快照
     */
    private fun createSnapshot(infoPool: InfoPoolImprove): InterruptOrchestrator.Snapshot {
        return InterruptOrchestrator.Snapshot(
            step = infoPool.totalSteps,
            plan = infoPool.plan,
            completedPlan = infoPool.completedPlan,
            actionHistory = infoPool.actionHistory.toList(),
            summaryHistory = infoPool.summaryHistory.toList(),
            actionOutcomes = infoPool.actionOutcomes.toList(),
            errorDescriptions = infoPool.errorDescriptions.toList()
        )
    }

    /**
     * 恢复暂停快照
     * @param restorePlan 是否恢复 plan（默认 true，重规划后应传 false）
     */
    private fun restoreSnapshot(infoPool: InfoPoolImprove, snapshot: InterruptOrchestrator.Snapshot, restorePlan: Boolean = true) {
        if (restorePlan) {
            infoPool.plan = snapshot.plan
            infoPool.completedPlan = snapshot.completedPlan
        }
        infoPool.actionHistory.clear()
        infoPool.actionHistory.addAll(snapshot.actionHistory)
        infoPool.summaryHistory.clear()
        infoPool.summaryHistory.addAll(snapshot.summaryHistory)
        infoPool.actionOutcomes.clear()
        infoPool.actionOutcomes.addAll(snapshot.actionOutcomes)
        infoPool.errorDescriptions.clear()
        infoPool.errorDescriptions.addAll(snapshot.errorDescriptions)
    }

    /**
     * 使用新意图重新规划
     */
    private suspend fun replanWithNewIntent(
        infoPool: InfoPoolImprove,
        newIntent: String,
        chatSession: ChatSession
    ) {
        log("开始重新规划，新意图: $newIntent")

        // 生成语义历史
        val semanticHistory = infoPool.summaryHistory.takeLast(10)

        // 设置重规划模式
        infoPool.managerMode = ManagerMode.REPLANNING
        infoPool.newIntent = newIntent

        // 生成重规划 Prompt
        val replanPrompt = manager.getReplanPrompt(infoPool, newIntent, semanticHistory)
        log("重规划 Prompt: $replanPrompt")

        // 调用 VLM
        val response = vlmClient?.predict(replanPrompt, emptyList())
        if (response?.isFailure == true) {
            log("重规划失败: ${response.exceptionOrNull()?.message}")
            return
        }

        val responseText = response?.getOrNull() ?: return
        log("重规划响应: $responseText")

        // 解析响应
        val planResult = manager.parseResponse(responseText)

        // 更新 InfoPool
        infoPool.completedPlan = planResult.completedSubgoal
        infoPool.plan = planResult.plan
        infoPool.lastPlanUpdateStep = infoPool.totalSteps

        // 设置强制更新标志，确保恢复执行时会调用 Manager
        infoPool.forceManagerUpdate = true

        // 更新原始指令（追加新意图）
        infoPool.instruction = "${infoPool.instruction}（调整：${newIntent}）"

        log("重规划完成，新计划: ${planResult.plan.take(100)}...")
        log("已设置 forceManagerUpdate 标志，恢复执行时将调用 Manager")
    }

    /**
     * 检查是否被暂停（用于在执行循环中检查）
     */
    fun isPaused(): Boolean {
        return isPausedForChat
    }

    /**
     * 等待恢复（如果被暂停）
     */
    private suspend fun waitForResume() {
        while (isPausedForChat && _state.value.isRunning) {
            delay(100)
        }
    }

    // ========== 自动沉淀模板 ==========

    /**
     * 自动沉淀流程模板
     * 
     * 触发条件：
     * 1. Flow 模式已启用
     * 2. 当前会话没有匹配到模板（避免重复）
     * 3. 执行步骤数 >= MIN_STEPS_FOR_LEARNING（太短的流程无意义）
     * 4. 成功完成或用户中断（有足够步骤）
     * 
     * @param instruction 用户指令
     * @param success 是否成功完成
     */
    private suspend fun autoSaveTemplate(instruction: String, success: Boolean) {
        if (!enableFlowMode) {
            Log.d(TAG, "Flow 模式未启用，跳过自动沉淀")
            return
        }

        val session = flowIntegration.currentSession
        if (session?.hasTemplate == true) {
            Log.d(TAG, "已匹配现有模板，跳过自动沉淀")
            return
        }

        val executionSteps = _state.value.executionSteps
        val minSteps = com.alian.assistant.core.flow.service.FlowLearner.MIN_EXECUTIONS_FOR_LEARNING

        if (executionSteps.size < minSteps) {
            Log.d(TAG, "执行步骤数 (${executionSteps.size}) < $minSteps，跳过自动沉淀")
            return
        }

        // 计算成功率
        val successCount = executionSteps.count { it.outcome == "A" || it.outcome == "D" }
        val successRate = if (executionSteps.isNotEmpty()) {
            successCount.toFloat() / executionSteps.size
        } else 0f

        // 用户中断时，至少要有 50% 成功率才沉淀
        if (!success && successRate < 0.5f) {
            Log.d(TAG, "用户中断且成功率 ($successRate) < 50%，跳过自动沉淀")
            return
        }

        log("🔄 开始自动沉淀流程模板...")
        log("   步骤数: ${executionSteps.size}, 成功率: ${"%.1f".format(successRate * 100)}%")

        try {
            val newTemplate = flowIntegration.learnFromTraditionalExecution(
                instruction = instruction,
                executionSteps = executionSteps
            )

            if (newTemplate != null) {
                log("✅ 自动沉淀成功: ${newTemplate.name}")
                log("   模板ID: ${newTemplate.id}")
                log("   类别: ${newTemplate.category}")
                log("   步骤数: ${newTemplate.steps.size}")
            } else {
                log("⚠️ 自动沉淀失败：无法生成模板")
            }
        } catch (e: Exception) {
            Log.e(TAG, "自动沉淀异常: ${e.message}", e)
            log("❌ 自动沉淀异常: ${e.message}")
        }
    }
}

// AgentState 和 AgentResult 现在使用从 com.alian.assistant.agent 包导入的原有类
