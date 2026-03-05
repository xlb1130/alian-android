package com.alian.assistant.core.agent.config.policy

/**
 * 自动化执行策略。
 *
 * 设计约束：
 * 1. 仅承载执行行为相关参数，不承载 UI 展示状态。
 * 2. 所有字段提供安全默认值，确保迁移期可回退。
 *
 * @property maxSteps 单次任务最大执行步数。
 * @property gestureDelayMs 手势执行后的基础延迟（毫秒）。
 * @property inputDelayMs 输入动作后的基础延迟（毫秒）。
 * @property swipeVelocity 滑动速度系数，取值范围 [0.0, 1.0]。
 */
data class ExecutionPolicy(
    val maxSteps: Int = 25,
    val gestureDelayMs: Int = 100,
    val inputDelayMs: Int = 50,
    val swipeVelocity: Float = 0.5f,
)
