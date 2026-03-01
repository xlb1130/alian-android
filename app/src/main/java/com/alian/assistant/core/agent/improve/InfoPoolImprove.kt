package com.alian.assistant.core.agent.improve

import com.alian.assistant.core.agent.memory.Action

/**
 * InfoPool 改进版本 - 添加优化相关字段
 * 
 * 新增功能：
 * - Manager 状态追踪（支持条件性调用）
 * - 置信度追踪（支持 Reflector 跳过）
 * - 优化指标统计
 */
data class InfoPoolImprove(
    // ========== 基础字段（继承自原 InfoPool） ==========
    
    // 用户指令
    var instruction: String = "",

    // 规划相关
    var plan: String = "",
    var completedPlan: String = "",
    var progressStatus: String = "",
    var currentSubgoal: String = "",

    // 动作历史
    val actionHistory: MutableList<Action> = mutableListOf(),
    val summaryHistory: MutableList<String> = mutableListOf(),
    val actionOutcomes: MutableList<String> = mutableListOf(),  // A=成功, B=错误页面, C=无变化, D=假设成功
    val errorDescriptions: MutableList<String> = mutableListOf(),

    // 最近一次动作
    var lastAction: Action? = null,
    var lastActionThought: String = "",
    var lastSummary: String = "",

    // 笔记
    var importantNotes: String = "",

    // 错误处理
    var errorFlagPlan: Boolean = false,
    val errToManagerThresh: Int = 2,

    // 屏幕尺寸
    var screenWidth: Int = 1080,
    var screenHeight: Int = 2400,

    // 额外知识
    var additionalKnowledge: String = "",

    // Skill 上下文
    var skillContext: String = "",

    // 已安装应用列表
    var installedApps: String = "",

    // ========== 优化相关字段（新增） ==========

    // Manager 状态追踪
    var managerMode: ManagerMode = ManagerMode.INITIAL,
    var lastPlanUpdateStep: Int = 0,
    var currentSubgoalIndex: Int = 0,
    var unexpectedScreenState: Boolean = false,
    var forceManagerUpdate: Boolean = false,  // 强制 Manager 更新标志（重规划后使用）

    // Reflector 置信度追踪
    var lastExecutorConfidence: Float = 0.5f,
    var consecutiveReflectorSkips: Int = 0,
    var forceReflector: Boolean = false,

    // 优化指标统计
    var totalSteps: Int = 0,
    var managerCalls: Int = 0,
    var reflectorCalls: Int = 0,
    var managerSkips: Int = 0,
    var reflectorSkips: Int = 0,
    var assumedSuccessCount: Int = 0,
    var actualFailuresAfterAssume: Int = 0,

    // 无障碍定位统计（新增）
    var accessibilityLocateAttempts: Int = 0,      // 无障碍定位尝试次数
    var accessibilityLocateSuccess: Int = 0,       // 无障碍定位成功次数
    var accessibilityLocateByMethod: MutableMap<String, Int> = mutableMapOf(),  // 按方法统计成功次数

    // ========== 聊天相关字段（新增） ==========

    // 聊天历史（用于上下文理解）
    val chatHistory: MutableList<String> = mutableListOf(),

    // 语义历史（用于重规划，压缩版动作历史）
    val semanticHistory: MutableList<String> = mutableListOf(),

    // 新意图（来自用户打断）
    var newIntent: String? = null,

    // 当前会话 ID
    var sessionId: String? = null
)

/**
 * Manager 工作模式
 */
enum class ManagerMode {
    INITIAL,      // 初始规划
    NORMAL,       // 正常执行（跳过 Manager）
    RECOVERY,     // 错误恢复
    REPLANNING    // 重新规划
}

/**
 * 获取 Manager 跳过率
 */
fun InfoPoolImprove.getManagerSkipRate(): Float {
    if (totalSteps == 0) return 0f
    return managerSkips.toFloat() / totalSteps
}

/**
 * 获取 Reflector 跳过率
 */
fun InfoPoolImprove.getReflectorSkipRate(): Float {
    if (totalSteps == 0) return 0f
    return reflectorSkips.toFloat() / totalSteps
}

/**
 * 获取平均每步 VLM 调用次数
 */
fun InfoPoolImprove.getAvgVLMCallsPerStep(): Float {
    if (totalSteps == 0) return 0f
    return (managerCalls + reflectorCalls + totalSteps).toFloat() / totalSteps
}

/**
 * 获取优化效果报告
 */
fun InfoPoolImprove.getOptimizationReport(): String {
    return buildString {
        appendLine("=== 优化指标报告 ===")
        appendLine("总步数: $totalSteps")
        appendLine("Manager 调用: $managerCalls (跳过率: ${(getManagerSkipRate() * 100).toInt()}%)")
        appendLine("Reflector 调用: $reflectorCalls (跳过率: ${(getReflectorSkipRate() * 100).toInt()}%)")
        appendLine("假设成功: $assumedSuccessCount (实际失误: $actualFailuresAfterAssume)")
        appendLine("平均每步 VLM 调用: ${String.format("%.2f", getAvgVLMCallsPerStep())} 次")
        appendLine("当前 Manager 模式: $managerMode")
        appendLine("连续 Reflector 跳过: $consecutiveReflectorSkips")
        
        // 无障碍定位统计
        if (accessibilityLocateAttempts > 0) {
            appendLine("--- 无障碍定位统计 ---")
            appendLine("尝试次数: $accessibilityLocateAttempts")
            appendLine("成功次数: $accessibilityLocateSuccess")
            appendLine("成功率: ${String.format("%.1f", (accessibilityLocateSuccess.toFloat() / accessibilityLocateAttempts * 100))}%")
            if (accessibilityLocateByMethod.isNotEmpty()) {
                appendLine("按方法统计:")
                accessibilityLocateByMethod.forEach { (method, count) ->
                    appendLine("  $method: $count 次")
                }
            }
        }
    }
}