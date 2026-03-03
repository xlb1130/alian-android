package com.alian.assistant.presentation.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.alian.assistant.R
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.common.utils.PermissionManager
import com.alian.assistant.common.utils.PermissionStatus
import com.alian.assistant.common.utils.PermissionType

/**
 * 权限管理二级页面内容
 */
@Composable
fun PermissionManagementSettingsContent(
    shizukuAvailable: Boolean,
    onNavigateToShizuku: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    // 获取所有权限状态
    val permissionStates by remember {
        mutableStateOf(
            PermissionManager.getAllPermissionStates(
                context = context,
                mediaProjectionResultCode = null,
                mediaProjectionData = null
            )
        )
    }

    // 基础权限列表
    val basicPermissions = listOf(
        PermissionType.RECORD_AUDIO,
        PermissionType.CAMERA,
        PermissionType.POST_NOTIFICATIONS
    )

    // 核心权限列表
    val corePermissions = listOf(
        PermissionType.ACCESSIBILITY_SERVICE,
        PermissionType.MEDIA_PROJECTION,
        PermissionType.SHIZUKU
    )

    // 其他权限列表
    val otherPermissions = listOf(
        PermissionType.SYSTEM_ALERT_WINDOW
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(colors.background),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 基础权限
        SettingsSection(title = stringResource(R.string.permission_basic))
        basicPermissions.forEachIndexed { index, permissionType ->
            PermissionItem(
                permissionType = permissionType,
                permissionState = permissionStates[permissionType]!!,
                context = context,
                itemIndex = index,
                onClick = {
                    PermissionManager.openPermissionSettings(context, permissionType)
                }
            )
        }

        // 核心权限
        Spacer(modifier = Modifier.height(8.dp))
        SettingsSection(title = stringResource(R.string.permission_core))
        corePermissions.forEachIndexed { index, permissionType ->
            if (permissionType == PermissionType.SHIZUKU) {
                PermissionItem(
                    permissionType = permissionType,
                    permissionState = permissionStates[permissionType]!!,
                    context = context,
                    itemIndex = basicPermissions.size + index,
                    onClick = {
                        onNavigateToShizuku()
                    }
                )
            } else {
                PermissionItem(
                    permissionType = permissionType,
                    permissionState = permissionStates[permissionType]!!,
                    context = context,
                    itemIndex = basicPermissions.size + index,
                    onClick = {
                        PermissionManager.openPermissionSettings(context, permissionType)
                    }
                )
            }
        }

        // 其他权限
        Spacer(modifier = Modifier.height(8.dp))
        SettingsSection(title = stringResource(R.string.permission_other))
        otherPermissions.forEachIndexed { index, permissionType ->
            PermissionItem(
                permissionType = permissionType,
                permissionState = permissionStates[permissionType]!!,
                context = context,
                itemIndex = basicPermissions.size + corePermissions.size + index,
                onClick = {
                    PermissionManager.openPermissionSettings(context, permissionType)
                }
            )
        }

        PageBottomSpacing()
    }
}

/**
 * 权限项组件
 */
@Composable
fun PermissionItem(
    permissionType: PermissionType,
    permissionState: PermissionManager.PermissionState,
    context: Context,
    itemIndex: Int = 0,
    onClick: () -> Unit
) {
    val colors = BaoziTheme.colors

    val (statusIcon, statusText, statusColor) = when (permissionState.status) {
        PermissionStatus.GRANTED -> Triple(
            Icons.Default.CheckCircle,
            stringResource(R.string.permission_granted),
            Color(0xFF4CAF50)
        )
        PermissionStatus.DENIED -> Triple(
            Icons.Default.Cancel,
            stringResource(R.string.permission_denied),
            Color(0xFFF44336)
        )
        PermissionStatus.NOT_REQUESTED -> Triple(
            Icons.Default.Warning,
            stringResource(R.string.permission_not_requested),
            Color(0xFFFF9800)
        )
    }

    val icon = when (permissionType) {
        PermissionType.RECORD_AUDIO -> Icons.Default.Mic
        PermissionType.CAMERA -> Icons.Default.Videocam
        PermissionType.POST_NOTIFICATIONS -> Icons.Default.Notifications
        PermissionType.SYSTEM_ALERT_WINDOW -> Icons.Default.PictureInPicture
        PermissionType.ACCESSIBILITY_SERVICE -> Icons.Default.Accessibility
        PermissionType.MEDIA_PROJECTION -> Icons.Default.Cast
        PermissionType.SHIZUKU -> Icons.Default.Security
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredFadeIn(itemIndex)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.backgroundCard)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 权限图标
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 权限名称和描述
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = PermissionManager.getPermissionName(context, permissionType),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = PermissionManager.getPermissionDescription(context, permissionType),
                    fontSize = 12.sp,
                    color = colors.textSecondary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 状态指示
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 箭头图标
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}