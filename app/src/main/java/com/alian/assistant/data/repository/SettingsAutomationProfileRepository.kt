package com.alian.assistant.data.repository

import com.alian.assistant.core.agent.config.AutomationProfile
import com.alian.assistant.core.agent.config.policy.ExecutionPolicy
import com.alian.assistant.core.agent.config.policy.FeatureFlags
import com.alian.assistant.core.agent.config.policy.ModelCallPolicy
import com.alian.assistant.core.agent.config.policy.RiskControlPolicy
import com.alian.assistant.core.agent.config.policy.SnapshotPolicy
import com.alian.assistant.core.agent.config.policy.VirtualDisplayPolicy
import com.alian.assistant.data.AppSettings
import com.alian.assistant.data.SettingsManager

/**
 * 基于 SettingsManager 的自动化配置仓储实现。
 *
 * 设计约束：
 * 1. 仅负责映射，不包含业务决策。
 * 2. 迁移期保持与现有行为一致，未暴露配置使用默认值。
 *
 * @property settingsManager 应用设置管理器。
 */
class SettingsAutomationProfileRepository(
    private val settingsManager: SettingsManager,
) : AutomationProfileRepository {

    /**
     * 获取当前生效的自动化配置。
     *
     * 失败行为：
     * - 当设置值异常时通过 `coerceIn` 回落到安全范围，避免运行时崩溃。
     */
    override fun getCurrentProfile(): AutomationProfile {
        return settingsManager.settings.value.toAutomationProfile()
    }
}

/**
 * 将应用设置映射为自动化配置聚合。
 *
 * 设计说明：
 * 1. 映射过程只做值归一化，不做业务流程判断。
 * 2. 未在当前设置中暴露的策略字段使用领域默认值。
 */
private fun AppSettings.toAutomationProfile(): AutomationProfile {
    val normalizedMaxSteps = maxSteps.coerceIn(5, 100)
    val normalizedGestureDelayMs = gestureDelayMs.coerceIn(0, 1_000)
    val normalizedInputDelayMs = inputDelayMs.coerceIn(0, 500)
    val normalizedSwipeVelocity = swipeVelocity.coerceIn(0f, 1f)
    val virtualDisplayEnabled = virtualDisplayExecutionEnabled ||
        executionStrategy.equals("virtual_display", ignoreCase = true)

    return AutomationProfile(
        execution = ExecutionPolicy(
            maxSteps = normalizedMaxSteps,
            gestureDelayMs = normalizedGestureDelayMs,
            inputDelayMs = normalizedInputDelayMs,
            swipeVelocity = normalizedSwipeVelocity,
        ),
        modelCall = ModelCallPolicy(),
        snapshot = SnapshotPolicy(
            cacheEnabled = screenshotCacheEnabled,
        ),
        riskControl = RiskControlPolicy(
            confirmBeforeSensitiveAction = phoneCallConfirmBeforeAction,
            stopOnCaptcha = true,
        ),
        virtualDisplay = VirtualDisplayPolicy(
            enabled = virtualDisplayEnabled,
            autoFallbackEnabled = true,
            prepareTimeoutMs = 8_000L,
        ),
        featureFlags = FeatureFlags(
            screenshotPipelineEnabled = false,
            inAppUpdateEnabled = true,
            virtualDisplayExecutionEnabled = virtualDisplayEnabled,
        ),
    )
}
