package com.alian.assistant.presentation.ui.screens

import android.content.Intent
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.alian.assistant.core.agent.AgentState
import com.alian.assistant.data.ChatMessageData
import com.alian.assistant.data.ExecutionRecord
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.presentation.ui.screens.components.AlianAppBar
import com.alian.assistant.presentation.ui.screens.components.AlianLogoutDialog
import com.alian.assistant.presentation.ui.screens.components.HapticUtils
import com.alian.assistant.presentation.ui.screens.components.MenuItem
import com.alian.assistant.presentation.ui.screens.local.AlianLocalHistoryDrawer
import com.alian.assistant.presentation.ui.screens.local.ExecutionStepsView
import com.alian.assistant.presentation.ui.screens.local.PresetCommandsView
import com.alian.assistant.presentation.ui.screens.online.AlianInputArea
import com.alian.assistant.presentation.ui.screens.online.VoiceRippleOverlay
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.infrastructure.voice.VoiceRecognitionManager
import com.alian.assistant.infrastructure.ai.asr.StreamingVoiceRecognitionManager
import com.alian.assistant.presentation.ui.screens.phonecall.PhoneCallScreen
import com.alian.assistant.presentation.ui.screens.videocall.VLMOnlyVideoCallScreen
import com.alian.assistant.presentation.ui.screens.voicecall.VoiceCallScreen
import com.alian.assistant.common.utils.AccessibilityUtils
import com.alian.assistant.common.utils.PermissionDialog
import com.alian.assistant.common.utils.PermissionManager
import com.alian.assistant.common.utils.PermissionType
import kotlinx.coroutines.launch

