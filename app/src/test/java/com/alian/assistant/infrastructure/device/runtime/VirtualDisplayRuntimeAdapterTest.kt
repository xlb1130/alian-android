package com.alian.assistant.infrastructure.device.runtime

import com.alian.assistant.core.agent.config.policy.VirtualDisplayPolicy
import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.infrastructure.device.controller.interfaces.ControllerType
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VirtualDisplayRuntimeAdapter 行为测试。
 */
class VirtualDisplayRuntimeAdapterTest {

    @Test
    fun `inject swipe should pass explicit velocity`() = runBlocking {
        val fakeController = FakeVirtualController()
        val adapter = VirtualDisplayRuntimeAdapter(
            controller = fakeController,
            policy = VirtualDisplayPolicy(enabled = true, autoFallbackEnabled = true),
        )

        val result = adapter.inject(
            action = Action(type = "swipe", x = 11, y = 22, x2 = 33, y2 = 44),
            velocity = 0.75f,
        )

        assertTrue(result.isSuccess)
        assertEquals(0.75f, fakeController.lastSwipeVelocity)
    }

    @Test
    fun `inject swipe should use default velocity when null`() = runBlocking {
        val fakeController = FakeVirtualController()
        val adapter = VirtualDisplayRuntimeAdapter(
            controller = fakeController,
            policy = VirtualDisplayPolicy(enabled = true, autoFallbackEnabled = true),
        )

        val result = adapter.inject(
            action = Action(type = "swipe", x = 11, y = 22, x2 = 33, y2 = 44),
            velocity = null,
        )

        assertTrue(result.isSuccess)
        assertEquals(0.5f, fakeController.lastSwipeVelocity)
    }

    @Test
    fun `prepare should fail when policy disabled`() = runBlocking {
        val fakeController = FakeVirtualController()
        val adapter = VirtualDisplayRuntimeAdapter(
            controller = fakeController,
            policy = VirtualDisplayPolicy(enabled = false, autoFallbackEnabled = true),
        )

        val result = adapter.prepare()

        assertTrue(result.isFailure)
    }

    @Test
    fun `prepare should fail when probe fails`() = runBlocking {
        val fakeController = FakeVirtualController(
            throwOnScreenshot = true,
        )
        val adapter = VirtualDisplayRuntimeAdapter(
            controller = fakeController,
            policy = VirtualDisplayPolicy(enabled = true, autoFallbackEnabled = true),
        )

        val result = adapter.prepare()

        assertTrue(result.isFailure)
    }
}

private class FakeVirtualController(
    private val throwOnScreenshot: Boolean = false,
) : IDeviceController {

    var lastSwipeVelocity: Float = -1f

    override fun tap(x: Int, y: Int) = Unit

    override fun longPress(x: Int, y: Int, durationMs: Int) = Unit

    override fun doubleTap(x: Int, y: Int) = Unit

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, velocity: Float) {
        lastSwipeVelocity = velocity
    }

    override fun type(text: String) = Unit

    override fun back() = Unit

    override fun home() = Unit

    override fun enter() = Unit

    override suspend fun screenshot() = null

    override suspend fun screenshotWithFallback(): IDeviceController.ScreenshotResult {
        if (throwOnScreenshot) {
            throw IllegalStateException("probe failed")
        }
        throw UnsupportedOperationException("bitmap unavailable in local unit test")
    }

    override fun getScreenSize(): Pair<Int, Int> = 1080 to 2400

    override fun openApp(appNameOrPackage: String) = Unit

    override fun openDeepLink(uri: String) = Unit

    override fun getCurrentPackage(): String? = "com.example"

    override fun isAvailable(): Boolean = true

    override fun getControllerType(): ControllerType = ControllerType.ACCESSIBILITY

    override fun unbindService() = Unit
}
