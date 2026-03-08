package com.alian.assistant.presentation.ui.screens.phonecall

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.alian.assistant.R
import com.alian.assistant.MainActivity
import com.alian.assistant.core.agent.AgentPermissionCheck
import com.alian.assistant.core.agent.PhoneCallMessage
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.presentation.ui.theme.BaoziColors
import com.alian.assistant.presentation.ui.screens.components.pressedEffect
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.viewmodel.FloatingWindowState
import com.alian.assistant.presentation.viewmodel.PhoneCallState
import com.alian.assistant.presentation.viewmodel.PhoneCallViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手机通话主屏幕
 * 
 * 支持两种模式：
 * 1. 全屏模式：显示屏幕预览、对话消息、操作状态
 * 2. 悬浮窗模式：系统级悬浮窗承载交互，页面内只保留模式占位与切换入口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneCallScreen(
    context: Context,
    apiKey: String = "",
    baseUrl: String = "",
    model: String = "qwen-plus",
    systemPrompt: String = "",
    ttsVoice: String = "longyingmu_v3",
    ttsSpeed: Float = 1.0f,
    ttsInterruptEnabled: Boolean = false,
    enableAEC: Boolean = false,
    enableStreaming: Boolean = false,
    volume: Int = 50,
    deviceController: IDeviceController? = null,
    onRequireMediaProjection: (() -> Unit)? = null,
    mediaProjectionResultCode: Int? = null,
    mediaProjectionData: Intent? = null,
    onBackClick: () -> Unit
) {
    Log.d("PhoneCallScreen", "PhoneCallScreen 初始化: mediaProjectionResultCode=$mediaProjectionResultCode, mediaProjectionData=${mediaProjectionData != null}")

    val colors = BaoziTheme.colors
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 创建 ViewModel
    val viewModel = remember { PhoneCallViewModel(context) }

    // 浮动窗口视图
    var floatingWindowView by remember { mutableStateOf<PhoneCallFloatingWindowView?>(null) }

    // 获取浮动窗口状态
    val floatingWindowState by viewModel.floatingWindowState.collectAsState()
    // 获取通话状态
    val state by viewModel.callState.collectAsState()
    val isMicrophoneEnabled by viewModel.isMicrophoneEnabled

    // 监听浮动窗口状态变化
    LaunchedEffect(floatingWindowState) {
        Log.d("PhoneCallScreen", "浮动窗口状态变化: $floatingWindowState")
        
        when (floatingWindowState) {
            is FloatingWindowState.Normal, is FloatingWindowState.Maximized, is FloatingWindowState.Minimized -> {
                if (floatingWindowView == null) {
                    floatingWindowView = PhoneCallFloatingWindowView(
                        context = context.applicationContext,
                        viewModel = viewModel,
                        onClose = {
                            viewModel.stopCall()
                            scope.launch {
                                delay(300)
                                onBackClick()
                            }
                        }
                    )
                    floatingWindowView?.show()
                    // 将浮动窗口视图引用设置到 ViewModel 中
                    val createdFloatingWindowView = floatingWindowView
                    viewModel.setFloatingWindowView(createdFloatingWindowView)
                    if (createdFloatingWindowView != null) {
                        viewModel.getScreenCaptureManager()?.let { screenCaptureManager ->
                            PhoneCallOverlayService.getInstance()?.bindFloatingWindowAndScreenCapture(
                                view = createdFloatingWindowView,
                                screenCaptureManager = screenCaptureManager,
                                shouldHideOverlayForCapture = {
                                    viewModel.floatingWindowState.value is FloatingWindowState.Minimized
                                }
                            )
                        }
                    }
                }
            }
            is FloatingWindowState.Disabled -> {
                floatingWindowView?.hide()
                floatingWindowView = null
                // 清除 ViewModel 中的浮动窗口视图引用
                viewModel.setFloatingWindowView(null)
            }
            else -> {}
        }
    }

    // 最小化 App 时确保悬浮窗显示
    val latestCallState by rememberUpdatedState(state)
    val latestFloatingState by rememberUpdatedState(floatingWindowState)
    val latestFloatingWindowView by rememberUpdatedState(floatingWindowView)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_STOP) {
                return@LifecycleEventObserver
            }

            val callIsActive = latestCallState is PhoneCallState.Recording ||
                    latestCallState is PhoneCallState.MicMuted ||
                    latestCallState is PhoneCallState.Processing ||
                    latestCallState is PhoneCallState.Playing ||
                    latestCallState is PhoneCallState.Operating

            if (!callIsActive) {
                return@LifecycleEventObserver
            }

            if (latestFloatingState is FloatingWindowState.Disabled) {
                Log.d("PhoneCallScreen", "应用进入后台且通话中，启用悬浮窗")
                viewModel.enableFloatingMode()
            } else {
                latestFloatingWindowView?.setVisible(true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 权限检查对话框状态
    var showPermissionDialog by remember { mutableStateOf(false) }
    var permissionCheckResult by remember { mutableStateOf<AgentPermissionCheck.CheckResult?>(null) }
    var showTextInputDialog by remember { mutableStateOf(false) }
    var textInputValue by remember { mutableStateOf("") }
    val syncMediaProjectionResult = {
        if (mediaProjectionResultCode != null && mediaProjectionData != null) {
            viewModel.setMediaProjectionResult(mediaProjectionResultCode, mediaProjectionData)
        }
    }

    // 更新配置
    LaunchedEffect(apiKey, baseUrl, model, systemPrompt, ttsVoice, ttsSpeed, ttsInterruptEnabled, enableAEC, enableStreaming, volume, deviceController) {
        viewModel.updateConfig(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            systemPrompt = systemPrompt,
            ttsVoice = ttsVoice,
            ttsSpeed = ttsSpeed,
            ttsInterruptEnabled = ttsInterruptEnabled,
            enableAEC = enableAEC,
            enableStreaming = enableStreaming,
            volume = volume,
            deviceController = deviceController
        )
    }

    // 监听 MediaProjection 权限结果
    LaunchedEffect(mediaProjectionResultCode, mediaProjectionData) {
        Log.d("PhoneCallScreen", "MediaProjection 参数变化: resultCode=$mediaProjectionResultCode, data=${mediaProjectionData != null}")
        if (mediaProjectionResultCode != null && mediaProjectionData != null) {
            syncMediaProjectionResult()
            Log.d("PhoneCallScreen", "MediaProjection 权限已更新: resultCode=$mediaProjectionResultCode")

            // 权限更新后重新检查
            val permissionResult = viewModel.checkAllPhoneCallPermissions(
                mediaProjectionResultCode = mediaProjectionResultCode,
                mediaProjectionData = mediaProjectionData
            )
            Log.d("PhoneCallScreen", "权限更新后的检查结果: $permissionResult")
            if (permissionResult is AgentPermissionCheck.CheckResult.Granted) {
                showPermissionDialog = false
                Log.d("PhoneCallScreen", "所有权限已授予")
            } else {
                Log.d("PhoneCallScreen", "权限检查未通过: $permissionResult")
            }
        }
    }

    // 监听状态变化
    LaunchedEffect(state) {
        Log.d("PhoneCallScreen", "状态变化: $state")
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d("PhoneCallScreen", "页面销毁，清理资源")
            viewModel.stopCall()
            viewModel.cleanup()
            // 清理浮动窗口
            floatingWindowView?.hide()
            floatingWindowView = null
        }
    }

    // 监听错误状态，显示权限检查对话框
    LaunchedEffect(state) {
        if (state is PhoneCallState.Error) {
            val errorMessage = (state as PhoneCallState.Error).message
            
            // 检查是否是权限相关的错误
            if (errorMessage.contains("需要") || errorMessage.contains("权限")) {
                permissionCheckResult = viewModel.checkAllPhoneCallPermissions(
                    mediaProjectionResultCode = mediaProjectionResultCode,
                    mediaProjectionData = mediaProjectionData
                )
                if (permissionCheckResult !is AgentPermissionCheck.CheckResult.Granted) {
                    showPermissionDialog = true
                }
            }
        }
    }

    // 权限检查对话框
    if (showPermissionDialog && permissionCheckResult != null) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            containerColor = colors.backgroundCard,
            title = {
                Text(
                    text = stringResource(R.string.phone_call_permission_title),
                    color = colors.textPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = viewModel.getPermissionDescription(permissionCheckResult!!),
                        color = colors.textSecondary
                    )
                    // MediaProjection 权限需要特殊说明
                    if (permissionCheckResult is AgentPermissionCheck.CheckResult.NeedsMediaProjection) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.phone_call_permission_hint),
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    syncMediaProjectionResult()
                    val latestPermissionResult = viewModel.checkAllPhoneCallPermissions(
                        mediaProjectionResultCode = mediaProjectionResultCode,
                        mediaProjectionData = mediaProjectionData
                    )
                    permissionCheckResult = latestPermissionResult
                    if (latestPermissionResult is AgentPermissionCheck.CheckResult.Granted) {
                        showPermissionDialog = false
                        return@TextButton
                    }

                    when (permissionCheckResult) {
                        is AgentPermissionCheck.CheckResult.NeedsMediaProjection -> {
                            // MediaProjection 需要特殊处理：优先走宿主回调请求系统授权弹窗。
                            if (onRequireMediaProjection != null) {
                                onRequireMediaProjection.invoke()
                            } else {
                                Log.w("PhoneCallScreen", "onRequireMediaProjection is null, fallback to MainActivity request")
                                val started = (context as? MainActivity)?.runCatching {
                                    requestMediaProjectionPermission()
                                }?.isSuccess == true

                                if (!started) {
                                    viewModel.openPermissionSettings(permissionCheckResult!!)
                                    Toast.makeText(
                                        context,
                                        "无法直接拉起屏幕录制授权，请在设置中手动授权后重试",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            showPermissionDialog = false
                        }
                        is AgentPermissionCheck.CheckResult.NeedsMultiplePermissions -> {
                            // 若缺失列表包含 MediaProjection，优先请求屏幕录制授权，避免用户点击无响应感。
                            val missingPermissions = (permissionCheckResult as AgentPermissionCheck.CheckResult.NeedsMultiplePermissions).missingPermissions
                            val containsMediaProjection = missingPermissions.any {
                                it is AgentPermissionCheck.CheckResult.NeedsMediaProjection
                            }

                            if (containsMediaProjection) {
                                if (onRequireMediaProjection != null) {
                                    onRequireMediaProjection.invoke()
                                } else {
                                    Log.w("PhoneCallScreen", "onRequireMediaProjection is null in multiple-permission case")
                                    val started = (context as? MainActivity)?.runCatching {
                                        requestMediaProjectionPermission()
                                    }?.isSuccess == true

                                    if (!started) {
                                        Toast.makeText(
                                            context,
                                            "无法直接拉起屏幕录制授权，请在设置中手动授权后重试",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            } else {
                                val firstMissingPermission = missingPermissions.firstOrNull()
                                if (firstMissingPermission != null) {
                                    viewModel.openPermissionSettings(firstMissingPermission)
                                }
                            }
                            showPermissionDialog = false
                        }
                        else -> {
                            viewModel.openPermissionSettings(permissionCheckResult!!)
                            showPermissionDialog = false
                        }
                    }
                }) {
                    Text(stringResource(R.string.phone_call_go_settings), color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text(stringResource(R.string.phone_call_cancel), color = colors.textSecondary)
                }
            }
        )
    }

    if (showTextInputDialog) {
        AlertDialog(
            onDismissRequest = { showTextInputDialog = false },
            containerColor = colors.backgroundCard,
            title = {
                Text(
                    text = stringResource(R.string.phone_call_text_input_title),
                    color = colors.textPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = textInputValue,
                    onValueChange = { textInputValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.phone_call_text_input_hint),
                            color = colors.textHint
                        )
                    },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.textHint,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = colors.primary
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val message = textInputValue.trim()
                        if (message.isNotBlank()) {
                            viewModel.handleTextInput(message)
                            textInputValue = ""
                            showTextInputDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.phone_call_send), color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTextInputDialog = false
                        textInputValue = ""
                    }
                ) {
                    Text(stringResource(R.string.phone_call_cancel), color = colors.textSecondary)
                }
            }
        )
    }

    // 处理返回事件
    BackHandler(enabled = true) {
        Log.d("PhoneCallScreen", "用户按下返回键")
        performLightHaptic(context)

        // 如果正在通话中，先停止通话
        if (state !is PhoneCallState.Idle) {
            viewModel.stopCall()
        }

        // 延迟返回，确保资源清理完成
        scope.launch {
            delay(300)
            onBackClick()
        }
    }

    // 根据浮动窗口状态决定显示模式
    when (floatingWindowState) {
        is FloatingWindowState.Disabled -> {
            // 全屏模式
            FullScreenPhoneCallUI(
                viewModel = viewModel,
                state = state,
                isMicrophoneEnabled = isMicrophoneEnabled,
                colors = colors,
                onBackClick = onBackClick,
                onStartCall = {
                    syncMediaProjectionResult()
                    val permissionResult = viewModel.checkAllPhoneCallPermissions(
                        mediaProjectionResultCode = mediaProjectionResultCode,
                        mediaProjectionData = mediaProjectionData
                    )
                    if (permissionResult is AgentPermissionCheck.CheckResult.Granted) {
                        viewModel.startCall()
                    } else {
                        permissionCheckResult = permissionResult
                        showPermissionDialog = true
                    }
                },
                onStopCall = {
                    viewModel.stopCall()
                    scope.launch {
                        delay(300)
                        onBackClick()
                    }
                },
                onSwitchToFloating = { viewModel.enableFloatingMode() },
                onToggleMicrophone = { viewModel.toggleMicrophone() },
                onTextInputClick = {
                    if (state is PhoneCallState.Idle) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.phone_call_start_hint),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        showTextInputDialog = true
                    }
                }
            )
        }
        else -> {
            // 浮动窗口模式：页面内仅展示模式状态，避免与系统级悬浮窗双层重叠
            FloatingModePlaceholderUI(
                state = state,
                colors = colors,
                onBackClick = onBackClick,
                onStopCall = {
                    viewModel.stopCall()
                    scope.launch {
                        delay(300)
                        onBackClick()
                    }
                },
                onSwitchToFullScreen = { viewModel.disableFloatingMode() }
            )
        }
    }
}

