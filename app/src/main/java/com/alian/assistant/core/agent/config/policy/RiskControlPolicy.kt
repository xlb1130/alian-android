package com.alian.assistant.core.agent.config.policy

/**
 * 风险控制策略。
 *
 * 设计约束：
 * 1. 对敏感操作（支付/验证码/授权）做统一控制。
 * 2. 与具体 UI 提示实现解耦，仅表达“是否需要确认”的业务语义。
 *
 * @property confirmBeforeSensitiveAction 敏感动作前是否必须确认。
 * @property stopOnCaptcha 检测验证码后是否立即停止自动执行。
 */
data class RiskControlPolicy(
    val confirmBeforeSensitiveAction: Boolean = false,
    val stopOnCaptcha: Boolean = true,
)
