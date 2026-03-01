package com.alian.assistant.presentation.ui.screens.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.activity.compose.BackHandler
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * 图片预览屏幕
 * 支持双击缩放、捏合缩放、拖拽平移等手势操作
 */
@Composable
fun ImagePreviewScreen(
    imageUrl: String,
    title: String = "图片预览",
    onBackClick: () -> Unit
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    BackHandler(onBack = onBackClick)
    
    LaunchedEffect(imageUrl, title) {
        Log.d("ImagePreviewScreen", "开始预览图片: title=$title, url=$imageUrl")
    }
    
    // 缩放和平移状态
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    
    // 图片尺寸信息
    var imageSize by remember { mutableStateOf(Size.Zero) }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    
    // 计算最大偏移量，防止图片被拖出屏幕
    fun calculateMaxOffset(): Offset {
        if (containerSize.width == 0f || containerSize.height == 0f || imageSize.width == 0f || imageSize.height == 0f) {
            return Offset.Zero
        }
        
        // 计算缩放后的图片尺寸
        val scaledWidth = imageSize.width * scale
        val scaledHeight = imageSize.height * scale
        
        // 计算最大偏移量（允许图片边缘最多超出容器10%）
        val maxX = if (scaledWidth > containerSize.width) {
            (scaledWidth - containerSize.width) / 2f + containerSize.width * 0.1f
        } else {
            (containerSize.width - scaledWidth) / 2f
        }
        
        val maxY = if (scaledHeight > containerSize.height) {
            (scaledHeight - containerSize.height) / 2f + containerSize.height * 0.1f
        } else {
            (containerSize.height - scaledHeight) / 2f
        }
        
        return Offset(maxX, maxY)
    }
    
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(0.5f, 5f)
        val newOffsetX = offsetX + panChange.x
        val newOffsetY = offsetY + panChange.y
        val maxOffset = calculateMaxOffset()
        val clampedOffsetX = newOffsetX.coerceIn(-maxOffset.x, maxOffset.x)
        val clampedOffsetY = newOffsetY.coerceIn(-maxOffset.y, maxOffset.y)

        scale = newScale
        offsetX = clampedOffsetX
        offsetY = clampedOffsetY
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
                .background(colors.background)
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
                            text = "图片加载失败",
                            color = colors.error,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "文件名: $title",
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "URL: $imageUrl",
                            color = colors.textSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
            } else {
                // 图片容器
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            containerSize = coordinates.size.toSize()
                        }
                        .pointerInput(Unit) {
                            // 双击缩放
                            detectTapGestures(
                                onDoubleTap = { tapOffset ->
                                    if (scale == 1f) {
                                        // 放大到2倍，以点击位置为中心
                                        scale = 2f
                                        offsetX = -tapOffset.x * (2f - 1f)
                                        offsetY = -tapOffset.y * (2f - 1f)
                                    } else {
                                        // 恢复到1倍
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            )
                        }
                        .transformable(state = transformableState)
                ) {
                    // 图片显示
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            }
                            .onGloballyPositioned { coordinates ->
                                imageSize = coordinates.size.toSize()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                            onSuccess = {
                                Log.d("ImagePreviewScreen", "图片加载成功: $imageUrl")
                                isLoading = false
                                isError = false
                            },
                            onError = {
                                Log.e("ImagePreviewScreen", "图片加载失败: $imageUrl")
                                isLoading = false
                                isError = true
                            }
                        )
                    }
                    
                    // 加载中状态（覆盖在图片上方）
                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(colors.background.copy(alpha = 0.7f)),
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
                                    color = colors.textSecondary,
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
