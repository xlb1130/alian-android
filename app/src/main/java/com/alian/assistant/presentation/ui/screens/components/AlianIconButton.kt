package com.alian.assistant.presentation.ui.screens.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
 * Alian 图标按钮 - 统一的图标按钮组件
 * 
 * 提供统一的图标按钮样式，包括按压效果、悬停效果、震动反馈等
 * 
 * @param icon 图标
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param size 按钮大小
 * @param iconSize 图标大小
 * @param iconColor 图标颜色
 * @param backgroundColor 背景颜色（null 表示透明背景）
 * @param enabled 是否启用
 * @param interactionSource 交互源
 * @param contentDescription 内容描述
 */
@Composable
fun AlianIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    iconColor: Color = BaoziTheme.colors.textPrimary,
    backgroundColor: Color? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val colors = BaoziTheme.colors
    
    IconButton(
        onClick = {
            HapticUtils.performLightHaptic(context)
            onClick()
        },
        modifier = modifier.size(size),
        enabled = enabled,
        interactionSource = interactionSource,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = iconColor,
            disabledContentColor = iconColor.copy(alpha = 0.5f)
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = iconColor
        )
    }
}

/**
 * Alian 圆形图标按钮 - 带圆形背景的图标按钮
 * 
 * @param icon 图标
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param size 按钮大小
 * @param iconSize 图标大小
 * @param iconColor 图标颜色
 * @param backgroundColor 背景颜色
 * @param enabled 是否启用
 * @param contentDescription 内容描述
 */
@Composable
fun AlianCircleIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    iconColor: Color = BaoziTheme.colors.textPrimary,
    backgroundColor: Color = BaoziTheme.colors.backgroundInput,
    enabled: Boolean = true,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .pressedEffect(interactionSource)
            .then(
                if (enabled) {
                    Modifier.pressedEffect(interactionSource)
                } else {
                    Modifier
                }
            )
    ) {
        AlianIconButton(
            icon = icon,
            onClick = onClick,
            modifier = Modifier.align(Alignment.Center),
            size = size,
            iconSize = iconSize,
            iconColor = iconColor,
            enabled = enabled,
            interactionSource = interactionSource,
            contentDescription = contentDescription
        )
    }
}

/**
 * 语音/键盘切换按钮 - 特殊的切换按钮
 * 
 * @param isVoiceMode 是否为语音模式
 * @param onToggle 切换回调
 * @param modifier 修饰符
 * @param size 按钮大小
 * @param enabled 是否启用
 */
@Composable
fun VoiceKeyboardToggleButton(
    isVoiceMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    enabled: Boolean = true
) {
    val colors = BaoziTheme.colors
    
    AlianCircleIconButton(
        icon = if (isVoiceMode) Icons.Default.Keyboard else Icons.Default.Mic,
        onClick = onToggle,
        modifier = modifier,
        size = size,
        iconSize = 16.dp,
        iconColor = colors.textPrimary,
        backgroundColor = colors.backgroundInput,
        enabled = enabled,
        contentDescription = if (isVoiceMode) "切换到键盘输入" else "切换到语音输入"
    )
}