package com.alian.assistant.presentation.ui.screens.voicecall

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.alian.assistant.R
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.viewmodel.VoiceCallState
import com.alian.assistant.presentation.viewmodel.VoiceCallViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 语音通话主屏幕
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCallScreen(
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
    onBackClick: () -> Unit
) {
    val colors = BaoziTheme.colors
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val window = (view.context as Activity).window

    // 录音权限状态
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 创建 ViewModel
    val viewModel = remember { VoiceCallViewModel(context) }

    // 录音权限请求 launcher
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasRecordPermission = isGranted
        if (!isGranted) {
            Log.w("VoiceCallScreen", "录音权限被拒绝")
        } else {
            Log.d("VoiceCallScreen", "录音权限已授予")
            // 权限授予后自动开始通话
            viewModel.startCall()
        }
    }

    // 更新配置
    LaunchedEffect(apiKey, baseUrl, model, systemPrompt, ttsVoice, ttsSpeed, ttsInterruptEnabled, enableAEC, enableStreaming, volume) {
        viewModel.updateConfig(apiKey, baseUrl, model, systemPrompt, ttsVoice, ttsSpeed, ttsInterruptEnabled, enableAEC, enableStreaming, volume)
    }

    // 获取状态（使用 collectAsState 自动订阅状态变化）
    val state by viewModel.callState.collectAsState()
    val stateColor = viewModel.getStateColor()
    
    // 监听状态变化
    LaunchedEffect(state) {
        Log.d("VoiceCallScreen", "状态变化: $state")
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d("VoiceCallScreen", "页面销毁，清理资源")
            
            // 停止通话
            viewModel.stopCall()
            
            // 清理 ViewModel
            viewModel.cleanup()
            
            Log.d("VoiceCallScreen", "资源清理完成")
        }
    }

    // 处理返回事件 - 在所有状态下都支持回退
    BackHandler(enabled = true) {
        Log.d("VoiceCallScreen", "用户按下返回键")
        performLightHaptic(context)

        // 如果正在通话中，先停止通话
        if (state !is VoiceCallState.Idle) {
            viewModel.stopCall()
        }

        // 延迟返回，确保资源清理完成
        scope.launch {
            delay(300)
            onBackClick()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            VoiceCallTopBar(
                state = state,
                onBackClick = {
                    performLightHaptic(context)

                    // 如果正在通话中，先停止通话
                    if (state !is VoiceCallState.Idle) {
                        viewModel.stopCall()
                    }

                    // 延迟返回，确保资源清理完成
                    scope.launch {
                        delay(300)
                        onBackClick()
                    }
                }
            )
        },
        bottomBar = {
            VoiceCallControlBar(
                state = state,
                onHangUpClick = {
                    viewModel.stopCall()
                    // 延迟返回，确保资源清理完成
                    scope.launch {
                        delay(300)
                        onBackClick()
                    }
                },
                onStartCallClick = {
                    // 检查录音权限
                    if (!hasRecordPermission) {
                        Log.d("VoiceCallScreen", "录音权限未授予，请求权限")
                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        Log.d("VoiceCallScreen", "录音权限已授予，开始通话")
                        viewModel.startCall()
                    }
                }
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
                modifier = Modifier
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 中央区域
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (state) {
                        is VoiceCallState.Idle -> {
                            // 空闲状态
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                // 声波动画
                                VoiceCallWaveform(
                                    isAnimating = false,
                                    color = colors.textHint,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                )

                                Spacer(modifier = Modifier.height(40.dp))

                                // 提示文本
                                Text(
                                    text = stringResource(R.string.voice_call_title),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.voice_call_start_hint),
                                    fontSize = 15.sp,
                                    color = colors.textSecondary
                                )
                            }
                        }
                        is VoiceCallState.Recording -> {
                            // 录音状态
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                // 声波动画
                                VoiceCallWaveform(
                                    isAnimating = true,
                                    color = stateColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                )

                                Spacer(modifier = Modifier.height(40.dp))

                                // 状态图标
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = stringResource(R.string.voice_call_recording),
                                    tint = stateColor,
                                    modifier = Modifier.size(32.dp)
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // 状态文本
                                Text(
                                    text = stringResource(R.string.voice_call_recording),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.voice_call_speak),
                                    fontSize = 15.sp,
                                    color = colors.textSecondary
                                )
                            }
                        }
                        is VoiceCallState.Processing -> {
                            // 处理状态
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                // 加载动画
                                CircularProgressIndicator(
                                    modifier = Modifier.size(72.dp),
                                    color = stateColor,
                                    strokeWidth = 4.dp
                                )

                                Spacer(modifier = Modifier.height(40.dp))

                                // 状态文本
                                Text(
                                    text = stringResource(R.string.voice_call_thinking),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary
                                )
                            }
                        }
                        is VoiceCallState.Playing -> {
                            // 播放状态
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                // 声波动画
                                VoiceCallWaveform(
                                    isAnimating = true,
                                    color = stateColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                )

                                Spacer(modifier = Modifier.height(40.dp))

                                // 状态图标
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = stringResource(R.string.voice_call_playing),
                                    tint = stateColor,
                                    modifier = Modifier.size(32.dp)
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // 状态文本
                                Text(
                                    text = stringResource(R.string.voice_call_playing),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary
                                )
                            }
                        }
                        is VoiceCallState.Error -> {
                            // 错误状态
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = stringResource(R.string.voice_call_error),
                                    tint = colors.error,
                                    modifier = Modifier.size(72.dp)
                                )

                                Spacer(modifier = Modifier.height(20.dp))

                                Text(
                                    text = stringResource(R.string.voice_call_error),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = (state as VoiceCallState.Error).message,
                                    fontSize = 15.sp,
                                    color = colors.textSecondary
                                )

                                Spacer(modifier = Modifier.height(40.dp))

                                // 重试按钮
                                Button(
                                    onClick = {
                                        scope.launch {
                                            viewModel.startCall()
                                        }
                                    },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .padding(horizontal = 32.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.primary
                                    )
                                ) {
                                    Text(
                                        text = stringResource(R.string.voice_call_retry),
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // 对话消息列表（仅在通话进行时显示）
                if (state !is VoiceCallState.Idle) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        VoiceCallMessageList(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
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