/**
 * Alian Local 屏幕 - 本地模式主屏幕
 * 
 * @param agentState Agent 状态
 * @param logs 日志列表
 * @param chatHistory 对话历史
 * @param onExecute 执行命令回调
 * @param onStop 停止任务回调
 * @param shizukuAvailable Shizuku 是否可用
 * @param currentModel 当前模型
 * @param onRefreshShizuku 刷新 Shizuku 回调
 * @param onShizukuRequired Shizuku 必需回调
 * @param isExecuting 是否正在执行
 * @param onVoiceInput 语音输入回调
 * @param voiceRecognitionManager 语音识别管理器
 * @param streamingVoiceRecognitionManager 流式语音识别管理器
 * @param executionRecords 执行记录列表
 * @param onRecordClick 记录点击回调
 * @param onDeleteRecord 删除记录回调
 * @param onLogin 登录回调
 * @param onNavigateToSettings 导航到设置回调
 * @param openDrawer 是否打开抽屉
 * @param onDrawerStateChanged 抽屉状态变化回调
 * @param showSwitchButton 是否显示切换按钮
 * @param onSwitchToChat 切换到聊天回调
 * @param userAvatar 用户头像路径
 * @param isLoggedIn 是否已登录
 * @param useBackend 是否使用 Backend 模式
 * @param onVideoCall 视频通话回调
 * @param onVoiceCall 语音通话回调
 * @param onCreateNewSession 创建新会话回调
 * @param onLogout 登出回调
 * @param apiKey API Key
 * @param baseUrl API Base URL
 * @param voiceCallSystemPrompt 语音通话系统提示词
 * @param videoCallSystemPrompt 视频通话系统提示词
 * @param ttsVoice TTS 语音
 * @param ttsSpeed TTS 语速
 * @param ttsInterruptEnabled TTS 打断启用
 * @param enableAEC 启用 AEC
 * @param enableStreaming 启用流式
 * @param volume 音量
 * @param executionStrategy 执行策略
 * @param accessibilityEnabled 无障碍服务是否已启用
 * @param mediaProjectionAvailable MediaProjection 是否可用
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AlianLocalScreen(
    agentState: AgentState?,
    logs: List<String>,
    chatHistory: List<ChatMessageData> = emptyList(),
    onExecute: (String) -> Unit,
    onStop: () -> Unit,
    shizukuAvailable: Boolean,
    currentModel: String = "",
    onRefreshShizuku: () -> Unit = {},
    onShizukuRequired: () -> Unit = {},
    onRequestMediaProjectionPermission: () -> Unit = {},
    isMediaProjectionAvailable: () -> Boolean = { false },
    isExecuting: Boolean = false,
    onVoiceInput: ((String) -> Unit)? = null,
    voiceRecognitionManager: VoiceRecognitionManager? = null,
    streamingVoiceRecognitionManager: StreamingVoiceRecognitionManager? = null,
    executionRecords: List<ExecutionRecord> = emptyList(),
    onRecordClick: (ExecutionRecord) -> Unit = {},
    onDeleteRecord: (ExecutionRecord) -> Unit = {},
    onLogin: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    openDrawer: Boolean = false,
    onDrawerStateChanged: (Boolean) -> Unit = {},
    showSwitchButton: Boolean = false,
    onSwitchToChat: (() -> Unit)? = null,
    userAvatar: String? = null,
    isLoggedIn: Boolean = false,
    useBackend: Boolean = false,
    onVideoCall: () -> Unit = {},
    onVoiceCall: () -> Unit = {},
    onCreateNewSession: () -> Unit = {},
    onLogout: () -> Unit = {},
    apiKey: String = "",
    baseUrl: String = "",
    voiceCallSystemPrompt: String = "",
    videoCallSystemPrompt: String = "",
    ttsVoice: String = "longyingmu_v3",
    ttsSpeed: Float = 1.0f,
    ttsInterruptEnabled: Boolean = false,
    enableAEC: Boolean = false,
    enableStreaming: Boolean = false,
    volume: Int = 50,
    executionStrategy: String = "auto",
    accessibilityEnabled: Boolean = false,
    mediaProjectionAvailable: Boolean = false,
    onRequireMediaProjection: (() -> Unit)? = null,
    mediaProjectionResultCode: Int? = null,
    mediaProjectionData: Intent? = null,
    deviceController: IDeviceController? = null
) {
    // 添加日志用于调试
    Log.d("AlianLocalScreen", "AlianLocalScreen - executionStrategy: $executionStrategy, accessibilityEnabled: $accessibilityEnabled, mediaProjectionAvailable: $mediaProjectionAvailable, mediaProjectionResultCode=$mediaProjectionResultCode, mediaProjectionData=${mediaProjectionData != null}")
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var isVoiceInputMode by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordedText by remember { mutableStateOf("") }
    var showVoiceRipple by remember { mutableStateOf(false) }
    var voiceRipplePosition by remember { mutableStateOf<Offset?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showVoiceCall by remember { mutableStateOf(false) }
    var showVideoCall by remember { mutableStateOf(false) }
    var showPhoneCall by remember { mutableStateOf(false) }
    
    // 权限引导对话框状态
    var showAccessibilityPermissionDialog by remember { mutableStateOf(false) }
    var showMediaProjectionPermissionDialog by remember { mutableStateOf(false) }
    
    val isRunning = isExecuting || agentState?.isRunning == true
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var wasRunning by remember { mutableStateOf(false) }

    // 监控 agentState 的变化
    LaunchedEffect(agentState?.executionSteps?.size) {
        Log.d("AlianLocalScreen", "agentState.executionSteps 数量变化: ${agentState?.executionSteps?.size}")
    }

    // 任务结束时清空输入框
    LaunchedEffect(isRunning) {
        if (wasRunning && !isRunning) {
            inputText = ""
        }
        wasRunning = isRunning
    }

    // 自动滚动到底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    // 抽屉状态
    val drawerState = rememberDrawerState(
        initialValue = if (openDrawer) DrawerValue.Open else DrawerValue.Closed
    )

    // 当 openDrawer 参数变化时，更新抽屉状态
    LaunchedEffect(openDrawer) {
        if (openDrawer && !drawerState.isOpen) {
            scope.launch { drawerState.open() }
        }
    }

    // 监听抽屉状态变化，添加震动反馈
    LaunchedEffect(drawerState.isOpen) {
        onDrawerStateChanged(drawerState.isOpen)
        HapticUtils.performLightHaptic(context)
    }

    // 构建更多菜单项
    val moreMenuItems = remember(isLoggedIn, useBackend) {
        val items = mutableListOf<MenuItem>()
        
        // 视频通话 - 登录和未登录都可使用
        items.add(MenuItem(
            text = "视频通话",
            icon = Icons.Default.Videocam,
            iconColor = Color(0xFF6366F1),
            onClick = { showVideoCall = true }
        ))
        
        // 语音通话 - 登录和未登录都可使用
        items.add(MenuItem(
            text = "语音通话",
            icon = Icons.Default.Phone,
            iconColor = Color(0xFF10B981),
            onClick = { showVoiceCall = true }
        ))
        
        // 手机通话 - 登录和未登录都可使用
        items.add(MenuItem(
            text = "手机通话",
            icon = Icons.Default.PhoneAndroid,
            iconColor = Color(0xFF8B5CF6),
            onClick = { showPhoneCall = true }
        ))
        
        if (isLoggedIn) {
            // 已登录：显示设置和登出
            items.add(MenuItem(
                text = "设置",
                icon = Icons.Default.Settings,
                iconColor = colors.secondary,
                onClick = onNavigateToSettings
            ))
            
            items.add(MenuItem(
                text = "登出",
                icon = Icons.Default.ExitToApp,
                iconColor = colors.error,
                onClick = { showLogoutDialog = true }
            ))
        } else {
            // 未登录：显示登录和设置
            items.add(MenuItem(
                text = "登录",
                icon = Icons.Default.Login,
                iconColor = Color(0xFF10B981),
                onClick = onLogin
            ))
            
            items.add(MenuItem(
                text = "设置",
                icon = Icons.Default.Settings,
                iconColor = colors.secondary,
                onClick = onNavigateToSettings
            ))
        }
        
        items
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            AlianLocalHistoryDrawer(
                context = context,
                records = executionRecords,
                onRecordClick = { record ->
                    onRecordClick(record)
                    scope.launch { drawerState.close() }
                },
                onClose = {
                    scope.launch { drawerState.close() }
                },
                onDeleteRecord = onDeleteRecord
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                // 顶部标题栏 - 使用公共组件
                AlianAppBar(
                    title = "Alian Local",
                    onMenuClick = {
                        scope.launch { drawerState.open() }
                    },
                    showSwitchButton = showSwitchButton,
                    onSwitchClick = onSwitchToChat,
                    showMoreMenu = true,
                    moreMenuItems = moreMenuItems
                )

                // 内容区域
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        )
                ) {
                    if (isRunning || agentState?.executionSteps?.isNotEmpty() == true) {
                        // 执行中或有执行步骤时显示步骤
                        ExecutionStepsView(
                            executionSteps = agentState?.executionSteps ?: emptyList(),
                            chatHistory = chatHistory,
                            instruction = agentState?.instruction ?: "",
                            answer = agentState?.answer,
                            isRunning = isRunning,
                            currentStep = agentState?.currentStep ?: 0,
                            currentModel = currentModel,
                            listState = listState,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // 空闲时显示预设命令
                        PresetCommandsView(
                            context = context,
                            onCommandClick = { command ->
                                // 实时检查权限状态
                                val realAccessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(context)
                                val realMediaProjectionAvailable = isMediaProjectionAvailable()
                                val promptInfo = PermissionManager.getExecutionPromptInfo(
                                    executionStrategy = executionStrategy,
                                    shizukuAvailable = shizukuAvailable,
                                    accessibilityEnabled = realAccessibilityEnabled,
                                    mediaProjectionAvailable = realMediaProjectionAvailable
                                )
                                
                                if (promptInfo.canExecute) {
                                    inputText = command
                                } else {
                                    // 根据缺失的权限显示相应的对话框
                                    when {
                                        !shizukuAvailable && executionStrategy == "shizuku_only" -> onShizukuRequired()
                                        !shizukuAvailable && executionStrategy in listOf("hybrid", "auto") -> onShizukuRequired()
                                        !realAccessibilityEnabled && executionStrategy == "accessibility_only" -> showAccessibilityPermissionDialog = true
                                        !realAccessibilityEnabled && executionStrategy in listOf("hybrid", "auto") -> showAccessibilityPermissionDialog = true
                                        !realMediaProjectionAvailable && PermissionManager.needsMediaProjectionPermission(executionStrategy) -> showMediaProjectionPermissionDialog = true
                                        else -> onShizukuRequired()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            shizukuAvailable = shizukuAvailable,
                            onRefreshShizuku = onRefreshShizuku,
                            executionStrategy = executionStrategy,
                            accessibilityEnabled = accessibilityEnabled,
                            mediaProjectionAvailable = mediaProjectionAvailable,
                            isMediaProjectionAvailable = isMediaProjectionAvailable
                        )
                    }
                }

                // 底部输入区域
                AlianInputArea(
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSend = {
                        Log.d("AlianLocalScreen", "onSend 被调用, inputText: $inputText")
                        if (inputText.isNotBlank()) {
                            // 实时检查权限状态
                            val realAccessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(context)
                            val realMediaProjectionAvailable = isMediaProjectionAvailable()
                            val promptInfo = PermissionManager.getExecutionPromptInfo(
                                executionStrategy = executionStrategy,
                                shizukuAvailable = shizukuAvailable,
                                accessibilityEnabled = realAccessibilityEnabled,
                                mediaProjectionAvailable = realMediaProjectionAvailable
                            )
                            
                            if (promptInfo.canExecute) {
                                Log.d("AlianLocalScreen", "权限检查通过，调用 onExecute")
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                onExecute(inputText)
                            } else {
                                Log.d("AlianLocalScreen", "权限检查未通过，显示权限引导对话框")
                                // 根据缺失的权限显示相应的对话框
                                when {
                                    !shizukuAvailable && executionStrategy == "shizuku_only" -> onShizukuRequired()
                                    !shizukuAvailable && executionStrategy in listOf("hybrid", "auto") -> onShizukuRequired()
                                    !realAccessibilityEnabled && executionStrategy == "accessibility_only" -> showAccessibilityPermissionDialog = true
                                    !realAccessibilityEnabled && executionStrategy in listOf("hybrid", "auto") -> showAccessibilityPermissionDialog = true
                                    !realMediaProjectionAvailable && PermissionManager.needsMediaProjectionPermission(executionStrategy) -> showMediaProjectionPermissionDialog = true
                                    else -> onShizukuRequired()
                                }
                            }
                        } else {
                            Log.d("AlianLocalScreen", "输入框为空，不执行")
                        }
                    },
                    isVoiceInputMode = isVoiceInputMode,
                    onVoiceInputModeChanged = { newValue -> isVoiceInputMode = newValue },
                    onVoiceInput = { recognizedText ->
                        if (recognizedText.isNotBlank()) {
                            // 实时检查权限状态
                            val realAccessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(context)
                            val realMediaProjectionAvailable = isMediaProjectionAvailable()
                            val promptInfo = PermissionManager.getExecutionPromptInfo(
                                executionStrategy = executionStrategy,
                                shizukuAvailable = shizukuAvailable,
                                accessibilityEnabled = realAccessibilityEnabled,
                                mediaProjectionAvailable = realMediaProjectionAvailable
                            )
                            
                            if (promptInfo.canExecute) {
                                Log.d("AlianLocalScreen", "语音输入权限检查通过，调用 onVoiceInput")
                                onVoiceInput?.invoke(recognizedText)
                            } else {
                                Log.d("AlianLocalScreen", "语音输入权限检查未通过，显示权限引导对话框")
                                // 根据缺失的权限显示相应的对话框
                                when {
                                    !shizukuAvailable && executionStrategy == "shizuku_only" -> onShizukuRequired()
                                    !shizukuAvailable && executionStrategy in listOf("hybrid", "auto") -> onShizukuRequired()
                                    !realAccessibilityEnabled && executionStrategy == "accessibility_only" -> showAccessibilityPermissionDialog = true
                                    !realAccessibilityEnabled && executionStrategy in listOf("hybrid", "auto") -> showAccessibilityPermissionDialog = true
                                    !realMediaProjectionAvailable && PermissionManager.needsMediaProjectionPermission(executionStrategy) -> showMediaProjectionPermissionDialog = true
                                    else -> onShizukuRequired()
                                }
                            }
                        }
                    },
                    isRecording = isRecording,
                    recordedText = recordedText,
                    onRecordingChange = { newValue -> isRecording = newValue },
                    onRecordedTextChanged = { newValue -> recordedText = newValue },
                    voiceRecognitionManager = voiceRecognitionManager,
                    streamingVoiceRecognitionManager = streamingVoiceRecognitionManager,
                    isProcessing = isRunning,
                    onCancel = {
                        inputText = ""
                        onStop()
                    },
                    sessionId = null,
                    backendClient = null,
                    onVoiceRippleChanged = { showVoiceRipple = it },
                    onVoiceRipplePositionChanged = { voiceRipplePosition = it }
                )
            }

            // 语音按钮波纹效果层
            if (showVoiceRipple) {
                VoiceRippleOverlay(
                    position = voiceRipplePosition,
                    colors = colors,
                    recordedText = recordedText
                )
            }

            // 语音通话页面（覆盖显示）
            AnimatedVisibility(
                visible = showVoiceCall,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                ) {
                    VoiceCallScreen(
                        context = context,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = currentModel,
                        systemPrompt = voiceCallSystemPrompt,
                        ttsVoice = ttsVoice,
                        ttsSpeed = ttsSpeed,
                        ttsInterruptEnabled = ttsInterruptEnabled,
                        enableAEC = ttsInterruptEnabled,
                        enableStreaming = enableStreaming,
                        volume = volume,
                        onBackClick = {
                            showVoiceCall = false
                        }
                    )
                }
            }

            // 视频通话页面（覆盖显示）
            AnimatedVisibility(
                visible = showVideoCall,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                ) {
                    VLMOnlyVideoCallScreen(
                        context = context,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = currentModel,
                        systemPrompt = videoCallSystemPrompt,
                        ttsVoice = ttsVoice,
                        ttsSpeed = ttsSpeed,
                        ttsInterruptEnabled = ttsInterruptEnabled,
                        enableAEC = ttsInterruptEnabled,
                        enableStreaming = enableStreaming,
                        volume = volume,
                        onBackClick = {
                            showVideoCall = false
                        }
                    )
                }
            }

            // 手机通话页面（覆盖显示）
            AnimatedVisibility(
                visible = showPhoneCall,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                ) {
                    PhoneCallScreen(
                        context = context,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = currentModel,
                        systemPrompt = voiceCallSystemPrompt,
                        ttsVoice = ttsVoice,
                        ttsSpeed = ttsSpeed,
                        ttsInterruptEnabled = ttsInterruptEnabled,
                        enableAEC = ttsInterruptEnabled,
                        enableStreaming = enableStreaming,
                        volume = volume,
                        onRequireMediaProjection = onRequireMediaProjection,
                        mediaProjectionResultCode = mediaProjectionResultCode,
                        mediaProjectionData = mediaProjectionData,
                        deviceController = deviceController,
                        onBackClick = {
                            showPhoneCall = false
                        }
                    )
                }
            }

            // 登出确认对话框
            if (showLogoutDialog) {
                AlianLogoutDialog(
                    onLogout = {
                        onLogout()
                        showLogoutDialog = false
                    },
                    onDismiss = {
                        showLogoutDialog = false
                    }
                )
            }
            
            // 无障碍服务权限引导对话框
            PermissionDialog(
                showDialog = showAccessibilityPermissionDialog,
                permissionType = PermissionType.ACCESSIBILITY_SERVICE,
                onConfirm = {
                    PermissionManager.openPermissionSettings(
                        context,
                        PermissionType.ACCESSIBILITY_SERVICE
                    )
                },
                onDismiss = {
                    showAccessibilityPermissionDialog = false
                }
            )
            
            // MediaProjection 权限引导对话框
            PermissionDialog(
                showDialog = showMediaProjectionPermissionDialog,
                permissionType = PermissionType.MEDIA_PROJECTION,
                onConfirm = {
                    showMediaProjectionPermissionDialog = false
                    // 直接请求 MediaProjection 权限
                    onRequestMediaProjectionPermission()
                },
                onDismiss = {
                    showMediaProjectionPermissionDialog = false
                }
            )
        }
    }
}