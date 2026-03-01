package com.alian.assistant.presentation.ui.screens.videocall

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.alian.assistant.presentation.ui.screens.voicecall.VoiceCallWaveform
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.viewmodel.VideoCallState
import com.alian.assistant.presentation.viewmodel.VideoCallViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 视频通话主屏幕
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoCallScreen(
    context: Context,
    apiKey: String = "",
    baseUrl: String = "",
    model: String = "qwen-vl-max",
    ttsVoice: String = "longyingmu_v3",
    ttsInterruptEnabled: Boolean = false,
    enableAEC: Boolean = false,
    enableStreaming: Boolean = false,
    volume: Int = 50,
    onBackClick: () -> Unit
) {
    val colors = BaoziTheme.colors
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val window = (view.context as Activity).window
    val context = LocalContext.current

    // 创建 ViewModel
    val viewModel = remember("video_call_view_model") { VideoCallViewModel(context) }

    // 相机权限状态
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 录音权限状态
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 相机权限请求 launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Log.w("VideoCallScreen", "相机权限被拒绝")
        } else {
            Log.d("VideoCallScreen", "相机权限已授予")
        }
    }

    // 录音权限请求 launcher
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasRecordPermission = isGranted
        if (!isGranted) {
            Log.w("VideoCallScreen", "录音权限被拒绝")
        } else {
            Log.d("VideoCallScreen", "录音权限已授予")
        }
    }

    // 更新配置
    LaunchedEffect(
        apiKey,
        baseUrl,
        model,
        ttsVoice,
        ttsInterruptEnabled,
        enableAEC,
        enableStreaming,
        volume
    ) {
        viewModel.updateConfig(
            apiKey,
            baseUrl,
            model,
            ttsVoice,
            ttsInterruptEnabled,
            enableAEC,
            enableStreaming,
            volume
        )
    }

    // 获取状态
    val state by viewModel.callState.collectAsState()
    val stateColor = viewModel.getStateColor()

    // 监听状态变化
    LaunchedEffect(state) {
        Log.d("VideoCallScreen", "状态变化: $state")
    }

    // 进入全屏模式
    DisposableEffect(Unit) {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        onDispose {
            Log.d("VideoCallScreen", "页面销毁，清理资源")
            viewModel.stopCall()
            viewModel.cleanup()
            // 延迟恢复窗口状态，避免 IME insets 变化触发输入框自动获得焦点
            scope.launch {
                delay(150)
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                WindowCompat.setDecorFitsSystemWindows(window, true)
                window.insetsController?.show(WindowInsets.Type.systemBars())
                Log.d("VideoCallScreen", "窗口状态已恢复")
            }
            Log.d("VideoCallScreen", "资源清理完成")
        }
    }

    // 处理返回事件
    BackHandler(enabled = state !is VideoCallState.Idle) {
        Log.d("VideoCallScreen", "用户按下返回键，停止通话")
        performLightHaptic(context)
        if (state !is VideoCallState.Idle) {
            viewModel.stopCall()
        }
        scope.launch {
            delay(300)
            onBackClick()
        }
    }

    // 相机预览区域 - 占满整个屏幕
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Log.d("VideoCallScreen", "[DEBUG] 当前状态: $state")

        // enable 参数在通话进行时为 true，通话结束时为 false
        val shouldEnableCamera = state !is VideoCallState.Idle

        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            enable = shouldEnableCamera,
            hasPermission = hasCameraPermission,
            onFrameCaptured = { bitmap ->
                viewModel.updateCurrentFrame(bitmap)
            },
            onCameraReady = { ready ->
                Log.d("VideoCallScreen", "[DEBUG] onCameraReady 回调: $ready")
                viewModel.setCameraReady(ready)
            }
        )

        // 顶部栏（叠加在相机之上）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
        ) {
            VideoCallTopBar(
                state = state,
                onBackClick = {
                    performLightHaptic(context)
                    if (state !is VideoCallState.Idle) {
                        viewModel.stopCall()
                    }
                    scope.launch {
                        delay(300)
                        onBackClick()
                    }
                }
            )
        }

        // 状态指示和提示信息
        when (state) {
            is VideoCallState.Idle -> {
                // 空闲状态 - 显示提示信息
                Log.d("VideoCallScreen", "[DEBUG] Idle 状态")
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 声波动画
                    VoiceCallWaveform(
                        isAnimating = false,
                        color = colors.textHint,
                        modifier = Modifier
                            .width(300.dp)
                            .height(200.dp)
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // 提示文本
                    Text(
                        text = "视频通话",
                        fontSize = 24.sp,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击下方按钮开始",
                        fontSize = 14.sp,
                        color = colors.textSecondary
                    )
                }
            }

            is VideoCallState.Recording -> {
                // 录音状态指示
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(start = 16.dp, top = 80.dp, end = 16.dp) // 避开顶部栏
                        .background(
                            colors.backgroundCard.copy(alpha = 0.8f),
                            shape = MaterialTheme.shapes.medium
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "正在录音...",
                        fontSize = 16.sp,
                        color = colors.textPrimary
                    )
                }
            }

            is VideoCallState.Processing -> {
                // 处理状态指示
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(start = 16.dp, top = 80.dp, end = 16.dp) // 避开顶部栏
                        .background(
                            colors.backgroundCard.copy(alpha = 0.8f),
                            shape = MaterialTheme.shapes.medium
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = colors.primary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在思考...",
                            fontSize = 16.sp,
                            color = colors.textPrimary
                        )
                    }
                }
            }

            is VideoCallState.Playing -> {
                // 播放状态指示
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(start = 16.dp, top = 80.dp, end = 16.dp) // 避开顶部栏
                        .background(
                            colors.backgroundCard.copy(alpha = 0.8f),
                            shape = MaterialTheme.shapes.medium
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "正在播放...",
                        fontSize = 16.sp,
                        color = colors.textPrimary
                    )
                }
            }

            is VideoCallState.Error -> {
                // 错误状态
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "错误",
                        tint = colors.error,
                        modifier = Modifier.size(80.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "发生错误",
                        fontSize = 20.sp,
                        color = colors.textPrimary
                    )
                    Text(
                        text = (state as VideoCallState.Error).message,
                        fontSize = 14.sp,
                        color = colors.textSecondary
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Row(
                        modifier = Modifier.wrapContentWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 重试按钮
                        Button(
                            onClick = {
                                scope.launch {
                                    viewModel.startCall()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary
                            )
                        ) {
                            Text(
                                text = "重试",
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }

                        // 关闭按钮
                        Button(
                            onClick = {
                                onBackClick()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.textSecondary
                            )
                        ) {
                            Text(
                                text = "关闭",
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }

        // 对话消息列表（浮动在相机之上，与状态文字相同的位置）
        if (state !is VideoCallState.Idle) {
            val conversationHistory = viewModel.conversationHistory
            val messages = conversationHistory.takeLast(1).toList() // 只显示最新的一条消息

            // 只显示存在的消息
            if (messages.isNotEmpty()) {
                val message = messages[0]
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(start = 16.dp, top = 110.dp, end = 16.dp) // 在状态文字下方
                        .background(
                            colors.backgroundCard.copy(alpha = 0.8f),
                            shape = MaterialTheme.shapes.medium
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = message.content.take(100) + if (message.content.length > 100) "..." else "",
                        fontSize = 16.sp,
                        color = colors.textPrimary,
                        maxLines = 3,
                        style = TextStyle(
                            shadow = Shadow(
                                color = Color.Black,
                                blurRadius = 4f,
                                offset = Offset(1f, 1f)
                            )
                        )
                    )
                }
            }
        }

        // 控制栏（叠加在相机之上，最底部）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
        ) {
            VideoCallControlBar(
                state = state,
                onHangUpClick = {
                    viewModel.stopCall()
                    scope.launch {
                        delay(300)
                        onBackClick()
                    }
                },
                onStartCallClick = {
                    Log.d("VideoCallScreen", "[DEBUG] onStartCallClick 被调用")
                    // 检查相机和录音权限
                    if (!hasCameraPermission) {
                        Log.d("VideoCallScreen", "[DEBUG] 相机权限未授予，请求权限")
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else if (!hasRecordPermission) {
                        Log.d("VideoCallScreen", "[DEBUG] 录音权限未授予，请求权限")
                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        Log.d("VideoCallScreen", "[DEBUG] 相机和录音权限都已授予，开始通话")
                        scope.launch {
                            // 开始通话（会等待相机就绪）
                            viewModel.startCall()
                        }
                    }
                }
            )
        }
    }

}

/**
 * 视频通话顶部栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoCallTopBar(
    state: VideoCallState,
    onBackClick: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    val stateDescription = when (state) {
        is VideoCallState.Idle -> "视频通话"
        is VideoCallState.Recording -> "正在录音"
        is VideoCallState.Processing -> "正在思考"
        is VideoCallState.Playing -> "正在播放"
        is VideoCallState.Error -> "连接错误"
    }

    val stateColor = when (state) {
        is VideoCallState.Idle -> colors.textSecondary
        is VideoCallState.Recording -> Color(0xFF10B981)  // 现代绿色
        is VideoCallState.Processing -> Color(0xFFF59E0B)  // 现代橙色
        is VideoCallState.Playing -> Color(0xFF3B82F6)  // 现代蓝色
        is VideoCallState.Error -> Color(0xFFEF4444)  // 现代红色
    }

    // 状态指示器动画
    val infiniteTransition = rememberInfiniteTransition(label = "statusIndicator")
    val animatedAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = colors.background.copy(alpha = 0.8f)
            )
    ) {
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (state !is VideoCallState.Idle) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = stateColor.copy(alpha = animatedAlpha),
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = stateDescription,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = stateColor
                    )
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = onBackClick
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.textPrimary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // 状态指示器
        if (state !is VideoCallState.Idle) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                stateColor.copy(alpha = 0.3f),
                                stateColor,
                                stateColor.copy(alpha = 0.3f)
                            )
                        )
                    )
                    .align(Alignment.BottomCenter)
            )
        }
    }
}

/**
 * 视频通话控制栏
 */
@Composable
private fun VideoCallControlBar(
    state: VideoCallState,
    onHangUpClick: () -> Unit,
    onStartCallClick: () -> Unit
) {
    val colors = BaoziTheme.colors

    Row(
        modifier = Modifier
            .wrapContentWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state) {
            is VideoCallState.Idle -> {
                // 开始通话按钮
                Button(
                    onClick = onStartCallClick,
                    modifier = Modifier
                        .size(80.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "开始通话",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            else -> {
                // 挂断按钮
                Button(
                    onClick = onHangUpClick,
                    modifier = Modifier
                        .size(80.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.error
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "挂断",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

/**
 * 视频通话消息列表
 */
@Composable
private fun VideoCallMessageList(
    viewModel: VideoCallViewModel,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    val conversationHistory = viewModel.conversationHistory

    if (conversationHistory.isEmpty()) {
        Box(
            modifier = modifier
                .wrapContentWidth()
                .wrapContentHeight()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "对话记录为空",
                fontSize = 14.sp,
                color = Color.White,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black,
                        blurRadius = 4f,
                        offset = Offset(1f, 1f)
                    )
                )
            )
        }
    } else {
        Column(
            modifier = modifier
                .wrapContentWidth()
                .wrapContentHeight()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            conversationHistory.takeLast(3).forEach { message ->
                Box(
                    modifier = Modifier
                        .wrapContentWidth(align = if (message.isUser) {
                            Alignment.End
                        } else {
                            Alignment.Start
                        })
                ) {
                    Text(
                        text = message.content.take(50) + if (message.content.length > 50) "..." else "",
                        fontSize = 12.sp,
                        color = Color.White,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = TextStyle(
                            shadow = Shadow(
                                color = Color.Black,
                                blurRadius = 6f,
                                offset = Offset(2f, 2f)
                            )
                        )
                    )
                }
            }
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