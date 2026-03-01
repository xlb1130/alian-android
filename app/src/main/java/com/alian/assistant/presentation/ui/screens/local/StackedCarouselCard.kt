package com.alian.assistant.presentation.ui.screens.local

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.media.AudioManager
import com.alian.assistant.presentation.ui.screens.components.HapticUtils
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * 堆叠卡片轮播视图 - 简洁现代风格
 * 
 * 采用无限循环轮播，卡片设计简洁优雅
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StackedCarouselView(
    presetCommands: List<PresetCommand>,
    onCommandClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val virtualPageSize = Int.MAX_VALUE
    val middleStart = virtualPageSize / 2
    val context = LocalContext.current

    val pagerState = rememberPagerState(
        initialPage = middleStart,
        pageCount = { virtualPageSize }
    )

    // 监听页面切换，播放音效和触觉反馈
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { _ ->
            // 轻触觉反馈
            HapticUtils.performLightHaptic(context)
            // 播放系统点击音效
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.3f)
        }
    }

    // 自定义 flingBehavior，允许根据手指力度滑过多张卡片（最多滑过5张）
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(5),
        snapPositionalThreshold = 0.2f
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 56.dp),
            pageSpacing = 16.dp,
            flingBehavior = flingBehavior,
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
        ) { page ->
            val realIndex = page % presetCommands.size
            val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            
            StackedPresetCard(
                preset = presetCommands[realIndex],
                onClick = { onCommandClick(presetCommands[realIndex].command) },
                pageOffset = pageOffset,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // 现代风格页面指示器
        ModernPageIndicator(
            pageCount = presetCommands.size,
            currentPage = pagerState.currentPage % presetCommands.size
        )
    }
}

/**
 * 现代简洁风格预设命令卡片 - 轻盈优雅设计
 * 
 * 根据 pageOffset 平滑过渡透明度和缩放，让卡片在屏幕边缘逐渐消失
 */
@Composable
fun StackedPresetCard(
    preset: PresetCommand,
    onClick: () -> Unit,
    pageOffset: Float,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    val absoluteOffset = kotlin.math.abs(pageOffset)

    // 根据偏移量计算透明度：居中时完全不透明，相邻卡片保持较高可见度，远处卡片逐渐消失
    val cardAlpha = (1f - (absoluteOffset * 0.35f)).coerceIn(0f, 1f)
    // 根据偏移量计算缩放：居中时原始大小，偏移越大越小
    val cardScale = (1f - (absoluteOffset * 0.05f)).coerceIn(0.9f, 1f)

    val cardShape = RoundedCornerShape(24.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
                // 使用 graphicsLayer 的 alpha 并配合 compositingStrategy 避免白色框
                alpha = cardAlpha
                // 在 graphicsLayer 内设置阴影
                shadowElevation = 16f
                shape = cardShape
                clip = false
                // 使用 ModulateAlpha 策略，不会创建离屏缓冲区，避免矩形底色暴露
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            }
            .clip(cardShape)
            .background(colors.backgroundInput, cardShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                HapticUtils.performLightHaptic(context)
                onClick()
            }
    ) {
        ModernCardContent(preset = preset, onClick = onClick)
    }
}

/**
 * 现代简洁风格卡片内容 - 轻盈自然设计
 */
@Composable
private fun ModernCardContent(
    preset: PresetCommand,
    onClick: () -> Unit
) {
    val colors = BaoziTheme.colors
    
    // Emoji 浮动动画
    val infiniteTransition = rememberInfiniteTransition(label = "emoji")
    val emojiOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "emojiFloat"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Emoji 图标 - 渐变圆形背景 + 浮动动画
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer { translationY = -emojiOffset }
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                colors.primary.copy(alpha = 0.12f),
                                colors.secondary.copy(alpha = 0.08f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = preset.icon,
                    fontSize = 40.sp
                )
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // 命令文字 - 更优雅的排版
            Text(
                text = preset.command,
                fontSize = 17.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Normal,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // 现代风格渐变按钮
            GradientTryButton(onClick = onClick)
        }
    }
}

/**
 * 渐变风格试试按钮 - 现代简洁设计
 */
@Composable
fun GradientTryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // 按压缩放动画
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "buttonScale"
    )
    
    // 微光动画
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )
    
    Box(
        modifier = modifier
            .graphicsLayer {
                this.scaleX = scale
                this.scaleY = scale
            }
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        colors.primary,
                        colors.primary.copy(alpha = 0.85f),
                        colors.secondary.copy(alpha = 0.9f)
                    )
                )
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                HapticUtils.performLightHaptic(context)
                onClick()
            }
            .padding(horizontal = 32.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "✨",
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "试一试",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                letterSpacing = 0.5.sp
            )
        }
    }
}

/**
 * 现代风格试试按钮 - 简洁优雅（保留备用）
 */
@Composable
fun ModernTryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "buttonScale"
    )
    
    Surface(
        modifier = modifier
            .graphicsLayer {
                this.scaleX = scale
                this.scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                HapticUtils.performLightHaptic(context)
                onClick()
            },
        shape = RoundedCornerShape(24.dp),
        color = colors.primary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "试一试",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                letterSpacing = 0.5.sp
            )
        }
    }
}

/**
 * 线性插值辅助函数 - Float 类型
 */
private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

/**
 * 线性插值辅助函数 - Dp 类型
 */
private fun lerp(start: androidx.compose.ui.unit.Dp, stop: androidx.compose.ui.unit.Dp, fraction: Float): androidx.compose.ui.unit.Dp {
    return androidx.compose.ui.unit.lerp(start, stop, fraction)
}

/**
 * 现代风格页面指示器 - 简洁优雅的线条设计
 */
@Composable
fun ModernPageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val isSelected = index == currentPage
            
            // 线条宽度动画
            val lineWidth by animateFloatAsState(
                targetValue = if (isSelected) 24f else 8f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "lineWidth"
            )
            
            // 透明度动画
            val lineAlpha by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0.25f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "lineAlpha"
            )
            
            // 高度动画 - 选中时略高
            val lineHeight by animateFloatAsState(
                targetValue = if (isSelected) 4f else 3f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "lineHeight"
            )
            
            Box(
                modifier = Modifier
                    .width(lineWidth.dp)
                    .height(lineHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (isSelected) {
                            Brush.linearGradient(
                                colors = listOf(
                                    colors.primary,
                                    colors.secondary.copy(alpha = 0.8f)
                                )
                            )
                        } else {
                            Brush.linearGradient(
                                colors = listOf(
                                    colors.primary.copy(alpha = lineAlpha),
                                    colors.primary.copy(alpha = lineAlpha)
                                )
                            )
                        }
                    )
            )
        }
    }
}