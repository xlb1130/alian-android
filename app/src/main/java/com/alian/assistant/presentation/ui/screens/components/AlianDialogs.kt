package com.alian.assistant.presentation.ui.screens.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * Alian 确认对话框 - 通用确认对话框组件
 * 
 * @param title 对话框标题
 * @param message 对话框消息
 * @param confirmText 确认按钮文本
 * @param dismissText 取消按钮文本
 * @param onConfirm 确认回调
 * @param onDismiss 取消回调
 * @param confirmColor 确认按钮颜色
 */
@Composable
fun AlianConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确认",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmColor: Color = BaoziTheme.colors.primary
) {
    val colors = BaoziTheme.colors
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text(title, color = colors.textPrimary)
        },
        text = {
            Text(message, color = colors.textSecondary)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = confirmColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText, color = colors.textSecondary)
            }
        },
        properties = DialogProperties(dismissOnBackPress = true)
    )
}

/**
 * Alian 登出确认对话框
 * 
 * @param onLogout 登出回调
 * @param onDismiss 取消回调
 */
@Composable
fun AlianLogoutDialog(
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = BaoziTheme.colors
    
    AlianConfirmDialog(
        title = "确认登出",
        message = "确定要登出吗？",
        confirmText = "登出",
        dismissText = "取消",
        onConfirm = onLogout,
        onDismiss = onDismiss,
        confirmColor = colors.error
    )
}