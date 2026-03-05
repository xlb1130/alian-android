package com.alian.assistant.data.repository

import com.alian.assistant.core.agent.runtime.service.RuntimeOrchestrator

/**
 * 运行时仓储。
 *
 * 设计约束：
 * 1. 统一运行时编排器实例来源。
 * 2. 允许后续替换为多任务隔离实现。
 */
interface RuntimeRepository {

    /**
     * 获取运行时编排器。
     */
    fun getOrchestrator(): RuntimeOrchestrator
}
