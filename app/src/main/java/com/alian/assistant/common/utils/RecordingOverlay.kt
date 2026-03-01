package com.alian.assistant.common.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.BorderStroke
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.infrastructure.ai.asr.StreamingVoiceRecognitionManager
import com.alian.assistant.infrastructure.voice.VoiceRecognitionManager

/**
 * 录音遮罩层组件
 */
@Composable
fun RecordingOverlay(
    isRecording: Boolean,
    recordedText: String = "",
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    isInputOverlay: Boolean = false,
    voiceRecognitionManager: VoiceRecognitionManager? = null,
    streamingVoiceRecognitionManager: StreamingVoiceRecognitionManager? = null
) {
    val colors = BaoziTheme.colors
    
    if (isRecording || recordedText.isNotEmpty()) {
        if (isInputOverlay) {
            // 作为输入框上方的覆盖层
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(colors.backgroundCard)
                    .padding(16.dp)
            ) {
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 动画圆形指示器
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(colors.error.copy(alpha = 0.2f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(colors.error)
                                    .align(Alignment.Center)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "录音中",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp).align(Alignment.Center)
                                )
                            }
                        }
                        
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (isRecording) "正在聆听..." else "已识别：",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary
                            )
                            
                            if (recordedText.isNotEmpty()) {
                                Text(
                                    text = recordedText,
                                    fontSize = 14.sp,
                                    color = colors.textSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        
                        OutlinedButton(
                            onClick = {
                                // 通过回调传递取消操作
                                voiceRecognitionManager?.cancelListening()
                                streamingVoiceRecognitionManager?.cancelListening()
                                onCancel()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = colors.error
                            ),
                            border = BorderStroke(1.dp, colors.error),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("CancelButton")
                        ) {
                            Text("取消", fontSize = 12.sp)
                        }
                    }
                    
                    
                }
            }
        } else {
            // 全屏覆盖模式
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { /* Prevent interaction with background */ }
            ) {
                Box {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(40.dp)
                            .background(colors.backgroundCard, RoundedCornerShape(20.dp))
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 动画圆形指示器
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(colors.error.copy(alpha = 0.2f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                                    .background(colors.error)
                                    .align(Alignment.Center)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "录音中",
                                    tint = Color.White,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                    
                    Text(
                        text = "正在聆听...",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    
                    if (recordedText.isNotEmpty()) {
                        Text(
                            text = recordedText,
                            fontSize = 16.sp,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    OutlinedButton(
                        onClick = { },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.error
                        ),
                        border = BorderStroke(1.dp, colors.error),
                        modifier = Modifier
                            .testTag("CancelButton")
                    ) {
                        Text("取消", color = colors.error)
                    }
                    
                    
                }
            }
        }
    }
}