/**
 * 全屏模式 UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenPhoneCallUI(
    viewModel: PhoneCallViewModel,
    state: PhoneCallState,
    isMicrophoneEnabled: Boolean,
    colors: BaoziColors,
    onBackClick: () -> Unit,
    onStartCall: () -> Unit,
    onStopCall: () -> Unit,
    onSwitchToFloating: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onTextInputClick: () -> Unit
) {
    val currentScreen by viewModel.currentScreen
    val conversationHistory = viewModel.conversationHistory
    val currentRecognizedText by viewModel.currentRecognizedText
    val currentOperation by viewModel.currentOperation
    val isAiOperating by viewModel.isAiOperating

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PhoneCallTopBar(
                onBackClick = onBackClick,
                onSwitchModeClick = onSwitchToFloating,
                modeName = stringResource(R.string.phone_call_fullscreen_mode),
                switchModeText = stringResource(R.string.phone_call_switch_to_floating)
            )
        },
        bottomBar = {
            PhoneCallControlBar(
                state = state,
                isMicrophoneEnabled = isMicrophoneEnabled,
                onHangUpClick = onStopCall,
                onStartCallClick = onStartCall,
                onToggleMicrophone = onToggleMicrophone,
                onTextInputClick = onTextInputClick,
                colors = colors
            )
        }
    ) { padding ->
        // 渐变背景
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            colors.background,
                            colors.backgroundCard
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 屏幕预览区域 (60%)
                Box(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.backgroundCard)
                ) {
                    if (currentScreen != null) {
                        val bitmap = currentScreen
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.phone_call_screen_preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = stringResource(R.string.phone_call_screen_preview),
                                tint = colors.textHint,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.phone_call_screen_preview),
                                color = colors.textHint,
                                fontSize = 14.sp
                            )
                        }
                    }

                    // AI 操作时显示高亮标记
                    if (isAiOperating && currentOperation.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .background(
                                    color = Color(0xFF8B5CF6).copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFF8B5CF6),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = currentOperation,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // 对话消息区域 (25%)
                Box(
                    modifier = Modifier
                        .weight(0.25f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    PhoneCallMessageList(
                        conversationHistory = conversationHistory,
                        currentRecognizedText = currentRecognizedText,
                        colors = colors,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 操作状态栏 (5%)
                if (isAiOperating) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF8B5CF6).copy(alpha = 0.2f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color(0xFF8B5CF6),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = currentOperation,
                                color = Color(0xFF8B5CF6),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(0.05f))
            }
        }
    }
}

/**
 * 悬浮窗模式占位页（避免与系统级悬浮窗双层显示）
 */
