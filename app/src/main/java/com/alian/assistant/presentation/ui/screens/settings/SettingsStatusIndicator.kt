package com.alian.assistant.presentation.ui.screens.settings

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * 状态指示器组件
 * 提供清晰的状态视觉反馈
 * 
 * @param status 状态类型
 * @param modifier 修饰符
 * @param size 指示器大小（默认 12.dp）
 * @param showIcon 是否显示图标（默认 false）
 */
@Composable
fun StatusIndicator(
    status: StatusType,
    modifier: Modifier = Modifier,
    size: Int = 12,
    showIcon: Boolean = false
) {
    val colors = BaoziTheme.colors
    
    val (backgroundColor, borderColor, icon) = when (status) {
        StatusType.SUCCESS -> Triple(
            colors.success.copy(alpha = 0.2f),
            colors.success,
            Icons.Default.CheckCircle
        )
        StatusType.ERROR -> Triple(
            colors.error.copy(alpha = 0.2f),
            colors.error,
            Icons.Default.Close
        )
        StatusType.WARNING -> Triple(
            colors.warning.copy(alpha = 0.2f),
            colors.warning,
            Icons.Default.Warning
        )
        StatusType.INFO -> Triple(
            colors.info.copy(alpha = 0.2f),
            colors.info,
            Icons.Default.Info
        )
    }
    
    if (showIcon) {
        // 显示图标版本
        Box(
            modifier = modifier
                .size((size * 2.5).dp)
                .clip(CircleShape)
                .background(backgroundColor)
                .border(1.5.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = borderColor,
                modifier = Modifier.size((size * 1.2).dp)
            )
        }
    } else {
        // 简单圆点版本
        Box(
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(backgroundColor)
                .border(1.5.dp, borderColor, CircleShape)
        )
    }
}

/**
 * 带文字的状态指示器
 * 
 * @param status 状态类型
 * @param text 状态文字
 * @param modifier 修饰符
 */
@Composable
fun StatusIndicatorWithText(
    status: StatusType,
    text: String,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StatusIndicator(status = status, size = 10)
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = when (status) {
                StatusType.SUCCESS -> colors.success
                StatusType.ERROR -> colors.error
                StatusType.WARNING -> colors.warning
                StatusType.INFO -> colors.info
            }
        )
    }
}

/**
 * 脉冲动画状态指示器
 * 用于强调活跃状态或正在进行的操作
 * 
 * @param status 状态类型
 * @param modifier 修饰符
 * @param size 指示器大小（默认 12.dp）
 */
@Composable
fun PulsingStatusIndicator(
    status: StatusType,
    modifier: Modifier = Modifier,
    size: Int = 12
) {
    val colors = BaoziTheme.colors
    
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )
    
    val backgroundColor = when (status) {
        StatusType.SUCCESS -> colors.success
        StatusType.ERROR -> colors.error
        StatusType.WARNING -> colors.warning
        StatusType.INFO -> colors.info
    }
    
    Box(
        modifier = modifier.size(size.dp),
        contentAlignment = Alignment.Center
    ) {
        // 脉冲效果
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(backgroundColor.copy(alpha = alpha))
        )
        // 核心指示器
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(backgroundColor)
        )
    }
}

/**
 * 连接状态指示器
 * 用于显示网络连接、Shizuku 连接等状态
 * 
 * @param isConnected 是否连接
 * @param text 状态文字
 * @param modifier 修饰符
 */
@Composable
fun ConnectionStatusIndicator(
    isConnected: Boolean,
    text: String,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isConnected) {
            PulsingStatusIndicator(status = StatusType.SUCCESS, size = 10)
        } else {
            StatusIndicator(status = StatusType.ERROR, size = 10)
        }
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (isConnected) colors.success else colors.error
        )
    }
}

/**
 * 状态徽章组件
 * 用于在卡片右上角显示状态
 * 
 * @param status 状态类型
 * @param text 状态文字
 * @param modifier 修饰符
 */
@Composable
fun StatusBadge(
    status: StatusType,
    text: String,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    
    val (backgroundColor, textColor) = when (status) {
        StatusType.SUCCESS -> Pair(
            colors.success.copy(alpha = 0.15f),
            colors.success
        )
        StatusType.ERROR -> Pair(
            colors.error.copy(alpha = 0.15f),
            colors.error
        )
        StatusType.WARNING -> Pair(
            colors.warning.copy(alpha = 0.15f),
            colors.warning
        )
        StatusType.INFO -> Pair(
            colors.info.copy(alpha = 0.15f),
            colors.info
        )
    }
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}