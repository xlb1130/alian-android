package com.alian.assistant.presentation.ui.screens.voicecall

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.R
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.viewmodel.VoiceCallMessage
import com.alian.assistant.presentation.viewmodel.VoiceCallState
import com.alian.assistant.presentation.viewmodel.VoiceCallViewModel

/**
 * 顶部栏
 */
@Composable
fun VoiceCallTopBar(
    state: VoiceCallState,
    onBackClick: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    val stateDescription = when (state) {
        is VoiceCallState.Idle -> stringResource(R.string.voice_call_state_idle)
        is VoiceCallState.Recording -> stringResource(R.string.voice_call_state_recording)
        is VoiceCallState.Processing -> stringResource(R.string.voice_call_state_processing)
        is VoiceCallState.Playing -> stringResource(R.string.voice_call_state_playing)
        is VoiceCallState.Error -> stringResource(R.string.voice_call_state_error)
    }

    val stateColor = when (state) {
        is VoiceCallState.Idle -> colors.textSecondary
        is VoiceCallState.Recording -> Color(0xFF10B981)  // 现代绿色
        is VoiceCallState.Processing -> Color(0xFFF59E0B)  // 现代橙色
        is VoiceCallState.Playing -> Color(0xFF3B82F6)  // 现代蓝色
        is VoiceCallState.Error -> Color(0xFFEF4444)  // 现代红色
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
            .background(colors.background)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回按钮
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.btn_back),
                    tint = colors.textPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 标题
            Text(
                text = stringResource(R.string.voice_call_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
        }

        // 状态指示器
        if (state !is VoiceCallState.Idle) {
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
 * 对话消息列表
 */
@Composable
fun VoiceCallMessageList(
    viewModel: VoiceCallViewModel,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors

    // 获取状态
    val state = viewModel.callState.value
    val currentRecognizedText = viewModel.currentRecognizedText.value
    val currentPlayingMessage = viewModel.currentPlayingMessage.value

    // 获取对话历史
    val conversationHistory = viewModel.conversationHistory

    // 只显示最近的 3 条对话
    val recentMessages = conversationHistory.takeLast(3)

    // 如果正在播放，排除最后一条消息（因为正在播放的那条消息会在 currentPlayingMessage 中显示）
    val messagesToShow = if (state is VoiceCallState.Playing && currentPlayingMessage.isNotBlank()) {
        recentMessages.dropLast(1)
    } else {
        recentMessages
    }

    Column(
        modifier = modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 显示当前播放的文本（播放时）
        if (state is VoiceCallState.Playing && currentPlayingMessage.isNotBlank()) {
            CurrentPlayingTextBubble(
                text = currentPlayingMessage,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 显示对话历史
        messagesToShow.forEach { message ->
            MessageBubble(
                message = message,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 显示当前识别的文本（录音时）- 作为实时更新的用户消息显示在最后
        if (state is VoiceCallState.Recording && currentRecognizedText.isNotBlank()) {
            MessageBubble(
                message = VoiceCallMessage(
                    id = "current_recording",
                    content = currentRecognizedText,
                    isUser = true
                ),
                modifier = Modifier.fillMaxWidth(),
                isRecording = true
            )
        }
    }
}

/**
 * 当前识别文本气泡
 */
@Composable
fun CurrentRecognizedTextBubble(
    text: String,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val animatedAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.backgroundCard
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 录音图标
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = stringResource(R.string.voice_call_recording_text),
                tint = Color(0xFF4CAF50).copy(alpha = animatedAlpha),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = colors.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 当前播放文本气泡
 */
@Composable
fun CurrentPlayingTextBubble(
    text: String,
    modifier: Modifier = Modifier,
    onStopClick: () -> Unit = {}
) {
    Card(
        modifier = modifier.shadow(
            elevation = 8.dp,
            shape = RoundedCornerShape(20.dp),
            spotColor = Color(0xFF3B82F6).copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF3B82F6)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = stringResource(R.string.voice_call_state_playing),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 对话消息气泡
 */
@Composable
fun MessageBubble(
    message: VoiceCallMessage,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false
) {
    val colors = BaoziTheme.colors

    // 如果正在录音，添加闪烁动画
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val animatedAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Card(
        modifier = modifier.shadow(
            elevation = 4.dp,
            shape = RoundedCornerShape(18.dp),
            spotColor = if (message.isUser) {
                colors.primary.copy(alpha = 0.2f)
            } else {
                Color.Black.copy(alpha = 0.1f)
            }
        ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (message.isUser) colors.primary else colors.backgroundCardElevated
        )
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 如果正在录音，显示麦克风图标；否则显示默认图标
            if (isRecording && message.isUser) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            color = colors.background.copy(alpha = animatedAlpha),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.voice_call_recording_text),
                        tint = colors.background,
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            color = if (message.isUser) {
                                colors.background.copy(alpha = 0.2f)
                            } else {
                                colors.primary.copy(alpha = 0.1f)
                            },
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (message.isUser) Icons.Default.Person else Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = if (message.isUser) colors.background else colors.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message.content,
                color = if (message.isUser) colors.background else colors.textPrimary,
                fontWeight = if (message.isUser) FontWeight.Medium else FontWeight.Normal,
                fontSize = 15.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 底部控制区
 */
@Composable
fun VoiceCallControlBar(
    state: VoiceCallState,
    onHangUpClick: () -> Unit,
    onStartCallClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors

    // 按钮动画
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val animatedScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = colors.background.copy(alpha = 0.9f)
            )
            .padding(vertical = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state is VoiceCallState.Idle) {
                // 开始通话按钮 - 圆形悬浮按钮
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clickable(onClick = onStartCallClick)
                        .background(
                            color = Color(0xFF10B981),
                            shape = RoundedCornerShape(40.dp)
                        )
                        .shadow(
                            elevation = 12.dp,
                            shape = RoundedCornerShape(40.dp),
                            spotColor = Color(0xFF10B981).copy(alpha = 0.4f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                                    Icon(
                                        Icons.Default.Phone,
                                        contentDescription = stringResource(R.string.voice_call_start),
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )                }
            } else {
                // 挂断按钮 - 圆形悬浮按钮
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clickable(onClick = onHangUpClick)
                        .background(
                            color = Color(0xFFEF4444),
                            shape = RoundedCornerShape(40.dp)
                        )
                        .shadow(
                            elevation = 12.dp,
                            shape = RoundedCornerShape(40.dp),
                            spotColor = Color(0xFFEF4444).copy(alpha = 0.4f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                                    Icon(
                                        Icons.Default.PhoneDisabled,
                                        contentDescription = stringResource(R.string.voice_call_end),
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )                }
            }
        }
    }
}

/**
 * 空状态
 */
@Composable
fun VoiceCallEmptyState(
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Phone,
                contentDescription = null,
                tint = colors.textHint,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.voice_call_empty_hint),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textHint
            )
        }
    }
}

/**
 * 加载状态
 */
@Composable
fun VoiceCallLoadingBubble() {
    val colors = BaoziTheme.colors
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val animatedProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "progress"
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.backgroundCard
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = colors.primary,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.voice_call_loading_text),
                color = colors.textSecondary,
                fontSize = 14.sp
            )
        }
    }
}