package com.alian.assistant.presentation.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * 图标容器组件
 * 提供统一的图标背景和样式
 * 
 * @param icon 图标
 * @param modifier 修饰符
 * @param size 容器大小（默认 48.dp）
 * @param iconSize 图标大小（默认 24.dp）
 * @param containerColor 容器背景色（默认使用主题色的半透明）
 * @param tint 图标颜色（默认使用主题色）
 */
@Composable
fun IconContainer(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Int = 48,
    iconSize: Int = 24,
    containerColor: Color? = null,
    tint: Color? = null
) {
    val colors = BaoziTheme.colors
    
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                containerColor ?: colors.primary.copy(alpha = 0.12f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint ?: colors.primary,
            modifier = Modifier.size(iconSize.dp)
        )
    }
}

/**
 * 圆形图标容器（用于头像、状态指示等）
 * 
 * @param icon 图标
 * @param modifier 修饰符
 * @param size 容器大小（默认 40.dp）
 * @param iconSize 图标大小（默认 20.dp）
 * @param containerColor 容器背景色
 * @param tint 图标颜色
 */
@Composable
fun CircleIconContainer(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Int = 40,
    iconSize: Int = 20,
    containerColor: Color,
    tint: Color
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize.dp)
        )
    }
}

/**
 * 状态指示器图标容器
 * 用于显示成功、失败、警告等状态
 * 
 * @param status 状态类型
 * @param modifier 修饰符
 * @param size 容器大小（默认 40.dp）
 */
@Composable
fun StatusIconContainer(
    status: StatusType,
    modifier: Modifier = Modifier,
    size: Int = 40
) {
    val colors = BaoziTheme.colors
    
    when (status) {
        StatusType.SUCCESS -> {
            CircleIconContainer(
                icon = Icons.Default.CheckCircle,
                modifier = modifier,
                size = size,
                iconSize = 22,
                containerColor = colors.success.copy(alpha = 0.15f),
                tint = colors.success
            )
        }
        StatusType.ERROR -> {
            CircleIconContainer(
                icon = Icons.Default.Close,
                modifier = modifier,
                size = size,
                iconSize = 22,
                containerColor = colors.error.copy(alpha = 0.15f),
                tint = colors.error
            )
        }
        StatusType.WARNING -> {
            CircleIconContainer(
                icon = Icons.Default.Close,
                modifier = modifier,
                size = size,
                iconSize = 22,
                containerColor = colors.warning.copy(alpha = 0.15f),
                tint = colors.warning
            )
        }
        StatusType.INFO -> {
            CircleIconContainer(
                icon = Icons.Default.CheckCircle,
                modifier = modifier,
                size = size,
                iconSize = 22,
                containerColor = colors.primary.copy(alpha = 0.15f),
                tint = colors.primary
            )
        }
    }
}

/**
 * 状态类型枚举
 */
enum class StatusType {
    SUCCESS,
    ERROR,
    WARNING,
    INFO
}

/**
 * 紧凑图标容器（用于网格布局）
 * 
 * @param icon 图标
 * @param modifier 修饰符
 * @param size 容器大小（默认 56.dp）
 * @param iconSize 图标大小（默认 28.dp）
 * @param enabled 是否启用
 */
@Composable
fun CompactIconContainer(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Int = 56,
    iconSize: Int = 28,
    enabled: Boolean = true
) {
    val colors = BaoziTheme.colors
    
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (enabled) {
                    colors.primary.copy(alpha = 0.12f)
                } else {
                    colors.textHint.copy(alpha = 0.08f)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) colors.primary else colors.textHint,
            modifier = Modifier.size(iconSize.dp)
        )
    }
}