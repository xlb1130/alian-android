package com.alian.assistant.core.agent.runtime.service

import com.alian.assistant.core.agent.config.policy.VirtualDisplayPolicy
import com.alian.assistant.core.agent.runtime.ExecutionRuntime
import com.alian.assistant.core.agent.runtime.RuntimeHealth
import com.alian.assistant.core.agent.runtime.RuntimeSource

/**
 * 运行时编排服务。
 *
 * 设计约束：
 * 1. 负责运行时选择、健康探针与自动降级。
 * 2. 不直接依赖具体设备控制细节，仅编排 `ExecutionRuntime`。
 */
class RuntimeOrchestrator(
    private val policy: VirtualDisplayPolicy,
    private val foregroundRuntime: ExecutionRuntime,
    private val virtualDisplayRuntime: ExecutionRuntime?,
) {

    private var activeRuntime: ExecutionRuntime = foregroundRuntime

    /**
     * 准备并选择运行时。
     *
     * 失败行为：
     * - 虚拟屏不可用且允许降级时自动切换前台运行时。
     */
    suspend fun prepareAndSelect(): Result<ExecutionRuntime> {
        val virtualRuntime = virtualDisplayRuntime

        if (!policy.enabled || virtualRuntime == null) {
            return foregroundRuntime.prepare().map {
                activeRuntime = foregroundRuntime
                activeRuntime
            }
        }

        val virtualPrepared = virtualRuntime.prepare()
        if (virtualPrepared.isFailure) {
            if (!policy.autoFallbackEnabled) {
                activeRuntime = virtualRuntime
                return Result.failure(virtualPrepared.exceptionOrNull() ?: IllegalStateException("虚拟屏准备失败"))
            }
            return fallbackToForeground("虚拟屏准备失败，自动降级")
        }

        val virtualHealth = virtualRuntime.health()
        if (!virtualHealth.healthy) {
            if (!policy.autoFallbackEnabled) {
                activeRuntime = virtualRuntime
                return Result.failure(IllegalStateException("虚拟屏健康检查失败: ${virtualHealth.message}"))
            }
            return fallbackToForeground("虚拟屏探针异常，自动降级")
        }

        activeRuntime = virtualRuntime
        return Result.success(activeRuntime)
    }

    /**
     * 获取当前激活运行时。
     */
    fun getActiveRuntime(): ExecutionRuntime = activeRuntime

    /**
     * 获取当前运行时健康状态。
     */
    suspend fun health(): RuntimeHealth = activeRuntime.health()

    /**
     * 强制降级到前台运行时。
     */
    suspend fun forceFallbackToForeground(reason: String): Result<ExecutionRuntime> {
        return fallbackToForeground(reason)
    }

    /**
     * 关闭当前运行时资源。
     */
    suspend fun close() {
        activeRuntime.close()
        if (activeRuntime.runtimeSource != RuntimeSource.FOREGROUND) {
            foregroundRuntime.close()
        }
    }

    private suspend fun fallbackToForeground(reason: String): Result<ExecutionRuntime> {
        val prepared = foregroundRuntime.prepare()
        if (prepared.isFailure) {
            return Result.failure(prepared.exceptionOrNull() ?: IllegalStateException("前台运行时准备失败: $reason"))
        }

        activeRuntime = foregroundRuntime
        return Result.success(activeRuntime)
    }
}
