package com.alian.assistant.presentation.ui.screens.online

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.infrastructure.voice.VoiceRecognitionManager
import com.alian.assistant.infrastructure.ai.asr.StreamingVoiceRecognitionManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.alian.assistant.R
import com.alian.assistant.core.alian.backend.BackendChatClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Alian 输入区域组件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlianInputArea(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isVoiceInputMode: Boolean = false,
    onVoiceInputModeChanged: ((Boolean) -> Unit)? = null,
    onVoiceInput: ((String) -> Unit)? = null,
    isRecording: Boolean,
    recordedText: String,
    onRecordingChange: (Boolean) -> Unit,
    onRecordedTextChanged: (String) -> Unit,
    voiceRecognitionManager: VoiceRecognitionManager? = null,
    streamingVoiceRecognitionManager: StreamingVoiceRecognitionManager? = null,
    isProcessing: Boolean = false,
    onCancel: (() -> Unit)? = null,
    sessionId: String? = null,
    backendClient: BackendChatClient? = null,
    onVoiceRippleChanged: (Boolean) -> Unit = {},
    onVoiceRipplePositionChanged: (Offset?) -> Unit = {}
) {
    val colors = BaoziTheme.colors
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current

    // 按钮中心位置（用于波纹效果中心）
    var buttonCenterPosition by remember { mutableStateOf<Offset?>(null) }

    // 输入框焦点状态
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // 监听输入框实际高度
    var textFieldActualHeight by remember { mutableStateOf(48.dp) }

    // 计算输入框高度（基于文本行数）
    val lineCount = inputText.count { it == '\n' } + 1
    val textFieldHeight by animateFloatAsState(
        targetValue = when {
            lineCount <= 1 -> 48f
            lineCount <= 2 -> 72f
            lineCount <= 3 -> 96f
            else -> 120f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "textFieldHeight"
    )

    // 按钮缩放比例（根据输入框高度）
    val buttonScale by animateFloatAsState(
        targetValue = when {
            textFieldActualHeight <= 48.dp -> 1f
            textFieldActualHeight >= 120.dp -> 0.8f
            else -> 1f - ((textFieldActualHeight.value - 48f) / (120f - 48f)) * 0.2f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "buttonScale"
    )

    // 模式切换动画
    val scale by animateFloatAsState(
        targetValue = if (isVoiceInputMode) 0.98f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scale"
    )

    // 焦点动画
    val focusAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(200, easing = EaseInOutCubic),
        label = "focusAlpha"
    )
    val textInputShadowElevation by animateDpAsState(
        targetValue = when {
            isProcessing -> 0.dp
            isFocused -> 8.dp
            else -> 4.dp
        },
        animationSpec = tween(durationMillis = 180, easing = EaseInOutCubic),
        label = "textInputShadowElevation"
    )

    // 发送按钮点击动画
    var sendButtonPressed by remember { mutableStateOf(false) }
    val sendButtonPressScale by animateFloatAsState(
        targetValue = if (sendButtonPressed) 0.95f else 1f,
        animationSpec = tween(100, easing = EaseInOutCubic),
        label = "sendButtonPressScale"
    )

    // 发送成功飞出动画
    var sendSuccess by remember { mutableStateOf(false) }
    val sendFlyoutScale by animateFloatAsState(
        targetValue = if (sendSuccess) 2f else 1f,
        animationSpec = tween(400, easing = EaseOutCubic),
        label = "sendFlyoutScale"
    )
    val sendFlyoutAlpha by animateFloatAsState(
        targetValue = if (sendSuccess) 0f else 1f,
        animationSpec = tween(400, easing = EaseOutCubic),
        label = "sendFlyoutAlpha"
    )

    // 语音波形动画
    val waveformAnimation = rememberInfiniteTransition(label = "waveform")
    val waveformAmplitude by waveformAnimation.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveformAmplitude"
    )

    // 呼吸灯动画
    val breathingAnimation = rememberInfiniteTransition(label = "breathing")
    val breathingAlpha by breathingAnimation.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingAlpha"
    )

    // 监听录音状态，当录音停止时取消波纹效果
    LaunchedEffect(isRecording) {
        Log.d("AlianVoice", "LaunchedEffect: isRecording = $isRecording")
        if (!isRecording) {
            Log.d("AlianVoice", "录音停止，取消波纹效果")
            onVoiceRippleChanged(false)
            onVoiceRipplePositionChanged(null)
        }
    }

    // 震动函数
    fun vibrate() {
        Log.d("AlianInputArea", "vibrate() called")
        val vibrator =
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator?.hasVibrator() == true) {
            Log.d("AlianInputArea", "Vibrator available, SDK: ${Build.VERSION.SDK_INT}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(50, 150)
                vibrator.vibrate(effect)
                Log.d("AlianInputArea", "Vibrated with VibrationEffect")
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
                Log.d("AlianInputArea", "Vibrated with deprecated method")
            }
        } else {
            Log.e("AlianInputArea", "Vibrator not available or null")
        }
    }

    // 停止任务函数
    fun stopTask() {
        // 如果有 sessionId 和 backendClient，调用停止 API
        if (sessionId != null && backendClient != null) {
            coroutineScope.launch {
                try {
                    backendClient.stopSession(sessionId)
                    Log.d("AlianInputArea", "成功停止任务: $sessionId")
                } catch (e: Exception) {
                    Log.e("AlianInputArea", "停止任务失败: ${e.message}", e)
                }
            }
        }

        onCancel?.invoke()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isVoiceInputMode && onVoiceInput != null) {
            // 语音输入模式
            // 停止按钮（处理中时显示）或键盘切换按钮（在左边）
            if (isProcessing) {
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    // 脉冲动画圆环
                    val pulseAnimation = rememberInfiniteTransition(label = "pulse")
                    val pulseScale by pulseAnimation.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.3f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = EaseInOutCubic),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "pulseScale"
                    )
                    val pulseAlpha by pulseAnimation.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = EaseInOutCubic),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "pulseAlpha"
                    )

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(Color.Red.copy(alpha = pulseAlpha))
                    )

                    IconButton(
                        onClick = {
                            vibrate()
                            stopTask()
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Red)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.cd_stop),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = {
                        vibrate()
                        if (isRecording) {
                            streamingVoiceRecognitionManager?.cancelListening()
                            onRecordingChange(false)
                            onRecordedTextChanged("")
                        }
                        onVoiceInputModeChanged?.let { it(false) }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(
                            elevation = 4.dp,
                            shape = CircleShape
                        )
                        .clip(CircleShape)
                        .background(colors.backgroundInput)
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = stringResource(R.string.voice_switch_keyboard),
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 语音按钮主体（在右边）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 120.dp)
                    .shadow(
                        elevation = if (isRecording) 8.dp else 4.dp,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .clip(RoundedCornerShape(24.dp))
                    .onGloballyPositioned { coordinates ->
                        // 计算按钮中心位置（相对于屏幕）
                        val positionInWindow = coordinates.positionInWindow()
                        val size = coordinates.size
                        buttonCenterPosition = Offset(
                            x = positionInWindow.x + size.width / 2,
                            y = positionInWindow.y + size.height / 2
                        )
                        Log.d(
                            "AlianVoice",
                            "按钮中心位置: $buttonCenterPosition, 按钮大小: $size, positionInWindow: $positionInWindow"
                        )
                    }
                    .background(
                        when {
                            isProcessing -> colors.backgroundInput.copy(alpha = 0.5f)
                            isRecording -> colors.primary.copy(alpha = breathingAlpha * 0.4f)
                            else -> colors.backgroundInput
                        }
                    )
                    .then(
                        if (isRecording) {
                            Modifier.drawBehind {
                                // 绘制语音波形
                                val strokeWidth = 1.dp.toPx()
                                val waveCount = 5
                                val waveWidth = size.width / waveCount
                                val centerY = size.height / 2

                                for (i in 0 until waveCount) {
                                    val x = i * waveWidth + waveWidth / 2
                                    val amplitude =
                                        (size.height / 3) * waveformAmplitude * (1 - abs(
                                            i - waveCount / 2
                                        ).toFloat() / waveCount)

                                    drawLine(
                                        color = colors.primary.copy(alpha = breathingAlpha),
                                        start = Offset(x, centerY - amplitude),
                                        end = Offset(x, centerY + amplitude),
                                        strokeWidth = strokeWidth
                                    )
                                }
                            }
                        } else {
                            Modifier
                        }
                    )
                    .then(
                        if (!isProcessing) {
                            Modifier.pointerInput("voiceInputButton") {
                                var isPressed = false
                                var hasStartedRecording = false
                                var pressStartTime = 0L
                                var releaseJob: Job? = null
                                val minPressDuration = 200L // 最小按压时长200ms，避免误触
                                val minReleaseDuration = 400L // 最小释放间隔400ms，避免误停

                                detectTapGestures(
                                    onPress = { offset ->
                                        Log.d("AlianVoice", "按下语音按钮，位置: $offset")
                                        Log.d("AlianVoice", "isProcessing: $isProcessing")

                                        // 按下时震动
                                        vibrate()

                                        // 取消之前的释放任务（如果存在）
                                        releaseJob?.cancel()
                                        releaseJob = null

                                        isPressed = true
                                        pressStartTime = System.currentTimeMillis()
                                        hasStartedRecording = false

                                        // 延迟启动录音，避免误触
                                        coroutineScope.launch {
                                            delay(minPressDuration)
                                            Log.d(
                                                "AlianVoice",
                                                "延迟检查完成，isPressed: $isPressed, hasStartedRecording: $hasStartedRecording, streamingVoiceRecognitionManager: $streamingVoiceRecognitionManager"
                                            )
                                            if (isPressed && !hasStartedRecording && streamingVoiceRecognitionManager != null) {
                                                Log.d("AlianVoice", "持续按压，开始录音")
                                                hasStartedRecording = true

                                                streamingVoiceRecognitionManager.setResultCallback(
                                                    onResult = { result ->
                                                        Log.d("AlianVoice", "识别结果: $result")
                                                        onVoiceInput(result)
                                                        onRecordingChange(false)
                                                        onRecordedTextChanged("")
                                                    },
                                                    onError = { error ->
                                                        Log.e("AlianVoice", "语音识别错误: $error")
                                                        onRecordingChange(false)
                                                        onRecordedTextChanged("")
                                                    },
                                                    onPartialResult = { partial ->
                                                        Log.d(
                                                            "AlianVoice",
                                                            "部分识别结果: $partial"
                                                        )
                                                        onRecordedTextChanged(partial)
                                                    }
                                                )

                                                onRecordedTextChanged("")
                                                onRecordingChange(true)
                                                // 触发波纹效果，使用按钮中心位置
                                                Log.d(
                                                    "AlianVoice",
                                                    "触发波纹效果，按钮中心位置: $buttonCenterPosition"
                                                )
                                                onVoiceRippleChanged(true)
                                                onVoiceRipplePositionChanged(buttonCenterPosition)
                                                Log.d(
                                                    "AlianVoice",
                                                    "波纹效果已触发，onVoiceRippleChanged(true) 调用完成"
                                                )
                                                streamingVoiceRecognitionManager.startListening()
                                            } else if (isPressed && streamingVoiceRecognitionManager == null) {
                                                Log.e(
                                                    "AlianVoice",
                                                    "StreamingVoiceRecognitionManager 为 null"
                                                )
                                            }
                                        }

                                        try {
                                            awaitPointerEventScope {
                                                waitForUpOrCancellation()
                                            }

                                            Log.d(
                                                "AlianVoice",
                                                "用户松开手指，按压时长: ${System.currentTimeMillis() - pressStartTime}ms"
                                            )
                                            isPressed = false

                                            // 松开时震动
                                            vibrate()

                                            // 延迟停止录音，避免误停
                                            if (hasStartedRecording) {
                                                releaseJob = coroutineScope.launch {
                                                    delay(minReleaseDuration)
                                                    // 再次检查是否真的要停止（可能用户又重新按下了）
                                                    if (!isPressed && hasStartedRecording) {
                                                        Log.d("AlianVoice", "确认停止录音")
                                                        streamingVoiceRecognitionManager?.stopListening()
                                                        onRecordingChange(false)
                                                        hasStartedRecording = false
                                                    } else if (isPressed) {
                                                        Log.d(
                                                            "AlianVoice",
                                                            "用户重新按下，取消停止录音"
                                                        )
                                                    }
                                                }
                                            } else {
                                                Log.d("AlianVoice", "按压时间过短，未开始录音")
                                                onRecordedTextChanged("")
                                            }
                                        } catch (e: Exception) {
                                            Log.e("AlianVoice", "录音异常: ${e.message}", e)
                                            isPressed = false
                                            releaseJob?.cancel()
                                            if (hasStartedRecording) {
                                                streamingVoiceRecognitionManager?.cancelListening()
                                            }
                                            onRecordingChange(false)
                                            onRecordedTextChanged("")
                                            hasStartedRecording = false
                                        }
                                    }
                                )
                            }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(
                            if (isRecording) R.string.voice_recording 
                            else R.string.voice_hold_to_speak
                        ),
                        tint = when {
                            isProcessing -> colors.textSecondary
                            isRecording -> colors.primary
                            else -> colors.textPrimary
                        },
                        modifier = Modifier
                            .size(16.dp)
                            .scale(if (isRecording) 1.1f + (waveformAmplitude - 1f) * 0.2f else 1f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            when {
                                isProcessing -> R.string.voice_processing
                                isRecording -> R.string.voice_release_to_send
                                else -> R.string.voice_hold_to_speak
                            }
                        ),
                        color = when {
                            isProcessing -> colors.textSecondary
                            isRecording -> colors.primary
                            else -> colors.textSecondary
                        },
                        fontSize = 15.sp
                    )
                }
            }

            // 录音遮罩层（已隐藏，改用波纹中的文本显示）
            // if (isRecording || (recordedText.isNotEmpty() && !isRecording)) {
            //     RecordingOverlay(
            //         isRecording = isRecording,
            //         recordedText = recordedText,
            //         onCancel = {
            //             streamingVoiceRecognitionManager?.cancelListening()
            //             onRecordingChange(false)
            //             onRecordedTextChanged("")
            //         },
            //         onSend = {
            //             if (recordedText.isNotEmpty()) {
            //                 onVoiceInput(recordedText)
            //                 onRecordedTextChanged("")
            //             }
            //             onRecordingChange(false)
            //         },
            //         isInputOverlay = true,
            //         voiceRecognitionManager = voiceRecognitionManager,
            //         streamingVoiceRecognitionManager = streamingVoiceRecognitionManager
            //     )
            // }
        } else {
            // 文字输入模式
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 120.dp)
                    .then(
                        if (textInputShadowElevation > 0.dp) {
                            Modifier.shadow(
                                elevation = textInputShadowElevation,
                                shape = RoundedCornerShape(28.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clip(RoundedCornerShape(28.dp))
                    .onGloballyPositioned { coordinates ->
                        textFieldActualHeight = with(density) { coordinates.size.height.toDp() }
                    }
                    .background(
                        when {
                            isProcessing -> colors.backgroundInput.copy(alpha = 0.5f)
                            else -> colors.backgroundInput
                        }
                    )
                    .drawBehind {
                        // 焦点时的发光效果（增强视觉反馈）
                        if (isFocused && !isProcessing) {
                            val glowColor = colors.primary.copy(alpha = 0.2f * focusAlpha)
                            drawRoundRect(
                                color = glowColor,
                                size = size,
                                cornerRadius = CornerRadius(28.dp.toPx())
                            )
                        }
                    }
                    .then(
                        if (isFocused && !isProcessing) {
                            Modifier.border(
                                width = 2.dp,
                                color = colors.primary.copy(alpha = 0.8f * focusAlpha),
                                shape = RoundedCornerShape(28.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .animateContentSize(
                        animationSpec = if (isProcessing) {
                            tween(durationMillis = 140, easing = EaseInOutCubic)
                        } else {
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        }
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 语音/文字切换按钮（最左边）
                    if (onVoiceInputModeChanged != null) {
                        // 录音权限检查对话框状态
                        var showAudioPermissionDialog by remember { mutableStateOf(false) }
                        var pendingVoiceInputMode by remember { mutableStateOf(false) }

                        // 权限对话框
                        if (showAudioPermissionDialog) {
                            AlertDialog(
                                onDismissRequest = {
                                    showAudioPermissionDialog = false
                                    pendingVoiceInputMode = false
                                },
                                icon = {
                                    Icon(Icons.Default.Mic, contentDescription = null, tint = colors.primary)
                                },
                                title = {
                                    Text(stringResource(R.string.voice_permission_title), color = colors.textPrimary)
                                },
                                text = {
                                    Text(
                                        stringResource(R.string.voice_permission_desc),
                                        color = colors.textSecondary
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            showAudioPermissionDialog = false
                                            // 请求权限（通过回调通知 MainActivity）
                                            onVoiceInputModeChanged?.let { it(true) }
                                        }
                                    ) {
                                        Text(stringResource(R.string.voice_go_authorize), color = colors.primary)
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            showAudioPermissionDialog = false
                                            pendingVoiceInputMode = false
                                        }
                                    ) {
                                        Text(stringResource(R.string.btn_cancel), color = colors.textSecondary)
                                    }
                                }
                            )
                        }

                        IconButton(
                            onClick = {
                                vibrate()
                                val newMode = !isVoiceInputMode
                                
                                // 如果切换到语音输入模式，检查录音权限
                                if (newMode) {
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (!hasPermission) {
                                        // 权限未授予，显示引导对话框
                                        pendingVoiceInputMode = true
                                        showAudioPermissionDialog = true
                                        return@IconButton
                                    }
                                }
                                
                                onVoiceInputModeChanged(newMode)
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .scale(buttonScale)
                        ) {
                            Icon(
                                imageVector = if (isVoiceInputMode) Icons.Default.Keyboard else Icons.Default.Mic,
                                contentDescription = stringResource(
                                    if (isVoiceInputMode) R.string.voice_switch_keyboard 
                                    else R.string.voice_switch_voice
                                ),
                                tint = colors.textPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // 文本输入框
                    BasicTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        textStyle = TextStyle(
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        ),
                        cursorBrush = SolidColor(colors.primary),
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f),
                        interactionSource = interactionSource,
                        maxLines = 5,
                        minLines = 1,
                        decorationBox = { innerTextField ->
                            Box {
                                if (inputText.isEmpty()) {
                                    val placeholderAlpha by animateFloatAsState(
                                        targetValue = if (isFocused) 0.5f else 1f,
                                        animationSpec = tween(200, easing = EaseInOutCubic),
                                        label = "placeholderAlpha"
                                    )
                                    Text(
                                        text = stringResource(
                                            if (isProcessing) R.string.voice_processing 
                                            else R.string.voice_say_something
                                        ),
                                        color = colors.textHint.copy(alpha = placeholderAlpha),
                                        fontSize = 15.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    // 发送按钮或停止按钮（最右边）
                    if (isProcessing) {
                        Box(
                            contentAlignment = Alignment.Center
                        ) {
                            // 脉冲动画圆环
                            val pulseAnimation = rememberInfiniteTransition(label = "pulse")
                            val pulseScale by pulseAnimation.animateFloat(
                                initialValue = 1f,
                                targetValue = 1.3f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = EaseInOutCubic),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "pulseScale"
                            )
                            val pulseAlpha by pulseAnimation.animateFloat(
                                initialValue = 0.5f,
                                targetValue = 0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = EaseInOutCubic),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "pulseAlpha"
                            )

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(Color.Red.copy(alpha = pulseAlpha))
                            )

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red)
                                    .clickable {
                                        vibrate()
                                        stopTask()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = stringResource(R.string.cd_stop),
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else {
                        IconButton(
                            onClick = {
                                vibrate()
                                sendButtonPressed = true
                                onSend()
                                sendSuccess = true
                                coroutineScope.launch {
                                    delay(400)
                                    sendButtonPressed = false
                                    sendSuccess = false
                                }
                            },
                            enabled = inputText.isNotBlank(),
                            modifier = Modifier
                                .size(32.dp)
                                .scale(buttonScale * sendButtonPressScale * sendFlyoutScale)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = stringResource(R.string.cd_send),
                                tint = if (inputText.isNotBlank()) colors.primary else colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
