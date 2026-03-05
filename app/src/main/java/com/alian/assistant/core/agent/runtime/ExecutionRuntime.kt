package com.alian.assistant.core.agent.runtime

import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController

/**
 * 执行运行时端口。
 *
 * 目标：统一前台与虚拟屏执行环境，避免业务层分支扩散。
 *
 * 设计约束：
 * 1. 实现类需具备幂等 `prepare` 能力。
 * 2. 所有异常通过 `Result` 返回，不向上抛未处理异常。
 */
interface ExecutionRuntime {

    /**
     * 运行时名称（用于日志与诊断）。
     */
    val runtimeName: String

    /**
     * 运行时来源。
     */
    val runtimeSource: RuntimeSource

    /**
     * 准备运行时。
     */
    suspend fun prepare(): Result<Unit>

    /**
     * 采集快照。
     */
    suspend fun captureSnapshot(): Result<IDeviceController.ScreenshotResult>

    /**
     * 注入动作。
     *
     * @param action 动作定义。
     * @param velocity 可选滑动速度参数，仅对滑动类动作生效。
     */
    suspend fun inject(action: Action, velocity: Float? = null): Result<Unit>

    /**
     * 运行时健康状态。
     */
    suspend fun health(): RuntimeHealth

    /**
     * 释放运行时资源。
     */
    suspend fun close()
}
