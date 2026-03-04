package com.alian.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import com.alian.assistant.core.agent.improve.MobileAgentImprove
import com.alian.assistant.core.agent.MobileAgent
import com.alian.assistant.core.agent.AgentRunner
import com.alian.assistant.core.skills.SkillConfig
import com.alian.assistant.core.skills.SkillRegistry
import com.alian.assistant.data.*
import com.alian.assistant.presentation.ui.screens.settings.CapabilitiesScreen
import com.alian.assistant.presentation.ui.screens.settings.SkillCreatorScreen
import com.alian.assistant.presentation.ui.settings.flowtemplate.FlowTemplateListScreen
import com.alian.assistant.presentation.ui.settings.flowtemplate.FlowTemplateDetailScreen
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.alian.assistant.infrastructure.voice.VoiceRecognitionManager
import com.alian.assistant.infrastructure.ai.llm.VLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import android.util.Log
import com.alian.assistant.infrastructure.device.accessibility.AccessibilityKeepAliveService
import com.alian.assistant.data.model.ChatMessage
import com.alian.assistant.data.model.ChatRole
import com.alian.assistant.data.model.ChatSession
import com.alian.assistant.data.model.InterruptType
import com.alian.assistant.core.agent.Agent
import com.alian.assistant.core.alian.AlianClient
import com.alian.assistant.core.alian.backend.AuthManager
import com.alian.assistant.infrastructure.device.AppScanner
import com.alian.assistant.infrastructure.device.controller.factory.ControllerConfig
import com.alian.assistant.infrastructure.device.controller.factory.DeviceControllerFactory
import com.alian.assistant.infrastructure.device.controller.factory.ExecutionStrategy
import com.alian.assistant.infrastructure.device.controller.factory.FallbackStrategy
import com.alian.assistant.infrastructure.device.controller.factory.ShizukuPrivilegeLevel
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.infrastructure.device.controller.shizuku.ShizukuController
import com.alian.assistant.presentation.ui.screens.local.AlianLocalHistoryScreen
import com.alian.assistant.presentation.ui.screens.local.HistoryDetailScreen
import com.alian.assistant.infrastructure.ai.asr.StreamingVoiceRecognitionManager
import com.alian.assistant.presentation.ui.screens.AlianLocalScreen
import com.alian.assistant.presentation.ui.screens.AlianScreen
import com.alian.assistant.presentation.ui.screens.OnboardingScreen
import com.alian.assistant.presentation.ui.screens.SettingsScreen
import com.alian.assistant.presentation.viewmodel.AlianViewModel
import com.alian.assistant.presentation.ui.screens.settings.VoiceSelectionScreen
import com.alian.assistant.presentation.ui.screens.settings.SpeechProviderSettingsScreen
import com.alian.assistant.data.model.SpeechProvider
import com.alian.assistant.data.model.SpeechProviderCredentials
import com.alian.assistant.data.model.SpeechModels
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.common.utils.AccessibilityUtils
import com.alian.assistant.common.utils.LanguageManager
import com.alian.assistant.common.utils.PermissionManager
import com.alian.assistant.data.UserSkillsManager
import com.alian.assistant.common.utils.PermissionType

