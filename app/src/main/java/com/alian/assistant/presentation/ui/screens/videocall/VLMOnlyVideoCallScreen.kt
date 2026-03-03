package com.alian.assistant.presentation.ui.screens.videocall

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.alian.assistant.R
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.viewmodel.VLMOnlyVideoCallViewModel
import com.alian.assistant.presentation.viewmodel.VideoCallState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 仅使用视觉大模型的视频通话主屏幕
 * 功能特点：
 * 1. 相机帧每5秒抓取并压缩保存
 * 2. ASR识别到用户文本后，与图像一起交由视觉大模型处理
 * 3. 支持流式返回响应
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VLMOnlyVideoCallScreen(
    context: Context,
    apiKey: String = "",
    baseUrl: String = "",
    model: String = "qwen-vl-max",
    systemPrompt: String = "",
    ttsVoice: String = "longyingmu_v3",
    ttsSpeed: Float = 1.0f,
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

    // 创建 ViewModel（仅使用视觉大模型）
    val viewModel =
        remember("vlm_only_video_call_view_model") { VLMOnlyVideoCallViewModel(context) }

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
            Log.w("VLMOnlyVideoCallScreen", "相机权限被拒绝")
        } else {
            Log.d("VLMOnlyVideoCallScreen", "相机权限已授予")
            // 相机权限授予后，检查录音权限
            if (hasRecordPermission) {
                scope.launch {
                    viewModel.startCall()
                }
            }
        }
    }

    // 录音权限请求 launcher
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasRecordPermission = isGranted
        if (!isGranted) {
            Log.w("VLMOnlyVideoCallScreen", "录音权限被拒绝")
        } else {
            Log.d("VLMOnlyVideoCallScreen", "录音权限已授予")
            // 录音权限授予后，检查相机权限
            if (hasCameraPermission) {
                scope.launch {
                    viewModel.startCall()
                }
            }
        }
    }

    // 更新配置
    LaunchedEffect(
        apiKey,
        baseUrl,
        model,
        systemPrompt,
        ttsVoice,
        ttsSpeed,
        ttsInterruptEnabled,
        enableAEC,
        enableStreaming
    ) {
        viewModel.updateConfig(
            apiKey,
            baseUrl,
            model,
            systemPrompt,
            ttsVoice,
            ttsSpeed,
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
        Log.d("VLMOnlyVideoCallScreen", "状态变化: $state")
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d("VLMOnlyVideoCallScreen", "页面销毁，清理资源")
            viewModel.stopCall()
            viewModel.cleanup()
            Log.d("VLMOnlyVideoCallScreen", "资源清理完成")
        }
    }

    // 处理返回事件
    BackHandler(enabled = true) {
        Log.d("VLMOnlyVideoCallScreen", "用户按下返回键")
        performLightHaptic(context)
        if (state !is VideoCallState.Idle) {
            viewModel.stopCall()
        }
        scope.launch {
            delay(300)
            onBackClick()
        }
    }

    // 页面进入/退出动画
    var isPageVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isPageVisible = true
    }

    AnimatedVisibility(
        visible = isPageVisible,
        enter = fadeIn(
            animationSpec = tween(400, easing = EaseOut)
        ),
        exit = fadeOut(
            animationSpec = tween(300, easing = EaseIn)
        )
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
            containerColor = colors.background,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                VLMOnlyVideoCallTopBar(
                    state = state,
                    imageHistorySize = viewModel.imageHistory.size,
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
            },
            bottomBar = {
                VLMOnlyVideoCallControlBar(
                    state = state,
                    onHangUpClick = {
                        viewModel.stopCall()
                        scope.launch {
                            delay(300)
                            onBackClick()
                        }
                    },
                    onStartCallClick = {
                        Log.d("VLMOnlyVideoCallScreen", "[VLM-ONLY] onStartCallClick 被调用")
                        // 检查相机和录音权限
                        if (!hasCameraPermission) {
                            Log.d("VLMOnlyVideoCallScreen", "[VLM-ONLY] 相机权限未授予，请求权限")
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        } else if (!hasRecordPermission) {
                            Log.d("VLMOnlyVideoCallScreen", "[VLM-ONLY] 录音权限未授予，请求权限")
                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            Log.d("VLMOnlyVideoCallScreen", "[VLM-ONLY] 相机和录音权限都已授予，开始通话")
                            scope.launch {
                                // 开始通话（会等待相机就绪）
                                viewModel.startCall()
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(colors.background),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 中央区域 - 相机预览
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Log.d("VLMOnlyVideoCallScreen", "[VLM-ONLY] 当前状态: $state")

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
                            Log.d("VLMOnlyVideoCallScreen", "[VLM-ONLY] onCameraReady 回调: $ready")
                            viewModel.setCameraReady(ready)
                        }
                    )

                    // 状态指示和提示信息
                    when (state) {
                        is VideoCallState.Idle -> {
                            // 空闲状态 - 显示提示信息
                            Log.d("VLMOnlyVideoCallScreen", "[VLM-ONLY] Idle 状态")
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                colors.primary.copy(alpha = 0.08f),
                                                colors.background,
                                                colors.background
                                            ),
                                            center = Offset(
                                                0.5f,
                                                0.4f
                                            ),
                                            radius = 0.6f
                                        )
                                    )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    // 现代图标 - 视频通话
                                    Box(
                                        modifier = Modifier
                                            .size(120.dp)
                                            .background(
                                                colors.primary.copy(alpha = 0.15f),
                                                shape = CircleShape
                                            )
                                            .padding(24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Videocam,
                                            contentDescription = "视频通话",
                                            tint = colors.primary,
                                            modifier = Modifier.size(72.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(32.dp))

                                    // 渐变标题
                                    Text(
                                        text = stringResource(R.string.vlm_video_call_title),
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.Bold,
                                        style = TextStyle(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(
                                                    colors.primary,
                                                    colors.secondary
                                                )
                                            )
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Text(
                                        text = stringResource(R.string.vlm_video_call_start_hint),
                                        fontSize = 14.sp,
                                        color = colors.textSecondary
                                    )

                                    Spacer(modifier = Modifier.height(32.dp))

                                    // 功能说明卡片
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        FeatureCard(
                                            icon = "📷",
                                            title = stringResource(R.string.vlm_video_call_feature_capture),
                                            description = stringResource(R.string.vlm_video_call_feature_capture_desc)
                                        )
                                        FeatureCard(
                                            icon = "🤖",
                                            title = stringResource(R.string.vlm_video_call_feature_vision),
                                            description = stringResource(R.string.vlm_video_call_feature_vision_desc)
                                        )
                                        FeatureCard(
                                            icon = "⚡",
                                            title = stringResource(R.string.vlm_video_call_feature_realtime),
                                            description = stringResource(R.string.vlm_video_call_feature_realtime_desc)
                                        )
                                    }
                                }
                            }
                        }

                        is VideoCallState.Recording -> {
                            // 录音状态指示 - 毛玻璃效果 + 脉冲动画
                            val infiniteTransition =
                                rememberInfiniteTransition(label = "recording_pulse")
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 0.7f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "pulseAlpha"
                            )

                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 32.dp, start = 16.dp, end = 16.dp)
                                    .background(
                                        Color.White.copy(alpha = 0.15f),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                            12.dp
                                        )
                                    )
                                    .drawWithContent {
                                        drawContent()
                                        // 渐变边框
                                        drawRoundRect(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    Color.White.copy(alpha = 0.3f),
                                                    Color.Transparent
                                                )
                                            ),
                                            style = Stroke(
                                                width = 1.dp.toPx(),
                                            ),
                                            cornerRadius = CornerRadius(
                                                12.dp.toPx(),
                                                12.dp.toPx()
                                            )
                                        )
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 脉冲圆点
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color.Red.copy(alpha = pulseAlpha))
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.vlm_video_call_recording) + "...",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                        style = TextStyle(
                                            shadow = Shadow(
                                                color = Color.Black.copy(alpha = 0.5f),
                                                blurRadius = 4f,
                                                offset = Offset(0f, 1f)
                                            )
                                        )
                                    )
                                }
                            }
                        }

                        is VideoCallState.Processing -> {
                            // 处理状态指示 - 毛玻璃效果
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 32.dp, start = 16.dp, end = 16.dp)
                                    .background(
                                        Color.White.copy(alpha = 0.15f),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                            12.dp
                                        )
                                    )
                                    .drawWithContent {
                                        drawContent()
                                        // 渐变边框
                                        drawRoundRect(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    Color.White.copy(alpha = 0.3f),
                                                    Color.Transparent
                                                )
                                            ),
                                            style = Stroke(
                                                width = 1.dp.toPx(),
                                            ),
                                            cornerRadius = CornerRadius(
                                                12.dp.toPx(),
                                                12.dp.toPx()
                                            )
                                        )
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.vlm_video_call_thinking_hint),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                        style = TextStyle(
                                            shadow = Shadow(
                                                color = Color.Black.copy(alpha = 0.5f),
                                                blurRadius = 4f,
                                                offset = Offset(0f, 1f)
                                            )
                                        )
                                    )
                                }
                            }
                        }

                        is VideoCallState.Playing -> {
                            // 播放状态指示 - 毛玻璃效果
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 32.dp, start = 16.dp, end = 16.dp)
                                    .background(
                                        Color.White.copy(alpha = 0.15f),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                            12.dp
                                        )
                                    )
                                    .drawWithContent {
                                        drawContent()
                                        // 渐变边框
                                        drawRoundRect(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    Color.White.copy(alpha = 0.3f),
                                                    Color.Transparent
                                                )
                                            ),
                                            style = Stroke(
                                                width = 1.dp.toPx(),
                                            ),
                                            cornerRadius = CornerRadius(
                                                12.dp.toPx(),
                                                12.dp.toPx()
                                            )
                                        )
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 播放波形图标
                                    Icons.Default.VolumeUp.let { icon ->
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = stringResource(R.string.vlm_video_call_playing),
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.vlm_video_call_playing) + "...",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                        style = TextStyle(
                                            shadow = Shadow(
                                                color = Color.Black.copy(alpha = 0.5f),
                                                blurRadius = 4f,
                                                offset = Offset(0f, 1f)
                                            )
                                        )
                                    )
                                }
                            }
                        }

                        is VideoCallState.Error -> {
                            // 错误状态
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = stringResource(R.string.vlm_video_call_error),
                                    tint = colors.error,
                                    modifier = Modifier.size(80.dp)
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = stringResource(R.string.vlm_video_call_error),
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
                                            text = stringResource(R.string.vlm_video_call_retry),
                                            color = Color.White
                                        )
                                    }

                                    // 关闭按钮
                                    Button(
                                        onClick = {
                                            onBackClick()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.Gray
                                        )
                                    ) {
                                        Text(
                                            text = stringResource(R.string.vlm_video_call_close),
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 对话消息区域（在相机下方，不覆盖相机）
                if (state !is VideoCallState.Idle) {
                    val conversationHistory = viewModel.conversationHistory
                    val messages = conversationHistory.takeLast(1).toList() // 只显示最新的一条消息

                    // 只显示存在的消息
                    if (messages.isNotEmpty()) {
                        val message = messages[0]
                        // 进入动画
                        val enterAnimation by animateFloatAsState(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = 300,
                                easing = EaseOut
                            ),
                            label = "messageEnter"
                        )

                        AnimatedVisibility(
                            visible = true,
                            enter = slideInVertically(
                                initialOffsetY = { it -> it },
                                animationSpec = tween(300)
                            ) + fadeIn(animationSpec = tween(300)),
                            exit = slideOutVertically(
                                targetOffsetY = { it -> it },
                                animationSpec = tween(300)
                            ) + fadeOut(animationSpec = tween(300))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = 16.dp,
                                        top = 8.dp,
                                        end = 16.dp,
                                        bottom = 8.dp
                                    )
                                    .graphicsLayer {
                                        alpha = enterAnimation
                                        translationY = (1f - enterAnimation) * 30f
                                    }
                                    .background(
                                        colors.backgroundCard,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                            16.dp
                                        )
                                    )
                                    .padding(horizontal = 18.dp, vertical = 14.dp)
                            ) {
                                Text(
                                    text = message.content.take(120) + if (message.content.length > 120) "..." else "",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    maxLines = 3
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 视觉大模型视频通话顶部栏
 */
@Composable
private fun VLMOnlyVideoCallTopBar(
    state: VideoCallState,
    imageHistorySize: Int,
    onBackClick: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    val maxImageHistorySize = 5  // 最大帧数限制

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
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 返回按钮 - 与AlianChat的菜单按钮样式一致
                val backInteractionSource = remember { MutableInteractionSource() }
                val backIsPressed by backInteractionSource.collectIsPressedAsState()

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (backIsPressed) colors.primary.copy(alpha = 0.1f)
                            else Color.Transparent
                        )
                        .scale(if (backIsPressed) 0.95f else 1f)
                        .clickable(
                            interactionSource = backInteractionSource,
                            indication = null,
                            onClick = onBackClick
                        ),
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
                    // 渐变标题文字 - 与AlianChat的标题样式一致
                    Text(
                        text = when (state) {
                            is VideoCallState.Idle -> stringResource(R.string.vlm_video_call_title)
                            is VideoCallState.Recording -> stringResource(R.string.vlm_video_call_recording)
                            is VideoCallState.Processing -> stringResource(R.string.vlm_video_call_thinking)
                            is VideoCallState.Playing -> stringResource(R.string.vlm_video_call_playing)
                            is VideoCallState.Error -> stringResource(R.string.vlm_video_call_error)
                        },
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    // 动态副标题
                    val subtitleText = when (state) {
                        is VideoCallState.Idle -> stringResource(R.string.vlm_video_call_start_hint)
                        is VideoCallState.Recording -> stringResource(R.string.vlm_video_call_speak)
                        is VideoCallState.Processing -> stringResource(R.string.vlm_video_call_thinking_hint)
                        is VideoCallState.Playing -> stringResource(R.string.vlm_video_call_playing_hint)
                        is VideoCallState.Error -> stringResource(R.string.vlm_video_call_error_hint)
                    }
                    Text(
                        text = subtitleText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 状态指示器（右侧）
            if (state !is VideoCallState.Idle) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = stateColor.copy(alpha = animatedAlpha),
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = when (state) {
                                is VideoCallState.Recording -> stringResource(R.string.vlm_video_call_recording_status)
                                is VideoCallState.Processing -> stringResource(R.string.vlm_video_call_thinking_status)
                                is VideoCallState.Playing -> stringResource(R.string.vlm_video_call_playing_status)
                                else -> ""
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = stateColor
                        )
                        Text(
                            text = stringResource(R.string.vlm_video_call_frames_captured, imageHistorySize, maxImageHistorySize),
                            fontSize = 10.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 视觉大模型视频通话控制栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VLMOnlyVideoCallControlBar(
    state: VideoCallState,
    onHangUpClick: () -> Unit,
    onStartCallClick: () -> Unit
) {
    val colors = BaoziTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state) {
            is VideoCallState.Idle -> {
                // 开始通话按钮 - 带点击动画
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.92f else 1f,
                    animationSpec = tween(
                        150,
                        easing = EaseOutBack
                    ),
                    label = "buttonScale"
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .shadow(
                            elevation = if (isPressed) 4.dp else 8.dp,
                            shape = CircleShape,
                            spotColor = Color(0xFF4CAF50).copy(alpha = 0.4f)
                        )
                ) {
                    Button(
                        onClick = onStartCallClick,
                        modifier = Modifier
                            .fillMaxSize(),
                        interactionSource = interactionSource,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPressed) Color(0xFF43A047) else Color(0xFF4CAF50)
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = stringResource(R.string.video_call_start),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            else -> {
                // 挂断按钮 - 带点击动画
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.92f else 1f,
                    animationSpec = tween(
                        150,
                        easing = EaseOutBack
                    ),
                    label = "buttonScale"
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .shadow(
                            elevation = if (isPressed) 4.dp else 8.dp,
                            shape = CircleShape,
                            spotColor = Color(0xFFF44336).copy(alpha = 0.4f)
                        )
                ) {
                    Button(
                        onClick = onHangUpClick,
                        modifier = Modifier
                            .fillMaxSize(),
                        interactionSource = interactionSource,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPressed) Color(0xFFE53935) else Color(0xFFF44336)
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = stringResource(R.string.video_call_end),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 视觉大模型视频通话消息列表
 */
@Composable
private fun VLMOnlyVideoCallMessageList(
    viewModel: VLMOnlyVideoCallViewModel,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    val conversationHistory = viewModel.conversationHistory

    if (conversationHistory.isEmpty()) {
        // 对话记录为空时不显示任何内容
    } else {
        Column(
            modifier = modifier
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 显示最近的对话消息
            conversationHistory.takeLast(2).forEach { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.isUser) {
                        Arrangement.End
                    } else {
                        Arrangement.Start
                    }
                ) {
                    Text(
                        text = message.content,
                        fontSize = 16.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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

/**
 * 功能特性卡片组件
 */
@Composable
private fun FeatureCard(
    icon: String,
    title: String,
    description: String
) {
    val colors = BaoziTheme.colors

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.backgroundCard.copy(alpha = 0.8f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Text(
                text = icon,
                fontSize = 28.sp
            )

            Spacer(modifier = Modifier.width(16.dp))

            // 文本内容
            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = colors.textSecondary
                )
            }
        }
    }
}