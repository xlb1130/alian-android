package com.alian.assistant.presentation.ui.screens.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * Alian 切换按钮 - 统一的切换按钮组件
 * 
 * 用于在 Local 和 Chat 模式之间切换，或其他类似的切换场景
 * 
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param size 按钮大小
 * @param iconSize 图标大小
 * @param icon 切换图标（默认为左右切换图标）
 * @param iconColor 图标颜色
 * @param contentDescription 内容描述
 */
@Composable
fun AlianSwitchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    icon: ImageVector = Icons.Default.SwapHoriz,
    iconColor: Color = BaoziTheme.colors.textPrimary,
    contentDescription: String = "切换"
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .pressedEffect(interactionSource)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        AlianIconButton(
            icon = icon,
            onClick = onClick,
            modifier = Modifier,
            size = size,
            iconSize = iconSize,
            iconColor = iconColor,
            interactionSource = interactionSource,
            contentDescription = contentDescription
        )
    }
}

/**
 * Alian 带标签的切换按钮 - 带文本标签的切换按钮
 * 
 * @param text 按钮文本
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param size 按钮大小
 * @param iconSize 图标大小
 * @param icon 切换图标
 * @param iconColor 图标颜色
 * @param textColor 文本颜色
 * @param contentDescription 内容描述
 */
@Composable
fun AlianLabeledSwitchButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    icon: ImageVector = Icons.Default.SwapHoriz,
    iconColor: Color = BaoziTheme.colors.textPrimary,
    textColor: Color = BaoziTheme.colors.textSecondary,
    contentDescription: String = "切换"
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val colors = BaoziTheme.colors
    
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .pressedEffect(interactionSource)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        AlianIconButton(
            icon = icon,
            onClick = onClick,
            modifier = Modifier,
            size = size,
            iconSize = iconSize,
            iconColor = iconColor,
            interactionSource = interactionSource,
            contentDescription = contentDescription
        )
    }
}