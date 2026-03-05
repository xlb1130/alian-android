package com.alian.assistant.infrastructure.device.runtime

import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.infrastructure.device.controller.interfaces.ControllerType
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ForegroundRuntimeAdapter 注入行为测试。
 */
class ForegroundRuntimeAdapterTest {

    @Test
    fun `inject swipe should pass explicit velocity`() = runBlocking {
        val fakeController = FakeDeviceController()
        val adapter = ForegroundRuntimeAdapter(fakeController)

        val result = adapter.inject(
            action = Action(type = "swipe", x = 10, y = 20, x2 = 30, y2 = 40),
            velocity = 0.8f,
        )

        assertTrue(result.isSuccess)
        assertEquals(0.8f, fakeController.lastSwipeVelocity)
    }

    @Test
    fun `inject swipe should use default velocity when null`() = runBlocking {
        val fakeController = FakeDeviceController()
        val adapter = ForegroundRuntimeAdapter(fakeController)

        val result = adapter.inject(
            action = Action(type = "swipe", x = 10, y = 20, x2 = 30, y2 = 40),
            velocity = null,
        )

        assertTrue(result.isSuccess)
        assertEquals(0.5f, fakeController.lastSwipeVelocity)
    }
}

private class FakeDeviceController : IDeviceController {
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
        throw UnsupportedOperationException("not required in this test")
    }

    override fun getScreenSize(): Pair<Int, Int> = 1080 to 2400

    override fun openApp(appNameOrPackage: String) = Unit

    override fun openDeepLink(uri: String) = Unit

    override fun getCurrentPackage(): String? = "com.example"

    override fun isAvailable(): Boolean = true

    override fun getControllerType(): ControllerType = ControllerType.ACCESSIBILITY

    override fun unbindService() = Unit
}
