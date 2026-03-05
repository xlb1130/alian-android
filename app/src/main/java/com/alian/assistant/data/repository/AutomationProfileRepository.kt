package com.alian.assistant.data.repository

import com.alian.assistant.core.agent.config.AutomationProfile

/**
 * 自动化配置仓储接口。
 *
 * 职责：
 * 1. 向 core 层提供统一的运行时配置聚合。
 * 2. 隔离 SettingsManager 等具体配置来源。
 */
interface AutomationProfileRepository {
    /**
     * 获取当前生效的自动化配置。
     */
    fun getCurrentProfile(): AutomationProfile
}
