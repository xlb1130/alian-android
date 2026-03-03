package com.alian.assistant.presentation.ui.screens.online

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.alian.assistant.R
import com.alian.assistant.presentation.ui.screens.components.MenuItem
import com.alian.assistant.presentation.ui.theme.BaoziColors

/**
 * 构建 AlianChatScreen 的更多菜单项
 * 
 * @param useBackend 是否使用 Backend 模式
 * @param isLoggedIn 是否已登录
 * @param colors 主题颜色
 * @param context Context 用于获取字符串资源
 * @param onVideoCall 视频通话回调
 * @param onVoiceCall 语音通话回调
 * @param onPhoneCall 手机通话回调
 * @param onCreateNewSession 创建新会话回调
 * @param onNavigateToSettings 导航到设置回调
 * @param onLogout 登出回调
 * @return 菜单项列表
 */
@Composable
fun buildAlianChatMenuItems(
    useBackend: Boolean,
    isLoggedIn: Boolean,
    colors: BaoziColors,
    context: Context,
    onVideoCall: () -> Unit,
    onVoiceCall: () -> Unit,
    onPhoneCall: () -> Unit = {},
    onCreateNewSession: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit
): List<MenuItem> {
    return remember(useBackend, isLoggedIn) {
        val items = mutableListOf<MenuItem>()
        
        items.add(MenuItem(
            text = context.getString(R.string.call_video),
            icon = Icons.Default.Videocam,
            iconColor = Color(0xFF6366F1),
            onClick = onVideoCall
        ))
        
        items.add(MenuItem(
            text = context.getString(R.string.call_voice),
            icon = Icons.Default.Phone,
            iconColor = Color(0xFF10B981),
            onClick = onVoiceCall
        ))
        
        items.add(MenuItem(
            text = context.getString(R.string.call_phone),
            icon = Icons.Default.PhoneAndroid,
            iconColor = Color(0xFF8B5CF6),
            onClick = onPhoneCall
        ))
        
        if (useBackend && isLoggedIn) {
            items.add(MenuItem(
                text = context.getString(R.string.menu_new_session),
                icon = Icons.Default.Add,
                iconColor = colors.primary,
                onClick = onCreateNewSession
            ))
        }
        
        items.add(MenuItem(
            text = context.getString(R.string.menu_settings),
            icon = Icons.Default.Settings,
            iconColor = colors.secondary,
            onClick = onNavigateToSettings
        ))
        
        items.add(MenuItem(
            text = context.getString(R.string.menu_logout),
            icon = Icons.Default.ExitToApp,
            iconColor = colors.error,
            onClick = onLogout
        ))
        
        items
    }
}