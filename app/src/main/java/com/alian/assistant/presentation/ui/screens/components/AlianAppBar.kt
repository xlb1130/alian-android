package com.alian.assistant.presentation.ui.screens.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * 菜单项数据类
 *
 * @param text 菜单项文本
 * @param icon 菜单项图标
 * @param iconColor 图标颜色
 * @param onClick 点击回调
 */
data class MenuItem(
    val text: String,
    val icon: ImageVector,
    val iconColor: Color? = null,
    val onClick: () -> Unit
)

/**
 * 右侧自定义按钮数据类
 *
 * @param icon 按钮图标
 * @param contentDescription 内容描述
 * @param onClick 点击回调
 */
data class RightActionButton(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit
)

/**
 * Alian 顶部标题栏 - 统一的顶部标题栏组件
 * 
 * 提供统一的顶部标题栏样式，包括菜单按钮、标题、切换按钮、更多菜单等
 * 
 * @param title 标题文本
 * @param onMenuClick 菜单按钮点击回调
 * @param menuIcon 菜单图标（默认为汉堡菜单图标）
 * @param showSwitchButton 是否显示切换按钮
 * @param onSwitchClick 切换按钮点击回调
 * @param switchIcon 切换按钮图标
 * @param rightActions 右侧自定义按钮列表（在切换按钮和更多菜单之间）
 * @param showMoreMenu 是否显示更多菜单
 * @param moreMenuItems 更多菜单项列表
 * @param moreMenuContent 更多菜单的自定义内容（如果提供，则忽略 moreMenuItems）
 */
@Composable
fun AlianAppBar(
    title: String,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuIcon: ImageVector = Icons.Default.Menu,
    showSwitchButton: Boolean = false,
    onSwitchClick: (() -> Unit)? = null,
    switchIcon: ImageVector = Icons.Default.SwapHoriz,
    rightActions: List<RightActionButton>? = null,
    showMoreMenu: Boolean = true,
    moreMenuItems: List<MenuItem>? = null,
    moreMenuContent: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val colors = BaoziTheme.colors
    val menuInteractionSource = remember { MutableInteractionSource() }
    
    val rightButtonSize = 36.dp
    val rightIconSize = 20.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(1f)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(0.dp)
            )
            .background(colors.background)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：菜单按钮 + 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 菜单按钮
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .pressedEffect(menuInteractionSource),
                    contentAlignment = Alignment.Center
                ) {
                    AlianIconButton(
                        icon = menuIcon,
                        onClick = onMenuClick,
                        interactionSource = menuInteractionSource,
                        contentDescription = "菜单"
                    )
                }
                
                Spacer(modifier = Modifier.width(10.dp))
                
                // 标题
                Text(
                    text = title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // 右侧：切换按钮 + 自定义按钮 + 更多菜单
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 切换按钮
                if (showSwitchButton && onSwitchClick != null) {
                    val switchInteractionSource = remember { MutableInteractionSource() }
                    
                    Box(
                        modifier = Modifier
                            .size(rightButtonSize)
                            .clip(CircleShape)
                            .pressedEffect(switchInteractionSource),
                        contentAlignment = Alignment.Center
                    ) {
                        AlianIconButton(
                            icon = switchIcon,
                            onClick = onSwitchClick,
                            interactionSource = switchInteractionSource,
                            size = rightButtonSize,
                            iconSize = rightIconSize,
                            contentDescription = "切换"
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(4.dp))
                }
                
                // 自定义按钮列表
                rightActions?.forEachIndexed { index, action ->
                    val actionInteractionSource = remember(index) { MutableInteractionSource() }
                    
                    Box(
                        modifier = Modifier
                            .size(rightButtonSize)
                            .clip(CircleShape)
                            .pressedEffect(actionInteractionSource),
                        contentAlignment = Alignment.Center
                    ) {
                        AlianIconButton(
                            icon = action.icon,
                            onClick = action.onClick,
                            interactionSource = actionInteractionSource,
                            size = rightButtonSize,
                            iconSize = rightIconSize,
                            contentDescription = action.contentDescription
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(4.dp))
                }
                
                // 更多菜单按钮
                if (showMoreMenu) {
                    if (moreMenuContent != null) {
                        moreMenuContent()
                    } else if (moreMenuItems != null) {
                        AlianMenuButton(
                            menuItems = moreMenuItems,
                            buttonSize = rightButtonSize,
                            iconSize = rightIconSize
                        )
                    }
                }
            }
        }
    }
}
