package com.alian.assistant.presentation.ui.screens.components

import android.util.Log
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * 视频预览屏幕
 * 使用 Android 原生 VideoView 播放视频
 */
@Composable
fun VideoPreviewScreen(
    videoUrl: String,
    title: String = "视频预览",
    onBackClick: () -> Unit
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    val videoView = remember { VideoView(context) }

    BackHandler(onBack = onBackClick)
    
    LaunchedEffect(videoUrl, title) {
        Log.d("VideoPreviewScreen", "开始预览视频: title=$title, url=$videoUrl")
    }
    
    DisposableEffect(videoView) {
        onDispose {
            videoView.stopPlayback()
            Log.d("VideoPreviewScreen", "视频播放器已释放")
        }
    }
    
    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(colors.background)
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.textPrimary
                    )
                }
                
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(paddingValues)
        ) {
            if (isError) {
                // 错误状态
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "视频加载失败",
                            color = colors.error,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage,
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "URL: $videoUrl",
                            color = colors.textSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
            } else {
                // 视频播放器
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = {
                            videoView.apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                setVideoPath(videoUrl)
                                setOnPreparedListener { mediaPlayer ->
                                    Log.d("VideoPreviewScreen", "视频准备完成: $videoUrl")
                                    isLoading = false
                                    // 自动开始播放
                                    start()
                                    isPlaying = true
                                }
                                setOnErrorListener { _, what, extra ->
                                    Log.e("VideoPreviewScreen", "视频播放错误: what=$what, extra=$extra, url=$videoUrl")
                                    isLoading = false
                                    isError = true
                                    errorMessage = "错误代码: $what, 附加信息: $extra"
                                    true
                                }
                                setOnCompletionListener {
                                    Log.d("VideoPreviewScreen", "视频播放完成: $videoUrl")
                                    isPlaying = false
                                }
                                setOnInfoListener { _, what, _ ->
                                    Log.d("VideoPreviewScreen", "视频信息: what=$what")
                                    false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // 播放/暂停控制按钮（覆盖在视频上方）
                    if (!isLoading) {
                        IconButton(
                            onClick = {
                                if (isPlaying) {
                                    videoView.pause()
                                    isPlaying = false
                                } else {
                                    videoView.start()
                                    isPlaying = true
                                }
                            },
                            modifier = Modifier
                                .size(64.dp)
                                .align(Alignment.Center)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                    
                    // 加载中状态
                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = colors.primary,
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "加载中...",
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