@Composable
private fun FloatingModePlaceholderUI(
    state: PhoneCallState,
    colors: BaoziColors,
    onBackClick: () -> Unit,
    onStopCall: () -> Unit,
    onSwitchToFullScreen: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PhoneCallTopBar(
                onBackClick = onBackClick,
                onSwitchModeClick = onSwitchToFullScreen,
                modeName = stringResource(R.string.phone_call_floating_mode),
                switchModeText = stringResource(R.string.phone_call_switch_to_fullscreen)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(colors.background, colors.backgroundCard)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.phone_call_floating_tip),
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = getStateLabel(state),
                    color = colors.textSecondary,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onSwitchToFullScreen) {
                        Text(stringResource(R.string.phone_call_switch_to_fullscreen))
                    }
                    if (state !is PhoneCallState.Idle) {
                        Button(
                            onClick = onStopCall,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                        ) {
                            Text(stringResource(R.string.phone_call_end), color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 手机通话顶部栏
 */
@Composable
private fun PhoneCallTopBar(
    onBackClick: () -> Unit,
    onSwitchModeClick: () -> Unit,
    modeName: String,
    switchModeText: String
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回按钮 - 与视觉大模型页面的样式一致
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable {
                        performLightHaptic(context)
                        onBackClick()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = colors.textPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.phone_call_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Text(
                    text = modeName,
                    fontSize = 12.sp,
                    color = colors.textSecondary
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    performLightHaptic(context)
                    onSwitchModeClick()
                }
            ) {
                Text(
                    text = switchModeText,
                    color = colors.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * 手机通话控制栏
 */
@Composable
fun PhoneCallControlBar(
    state: PhoneCallState,
    isMicrophoneEnabled: Boolean,
    onHangUpClick: () -> Unit,
    onStartCallClick: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onTextInputClick: () -> Unit,
    colors: BaoziColors,
    textInputEnabled: Boolean = true
) {
    val context = LocalContext.current
    val isIdle = state is PhoneCallState.Idle

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 麦克风按钮
        PhoneCallControlButton(
            icon = if (isMicrophoneEnabled) Icons.Default.Mic else Icons.Default.MicOff,
            contentDescription = if (isMicrophoneEnabled) {
                stringResource(R.string.call_mic)
            } else {
                stringResource(R.string.phone_call_mic_muted)
            },
            iconColor = when (state) {
                is PhoneCallState.Recording -> if (isMicrophoneEnabled) Color(0xFF8B5CF6) else colors.textSecondary
                else -> colors.textSecondary
            },
            backgroundColor = when {
                isIdle -> colors.backgroundCard
                !isMicrophoneEnabled -> Color(0xFFEF4444).copy(alpha = 0.2f)
                state is PhoneCallState.Recording -> Color(0xFF8B5CF6).copy(alpha = 0.2f)
                else -> colors.backgroundCard
            },
            enabled = !isIdle,
            onClick = {
                performLightHaptic(context)
                onToggleMicrophone()
            }
        )

        // 开始/挂断按钮
        PhoneCallControlButton(
            icon = if (isIdle) Icons.Default.Phone else Icons.Default.PhoneDisabled,
            contentDescription = if (isIdle) stringResource(R.string.phone_call_start) else stringResource(R.string.phone_call_end),
            iconColor = Color.White,
            backgroundColor = if (isIdle) Color(0xFF8B5CF6) else Color(0xFFEF4444),
            buttonSize = 64.dp,
            iconSize = 28.dp,
            enabled = true,
            onClick = {
                performLightHaptic(context)
                if (isIdle) {
                    onStartCallClick()
                } else {
                    onHangUpClick()
                }
            }
        )

        // 文本输入按钮
        PhoneCallControlButton(
            icon = Icons.Default.Chat,
            contentDescription = stringResource(R.string.phone_call_title),
            iconColor = colors.textSecondary,
            backgroundColor = colors.backgroundCard,
            enabled = !isIdle && textInputEnabled,
            onClick = {
                performLightHaptic(context)
                onTextInputClick()
            }
        )
    }
}

/**
 * 手机通话控制按钮
 */
@Composable
private fun PhoneCallControlButton(
    icon: ImageVector,
    contentDescription: String,
    iconColor: Color,
    backgroundColor: Color,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .pressedEffect(interactionSource)
            .clip(CircleShape)
            .background(backgroundColor)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 手机通话消息列表
 */
@Composable
fun PhoneCallMessageList(
    conversationHistory: List<PhoneCallMessage>,
    currentRecognizedText: String,
    colors: BaoziColors,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(conversationHistory.size) {
        if (conversationHistory.isNotEmpty()) {
            listState.animateScrollToItem(conversationHistory.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 显示对话历史
        items(conversationHistory) { message ->
            PhoneCallMessageItem(
                message = message,
                colors = colors
            )
        }

        // 显示当前识别的文本
        if (currentRecognizedText.isNotEmpty()) {
            item {
                PhoneCallMessageItem(
                    message = PhoneCallMessage(
                        content = currentRecognizedText,
                        isUser = true,
                        timestamp = System.currentTimeMillis()
                    ),
                    colors = colors,
                    isPartial = true
                )
            }
        }
    }
}

/**
 * 手机通话消息项
 */
@Composable
private fun PhoneCallMessageItem(
    message: PhoneCallMessage,
    colors: BaoziColors,
    isPartial: Boolean = false
) {
    val isUser = message.isUser

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = if (isUser) 16.dp else 4.dp,
                        topEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 16.dp
                    )
                )
                .background(
                    if (isUser) {
                        Color(0xFF8B5CF6).copy(alpha = if (isPartial) 0.6f else 1.0f)
                    } else {
                        colors.backgroundCard
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = message.content,
                color = if (isUser) Color.White else colors.textPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun getStateLabel(state: PhoneCallState): String {
    return when (state) {
        is PhoneCallState.Idle -> stringResource(R.string.phone_call_start_hint)
        is PhoneCallState.Recording -> "正在监听语音"
        is PhoneCallState.MicMuted -> stringResource(R.string.phone_call_mic_muted)
        is PhoneCallState.Processing -> "正在处理请求"
        is PhoneCallState.Playing -> "AI 正在回复"
        is PhoneCallState.Operating -> "正在执行操作"
        is PhoneCallState.Error -> stringResource(R.string.phone_call_error)
    }
}

// 轻微震动效果
private fun performLightHaptic(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    if (vibrator?.hasVibrator() == true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(50, 150)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }
}
