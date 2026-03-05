package com.alian.assistant.core.agent.runtime.service

import com.alian.assistant.core.agent.config.policy.VirtualDisplayPolicy
import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.core.agent.runtime.ExecutionRuntime
import com.alian.assistant.core.agent.runtime.RuntimeHealth
import com.alian.assistant.core.agent.runtime.RuntimeSource
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RuntimeOrchestrator 行为测试。
 */
class RuntimeOrchestratorTest {

    @Test
    fun `use foreground when virtual display disabled`() = runBlocking {
        val foreground = FakeRuntime("foreground", RuntimeSource.FOREGROUND)
        val virtual = FakeRuntime("virtual", RuntimeSource.VIRTUAL_DISPLAY)
        val orchestrator = RuntimeOrchestrator(
            policy = VirtualDisplayPolicy(enabled = false, autoFallbackEnabled = true),
            foregroundRuntime = foreground,
            virtualDisplayRuntime = virtual,
        )

        val result = orchestrator.prepareAndSelect()

        assertTrue(result.isSuccess)
        assertEquals(RuntimeSource.FOREGROUND, orchestrator.getActiveRuntime().runtimeSource)
        assertEquals(1, foreground.prepareCallCount)
        assertEquals(0, virtual.prepareCallCount)
    }

    @Test
    fun `fallback to foreground when virtual prepare failed and fallback enabled`() = runBlocking {
        val foreground = FakeRuntime("foreground", RuntimeSource.FOREGROUND)
        val virtual = FakeRuntime(
            "virtual",
            RuntimeSource.VIRTUAL_DISPLAY,
            prepareResult = Result.failure(IllegalStateException("virtual down")),
        )
        val orchestrator = RuntimeOrchestrator(
            policy = VirtualDisplayPolicy(enabled = true, autoFallbackEnabled = true),
            foregroundRuntime = foreground,
            virtualDisplayRuntime = virtual,
        )

        val result = orchestrator.prepareAndSelect()

        assertTrue(result.isSuccess)
        assertEquals(RuntimeSource.FOREGROUND, orchestrator.getActiveRuntime().runtimeSource)
        assertEquals(1, virtual.prepareCallCount)
        assertEquals(1, foreground.prepareCallCount)
    }

    @Test
    fun `fail when virtual prepare failed and fallback disabled`() = runBlocking {
        val foreground = FakeRuntime("foreground", RuntimeSource.FOREGROUND)
        val virtual = FakeRuntime(
            "virtual",
            RuntimeSource.VIRTUAL_DISPLAY,
            prepareResult = Result.failure(IllegalStateException("virtual down")),
        )
        val orchestrator = RuntimeOrchestrator(
            policy = VirtualDisplayPolicy(enabled = true, autoFallbackEnabled = false),
            foregroundRuntime = foreground,
            virtualDisplayRuntime = virtual,
        )

        val result = orchestrator.prepareAndSelect()

        assertTrue(result.isFailure)
        assertEquals(RuntimeSource.VIRTUAL_DISPLAY, orchestrator.getActiveRuntime().runtimeSource)
        assertEquals(1, virtual.prepareCallCount)
        assertEquals(0, foreground.prepareCallCount)
    }

    @Test
    fun `fallback to foreground when virtual health not healthy`() = runBlocking {
        val foreground = FakeRuntime("foreground", RuntimeSource.FOREGROUND)
        val virtual = FakeRuntime(
            "virtual",
            RuntimeSource.VIRTUAL_DISPLAY,
            healthState = RuntimeHealth(
                healthy = false,
                source = RuntimeSource.VIRTUAL_DISPLAY,
                message = "probe failed",
                checkedAtMs = System.currentTimeMillis(),
            ),
        )
        val orchestrator = RuntimeOrchestrator(
            policy = VirtualDisplayPolicy(enabled = true, autoFallbackEnabled = true),
            foregroundRuntime = foreground,
            virtualDisplayRuntime = virtual,
        )

        val result = orchestrator.prepareAndSelect()

        assertTrue(result.isSuccess)
        assertEquals(RuntimeSource.FOREGROUND, orchestrator.getActiveRuntime().runtimeSource)
        assertEquals(1, virtual.prepareCallCount)
        assertEquals(1, foreground.prepareCallCount)
    }
}

private class FakeRuntime(
    override val runtimeName: String,
    override val runtimeSource: RuntimeSource,
    private val prepareResult: Result<Unit> = Result.success(Unit),
    private val healthState: RuntimeHealth = RuntimeHealth(
        healthy = true,
        source = runtimeSource,
        message = "ok",
        checkedAtMs = System.currentTimeMillis(),
    ),
) : ExecutionRuntime {

    var prepareCallCount: Int = 0

    override suspend fun prepare(): Result<Unit> {
        prepareCallCount += 1
        return prepareResult
    }

    override suspend fun captureSnapshot(): Result<IDeviceController.ScreenshotResult> {
        return Result.failure(UnsupportedOperationException("not used in tests"))
    }

    override suspend fun inject(action: Action, velocity: Float?): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun health(): RuntimeHealth {
        return healthState
    }

    override suspend fun close() {
    }
}
