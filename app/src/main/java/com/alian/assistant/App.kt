package com.alian.assistant

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import com.alian.assistant.common.utils.LanguageManager
import com.alian.assistant.infrastructure.device.AppScanner
import com.alian.assistant.infrastructure.device.DeviceController
import com.alian.assistant.core.skills.SkillManager
import com.alian.assistant.core.tools.ToolManager
import com.alian.assistant.common.utils.CrashHandler
import com.alian.assistant.core.alian.backend.AuthManager
import com.alian.assistant.core.alian.backend.BackendChatClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class App : Application() {

    lateinit var deviceController: DeviceController
        private set
    lateinit var appScanner: AppScanner
        private set
    lateinit var settingsManager: com.alian.assistant.data.SettingsManager
        private set
    // 应用级别的协程作用域
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Token定期刷新任务
    private var tokenRefreshJob: kotlinx.coroutines.Job? = null

    // Token刷新间隔（10分钟）
    private val TOKEN_REFRESH_INTERVAL = 10 * 60 * 1000L

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(LanguageManager.applyLanguage(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化崩溃捕获（本地日志）
        CrashHandler.getInstance().init(this)

        // 初始化 Firebase Crashlytics（根据用户设置决定是否启用）
//        val settingsManager = SettingsManager(this)
//        val cloudCrashReportEnabled = settingsManager.settings.value.cloudCrashReportEnabled
//        FirebaseCrashlytics.getInstance().apply {
//            setCrashlyticsCollectionEnabled(cloudCrashReportEnabled)
//            setCustomKey("app_version", BuildConfig.VERSION_NAME)
//            setCustomKey("device_model", android.os.Build.MODEL)
//            setCustomKey("android_version", android.os.Build.VERSION.SDK_INT.toString())
//            // 发送待上传的崩溃报告
//            sendUnsentReports()
//        }
//        println("[App] 云端崩溃上报: ${if (cloudCrashReportEnabled) "已开启" else "已关闭"}")

        // 初始化 Shizuku
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)

        // 初始化核心组件
        initializeComponents()

        // 应用启动时检查并刷新 Token
        checkAndRefreshToken()
    }

    private fun initializeComponents() {
        // 初始化设备控制器
        deviceController = DeviceController(this)
        deviceController.setCacheDir(cacheDir)

        // 初始化应用扫描器
        appScanner = AppScanner(this)

        // 初始化设置管理器
        settingsManager = com.alian.assistant.data.SettingsManager(this)

        // 初始化 Tools 层
        val toolManager = ToolManager.init(this, deviceController, appScanner)

        // 异步预扫描应用列表（避免 ANR）
        println("[App] 开始异步扫描已安装应用...")
        Thread {
            appScanner.refreshApps()
            println("[App] 已扫描 ${appScanner.getApps().size} 个应用")
        }.start()

        // 初始化 Skills 层（传入 appScanner 用于检测已安装应用）
        val skillManager = SkillManager.init(this, toolManager, appScanner)
        println("[App] SkillManager 已加载 ${skillManager.getAllSkills().size} 个 Skills")

        // 初始化 MCP 层
        val mcpManager = com.alian.assistant.core.mcp.MCPManager.init(this)
        println("[App] MCPManager 已初始化")
        // 异步预初始化 MCP Server 连接
        applicationScope.launch {
            try {
                mcpManager.preInitializeServers()
                println("[App] MCP Servers 预初始化完成")
            } catch (e: Exception) {
                println("[App] MCP Servers 预初始化失败: ${e.message}")
            }
        }
        println("[App] 组件初始化完成")
    }

    /**
     * 检查并刷新 Token
     * 在应用启动时调用，确保用户登录状态有效
     */
    private fun checkAndRefreshToken() {
        applicationScope.launch {
            try {
                val authManager = AuthManager.getInstance(this@App)

                // 检查是否已登录
                if (!authManager.isLoggedIn()) {
                    println("[App] 用户未登录，跳过 Token 检查")
                    return@launch
                }

                println("[App] 检查认证状态...")

                // 创建 BackendChatClient 用于检查认证状态
                val backendChatClient = BackendChatClient(this@App)

                // 先调用 auth/status 检查认证状态
                val isValid = backendChatClient.checkAuthStatus()

                if (isValid) {
                    println("[App] 认证状态有效，启动定期刷新 Token")
                    // 启动定期刷新 Token
                    startTokenRefreshJob()
                } else {
                    println("[App] 认证状态无效，清除登录状态并停止定期刷新")
                    // 认证状态无效，清除登录状态
                    authManager.clearAuth()
                    // 停止定期刷新任务
                    stopTokenRefreshJob()
                    // 提示登录已过期（通过 AlianScreen 的登录状态变化自动跳转到 AlianLocal 页面）
                }
            } catch (e: Exception) {
                println("[App] Token 检查失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 启动定期刷新 Token 任务
     */
    private fun startTokenRefreshJob() {
        // 停止之前的任务（如果有）
        stopTokenRefreshJob()

        tokenRefreshJob = applicationScope.launch {
            while (isActive) {
                try {
                    val authManager = AuthManager.getInstance(this@App)

                    // 检查是否已登录
                    if (!authManager.isLoggedIn()) {
                        println("[App] 用户未登录，停止定期刷新 Token")
                        break
                    }

                    // 检查Token是否即将过期（5分钟内）
                    if (authManager.isTokenExpiringSoon()) {
                        println("[App] Token 即将过期，开始刷新...")

                        // 尝试刷新 Token
                        val newToken = authManager.refreshAccessToken()

                        if (newToken != null) {
                            println("[App] Token 刷新成功")
                        } else {
                            println("[App] Token 刷新失败，清除登录状态并停止定期刷新")
                            // 刷新失败，清除登录状态
                            authManager.clearAuth()
                            // 停止定期刷新任务
                            break
                            // 提示登录已过期（通过 AlianScreen 的登录状态变化自动跳转到 AlianLocal 页面）
                        }
                    } else {
                        println("[App] Token 仍然有效，无需刷新")
                    }

                    // 等待下一次检查
                    delay(TOKEN_REFRESH_INTERVAL)
                } catch (e: Exception) {
                    println("[App] 定期刷新 Token 失败: ${e.message}")
                    e.printStackTrace()
                    // 发生错误后等待一段时间再重试
                    delay(TOKEN_REFRESH_INTERVAL)
                }
            }
        }
    }

    /**
     * 公开方法：启动定期刷新 Token 任务
     * 用于在登录成功后调用
     */
    fun startTokenRefreshJobPublic() {
        startTokenRefreshJob()
    }

    /**
     * 停止定期刷新 Token 任务
     */
    private fun stopTokenRefreshJob() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
    }

    override fun onTerminate() {
        super.onTerminate()
        // 停止定期刷新 Token 任务
        stopTokenRefreshJob()
        // 停止 MCP 保活任务
        if (com.alian.assistant.core.mcp.MCPManager.isInitialized()) {
            com.alian.assistant.core.mcp.MCPManager.getInstance().stopKeepalive()
        }
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }

    /**
     * 动态更新云端崩溃上报开关
     */
    fun updateCloudCrashReportEnabled(enabled: Boolean) {
//        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
//        println("[App] 云端崩溃上报已${if (enabled) "开启" else "关闭"}")
    }

    companion object {
        @Volatile
        private var instance: App? = null

        fun getInstance(): App {
            return instance ?: throw IllegalStateException("App 未初始化")
        }

        private val REQUEST_PERMISSION_RESULT_LISTENER =
            Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                println("[Shizuku] Permission result: $granted")
            }
    }
}