private const val TAG = "MainActivity"

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val showInBottomNav: Boolean = true  // 是否显示在底部导航栏
) {
    object Home : Screen("home", "艾莲", Icons.Outlined.Home, Icons.Filled.Home)
    object Alian : Screen("alian", "Alian", Icons.Outlined.Chat, Icons.Filled.Chat)
    object Capabilities : Screen("capabilities", "能力", Icons.Outlined.Star, Icons.Filled.Star)
    object History : Screen("history", "记录", Icons.Outlined.List, Icons.Filled.List)
    object Settings : Screen("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
    object VoiceSelection :
        Screen("voice_selection", "音色选择", Icons.Outlined.Settings, Icons.Filled.Settings, showInBottomNav = false)

    object SkillCreator :
        Screen("skill_creator", "创建技能", Icons.Outlined.Add, Icons.Filled.Add, showInBottomNav = false)

    object SpeechProviderSettings :
        Screen("speech_provider_settings", "语音服务商", Icons.Outlined.Settings, Icons.Filled.Settings, showInBottomNav = false)

    object FlowTemplate :
        Screen("flow_template", "流程模板", Icons.Outlined.AccountTree, Icons.Filled.AccountTree, showInBottomNav = false)

    object FlowTemplateDetail :
        Screen("flow_template_detail", "模板详情", Icons.Outlined.AccountTree, Icons.Filled.AccountTree, showInBottomNav = false)
}

class MainActivity : ComponentActivity() {

    private lateinit var deviceController: IDeviceController
    private lateinit var settingsManager: SettingsManager
    private lateinit var executionRepository: ExecutionRepository
    private lateinit var authManager: AuthManager
    private lateinit var agentRunner: AgentRunner

    private val mobileAgent = mutableStateOf<Agent?>(null)
    private var shizukuAvailable = mutableStateOf(false)

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(LanguageManager.applyLanguage(newBase))
    }


    // 执行记录列表
    private val executionRecords = mutableStateOf<List<ExecutionRecord>>(emptyList())

    // 是否正在执行（点击发送后立即为 true）
    private val isExecuting = mutableStateOf(false)

    // 当前执行的记录 ID（用于停止后跳转）
    private val currentRecordId = mutableStateOf<String?>(null)

    // 是否需要跳转到记录详情（悬浮窗停止后触发）
    private val shouldNavigateToRecord = mutableStateOf(false)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        shizukuAvailable.value = true
        if (checkShizukuPermission()) {
            Log.d(TAG, "Shizuku permission granted, binding service")
            if (deviceController is ShizukuController) {
                (deviceController as ShizukuController).bindService()
            }
        } else {
            Log.d(TAG, "Shizuku permission not granted")
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        shizukuAvailable.value = false
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            Log.d(TAG, "Shizuku permission result: $grantResult")
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                if (deviceController is ShizukuController) {
                    (deviceController as ShizukuController).bindService()
                }
                Toast.makeText(this, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
            }
        }

    // 录音权限请求
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "录音权限被拒绝，语音输入功能将不可用", Toast.LENGTH_LONG).show()
        }
    }

    // 通知权限请求（Android 13+）
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "通知权限被拒绝，后台运行可能更容易被系统回收", Toast.LENGTH_LONG)
                .show()
        }
    }

    // 请求录音权限
    fun requestAudioPermission() {
        if (!PermissionManager.isPermissionGranted(this, PermissionType.RECORD_AUDIO)) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 检查通知权限（用于前台服务常驻通知，防止后台被系统回收）
    // 优化：启动时只检查状态，不主动弹窗申请
    private fun checkNotificationPermission(): Boolean {
        val granted = PermissionManager.isPermissionGranted(
            this,
            PermissionType.POST_NOTIFICATIONS
        )
        if (!granted) {
            Log.d(TAG, "通知权限未授予，后台运行可能更容易被系统回收")
        }
        return granted
    }

    // 请求通知权限（在需要时主动调用）
    private fun requestNotificationPermission() {
        if (!PermissionManager.isPermissionGranted(
                this,
                PermissionType.POST_NOTIFICATIONS
            )
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 相机权限请求
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "相机权限被拒绝，视频通话功能将不可用", Toast.LENGTH_LONG).show()
        }
    }

    // 请求相机权限
    fun requestCameraPermission() {
        if (!PermissionManager.isPermissionGranted(this, PermissionType.CAMERA)) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // MediaProjection 权限请求
    private var mediaProjectionResultCode by mutableStateOf<Int?>(null)
    private var mediaProjectionData by mutableStateOf<Intent?>(null)

    // 待执行的指令（用于权限授予后自动执行）
    private var pendingInstruction: String? = null

    // 显示 MediaProjection 权限引导对话框
    private var showMediaProjectionPermissionDialog by mutableStateOf(false)

    // 显示无障碍服务引导对话框
    private var showAccessibilityPermissionDialog by mutableStateOf(false)

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            mediaProjectionResultCode = result.resultCode
            mediaProjectionData = data
            Toast.makeText(this, "屏幕录制权限已授予", Toast.LENGTH_SHORT).show()
            Log.d(
                TAG,
                "MediaProjection 权限已授予: resultCode=$mediaProjectionResultCode, data=$mediaProjectionData"
            )

            // 设置 AccessibilityKeepAliveService 的 MediaProjection 参数用于保活
            Log.d(TAG, "开始设置 AccessibilityKeepAliveService 的 MediaProjection 参数...")
            Log.d(
                TAG,
                "AccessibilityKeepAliveService.isRunning() = ${AccessibilityKeepAliveService.isRunning()}"
            )
            AccessibilityKeepAliveService.setMediaProjectionParams(
                result.resultCode,
                data
            )
            Log.d(TAG, "AccessibilityKeepAliveService 的 MediaProjection 参数设置完成")
            Log.d(
                TAG,
                "AccessibilityKeepAliveService.getMediaProjection() = ${AccessibilityKeepAliveService.getMediaProjection()}"
            )

            // 重新创建设备控制器，传入 MediaProjection 权限
            recreateDeviceControllerWithMediaProjection()

            // 如果有待执行的指令，自动执行
            pendingInstruction?.let { instruction ->
                Log.d(TAG, "权限授予后自动执行指令: $instruction")
                pendingInstruction = null
                // 重新调用 runAgent，此时权限已授予
                val settings = settingsManager.settings.value
                runAgent(
                    instruction = instruction,
                    apiKey = settings.apiKey,
                    baseUrl = settings.baseUrl,
                    model = settings.model,
                    maxSteps = settings.maxSteps,
                    isGUIAgent = settings.currentProvider.isGUIAgent,
                    providerId = settings.currentProviderId,
                    ttsEnabled = settings.ttsEnabled,
                    ttsVoice = settings.ttsVoice
                )
            }
        } else {
            Toast.makeText(this, "屏幕录制权限被拒绝，无障碍截图功能将不可用", Toast.LENGTH_LONG)
                .show()
            Log.w(TAG, "MediaProjection 权限被拒绝")
            // 清除待执行的指令
            pendingInstruction = null
        }
    }

    // 请求 MediaProjection 权限
    fun requestMediaProjectionPermission() {
        val intent = PermissionManager.createMediaProjectionRequestIntent(this)
        mediaProjectionLauncher.launch(intent)
    }

    // 使用 MediaProjection 权限重新创建设备控制器
    private fun recreateDeviceControllerWithMediaProjection() {
        Log.d(TAG, "========== recreateDeviceControllerWithMediaProjection ==========")
        Log.d(
            TAG,
            "当前权限状态: resultCode=$mediaProjectionResultCode, data=${if (mediaProjectionData != null) "available" else "null"}"
        )

        val settings = settingsManager.settings.value
        val executionStrategy = when (settings.executionStrategy) {
            "shizuku_only" -> ExecutionStrategy.SHIZUKU_ONLY
            "accessibility_only" -> ExecutionStrategy.ACCESSIBILITY_ONLY
            "hybrid" -> ExecutionStrategy.HYBRID
            else -> ExecutionStrategy.AUTO
        }
        val fallbackStrategy = when (settings.fallbackStrategy) {
            "shizuku_first" -> FallbackStrategy.SHIZUKU_FIRST
            "accessibility_first" -> FallbackStrategy.ACCESSIBILITY_FIRST
            else -> FallbackStrategy.AUTO
        }

        // 创建新的 ControllerConfig，包含 MediaProjection 权限
        val controllerConfig = ControllerConfig(
            shizukuEnabled = executionStrategy == ExecutionStrategy.SHIZUKU_ONLY ||
                    executionStrategy == ExecutionStrategy.HYBRID ||
                    executionStrategy == ExecutionStrategy.AUTO,
            shizukuPrivilegeLevel = ShizukuPrivilegeLevel.ADB,
            accessibilityEnabled = executionStrategy == ExecutionStrategy.ACCESSIBILITY_ONLY ||
                    executionStrategy == ExecutionStrategy.HYBRID ||
                    executionStrategy == ExecutionStrategy.AUTO,
            mediaProjectionEnabled = true,
            mediaProjectionResultCode = mediaProjectionResultCode,
            mediaProjectionData = mediaProjectionData,
            fallbackEnabled = true,
            fallbackStrategy = fallbackStrategy,
            screenshotCacheEnabled = settings.screenshotCacheEnabled,
            gestureDelayMs = settings.gestureDelayMs,
            inputDelayMs = settings.inputDelayMs
        )

        // 解绑旧的服务
        if (deviceController is ShizukuController) {
            (deviceController as ShizukuController).unbindService()
        }

        // 设置 AccessibilityKeepAliveService 的 MediaProjection 参数用于保活（在创建控制器之前）
        if (mediaProjectionResultCode != null && mediaProjectionData != null) {
            AccessibilityKeepAliveService.setMediaProjectionParams(
                mediaProjectionResultCode!!,
                mediaProjectionData!!
            )
        }

        // 创建新的设备控制器
        deviceController = DeviceControllerFactory.createController(this, controllerConfig)

        // 如果是 ShizukuController，需要绑定服务
        if (deviceController is ShizukuController) {
            (deviceController as ShizukuController).bindService()
        }

        Log.d(TAG, "设备控制器已重新创建，包含 MediaProjection 权限")
    }

    // 语音识别管理器
    private lateinit var voiceRecognitionManager: VoiceRecognitionManager

    // 流式语音识别管理器
    private var streamingVoiceRecognitionManager: StreamingVoiceRecognitionManager? = null

    // Alian客户端（用于登出）
    private var alianClient: AlianClient? = null

    // AlianViewModel（用于在 MainActivity 层级管理，避免切换 tab 时重新创建）
    private lateinit var alianViewModel: AlianViewModel

    // 初始化语音识别管理器
    private fun initVoiceRecognition() {
        voiceRecognitionManager = VoiceRecognitionManager(this)

        // 初始化 AlianViewModel
        alianViewModel = AlianViewModel(this)

        // 初始化千问语音识别客户端
        lifecycleScope.launch {
            // 等待设置加载完成
            settingsManager.settings.collect { settings ->
                Log.d(
                    "MainActivity",
                    "initVoiceRecognition: apiKey=${settings.apiKey}, speechModel=${settings.speechModel}"
                )
                if (settings.apiKey.isNotEmpty()) {
                    voiceRecognitionManager.initializeQwenSpeechClient(
                        settings.apiKey,
                        settings.speechModel,
                        16000,      // sample rate
                        "pcm",      // audio format
                        true,       // vad enabled
                        true        // punctuation enabled
                    )

                    // 初始化流式语音识别管理器
                    streamingVoiceRecognitionManager = StreamingVoiceRecognitionManager(
                        context = this@MainActivity,
                        apiKey = settings.apiKey,
                        model = settings.speechModel
                    )
                    Log.d("MainActivity", "StreamingVoiceRecognitionManager 初始化成功")
                } else {
                    Log.w("MainActivity", "apiKey 为空，无法初始化 StreamingVoiceRecognitionManager")
                    Toast.makeText(
                        this@MainActivity,
                        "请先设置 API Key 以使用语音功能",
                        Toast.LENGTH_LONG
                    ).show()
                }

                // 初始化AlianClient
                alianClient = if (settings.useBackend) {
                    AlianClient(
                        context = this@MainActivity,
                        useBackend = true,
                        baseUrl = settings.backendBaseUrl.ifBlank { "http://39.98.113.244:5173/api/v1" }
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // 设置边到边显示，深色状态栏和导航栏
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        settingsManager = SettingsManager(this)

        // 从Settings读取设备控制器配置
        val settings = settingsManager.settings.value
        android.util.Log.d(
            TAG,
            "onCreate - executionStrategy from settings: ${settings.executionStrategy}"
        )
        val executionStrategy = when (settings.executionStrategy) {
            "shizuku_only" -> ExecutionStrategy.SHIZUKU_ONLY
            "accessibility_only" -> ExecutionStrategy.ACCESSIBILITY_ONLY
            "hybrid" -> ExecutionStrategy.HYBRID
            else -> ExecutionStrategy.AUTO
        }
        val fallbackStrategy = when (settings.fallbackStrategy) {
            "shizuku_first" -> FallbackStrategy.SHIZUKU_FIRST
            "accessibility_first" -> FallbackStrategy.ACCESSIBILITY_FIRST
            else -> FallbackStrategy.AUTO
        }

        // 使用工厂创建设备控制器
        val controllerConfig = ControllerConfig(
            shizukuEnabled = executionStrategy == ExecutionStrategy.SHIZUKU_ONLY ||
                    executionStrategy == ExecutionStrategy.HYBRID ||
                    executionStrategy == ExecutionStrategy.AUTO,
            shizukuPrivilegeLevel = ShizukuPrivilegeLevel.ADB,
            accessibilityEnabled = executionStrategy == ExecutionStrategy.ACCESSIBILITY_ONLY ||
                    executionStrategy == ExecutionStrategy.HYBRID ||
                    executionStrategy == ExecutionStrategy.AUTO,
            mediaProjectionEnabled = true,
            mediaProjectionResultCode = mediaProjectionResultCode,
            mediaProjectionData = mediaProjectionData,
            fallbackEnabled = true,
            fallbackStrategy = fallbackStrategy,
            screenshotCacheEnabled = settings.screenshotCacheEnabled,
            gestureDelayMs = settings.gestureDelayMs,
            inputDelayMs = settings.inputDelayMs
        )

        // 启动 AccessibilityKeepAliveService 用于保活
        AccessibilityKeepAliveService.start(this)

        // 注册 MediaProjection 失效回调，当系统停止 MediaProjection 时更新授权状态
        AccessibilityKeepAliveService.setOnMediaProjectionRevokedListener(object :
            AccessibilityKeepAliveService.Companion.OnMediaProjectionRevokedListener {
            override fun onMediaProjectionRevoked() {
                Log.w(TAG, "⚠️ MediaProjection 被系统停止，清除授权状态")
                runOnUiThread {
                    mediaProjectionResultCode = null
                    mediaProjectionData = null
                    Toast.makeText(
                        this@MainActivity,
                        "屏幕录制权限已失效，请重新授权",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        // 设置 AccessibilityKeepAliveService 的 MediaProjection 参数用于保活（在创建控制器之前）
        if (mediaProjectionResultCode != null && mediaProjectionData != null) {
            AccessibilityKeepAliveService.setMediaProjectionParams(
                mediaProjectionResultCode!!,
                mediaProjectionData!!
            )
        }

        deviceController = DeviceControllerFactory.createController(this, controllerConfig)

        // 如果是 ShizukuController，需要绑定服务
        if (deviceController is ShizukuController) {
            (deviceController as ShizukuController).bindService()
        }
        executionRepository = ExecutionRepository(this)
        authManager = AuthManager.getInstance(this)
        agentRunner = AgentRunner(
            context = this,
            settingsManager = settingsManager,
            deviceControllerProvider = { deviceController },
            createVLMClient = { apiKey, baseUrl, model -> createVLMClient(apiKey, baseUrl, model) },
            executionRepository = executionRepository,
            scope = lifecycleScope,
            mobileAgentState = mobileAgent,
            executionRecords = executionRecords,
            isExecuting = isExecuting,
            currentRecordId = currentRecordId,
            shouldNavigateToRecord = shouldNavigateToRecord,
            getMediaProjectionInfo = { mediaProjectionResultCode to mediaProjectionData },
            onRequireAccessibility = { instruction ->
                showAccessibilityPermissionDialog = true
                pendingInstruction = instruction
            },
            onRequireMediaProjection = { instruction ->
                showMediaProjectionPermissionDialog = true
                pendingInstruction = instruction
            }
        )

        // 加载执行记录
        lifecycleScope.launch {
            executionRecords.value = executionRepository.getAllRecords()
        }

        // 添加 Shizuku 监听器
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        // 检查 Shizuku 状态
        checkAndUpdateShizukuStatus()

        // 预加载已安装应用
        lifecycleScope.launch(Dispatchers.IO) {
            AppScanner(this@MainActivity).getApps()
        }

        // 初始化语音识别管理器
        initVoiceRecognition()

        // 检查通知权限状态（不主动申请，启动时只静默检查）
        checkNotificationPermission()

        setContent {
            val settings by settingsManager.settings.collectAsState()
            com.alian.assistant.presentation.ui.theme.BaoziTheme(themeMode = settings.themeMode) {
                val colors = BaoziTheme.colors
                // 动态更新系统栏颜色
                SideEffect {
                    val window = this@MainActivity.window
                    window.statusBarColor = colors.background.toArgb()
                    window.navigationBarColor = colors.background.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !colors.isDark
                        isAppearanceLightNavigationBars = !colors.isDark
                    }
                }

                // 首次启动显示引导画面
                if (!settings.hasSeenOnboarding) {
                    OnboardingScreen(
                        onComplete = {
                            settingsManager.setOnboardingSeen()
                        }
                    )
                } else {
                    MainApp()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainApp() {
        var currentScreen by remember { mutableStateOf<Screen>(Screen.Alian) }
        var selectedRecord by remember { mutableStateOf<ExecutionRecord?>(null) }
        var currentChatHistory by remember {
            mutableStateOf<List<com.alian.assistant.data.ChatMessageData>>(
                emptyList()
            )
        }
        var showShizukuHelpDialog by remember { mutableStateOf(false) }
        var hasShownShizukuHelp by remember { mutableStateOf(false) }
        var openDrawer by remember { mutableStateOf(false) }  // 控制抽屉是否打开

        val settings by settingsManager.settings.collectAsState()
        val colors = BaoziTheme.colors
        val agent = mobileAgent.value
        val agentState by agent?.state?.collectAsState() ?: remember { mutableStateOf(null) }
        val logs by agent?.logs?.collectAsState()
            ?: remember { mutableStateOf(emptyList<String>()) }
        val records by remember { executionRecords }
        val isShizukuAvailable = shizukuAvailable.value && checkShizukuPermission()
        val executing by remember { isExecuting }
        val navigateToRecord by remember { shouldNavigateToRecord }
        val recordId by remember { currentRecordId }

        // 监听聊天消息，实时追加到历史列表（用于语音打断消息渲染）
        DisposableEffect(agent) {
            val improveAgent = agent as? MobileAgentImprove
            if (improveAgent == null) {
                onDispose { }
            } else {
                val callback: (ChatMessage) -> Unit = { message ->
                    runOnUiThread {
                        val msgData = ChatMessageData(
                            id = message.id,
                            role = message.role.name,
                            content = message.content,
                            timestamp = message.timestamp,
                            interruptType = message.interruptType?.name,
                            newIntent = message.newIntent
                        )
                        if (currentChatHistory.none { it.id == msgData.id }) {
                            currentChatHistory = currentChatHistory + msgData
                        }
                    }
                }
                improveAgent.setOnChatMessageCallback(callback)
                onDispose {
                    improveAgent.setOnChatMessageCallback(null)
                }
            }
        }

        // 控制底部导航栏的显示
        var hideBottomBar by remember { mutableStateOf(false) }

        // 控制是否在 AlianScreen 中显示登录页面
        var showAlianLoginScreen by remember { mutableStateOf(false) }

        // 控制是否在 AlianScreen 中强制显示 Local 界面
        var forceShowLocal by remember { mutableStateOf(false) }

        // 控制要编辑的 Skill（null 表示创建新 Skill）
        var editingSkillConfig by remember { mutableStateOf<SkillConfig?>(null) }

        // 控制要查看的流程模板 ID（null 表示在列表页）
        var selectedFlowTemplateId by remember { mutableStateOf<String?>(null) }

        // 监听跳转事件
        LaunchedEffect(navigateToRecord, recordId) {
            if (navigateToRecord && recordId != null) {
                // 找到对应的记录并跳转
                val record = records.find { it.id == recordId }
                if (record != null) {
                    selectedRecord = record
                    currentScreen = Screen.History
                }
                shouldNavigateToRecord.value = false
            }
        }

        // 首次进入时，根据执行策略显示相应的引导（只显示一次）
        LaunchedEffect(Unit) {
            if (settings.hasSeenOnboarding && !hasShownShizukuHelp) {
                hasShownShizukuHelp = true

                // 使用 PermissionManager 获取执行提示信息
                val promptInfo = PermissionManager.getExecutionPromptInfo(
                    context = this@MainActivity,
                    executionStrategy = settings.executionStrategy,
                    shizukuAvailable = isShizukuAvailable,
                    accessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(this@MainActivity),
                    mediaProjectionAvailable = mediaProjectionResultCode != null && mediaProjectionData != null
                )

                // 根据提示信息显示相应的引导
                when {
                    !isShizukuAvailable && PermissionManager.needsShizukuPermission(settings.executionStrategy) -> {
                        showShizukuHelpDialog = true
                    }

                    else -> {
                        // 其他情况不显示引导
                    }
                }
            }
        }

        // 监听登录状态变化，登录成功后关闭登录页面
        LaunchedEffect(alianViewModel.isLoggedIn.value) {
            if (alianViewModel.isLoggedIn.value && showAlianLoginScreen) {
                showAlianLoginScreen = false
            }
        }

        // 监听页面切换，当离开 Alian 页面时重置登录页面状态
        LaunchedEffect(currentScreen) {
            if (currentScreen != Screen.Alian) {
                showAlianLoginScreen = false
                // 离开 AlianLocal 页面时，清除权限对话框状态
                if (showMediaProjectionPermissionDialog) {
                    showMediaProjectionPermissionDialog = false
                    pendingInstruction = null
                }
                if (showAccessibilityPermissionDialog) {
                    showAccessibilityPermissionDialog = false
                    pendingInstruction = null
                }
            }
        }

        Scaffold(
            modifier = Modifier.background(colors.background),
            containerColor = colors.background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 处理系统返回手势
                BackHandler(enabled = selectedRecord != null) {
                    performLightHaptic()
                    selectedRecord = null
                }

                // 详情页优先显示
                if (selectedRecord != null) {
                    HistoryDetailScreen(
                        record = selectedRecord!!,
                        onBack = {
                            selectedRecord = null
                            currentChatHistory = emptyList()
                            openDrawer = true  // 打开抽屉
                        },
                        onContinueAsk = { record ->
                            Log.d(
                                TAG,
                                "继续追问被点击 - 记录ID: ${record.id}, 步骤数量: ${record.steps.size}"
                            )

                            // 确保创建 MobileAgent 实例（如果还没有的话）
                            if (mobileAgent.value == null) {
                                val vlmClient = createVLMClient()
                                val settings = settingsManager.settings.value

                                if (settings.enableImproveMode) {
                                    mobileAgent.value = MobileAgentImprove(
                                        vlmClient,
                                        deviceController,
                                        this@MainActivity,
                                        settings.reactOnly,
                                        settings.enableChatAgent,
                                        settings.enableFlowMode
                                    )
                                } else {
                                    mobileAgent.value =
                                        MobileAgent(vlmClient, deviceController, this@MainActivity)
                                }
                            }

                            // 加载历史记录的执行步骤到 MobileAgent
                            mobileAgent.value?.loadExecutionRecord(
                                record.steps,
                                record.instruction,
                                record.resultMessage
                            )
                            Log.d(
                                TAG,
                                "历史记录已加载到 MobileAgent，当前步骤数: ${mobileAgent.value?.state?.value?.executionSteps?.size}"
                            )

                            // 加载对话历史（如果有）
                            Log.d(
                                TAG,
                                "检查对话历史 - record.chatHistory 数量: ${record.chatHistory.size}"
                            )
                            record.chatHistory.forEachIndexed { index, msg ->
                                Log.d(
                                    TAG,
                                    "  ChatMessage[$index]: role=${msg.role}, content=${
                                        msg.content.take(50)
                                    }"
                                )
                            }

                            if (record.chatHistory.isNotEmpty()) {
                                currentChatHistory = record.chatHistory
                                val chatSession = ChatSession(
                                    id = record.id,
                                    originalInstruction = record.instruction,
                                    createdAt = record.startTime,
                                    updatedAt = record.endTime,
                                    messages = record.chatHistory.map { msgData ->
                                        ChatMessage(
                                            id = msgData.id,
                                            role = when (msgData.role) {
                                                "USER" -> ChatRole.USER
                                                "ASSISTANT" -> ChatRole.ASSISTANT
                                                "SYSTEM" -> ChatRole.SYSTEM
                                                else -> ChatRole.USER
                                            },
                                            content = msgData.content,
                                            timestamp = msgData.timestamp,
                                            interruptType = msgData.interruptType?.let { type ->
                                                when (type) {
                                                    "QA" -> InterruptType.QA
                                                    "NEW_INTENT" -> InterruptType.NEW_INTENT
                                                    "TAKE_OVER" -> InterruptType.TAKE_OVER
                                                    "CONFIRM" -> InterruptType.CONFIRM
                                                    "STOP" -> InterruptType.STOP
                                                    else -> null
                                                }
                                            },
                                            newIntent = msgData.newIntent
                                        )
                                    }.toMutableList()
                                )

                                // 设置对话会话到 MobileAgent
                                when (val agent = mobileAgent.value) {
                                    is MobileAgentImprove -> agent.setChatSession(chatSession)
                                    is MobileAgent -> agent.setChatSession(chatSession)
                                    else -> Log.w(TAG, "未知的 Agent 类型，无法设置对话会话")
                                }

                                Log.d(
                                    TAG,
                                    "对话历史已加载到 MobileAgent，消息数: ${chatSession.messages.size}"
                                )
                            } else {
                                Log.d(TAG, "对话历史为空，跳过加载")
                                currentChatHistory = emptyList()
                            }

                            // 返回首页，用户可以继续交互
                            selectedRecord = null
                            openDrawer = true  // 打开抽屉
                            // 显示提示
                            Toast.makeText(
                                this@MainActivity,
                                "已加载 ${record.steps.size} 个历史步骤，您可以继续提问",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                } else {
                    // 主页面切换
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "screen"
                    ) { screen ->
                        when (screen) {
                            Screen.Home -> {
                                // 每次进入首页都检测 Shizuku 状态
                                LaunchedEffect(Unit) {
                                    checkAndUpdateShizukuStatus()
                                }
                                // 添加日志用于调试
                                val currentExecutionStrategy = settings.executionStrategy
                                val currentAccessibilityEnabled =
                                    AccessibilityUtils.isAccessibilityServiceEnabled(this@MainActivity)
                                val currentMediaProjectionAvailable =
                                    mediaProjectionResultCode != null && mediaProjectionData != null
                                android.util.Log.d(
                                    "MainActivity",
                                    "AlianLocalScreen - executionStrategy: $currentExecutionStrategy, accessibilityEnabled: $currentAccessibilityEnabled, mediaProjectionAvailable: $currentMediaProjectionAvailable"
                                )

                                AlianLocalScreen(
                                    agentState = agentState,
                                    logs = logs,
                                    chatHistory = currentChatHistory,
                                    apiKey = settings.apiKey,
                                    baseUrl = settings.baseUrl,
                                    voiceCallSystemPrompt = settings.voiceCallSystemPrompt,
                                    videoCallSystemPrompt = settings.videoCallSystemPrompt,
                                    ttsVoice = settings.ttsVoice,
                                    ttsSpeed = settings.ttsSpeed,
                                    ttsInterruptEnabled = settings.ttsInterruptEnabled,
                                    enableAEC = settings.ttsInterruptEnabled,
                                    enableStreaming = settings.enableStreaming,
                                    volume = settings.volume,
                                    executionStrategy = currentExecutionStrategy,
                                    accessibilityEnabled = currentAccessibilityEnabled,
                                    mediaProjectionAvailable = currentMediaProjectionAvailable,
                                    onExecute = { instruction ->
                                        runAgent(
                                            instruction = instruction,
                                            apiKey = settings.apiKey,
                                            baseUrl = settings.baseUrl,
                                            model = settings.model,
                                            maxSteps = settings.maxSteps,
                                            isGUIAgent = settings.currentProvider.isGUIAgent,
                                            providerId = settings.currentProviderId,
                                            ttsEnabled = settings.ttsEnabled,
                                            ttsVoice = settings.ttsVoice
                                        )
                                    },
                                    onStop = {
                                        mobileAgent.value?.stop()
                                    },
                                    shizukuAvailable = isShizukuAvailable,
                                    currentModel = settings.model,
                                    onRefreshShizuku = { refreshShizukuStatus() },
                                    onShizukuRequired = { showShizukuHelpDialog = true },
                                    onRequestMediaProjectionPermission = { requestMediaProjectionPermission() },
                                    isMediaProjectionAvailable = { mediaProjectionResultCode != null && mediaProjectionData != null },
                                    isExecuting = executing,
                                    onVoiceInput = { recognizedText ->
                                        runOnUiThread {
                                            if (recognizedText == "#REQUEST_AUDIO_PERMISSION") {
                                                // 请求录音权限
                                                requestAudioPermission()
                                            } else if (recognizedText.startsWith("#ERROR_VOICE_RECOGNITION_FAILED:")) {
                                                // 处理语音识别失败
                                                val errorMessage =
                                                    recognizedText.substring("#ERROR_VOICE_RECOGNITION_FAILED:".length)
                                                        .trim()
                                                // Toast.makeText(this, "语音识别失败: $errorMessage", Toast.LENGTH_LONG).show()
                                            } else if (recognizedText.startsWith("#INFO_NO_VOICE_RECOGNIZED")) {
                                                // 处理未识别到语音的情况
                                                //Toast.makeText(this, "未识别到语音内容，请重试", Toast.LENGTH_SHORT).show()
                                            } else if (recognizedText.isNotEmpty()) {
                                                // 执行语音识别
                                                runAgent(
                                                    instruction = recognizedText,
                                                    apiKey = settings.apiKey,
                                                    baseUrl = settings.baseUrl,
                                                    model = settings.model,
                                                    maxSteps = settings.maxSteps,
                                                    isGUIAgent = settings.currentProvider.isGUIAgent,
                                                    providerId = settings.currentProviderId,
                                                    ttsEnabled = settings.ttsEnabled,
                                                    ttsVoice = settings.ttsVoice
                                                )
                                            }
                                        }
                                    },
                                    voiceRecognitionManager = voiceRecognitionManager,
                                    streamingVoiceRecognitionManager = streamingVoiceRecognitionManager,
                                    executionRecords = records,
                                    onRecordClick = { record: ExecutionRecord ->
                                        selectedRecord = record
                                    },
                                    onDeleteRecord = { record ->
                                        lifecycleScope.launch {
                                            executionRepository.deleteRecord(record.id)
                                            executionRecords.value =
                                                executionRepository.getAllRecords()
                                        }
                                    },
                                    onLogin = {
                                        // 从本地界面打开登录页面
                                        currentScreen = Screen.Alian
                                        showAlianLoginScreen = true
                                    },
                                    onNavigateToSettings = {
                                        currentScreen = Screen.Settings
                                    },
                                    openDrawer = openDrawer,
                                    onDrawerStateChanged = { isOpen ->
                                        openDrawer = isOpen
                                    },
                                    isLoggedIn = alianViewModel.isLoggedIn.value,
                                    useBackend = settings.useBackend,
                                    onVideoCall = {
                                        // 视频通话功能
                                    },
                                    onVoiceCall = {
                                        // 语音通话功能
                                    },
                                    onCreateNewSession = {
                                        alianViewModel.createNewSession()
                                    },
                                    onLogout = {
                                        alianViewModel.logout()
                                    },
                                    onRequireMediaProjection = { requestMediaProjectionPermission() },
                                    mediaProjectionResultCode = mediaProjectionResultCode,
                                    mediaProjectionData = mediaProjectionData
                                )
                            }

                            Screen.Alian -> AlianScreen(
                                context = this@MainActivity,
                                viewModel = alianViewModel,
                                apiKey = settings.apiKey,
                                baseUrl = settings.baseUrl,
                                model = settings.model,
                                voiceCallSystemPrompt = settings.voiceCallSystemPrompt,
                                videoCallSystemPrompt = settings.videoCallSystemPrompt,
                                useBackend = settings.useBackend,
                                backendBaseUrl = settings.backendBaseUrl,
                                ttsEnabled = settings.ttsEnabled,
                                ttsRealtime = settings.ttsRealtime,
                                onTtsRealtimeChanged = { settingsManager.updateTTSRealtime(it) },
                                ttsVoice = settings.ttsVoice,
                                ttsSpeed = settings.ttsSpeed,
                                ttsInterruptEnabled = settings.ttsInterruptEnabled,
                                enableStreaming = settings.enableStreaming,
                                volume = settings.volume,
                                executionStrategy = settings.executionStrategy,
                                accessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(
                                    this@MainActivity
                                ),
                                isMediaProjectionAvailable = { mediaProjectionResultCode != null && mediaProjectionData != null },
                                onVoiceInput = { recognizedText ->
                                    runOnUiThread {
                                        if (recognizedText == "#REQUEST_AUDIO_PERMISSION") {
                                            requestAudioPermission()
                                        } else if (recognizedText.startsWith("#ERROR_VOICE_RECOGNITION_FAILED:")) {
                                            // 处理语音识别失败
                                            val errorMessage =
                                                recognizedText.substring("#ERROR_VOICE_RECOGNITION_FAILED:".length)
                                                    .trim()
                                        } else if (recognizedText.startsWith("#INFO_NO_VOICE_RECOGNIZED")) {
                                            // 处理未识别到语音的情况
                                        } else if (recognizedText.isNotEmpty()) {
                                            // 执行语音识别
                                            runAgent(
                                                instruction = recognizedText,
                                                apiKey = settings.apiKey,
                                                baseUrl = settings.baseUrl,
                                                model = settings.model,
                                                maxSteps = settings.maxSteps,
                                                isGUIAgent = settings.currentProvider.isGUIAgent,
                                                providerId = settings.currentProviderId,
                                                ttsEnabled = settings.ttsEnabled,
                                                ttsVoice = settings.ttsVoice
                                            )
                                        }
                                    }
                                },
                                voiceRecognitionManager = voiceRecognitionManager,
                                streamingVoiceRecognitionManager = streamingVoiceRecognitionManager,
                                onHideBottomBarChanged = { hideBottomBar = it },
                                onNavigateToSettings = { currentScreen = Screen.Settings },
                                // AlianLocalScreen 相关参数
                                agentState = agentState,
                                logs = logs,
                                onExecute = { instruction ->
                                    runAgent(
                                        instruction = instruction,
                                        apiKey = settings.apiKey,
                                        baseUrl = settings.baseUrl,
                                        model = settings.model,
                                        maxSteps = settings.maxSteps,
                                        isGUIAgent = settings.currentProvider.isGUIAgent,
                                        providerId = settings.currentProviderId,
                                        ttsEnabled = settings.ttsEnabled,
                                        ttsVoice = settings.ttsVoice
                                    )
                                },
                                onStop = {
                                    mobileAgent.value?.stop()
                                },
                                shizukuAvailable = isShizukuAvailable,
                                onRefreshShizuku = { refreshShizukuStatus() },
                                onShizukuRequired = { showShizukuHelpDialog = true },
                                onRequestMediaProjectionPermission = { requestMediaProjectionPermission() },
                                isExecuting = executing,
                                executionRecords = records,
                                onRecordClick = { record: ExecutionRecord ->
                                    selectedRecord = record
                                },
                                onDeleteRecord = { record ->
                                    lifecycleScope.launch {
                                        executionRepository.deleteRecord(record.id)
                                        executionRecords.value = executionRepository.getAllRecords()
                                    }
                                },
                                showLoginScreen = showAlianLoginScreen,
                                onLoginBack = {
                                    showAlianLoginScreen = false
                                },
                                forceShowLocal = forceShowLocal,
                                onForceShowLocalChanged = { forceShowLocal = it },
                                userAvatar = settings.userAvatar,
                                assistantAvatar = settings.assistantAvatar,
                                deviceController = deviceController,
                                mediaProjectionResultCode = mediaProjectionResultCode,
                                mediaProjectionData = mediaProjectionData
                            )

                            Screen.Capabilities -> CapabilitiesScreen(
                                onBack = {
                                    currentScreen = Screen.Settings
                                },
                                onCreateSkill = {
                                    editingSkillConfig = null
                                    currentScreen = Screen.SkillCreator
                                },
                                onEditSkill = { config ->
                                    editingSkillConfig = config
                                    currentScreen = Screen.SkillCreator
                                },
                                onDeleteSkill = { skillId, onComplete ->
                                    // 删除用户自定义 Skill
                                    UserSkillsManager.getInstance().deleteUserSkill(skillId)
                                    SkillRegistry.getInstance().unregister(skillId)
                                    onComplete()  // 触发页面刷新
                                }
                            )

                            Screen.History -> AlianLocalHistoryScreen(
                                records = records,
                                onRecordClick = { record -> selectedRecord = record },
                                onDeleteRecord = { id -> deleteRecord(id) },
                                onBack = { currentScreen = Screen.Alian }
                            )

                            Screen.Settings -> SettingsScreen(
                                settings = settings,
                                onUpdateApiKey = { settingsManager.updateApiKey(it) },
                                onUpdateBaseUrl = { settingsManager.updateBaseUrl(it) },
                                onUpdateModel = { settingsManager.updateModel(it) },
                                onUpdateTextModel = { settingsManager.updateTextModel(it) },
                                onUpdateCachedModels = { settingsManager.updateCachedModels(it) },
                                onUpdateThemeMode = { settingsManager.updateThemeMode(it) },
                                onUpdateMaxSteps = { settingsManager.updateMaxSteps(it) },
                                onUpdateCloudCrashReport = { enabled ->
                                    settingsManager.updateCloudCrashReportEnabled(enabled)
                                    App.getInstance().updateCloudCrashReportEnabled(enabled)
                                },
                                onUpdateRootModeEnabled = { settingsManager.updateRootModeEnabled(it) },
                                onUpdateSuCommandEnabled = {
                                    settingsManager.updateSuCommandEnabled(
                                        it
                                    )
                                },
                                onUpdateUseBackend = { settingsManager.updateUseBackend(it) },
                                onUpdateTTSEnabled = { settingsManager.updateTTSEnabled(it) },
                                onUpdateTTSRealtime = { settingsManager.updateTTSRealtime(it) },
                                onUpdateTTSVoice = { settingsManager.updateTTSVoice(it) },
                                onUpdateTTSSpeed = { settingsManager.updateTTSSpeed(it) },
                                onUpdateTTSInterruptEnabled = {
                                    settingsManager.updateTTSInterruptEnabled(
                                        it
                                    )
                                },
                                onUpdateEnableAEC = { settingsManager.updateEnableAEC(it) },
                                onUpdateEnableStreaming = { settingsManager.updateEnableStreaming(it) },
                                onUpdateVolume = { settingsManager.updateVolume(it) },
                                onUpdateBackendUrl = { settingsManager.updateBackendBaseUrl(it) },
                                onUpdateVoiceCallSystemPrompt = {
                                    settingsManager.updateVoiceCallSystemPrompt(
                                        it
                                    )
                                },
                                onUpdateVideoCallSystemPrompt = {
                                    settingsManager.updateVideoCallSystemPrompt(
                                        it
                                    )
                                },
                                onUpdateAssistantAvatar = { settingsManager.updateAssistantAvatar(it) },
                                onUpdateUserAvatar = { settingsManager.updateUserAvatar(it) },
                                onUpdateEnableBatchExecution = {
                                    settingsManager.updateEnableBatchExecution(
                                        it
                                    )
                                },
                                onUpdateEnableImproveMode = {
                                    settingsManager.updateEnableImproveMode(
                                        it
                                    )
                                },
                                onUpdateReactOnly = { settingsManager.updateReactOnly(it) },
                                onUpdateEnableChatAgent = { settingsManager.updateEnableChatAgent(it) },
                                onUpdateEnableFlowMode = { settingsManager.updateEnableFlowMode(it) },
                                onUpdateExecutionStrategy = {
                                    settingsManager.updateExecutionStrategy(it)
                                    recreateDeviceController()
                                },
                                onUpdateFallbackStrategy = {
                                    settingsManager.updateFallbackStrategy(it)
                                    recreateDeviceController()
                                },
                                onUpdateScreenshotCacheEnabled = {
                                    settingsManager.updateScreenshotCacheEnabled(it)
                                    recreateDeviceController()
                                },
                                onUpdateGestureDelayMs = {
                                    settingsManager.updateGestureDelayMs(it)
                                    recreateDeviceController()
                                },
                                onUpdateInputDelayMs = {
                                    settingsManager.updateInputDelayMs(it)
                                    recreateDeviceController()
                                },
                                onRequestMediaProjectionPermission = { requestMediaProjectionPermission() },
                                onSelectProvider = { settingsManager.selectProvider(it) },
                                shizukuAvailable = isShizukuAvailable,
                                shizukuPrivilegeLevel = if (isShizukuAvailable && deviceController is ShizukuController) {
                                    when ((deviceController as ShizukuController).getShizukuPrivilegeLevel()) {
                                        ShizukuPrivilegeLevel.ROOT -> "ROOT"
                                        ShizukuPrivilegeLevel.ADB -> "ADB"
                                        else -> "NONE"
                                    }
                                } else "NONE",
                                onFetchModels = { onSuccess, onError ->
                                    lifecycleScope.launch {
                                        val result =
                                            VLMClient.fetchModels(settings.baseUrl, settings.apiKey)
                                        result.onSuccess { models ->
                                            onSuccess(models)
                                        }.onFailure { error ->
                                            onError(error.message ?: "未知错误")
                                        }
                                    }
                                },
                                onLogout = {
                                    // 清除认证信息（token、session）
                                    alianClient?.logout()
                                    // 清除 AlianViewModel 的登录状态和消息
                                    alianViewModel.logout()
                                },
                                onLogin = {
                                    currentScreen = Screen.Alian
                                    showAlianLoginScreen = true
                                },
                                isLoggedIn = authManager.isLoggedIn(),
                                userEmail = authManager.getUserEmail(),
                                navigateToCapabilities = {
                                    currentScreen = Screen.Capabilities
                                },
                                onNavigateToVoiceSelection = {
                                    currentScreen = Screen.VoiceSelection
                                },
                                onNavigateToSpeechProviderSettings = {
                                    currentScreen = Screen.SpeechProviderSettings
                                },
                                onNavigateToFlowTemplate = {
                                    currentScreen = Screen.FlowTemplate
                                },
                                onBack = {
                                    currentScreen = Screen.Alian
                                    showAlianLoginScreen = false
                                },
                                mediaProjectionResultCode = mediaProjectionResultCode,
                                mediaProjectionData = mediaProjectionData,
                                onShowShizukuHelpDialog = { showShizukuHelpDialog = true }
                            )

                            Screen.VoiceSelection -> VoiceSelectionScreen(
                                currentVoice = settings.ttsVoice,
                                onBack = { currentScreen = Screen.Settings },
                                onSelectVoice = { voiceParam, auditionUrl ->
                                    settingsManager.updateTTSVoice(voiceParam)
                                },
                                onPlayVoice = { voiceParam ->
                                    // 试听功能已在VoiceSelectionScreen内部实现
                                }
                            )

                            Screen.SkillCreator -> SkillCreatorScreen(
                                editingSkill = editingSkillConfig,
                                onBack = {
                                    editingSkillConfig = null
                                    currentScreen = Screen.Capabilities
                                },
                                onSaved = {
                                    editingSkillConfig = null
                                    currentScreen = Screen.Capabilities
                                }
                            )

                            Screen.SpeechProviderSettings -> SpeechProviderSettingsScreen(
                                currentProvider = settings.speechProvider,
                                credentials = settings.speechCredentials,
                                models = settings.speechModels,
                                onBack = { currentScreen = Screen.Settings },
                                onSelectProvider = { provider ->
                                    settingsManager.selectSpeechProvider(provider)
                                },
                                onUpdateCredentials = { provider, creds ->
                                    settingsManager.updateSpeechCredentials(provider, creds)
                                },
                                onUpdateModels = { provider, models ->
                                    settingsManager.updateSpeechModels(provider, models)
                                }
                            )

                            Screen.FlowTemplate -> FlowTemplateListScreen(
                                onNavigateToDetail = { templateId ->
                                    selectedFlowTemplateId = templateId
                                    currentScreen = Screen.FlowTemplateDetail
                                },
                                onBack = {
                                    currentScreen = Screen.Settings
                                }
                            )

                            Screen.FlowTemplateDetail -> {
                                val templateId = selectedFlowTemplateId ?: ""
                                FlowTemplateDetailScreen(
                                    templateId = templateId,
                                    onNavigateBack = {
                                        selectedFlowTemplateId = null
                                        currentScreen = Screen.FlowTemplate
                                    },
                                    onExecute = { instruction ->
                                        // TODO: 执行流程模板
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

//        // Shizuku 帮助对话框
//        if (showShizukuHelpDialog) {
//            ShizukuHelpDialog(onDismiss = { showShizukuHelpDialog = false })
//        }
//
//        // 无障碍服务引导对话框（只在 AlianLocal 页面显示）
//        if (showAccessibilityPermissionDialog && currentScreen == Screen.Alian) {
//            com.alian.assistant.utils.PermissionDialog(
//                showDialog = showAccessibilityPermissionDialog,
//                permissionType = com.alian.assistant.utils.PermissionType.ACCESSIBILITY_SERVICE,
//                onConfirm = {
//                    com.alian.assistant.utils.AccessibilityUtils.openAccessibilitySettings(this)
//                },
//                onDismiss = {
//                    showAccessibilityPermissionDialog = false
//                    pendingInstruction = null
//                }
//            )
//        }
//
//        // MediaProjection 权限引导对话框（只在 AlianLocal 页面显示）
//        Log.d(TAG, "检查对话框显示: showMediaProjectionPermissionDialog=$showMediaProjectionPermissionDialog, currentScreen=$currentScreen")
//        if (showMediaProjectionPermissionDialog && currentScreen == Screen.Alian) {
//            Log.d(TAG, "显示 MediaProjection 权限对话框")
//            com.alian.assistant.utils.PermissionDialog(
//                showDialog = showMediaProjectionPermissionDialog,
//                permissionType = com.alian.assistant.utils.PermissionType.MEDIA_PROJECTION,
//                onConfirm = {
//                    requestMediaProjectionPermission()
//                },
//                onDismiss = {
//                    showMediaProjectionPermissionDialog = false
//                    pendingInstruction = null
//                }
//            )
//        }
    }

    private fun deleteRecord(id: String) {
        lifecycleScope.launch {
            executionRepository.deleteRecord(id)
            executionRecords.value = executionRepository.getAllRecords()
        }
    }

    // 轻微震动效果
    private fun performLightHaptic() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator?.hasVibrator() == true) {
            val effect = VibrationEffect.createOneShot(50, 150)
            vibrator.vibrate(effect)
        }
    }

    override fun onResume() {
        super.onResume()
        // 重新检测 Shizuku 状态
        checkAndUpdateShizukuStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        deviceController.unbindService()

        // 清除 MediaProjection 失效回调监听器
        AccessibilityKeepAliveService.setOnMediaProjectionRevokedListener(null)

        // 释放语音识别资源
        if (::voiceRecognitionManager.isInitialized) {
            voiceRecognitionManager.destroy()
        }

        streamingVoiceRecognitionManager?.destroy()
    }

    private fun checkShizukuPermission(): Boolean {
        return PermissionManager.isShizukuPermissionGranted()
    }

    private fun checkAndUpdateShizukuStatus() {
        Log.d(TAG, "checkAndUpdateShizukuStatus called")

        // 使用 PermissionManager 检查 Shizuku 状态
        val shizukuStatus = PermissionManager.checkShizukuStatus()
        Log.d(TAG, "Shizuku status: ${shizukuStatus.status}")

        if (shizukuStatus.available) {
            shizukuAvailable.value = true
            Log.d(TAG, "Shizuku hasPermission: ${shizukuStatus.permissionGranted}")

            if (shizukuStatus.permissionGranted) {
                Log.d(TAG, "Binding Shizuku service")
                if (deviceController is ShizukuController) {
                    (deviceController as ShizukuController).bindService()
                }
            } else {
                Log.d(TAG, "Requesting Shizuku permission")
                requestShizukuPermission()
            }
        } else {
            Log.d(TAG, "Shizuku binder not alive")
            shizukuAvailable.value = false
        }
    }

    /**
     * 创建 VLMClient 实例
     * @param apiKey 可选的 API Key，如果不提供则从设置中获取
     * @param baseUrl 可选的 Base URL，如果不提供则从设置中获取
     * @param model 可选的模型，如果不提供则从设置中获取
     */
    private fun createVLMClient(
        apiKey: String? = null,
        baseUrl: String? = null,
        model: String? = null
    ): VLMClient {
        val settings = settingsManager.settings.value
        return VLMClient(
            apiKey = apiKey ?: settings.apiKey,
            baseUrl = (baseUrl
                ?: settings.baseUrl).ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
            model = (model ?: settings.model).ifBlank { "qwen3-vl-plus" }
        )
    }

    /**
     * 根据当前设置重新创建设备控制器
     */
    private fun recreateDeviceController() {
        Log.d(TAG, "Recreating device controller...")

        val settings = settingsManager.settings.value

        // 解析执行策略
        val executionStrategy = when (settings.executionStrategy) {
            "shizuku_only" -> ExecutionStrategy.SHIZUKU_ONLY
            "accessibility_only" -> ExecutionStrategy.ACCESSIBILITY_ONLY
            "hybrid" -> ExecutionStrategy.HYBRID
            else -> ExecutionStrategy.AUTO
        }

        // 解析降级策略
        val fallbackStrategy = when (settings.fallbackStrategy) {
            "shizuku_first" -> FallbackStrategy.SHIZUKU_FIRST
            "accessibility_first" -> FallbackStrategy.ACCESSIBILITY_FIRST
            else -> FallbackStrategy.AUTO
        }

        // 创建新的控制器配置
        val controllerConfig = ControllerConfig(
            shizukuEnabled = executionStrategy == ExecutionStrategy.SHIZUKU_ONLY ||
                    executionStrategy == ExecutionStrategy.HYBRID ||
                    executionStrategy == ExecutionStrategy.AUTO,
            shizukuPrivilegeLevel = ShizukuPrivilegeLevel.ADB,
            accessibilityEnabled = executionStrategy == ExecutionStrategy.ACCESSIBILITY_ONLY ||
                    executionStrategy == ExecutionStrategy.HYBRID ||
                    executionStrategy == ExecutionStrategy.AUTO,
            mediaProjectionEnabled = true,
            mediaProjectionResultCode = mediaProjectionResultCode,
            mediaProjectionData = mediaProjectionData,
            fallbackEnabled = true,
            fallbackStrategy = fallbackStrategy,
            screenshotCacheEnabled = settings.screenshotCacheEnabled,
            gestureDelayMs = settings.gestureDelayMs,
            inputDelayMs = settings.inputDelayMs
        )

        // 设置 AccessibilityKeepAliveService 的 MediaProjection 参数用于保活（在创建控制器之前）
        if (mediaProjectionResultCode != null && mediaProjectionData != null) {
            AccessibilityKeepAliveService.setMediaProjectionParams(
                mediaProjectionResultCode!!,
                mediaProjectionData!!
            )
        }

        // 创建新的设备控制器
        val oldController = deviceController
        deviceController = DeviceControllerFactory.createController(this, controllerConfig)

        Log.d(TAG, "Device controller recreated: ${deviceController.javaClass.simpleName}")

        // 如果是 ShizukuController，需要绑定服务
        if (deviceController is ShizukuController) {
            (deviceController as ShizukuController).bindService()
        }

        // 如果旧控制器是 ShizukuController，需要解绑服务
        if (oldController is ShizukuController) {
            oldController.unbindService()
        }

        // 重新创建 MobileAgentImprove 实例（如果存在）
        mobileAgent.value?.let { agent ->
            if (agent is MobileAgentImprove) {
                val vlmClient = createVLMClient()
                val settings = settingsManager.settings.value
                mobileAgent.value = MobileAgentImprove(
                    vlmClient,
                    deviceController,
                    this,
                    settings.reactOnly,
                    settings.enableChatAgent,
                    settings.enableFlowMode
                )
                Log.d(TAG, "MobileAgentImprove recreated with new controller")
            }
        }
    }

    private fun refreshShizukuStatus() {
        Log.d(TAG, "refreshShizukuStatus called by user")
        Toast.makeText(this, "正在检查 Shizuku 状态...", Toast.LENGTH_SHORT).show()
        checkAndUpdateShizukuStatus()

        val shizukuStatus = PermissionManager.checkShizukuStatus()
        when (shizukuStatus.status) {
            PermissionManager.ShizukuStatus.Status.READY -> {
                Toast.makeText(this, "Shizuku 已连接", Toast.LENGTH_SHORT).show()
            }

            PermissionManager.ShizukuStatus.Status.PERMISSION_NOT_GRANTED -> {
                Toast.makeText(this, "请在弹窗中授权 Shizuku", Toast.LENGTH_SHORT).show()
            }

            PermissionManager.ShizukuStatus.Status.NOT_AVAILABLE -> {
                Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
            }

            PermissionManager.ShizukuStatus.Status.VERSION_TOO_OLD -> {
                Toast.makeText(this, "Shizuku 版本过低", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestShizukuPermission() {
        val success = PermissionManager.requestShizukuPermissionSafely()

        if (!success) {
            val shizukuStatus = PermissionManager.checkShizukuStatus()
            when (shizukuStatus.status) {
                PermissionManager.ShizukuStatus.Status.NOT_AVAILABLE -> {
                    Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
                }

                PermissionManager.ShizukuStatus.Status.VERSION_TOO_OLD -> {
                    Toast.makeText(this, "Shizuku 版本过低", Toast.LENGTH_SHORT).show()
                }

                else -> {
                    // 其他情况不显示 Toast
                }
            }
        } else {
            shizukuAvailable.value = true
        }
    }

    private fun runAgent(
        instruction: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        maxSteps: Int,
        isGUIAgent: Boolean = false,
        providerId: String = "",
        ttsEnabled: Boolean = false,
        ttsVoice: String = "longyingmu_v3"
    ) {
        agentRunner.runAgent(
            instruction = instruction,
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            maxSteps = maxSteps,
            isGUIAgent = isGUIAgent,
            providerId = providerId,
            ttsEnabled = ttsEnabled,
            ttsVoice = ttsVoice
        )
    }
}
