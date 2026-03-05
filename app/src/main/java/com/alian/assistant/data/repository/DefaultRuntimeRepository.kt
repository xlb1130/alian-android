package com.alian.assistant.data.repository

import com.alian.assistant.core.agent.runtime.service.RuntimeOrchestrator

/**
 * 默认运行时仓储实现。
 */
class DefaultRuntimeRepository(
    private val orchestrator: RuntimeOrchestrator,
) : RuntimeRepository {

    override fun getOrchestrator(): RuntimeOrchestrator = orchestrator
}
