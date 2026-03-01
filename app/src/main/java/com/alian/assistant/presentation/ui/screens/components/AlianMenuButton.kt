package com.alian.assistant.presentation.ui.screens.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.common.constant.AnimationConstants
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * Alian 更多菜单按钮 - 统一的更多菜单按钮组件
 * 
 * 提供统一的更多菜单按钮样式，包括旋转动画、下拉菜单等
 * 
 * @param menuItems 菜单项列表
 * @param modifier 修饰符
 * @param icon 菜单图标（默认为三点图标）
 * @param iconSize 图标大小
 * @param buttonSize 按钮大小
 */
@Composable
fun AlianMenuButton(
    menuItems: List<MenuItem>,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.MoreVert,
    iconSize: Dp = 22.dp,
    buttonSize: Dp = 44.dp
) {
    val context = LocalContext.current
    val colors = BaoziTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // 菜单图标旋转动画
    val rotation by animateFloatAsState(
        targetValue = if (showMenu) AnimationConstants.MenuRotationAngle else 0f,
        animationSpec = AnimationConstants.MenuRotationAnimationSpec,
        label = "menuRotation"
    )
    
    Box {
        // 更多按钮
        Box(
            modifier = modifier
                .size(buttonSize)
                .clip(CircleShape)
                .pressedEffect(interactionSource)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        HapticUtils.performLightHaptic(context)
                        showMenu = true
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "更多",
                tint = colors.textPrimary,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
        
        // 下拉菜单
        StyledDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            menuItems.forEach { item ->
                StyledDropdownMenuItem(
                    text = item.text,
                    icon = item.icon,
                    iconColor = item.iconColor ?: BaoziTheme.colors.primary,
                    onClick = {
                        HapticUtils.performLightHaptic(context)
                        item.onClick()
                        showMenu = false
                    }
                )
            }
        }
    }
}

/**
 * 样式化下拉菜单 - 统一下拉菜单样式
 * 
 * @param expanded 是否展开
 * @param onDismissRequest 关闭回调
 * @param modifier 修饰符
 * @param content 菜单内容
 */
@Composable
fun StyledDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = BaoziTheme.colors

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = colors.backgroundCard,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            content()
        }
    }
}

/**
 * 样式化下拉菜单项 - 统一下拉菜单项样式
 * 
 * @param text 菜单项文本
 * @param icon 菜单项图标
 * @param iconColor 图标颜色
 * @param onClick 点击回调
 * @param modifier 修饰符
 */
@Composable
fun StyledDropdownMenuItem(
    text: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = iconColor,
                modifier = Modifier.size(16.dp)
            )
        },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
    )
}
