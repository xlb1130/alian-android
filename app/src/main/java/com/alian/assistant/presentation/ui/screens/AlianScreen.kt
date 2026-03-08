package com.alian.assistant.presentation.ui.screens

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.alian.assistant.core.agent.AgentState
import com.alian.assistant.data.ExecutionRecord
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.presentation.viewmodel.AlianViewModel
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.infrastructure.voice.VoiceRecognitionManager
import com.alian.assistant.infrastructure.ai.asr.StreamingVoiceRecognitionManager
import kotlinx.coroutines.launch

/**
 * Alian 主屏幕
 * 根据登录状态显示不同界面：
 * - 已登录：显示 AlianChatScreen
 * - 未登录：显示 AlianLocalScreen
 */
@Composable
fun AlianScreen(
    context: Context,
    viewModel: AlianViewModel,
    apiKey: String = "",
    baseUrl: String = "",
    model: String = "",
    voiceCallSystemPrompt: String = "",
    videoCallSystemPrompt: String = "",
    useBackend: Boolean = false,
    backendBaseUrl: String = "http://39.98.113.244:5173/api/v1",
    ttsEnabled: Boolean = false,
    ttsRealtime: Boolean = false,
    onTtsRealtimeChanged: (Boolean) -> Unit = {},
    ttsVoice: String = "longyingmu_v3",
    ttsSpeed: Float = 1.0f,
    ttsInterruptEnabled: Boolean = false,
    enableAEC: Boolean = false,
    enableStreaming: Boolean = false,
    offlineTtsEnabled: Boolean = false,
    offlineTtsAutoFallbackToCloud: Boolean = true,
    volume: Int = 50,
    onVoiceInput: ((String) -> Unit)? = null,
    voiceRecognitionManager: VoiceRecognitionManager? = null,
    streamingVoiceRecognitionManager: StreamingVoiceRecognitionManager? = null,
    onHideBottomBarChanged: (Boolean) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    // AlianLocalScreen 相关参数
    agentState: AgentState? = null,
    logs: List<String> = emptyList(),
    onExecute: (String) -> Unit = {},
    onStop: () -> Unit = {},
    shizukuAvailable: Boolean = false,
    onRefreshShizuku: () -> Unit = {},
    onShizukuRequired: () -> Unit = {},
    onRequestMediaProjectionPermission: () -> Unit = {},
    isMediaProjectionAvailable: () -> Boolean = { false },
    isExecuting: Boolean = false,
    executionRecords: List<ExecutionRecord> = emptyList(),
    onRecordClick: (ExecutionRecord) -> Unit = {},
    onDeleteRecord: (ExecutionRecord) -> Unit = {},
    showLoginScreen: Boolean = false,
    onLoginBack: () -> Unit = {},
    // 切换控制
    forceShowLocal: Boolean = false,
    onForceShowLocalChanged: (Boolean) -> Unit = {},
    userAvatar: String? = null,  // 用户头像路径
    assistantAvatar: String? = null,  // 艾莲头像路径
    executionStrategy: String = "auto",
    accessibilityEnabled: Boolean = false,
    deviceController: IDeviceController? = null,
    mediaProjectionResultCode: Int? = null,
    mediaProjectionData: Intent? = null
) {
    val colors = BaoziTheme.colors
    val scope = rememberCoroutineScope()

    // 控制是否显示登录页面（用于从 AlianLocalScreen 打开登录页面）
    var showLocalLoginScreen by remember { mutableStateOf(false) }

    // 控制历史记录抽屉状态
    var showOnlineHistoryDrawer by remember { mutableStateOf(false) }

    // 更新 API 配置
    LaunchedEffect(apiKey, baseUrl, model, useBackend, backendBaseUrl) {
        Log.d("AlianScreen", "LaunchedEffect触发: useBackend=$useBackend, backendBaseUrl=$backendBaseUrl")
        viewModel.apiKey = apiKey
        viewModel.baseUrl = baseUrl
        viewModel.model = model
        // 使用 updateBackendConfig 方法，确保配置更新后自动检查登录状态
        viewModel.updateBackendConfig(useBackend, backendBaseUrl)
        Log.d("AlianScreen", "配置已更新: viewModel.useBackend=${viewModel.useBackend}, viewModel.backendBaseUrl=${viewModel.backendBaseUrl}")
    }

    // 监听登录状态变化，登录成功后关闭登录页面
    LaunchedEffect(viewModel.isLoggedIn.value) {
        if (viewModel.isLoggedIn.value && showLocalLoginScreen) {
            showLocalLoginScreen = false
        }
    }

    // 更新 TTS 配置
    LaunchedEffect(ttsEnabled, ttsRealtime, ttsVoice, apiKey, offlineTtsEnabled, offlineTtsAutoFallbackToCloud) {
        Log.d("AlianScreen", "LaunchedEffect触发: TTS配置更新")
        viewModel.updateTTSConfig(
            enabled = ttsEnabled,
            realtime = ttsRealtime,
            voice = ttsVoice,
            offlineEnabled = offlineTtsEnabled,
            offlineAutoFallbackToCloud = offlineTtsAutoFallbackToCloud
        )
    }

    // 根据登录状态显示不同界面
    if (viewModel.isLoggedIn.value) {
        // 已登录：根据 forceShowLocal 决定显示哪个界面
        if (forceShowLocal) {
            // 强制显示 Local 界面
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
            ) {
                AlianLocalScreen(
                    agentState = agentState,
                    logs = logs,
                    chatHistory = emptyList(),
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    voiceCallSystemPrompt = voiceCallSystemPrompt,
                    videoCallSystemPrompt = videoCallSystemPrompt,
                    ttsVoice = ttsVoice,
                    ttsSpeed = ttsSpeed,
                    ttsInterruptEnabled = ttsInterruptEnabled,
                    enableAEC = enableAEC,
                    enableStreaming = enableStreaming,
                    volume = volume,
                    onExecute = onExecute,
                    onStop = onStop,
                    shizukuAvailable = shizukuAvailable,
                    currentModel = model,
                    onRefreshShizuku = onRefreshShizuku,
                    onShizukuRequired = onShizukuRequired,
                    onRequestMediaProjectionPermission = onRequestMediaProjectionPermission,
                    isMediaProjectionAvailable = isMediaProjectionAvailable,
                    isExecuting = isExecuting,
                    onVoiceInput = onVoiceInput,
                    voiceRecognitionManager = voiceRecognitionManager,
                    streamingVoiceRecognitionManager = streamingVoiceRecognitionManager,
                    executionRecords = executionRecords,
                    onRecordClick = onRecordClick,
                    onDeleteRecord = onDeleteRecord,
                    onLogin = {
                        showLocalLoginScreen = true
                    },
                    onNavigateToSettings = onNavigateToSettings,
                    showSwitchButton = true,
                    onSwitchToChat = { onForceShowLocalChanged(false) },
                    userAvatar = userAvatar,
                    isLoggedIn = viewModel.isLoggedIn.value,
                    useBackend = useBackend,
                    onVideoCall = {
                        // 视频通话功能
                    },
                    onVoiceCall = {
                        // 语音通话功能
                    },
                    onCreateNewSession = {
                        viewModel.createNewSession()
                    },
                    onLogout = {
                        viewModel.logout()
                    },
                    executionStrategy = executionStrategy,
                    accessibilityEnabled = accessibilityEnabled,
                    onRequireMediaProjection = onRequestMediaProjectionPermission,
                    mediaProjectionResultCode = mediaProjectionResultCode,
                    mediaProjectionData = mediaProjectionData,
                    deviceController = deviceController
                )
            }
        } else {
            // 显示聊天界面
            AlianChatScreen(
                context = context,
                viewModel = viewModel,
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model,
                voiceCallSystemPrompt = voiceCallSystemPrompt,
                videoCallSystemPrompt = videoCallSystemPrompt,
                useBackend = useBackend,
                ttsEnabled = ttsEnabled,
                ttsRealtime = ttsRealtime,
                onTtsRealtimeChanged = onTtsRealtimeChanged,
                ttsVoice = ttsVoice,
                ttsSpeed = ttsSpeed,
                ttsInterruptEnabled = ttsInterruptEnabled,
                enableAEC = enableAEC,
                enableStreaming = enableStreaming,
                offlineTtsEnabled = offlineTtsEnabled,
                offlineTtsAutoFallbackToCloud = offlineTtsAutoFallbackToCloud,
                volume = volume,
                voiceRecognitionManager = voiceRecognitionManager,
                streamingVoiceRecognitionManager = streamingVoiceRecognitionManager,
                onHideBottomBarChanged = onHideBottomBarChanged,
                onNavigateToSettings = onNavigateToSettings,
                onLogout = {
                    viewModel.logout()
                },
                onShowHistory = {
                    showOnlineHistoryDrawer = true
                },
                showHistoryDrawer = showOnlineHistoryDrawer,
                onHistoryDrawerChanged = { showOnlineHistoryDrawer = it },
                showSwitchButton = true,
                onSwitchToLocal = { onForceShowLocalChanged(true) },
                userAvatar = userAvatar,
                assistantAvatar = assistantAvatar,
                deviceController = deviceController,
                onRequireMediaProjection = onRequestMediaProjectionPermission,
                mediaProjectionResultCode = mediaProjectionResultCode,
                mediaProjectionData = mediaProjectionData
            )
        }
    } else if (showLoginScreen || showLocalLoginScreen) {
        // 未登录且要求显示登录页面：显示登录界面
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            AnimatedVisibility(
                visible = showLoginScreen || showLocalLoginScreen,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(700)
                ) + fadeIn(animationSpec = tween(700)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(700)
                ) + fadeOut(animationSpec = tween(700))
            ) {
                AlianLoginScreen(
                    email = viewModel.email.value,
                    onEmailChange = { viewModel.email.value = it },
                    password = viewModel.password.value,
                    onPasswordChange = { viewModel.password.value = it },
                    onLogin = {
                        scope.launch {
                            viewModel.login()
                        }
                    },
                    isLoading = viewModel.isLoading.value,
                    errorMessage = viewModel.errorMessage.value,
                    onBack = {
                        if (showLoginScreen) {
                            onLoginBack()
                        } else {
                            showLocalLoginScreen = false
                        }
                    }
                )
            }
        }
    } else {
        // 未登录：显示 AlianLocalScreen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            AlianLocalScreen(
                agentState = agentState,
                logs = logs,
                chatHistory = emptyList(),
                apiKey = apiKey,
                baseUrl = baseUrl,
                voiceCallSystemPrompt = voiceCallSystemPrompt,
                videoCallSystemPrompt = videoCallSystemPrompt,
                ttsVoice = ttsVoice,
                ttsSpeed = ttsSpeed,
                ttsInterruptEnabled = ttsInterruptEnabled,
                enableAEC = enableAEC,
                enableStreaming = enableStreaming,
                volume = volume,
                onExecute = onExecute,
                onStop = onStop,
                shizukuAvailable = shizukuAvailable,
                currentModel = model,
                onRefreshShizuku = onRefreshShizuku,
                onShizukuRequired = onShizukuRequired,
                onRequestMediaProjectionPermission = onRequestMediaProjectionPermission,
                isMediaProjectionAvailable = isMediaProjectionAvailable,
                isExecuting = isExecuting,
                onVoiceInput = onVoiceInput,
                voiceRecognitionManager = voiceRecognitionManager,
                streamingVoiceRecognitionManager = streamingVoiceRecognitionManager,
                executionRecords = executionRecords,
                onRecordClick = onRecordClick,
                onLogin = {
                    showLocalLoginScreen = true
                },
                onNavigateToSettings = onNavigateToSettings,
                userAvatar = userAvatar,
                isLoggedIn = false,
                useBackend = useBackend,
                executionStrategy = executionStrategy,
                accessibilityEnabled = accessibilityEnabled,
                onRequireMediaProjection = onRequestMediaProjectionPermission,
                mediaProjectionResultCode = mediaProjectionResultCode,
                mediaProjectionData = mediaProjectionData,
                deviceController = deviceController
            )
        }
    }
}
