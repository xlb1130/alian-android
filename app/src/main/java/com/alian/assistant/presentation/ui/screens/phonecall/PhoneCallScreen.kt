package com.alian.assistant.presentation.ui.screens.phonecall

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手机通话主屏幕
 * 
 * 支持两种模式：
 * 1. 全屏模式：显示屏幕预览、对话消息、操作状态
 * 2. 浮动窗口模式：仅显示对话消息和控制按钮，用户可自由操作手机
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
    val view = LocalView.current

    // 创建 ViewModel
    val viewModel = remember { PhoneCallViewModel(context) }

    // 浮动窗口视图
    var floatingWindowView by remember { mutableStateOf<PhoneCallFloatingWindowView?>(null) }

    // 获取浮动窗口状态
    val floatingWindowState by viewModel.floatingWindowState.collectAsState()

    // 监听浮动窗口状态变化
    LaunchedEffect(floatingWindowState) {
        Log.d("PhoneCallScreen", "浮动窗口状态变化: $floatingWindowState")
        
        when (floatingWindowState) {
            is FloatingWindowState.Normal, is FloatingWindowState.Maximized, is FloatingWindowState.Minimized -> {
                if (floatingWindowView == null) {
                    floatingWindowView = PhoneCallFloatingWindowView(
                        context = context,
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
                    viewModel.setFloatingWindowView(floatingWindowView)
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

    // 权限检查对话框状态
    var showPermissionDialog by remember { mutableStateOf(false) }
    var permissionCheckResult by remember { mutableStateOf<AgentPermissionCheck.CheckResult?>(null) }

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
            viewModel.setMediaProjectionResult(mediaProjectionResultCode, mediaProjectionData)
            Log.d("PhoneCallScreen", "MediaProjection 权限已更新: resultCode=$mediaProjectionResultCode")

            // 权限更新后重新检查
            val permissionResult = viewModel.checkAllPhoneCallPermissions()
            Log.d("PhoneCallScreen", "权限更新后的检查结果: $permissionResult")
            if (permissionResult is AgentPermissionCheck.CheckResult.Granted) {
                showPermissionDialog = false
                Log.d("PhoneCallScreen", "所有权限已授予")
            } else {
                Log.d("PhoneCallScreen", "权限检查未通过: $permissionResult")
            }
        }
    }

    // 获取状态
    val state by viewModel.callState.collectAsState()
    val stateColor = viewModel.getStateColor()

    // 监听通话状态，通话开始时自动显示浮动窗口
    LaunchedEffect(state) {
        Log.d("PhoneCallScreen", "通话状态变化: $state")
        
        when (state) {
            is PhoneCallState.Recording,
            is PhoneCallState.Processing,
            is PhoneCallState.Playing,
            is PhoneCallState.Operating -> {
                // 通话进行中，显示浮动窗口
                if (floatingWindowView == null) {
                    viewModel.enableFloatingMode()
                }
            }
            is PhoneCallState.Idle -> {
                // 通话结束，隐藏浮动窗口
                if (floatingWindowView != null) {
                    viewModel.disableFloatingMode()
                }
            }
            else -> {}
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
                permissionCheckResult = viewModel.checkAllPhoneCallPermissions()
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
                stateColor = stateColor,
                colors = colors,
                context = context,
                scope = scope,
                onBackClick = onBackClick,
                onStartCall = {
                    val permissionResult = viewModel.checkAllPhoneCallPermissions()
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
                onSwitchToFloating = { viewModel.enableFloatingMode() }
            )
        }
        else -> {
            // 浮动窗口模式
            FloatingWindowPhoneCallUI(
                viewModel = viewModel,
                state = state,
                stateColor = stateColor,
                colors = colors,
                floatingWindowState = floatingWindowState,
                context = context,
                scope = scope,
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
    stateColor: Color,
    colors: BaoziColors,
    context: Context,
    scope: CoroutineScope,
    onBackClick: () -> Unit,
    onStartCall: () -> Unit,
    onStopCall: () -> Unit,
    onSwitchToFloating: () -> Unit
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
                state = state,
                onBackClick = onBackClick,
                onSwitchModeClick = onSwitchToFloating,
                modeName = "全屏模式"
            )
        },
        bottomBar = {
            PhoneCallControlBar(
                state = state,
                onHangUpClick = onStopCall,
                onStartCallClick = onStartCall,
                colors = colors,
                stateColor = stateColor
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
 * 浮动窗口模式 UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FloatingWindowPhoneCallUI(
    viewModel: PhoneCallViewModel,
    state: PhoneCallState,
    stateColor: Color,
    colors: BaoziColors,
    floatingWindowState: FloatingWindowState,
    context: Context,
    scope: CoroutineScope,
    onBackClick: () -> Unit,
    onStopCall: () -> Unit,
    onSwitchToFullScreen: () -> Unit
) {
    val conversationHistory = viewModel.conversationHistory
    val currentRecognizedText by viewModel.currentRecognizedText
    val currentOperation by viewModel.currentOperation
    val isAiOperating by viewModel.isAiOperating
    val windowOpacity by viewModel.floatingWindowOpacity

    // 浮动窗口透明度动画
    val opacity by animateFloatAsState(
        targetValue = windowOpacity,
        animationSpec = tween(durationMillis = 300),
        label = "windowOpacity"
    )

    // 根据状态调整窗口大小
    val windowWidth = when (floatingWindowState) {
        is FloatingWindowState.Minimized -> 120.dp
        is FloatingWindowState.Maximized -> 400.dp
        else -> 360.dp
    }

    val windowHeight = when (floatingWindowState) {
        is FloatingWindowState.Minimized -> 80.dp
        is FloatingWindowState.Maximized -> 600.dp
        else -> 540.dp
    }

    // 浮动窗口容器
    Box(
        modifier = Modifier
            .width(windowWidth)
            .height(windowHeight)
            .alpha(opacity)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.backgroundCard.copy(alpha = 0.95f))
            .zIndex(1000f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 最小化按钮
                IconButton(
                    onClick = {
                        when (floatingWindowState) {
                            is FloatingWindowState.Normal -> viewModel.minimizeFloatingWindow()
                            is FloatingWindowState.Maximized -> viewModel.minimizeFloatingWindow()
                            else -> viewModel.showFloatingWindow()
                        }
                        performLightHaptic(context)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = when (floatingWindowState) {
                            is FloatingWindowState.Minimized -> Icons.Default.OpenInFull
                            else -> Icons.Default.Minimize
                        },
                        contentDescription = stringResource(R.string.phone_call_minimize),
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 标题
                Text(
                    text = stringResource(R.string.phone_call_title),
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                // 关闭按钮
                IconButton(
                    onClick = {
                        performLightHaptic(context)
                        onStopCall()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.phone_call_close),
                        tint = colors.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 内容区域
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (floatingWindowState) {
                    is FloatingWindowState.Minimized -> {
                        // 最小化状态：显示状态图标
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = when (state) {
                                    is PhoneCallState.Idle -> Icons.Default.Phone
                                    is PhoneCallState.Recording -> Icons.Default.Mic
                                    is PhoneCallState.Processing -> Icons.Default.Psychology
                                    is PhoneCallState.Playing -> Icons.Default.VolumeUp
                                    is PhoneCallState.Operating -> Icons.Default.TouchApp
                                    is PhoneCallState.Error -> Icons.Default.Error
                                },
                                contentDescription = null,
                                tint = stateColor,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = viewModel.getStateDescription(),
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    else -> {
                        // 正常/最大化状态：显示对话消息
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // 对话消息区域 (70%)
                            Box(
                                modifier = Modifier
                                    .weight(0.7f)
                                    .fillMaxWidth()
                            ) {
                                PhoneCallMessageList(
                                    conversationHistory = conversationHistory,
                                    currentRecognizedText = currentRecognizedText,
                                    colors = colors,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // 操作状态栏 (10%)
                            if (isAiOperating) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF8B5CF6).copy(alpha = 0.2f))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            color = Color(0xFF8B5CF6),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = currentOperation,
                                            color = Color(0xFF8B5CF6),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            // 控制按钮区域 (20%)
                            PhoneCallControlBar(
                                state = state,
                                onHangUpClick = onStopCall,
                                onStartCallClick = {},
                                colors = colors,
                                stateColor = stateColor
                            )
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
    state: PhoneCallState,
    onBackClick: () -> Unit,
    onSwitchModeClick: () -> Unit,
    modeName: String
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
            // 标题
            Text(
                text = stringResource(R.string.phone_call_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
        }
    }
}

/**
 * 手机通话控制栏
 */
@Composable
fun PhoneCallControlBar(
    state: PhoneCallState,
    onHangUpClick: () -> Unit,
    onStartCallClick: () -> Unit,
    colors: BaoziColors,
    stateColor: Color
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
            icon = Icons.Default.Mic,
            contentDescription = stringResource(R.string.call_mic),
            iconColor = when (state) {
                is PhoneCallState.Recording -> Color(0xFF8B5CF6)
                else -> colors.textSecondary
            },
            backgroundColor = when (state) {
                is PhoneCallState.Recording -> Color(0xFF8B5CF6).copy(alpha = 0.2f)
                else -> colors.backgroundCard
            },
            onClick = {
                performLightHaptic(context)
                // TODO: 切换麦克风开关
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
            onClick = {
                performLightHaptic(context)
                // TODO: 显示文本输入对话框
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
    val isPressed by interactionSource.collectIsPressedAsState()

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
