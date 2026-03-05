package com.alian.assistant.core.agent

import com.alian.assistant.data.ExecutionMetricsData
import com.alian.assistant.data.model.ChatSession
import com.alian.assistant.data.ExecutionStep
import kotlinx.coroutines.flow.StateFlow

/**
 * Agent 接口 - 定义所有 Agent 实现的公共接口
 */
interface Agent {
    /**
     * Agent 状态流
     */
    val state: StateFlow<AgentState>

    /**
     * 日志流
     */
    val logs: StateFlow<List<String>>

    /**
     * 停止回调（在任务停止时调用）
     */
    var onStopRequested: (() -> Unit)?

    /**
     * 执行指令
     */
    suspend fun runInstruction(
        instruction: String,
        maxSteps: Int = 25,
        useNotetaker: Boolean = false,
        enableBatchExecution: Boolean = true
    ): AgentResult

    /**
     * 停止执行
     */
    fun stop()

    /**
     * 清空日志
     */
    fun clearLogs()

    /**
     * 加载历史执行步骤
     */
    fun loadExecutionSteps(steps: List<ExecutionStep>)

    /**
     * 加载历史执行记录（包含步骤、指令和答案）
     */
    fun loadExecutionRecord(steps: List<ExecutionStep>, instruction: String, answer: String?)

    /**
     * 获取对话历史（如果存在）
     */
    fun getChatSession(): ChatSession?

    /**
     * 获取执行指标快照（可选能力）。
     *
     * 设计说明：
     * - 默认返回 null，避免影响旧 Agent 实现。
     * - 新实现可直接返回结构化指标，避免上层依赖日志解析。
     */
    fun getExecutionMetricsSnapshot(): ExecutionMetricsData? = null
}
