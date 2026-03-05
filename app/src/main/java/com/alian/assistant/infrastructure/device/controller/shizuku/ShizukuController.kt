package com.alian.assistant.infrastructure.device.controller.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.alian.assistant.IShellService
import com.alian.assistant.infrastructure.device.controller.factory.ShizukuPrivilegeLevel
import com.alian.assistant.infrastructure.device.controller.interfaces.ControllerType
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.device.service.ShellService
import rikka.shizuku.Shizuku

/**
 * Shizuku 设备控制器实现
 * 
 * 通过 Shizuku UserService 执行 shell 命令，实现设备控制功能
 */
class ShizukuController(
    private val context: Context? = null
) : IDeviceController {
    
    private var shellService: IShellService? = null
    private var serviceBound = false
    
    private val screenshotProvider: ShizukuScreenshotProvider by lazy {
        ShizukuScreenshotProvider(context, shellService)
    }
    
    private val appLauncher: ShizukuAppLauncher by lazy {
        ShizukuAppLauncher(context, shellService)
    }
    
    private val inputProvider: ShizukuInputProvider by lazy {
        ShizukuInputProvider(context, shellService)
    }
    
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(
            "com.alian.assistant",
            ShellService::class.java.name
        )
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(true)
        .version(1)
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShellService.Stub.asInterface(service)
            serviceBound = true
            println("[ShizukuController] ShellService connected")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            serviceBound = false
            println("[ShizukuController] ShellService disconnected")
        }
    }
    
    /**
     * 绑定 Shizuku UserService
     */
    fun bindService() {
        if (!isShizukuAvailable()) {
            println("[ShizukuController] Shizuku not available")
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 解绑服务
     */
    override fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 检查 Shizuku 是否可用
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查服务是否可用
     */
    override fun isAvailable(): Boolean {
        return serviceBound && shellService != null
    }
    
    /**
     * 获取当前 Shizuku 权限级别
     * UID 0 = root, UID 2000 = shell (ADB)
     */
    fun getShizukuPrivilegeLevel(): ShizukuPrivilegeLevel {
        if (!isAvailable()) {
            return ShizukuPrivilegeLevel.NONE
        }
        return try {
            val uid = Shizuku.getUid()
            println("[ShizukuController] Shizuku UID: $uid")
            when (uid) {
                0 -> ShizukuPrivilegeLevel.ROOT
                else -> ShizukuPrivilegeLevel.ADB
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ShizukuPrivilegeLevel.NONE
        }
    }
    
    override fun tap(x: Int, y: Int) {
        inputProvider.tap(x, y)
    }
    
    override fun longPress(x: Int, y: Int, durationMs: Int) {
        inputProvider.longPress(x, y, durationMs)
    }
    
    override fun doubleTap(x: Int, y: Int) {
        inputProvider.doubleTap(x, y)
    }
    
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, velocity: Float) {
        inputProvider.swipe(x1, y1, x2, y2, durationMs, velocity)
    }
    
    override fun type(text: String) {
        inputProvider.type(text)
    }
    
    override fun back() {
        inputProvider.back()
    }
    
    override fun home() {
        inputProvider.home()
    }
    
    override fun enter() {
        inputProvider.enter()
    }
    
    override suspend fun screenshot(): android.graphics.Bitmap? {
        return screenshotProvider.screenshot()
    }
    
    override suspend fun screenshotWithFallback(): IDeviceController.ScreenshotResult {
        return screenshotProvider.screenshotWithFallback()
    }
    
    override fun getScreenSize(): Pair<Int, Int> {
        return screenshotProvider.getScreenSize()
    }
    
    override fun openApp(appNameOrPackage: String) {
        appLauncher.openApp(appNameOrPackage)
    }
    
    override fun openDeepLink(uri: String) {
        appLauncher.openDeepLink(uri)
    }
    
    override fun getCurrentPackage(): String? {
        // 使用 dumpsys activity top 获取当前前台应用
        val output = shellService?.exec("dumpsys activity top") ?: return null
        
        // 解析输出，查找 ACTIVITY 的包名
        // 输出格式类似: ACTIVITY com.example.app/.MainActivity 12345678 pid=12345
        val packageNameMatch = Regex("ACTIVITY ([^/]+)/").find(output)
        return packageNameMatch?.groupValues?.get(1)
    }
    
    override fun getControllerType(): ControllerType {
        return ControllerType.SHIZUKU
    }

    /**
     * 执行 shell 命令。
     *
     * 失败行为：
     * - 当 Shizuku 服务不可用时返回错误文本，不抛异常。
     */
    fun execShell(command: String): String {
        return try {
            shellService?.exec(command) ?: "Error: shell service unavailable"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /**
     * 在指定 display 启动应用（后台虚拟屏场景）。
     */
    fun openAppOnDisplay(packageName: String, displayId: Int): Result<Unit> {
        if (packageName.isBlank()) {
            return Result.failure(IllegalArgumentException("packageName is blank"))
        }
        val component = context
            ?.packageManager
            ?.getLaunchIntentForPackage(packageName)
            ?.component
            ?.flattenToShortString()
            ?: return Result.failure(IllegalStateException("无法解析启动组件: $packageName"))

        val output = execShell("am start --display $displayId -n $component")
        if (output.contains("Error", ignoreCase = true) || output.contains("Exception", ignoreCase = true)) {
            return Result.failure(IllegalStateException("display start failed: $output"))
        }
        return Result.success(Unit)
    }

    /**
     * 在指定 display 打开 DeepLink（后台虚拟屏场景）。
     */
    fun openDeepLinkOnDisplay(uri: String, displayId: Int): Result<Unit> {
        if (uri.isBlank()) {
            return Result.failure(IllegalArgumentException("uri is blank"))
        }
        val output = execShell("am start --display $displayId -a android.intent.action.VIEW -d \"$uri\"")
        if (output.contains("Error", ignoreCase = true) || output.contains("Exception", ignoreCase = true)) {
            return Result.failure(IllegalStateException("display deeplink failed: $output"))
        }
        return Result.success(Unit)
    }
}
