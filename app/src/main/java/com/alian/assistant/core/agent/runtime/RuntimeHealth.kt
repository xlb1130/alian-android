package com.alian.assistant.core.agent.runtime

/**
 * 运行时健康状态。
 *
 * 设计约束：
 * 1. 只表达可观测状态，不包含恢复动作。
 * 2. 面向编排层统一前台与虚拟屏诊断语义。
 *
 * @property healthy 当前运行时是否健康可用。
 * @property source 运行时来源标识。
 * @property message 健康检查说明。
 * @property checkedAtMs 检查时间戳（毫秒）。
 */
data class RuntimeHealth(
    val healthy: Boolean,
    val source: RuntimeSource,
    val message: String,
    val checkedAtMs: Long,
)

/**
 * 运行时来源。
 */
enum class RuntimeSource {
    FOREGROUND,
    VIRTUAL_DISPLAY,
}
