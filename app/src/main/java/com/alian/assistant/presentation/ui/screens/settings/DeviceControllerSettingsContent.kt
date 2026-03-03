package com.alian.assistant.presentation.ui.screens.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alian.assistant.R
import com.alian.assistant.data.AppSettings
import com.alian.assistant.presentation.ui.screens.components.HapticUtils.performLightHaptic
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.common.utils.AccessibilityUtils
import com.alian.assistant.common.utils.PermissionManager
import com.alian.assistant.common.utils.PermissionStatus
import rikka.shizuku.Shizuku

/**
 * 设备控制器设置内容
 */
@Composable
fun DeviceControllerSettingsContent(
    settings: AppSettings,
    onUpdateExecutionStrategy: (String) -> Unit,
    onUpdateFallbackStrategy: (String) -> Unit,
    onUpdateScreenshotCacheEnabled: (Boolean) -> Unit,
    onUpdateGestureDelayMs: (Int) -> Unit,
    onUpdateInputDelayMs: (Int) -> Unit,
    onRequestMediaProjectionPermission: () -> Unit = {},
    onShowShizukuHelpDialog: () -> Unit = {},
    mediaProjectionResultCode: Int? = null,
    mediaProjectionData: Intent? = null,
    shizukuAvailable: Boolean = false
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    // 无障碍服务权限检查对话框
    var showAccessibilityPermissionDialog by remember { mutableStateOf(false) }
    var pendingExecutionStrategy by remember { mutableStateOf<String?>(null) }

    // MediaProjection 权限引导对话框
    var showMediaProjectionPermissionDialog by remember { mutableStateOf(false) }

    // Shizuku 权限引导对话框
    var showShizukuPermissionDialog by remember { mutableStateOf(false) }

    // 检查无障碍服务权限
    val isAccessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(context)

    // 检查 Shizuku 权限（仅在 binder 可用时检查）
    val isShizukuEnabled = if (shizukuAvailable) {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } else {
        false
    }

    // 处理执行策略选择
    fun handleExecutionStrategyChange(strategy: String) {
        Log.d("DeviceControllerSettings", "handleExecutionStrategyChange - strategy: $strategy, isShizukuEnabled: $isShizukuEnabled, isAccessibilityEnabled: $isAccessibilityEnabled")
        
        // 如果选择"仅 Shizuku"或"混合模式"，检查 Shizuku 权限
        if (strategy == "shizuku_only" || strategy == "hybrid") {
            if (!isShizukuEnabled) {
                Log.d("DeviceControllerSettings", "Shizuku 权限未授予，显示权限对话框")
                pendingExecutionStrategy = strategy
                showShizukuPermissionDialog = true
                return
            }
        }

        // 如果选择"仅无障碍"或"混合模式"，检查无障碍服务权限
        if (strategy == "accessibility_only" || strategy == "hybrid") {
            if (!isAccessibilityEnabled) {
                Log.d("DeviceControllerSettings", "无障碍服务未启用，显示权限对话框")
                pendingExecutionStrategy = strategy
                showAccessibilityPermissionDialog = true
                return
            }

            // 无障碍服务已启用，检查 MediaProjection 权限
            // 使用 PermissionManager 检查权限状态
            val mediaProjectionPermissionState = PermissionManager.checkMediaProjection(
                mediaProjectionResultCode,
                mediaProjectionData
            )
            Log.d("DeviceControllerSettings", "MediaProjection 权限状态: ${mediaProjectionPermissionState.status}")
            if (mediaProjectionPermissionState.status != PermissionStatus.GRANTED) {
                Log.d("DeviceControllerSettings", "MediaProjection 权限未授予，显示权限对话框")
                showMediaProjectionPermissionDialog = true
                pendingExecutionStrategy = strategy
                return
            }
        }
        
        Log.d("DeviceControllerSettings", "所有权限检查通过，更新执行策略: $strategy")
        onUpdateExecutionStrategy(strategy)
    }

    // 无障碍权限引导对话框
    if (showAccessibilityPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showAccessibilityPermissionDialog = false
                pendingExecutionStrategy = null
            },
            icon = {
                Icon(Icons.Default.Warning, contentDescription = null, tint = colors.warning)
            },
            title = {
                Text(stringResource(R.string.device_accessibility_required_title), color = colors.textPrimary)
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.device_accessibility_required_desc),
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.device_accessibility_required_hint),
                        color = colors.textSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAccessibilityPermissionDialog = false
                        AccessibilityUtils.openAccessibilitySettings(context)
                    }
                ) {
                    Text(stringResource(R.string.settings_show), color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAccessibilityPermissionDialog = false
                        pendingExecutionStrategy = null
                    }
                ) {
                    Text(stringResource(R.string.btn_cancel), color = colors.textSecondary)
                }
            }
        )
    }

    // MediaProjection 权限引导对话框
    if (showMediaProjectionPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showMediaProjectionPermissionDialog = false
                pendingExecutionStrategy = null
            },
            icon = {
                Icon(Icons.Default.Tune, contentDescription = null, tint = colors.primary)
            },
            title = {
                Text(stringResource(R.string.device_mediaprojection_required_title), color = colors.textPrimary)
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.device_mediaprojection_required_desc),
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.device_mediaprojection_required_hint),
                        color = colors.textSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMediaProjectionPermissionDialog = false
                        // 先应用策略变更
                        pendingExecutionStrategy?.let { onUpdateExecutionStrategy(it) }
                        pendingExecutionStrategy = null
                        // 然后请求 MediaProjection 权限
                        onRequestMediaProjectionPermission()
                    }
                ) {
                    Text(stringResource(R.string.device_go_authorize), color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMediaProjectionPermissionDialog = false
                        pendingExecutionStrategy = null
                    }
                ) {
                    Text(stringResource(R.string.device_later), color = colors.textSecondary)
                }
            }
        )
    }

    // Shizuku 权限引导对话框
    if (showShizukuPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showShizukuPermissionDialog = false
                pendingExecutionStrategy = null
            },
            icon = {
                Icon(Icons.Default.Tune, contentDescription = null, tint = colors.primary)
            },
            title = {
                Text(stringResource(R.string.device_shizuku_required_title), color = colors.textPrimary)
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.device_shizuku_required_desc),
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.device_shizuku_required_hint),
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.device_shizuku_required_steps),
                        color = colors.textSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showShizukuPermissionDialog = false
                        // 先应用策略变更
                        pendingExecutionStrategy?.let { onUpdateExecutionStrategy(it) }
                        pendingExecutionStrategy = null
                        // 打开 Shizuku App 或应用详情页
                        try {
                            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            intent?.let {
                                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(it)
                            } ?: run {
                                // 如果未安装 Shizuku，打开应用详情页
                                val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(settingsIntent)
                            }
                        } catch (e: Exception) {
                            Log.e("DeviceControllerSettingsContent", "Failed to open Shizuku", e)
                        }
                    }
                ) {
                    Text(stringResource(R.string.device_go_authorize), color = colors.primary)
                }
            },
            dismissButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 授权引导按钮
                    OutlinedButton(
                        onClick = {
                            showShizukuPermissionDialog = false
                            onShowShizukuHelpDialog()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.device_authorize_guide), color = colors.primary)
                    }
                    // 稍后按钮
                    TextButton(
                        onClick = {
                            showShizukuPermissionDialog = false
                            pendingExecutionStrategy = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.device_later), color = colors.textSecondary)
                    }
                }
            }
        )
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 执行策略
        SettingsCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.device_execution_strategy),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                
                Text(
                    text = stringResource(R.string.device_execution_strategy_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 执行策略选择
                val executionStrategies = listOf(
                    stringResource(R.string.device_strategy_auto) to "auto",
                    stringResource(R.string.device_strategy_shizuku_only) to "shizuku_only",
                    stringResource(R.string.device_strategy_accessibility_only) to "accessibility_only",
                    stringResource(R.string.device_strategy_hybrid) to "hybrid"
                )

                executionStrategies.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary
                        )

                        RadioButton(
                            selected = settings.executionStrategy == value,
                            onClick = { handleExecutionStrategyChange(value) }
                        )
                    }
                }

                // 显示无障碍服务状态提示
                if ((settings.executionStrategy == "accessibility_only" || settings.executionStrategy == "hybrid") && !isAccessibilityEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .background(colors.warning.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = colors.warning,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.device_accessibility_not_enabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.warning
                        )
                    }
                }
            }
        }
        
        // 降级策略
        SettingsCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.device_fallback_strategy),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                
                Text(
                    text = stringResource(R.string.device_fallback_strategy_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 降级策略选择
                val fallbackStrategies = listOf(
                    stringResource(R.string.device_fallback_auto) to "auto",
                    stringResource(R.string.device_fallback_shizuku_first) to "shizuku_first",
                    stringResource(R.string.device_fallback_accessibility_first) to "accessibility_first"
                )
                
                fallbackStrategies.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary
                        )
                        
                        RadioButton(
                            selected = settings.fallbackStrategy == value,
                            onClick = { onUpdateFallbackStrategy(value) }
                        )
                    }
                }
            }
        }
        
        // 性能设置
        SettingsCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.device_performance_settings),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                
                // 截图缓存
                SettingsSwitch(
                    title = stringResource(R.string.device_screenshot_cache),
                    description = stringResource(R.string.device_screenshot_cache_desc),
                    checked = settings.screenshotCacheEnabled,
                    onCheckedChange = onUpdateScreenshotCacheEnabled
                )
                
                // 手势延迟
                var showGestureDelayDialog by remember { mutableStateOf(false) }

                SettingsItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.device_gesture_delay),
                    subtitle = stringResource(R.string.dialog_unit_ms, settings.gestureDelayMs),
                    onClick = { showGestureDelayDialog = true }
                )
                
                if (showGestureDelayDialog) {
                    DelayInputDialog(
                        title = stringResource(R.string.device_gesture_delay),
                        currentValue = settings.gestureDelayMs,
                        range = 0..1000,
                        unit = "ms",
                        onConfirm = { onUpdateGestureDelayMs(it) },
                        onDismiss = { showGestureDelayDialog = false }
                    )
                }
                
                // 输入延迟
                var showInputDelayDialog by remember { mutableStateOf(false) }

                SettingsItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.device_input_delay),
                    subtitle = stringResource(R.string.dialog_unit_ms, settings.inputDelayMs),
                    onClick = { showInputDelayDialog = true }
                )
                
                if (showInputDelayDialog) {
                    DelayInputDialog(
                        title = stringResource(R.string.device_input_delay),
                        currentValue = settings.inputDelayMs,
                        range = 0..500,
                        unit = "ms",
                        onConfirm = { onUpdateInputDelayMs(it) },
                        onDismiss = { showInputDelayDialog = false }
                    )
                }
            }
        }
        
        // MediaProjection 权限设置
        SettingsCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.device_mediaprojection_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                
                Text(
                    text = stringResource(R.string.device_mediaprojection_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = onRequestMediaProjectionPermission,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary
                    )
                ) {
                    Text(stringResource(R.string.device_mediaprojection_request))
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = stringResource(R.string.device_mediaprojection_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
        
        PageBottomSpacing()
    }
}

@Composable
fun SettingsSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clickable {
                    performLightHaptic(context)
                    onCheckedChange(!checked)
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = {
                    performLightHaptic(context)
                    onCheckedChange(it)
                }
            )
        }
    }
}

/**
 * 延迟输入对话框
 */
@Composable
private fun DelayInputDialog(
    title: String,
    currentValue: Int,
    range: IntRange,
    unit: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(currentValue) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title)
        },
        text = {
            Column {
                Text(
                    text = "当前值: $value $unit",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Slider(
                    value = value.toFloat(),
                    onValueChange = { value = it.toInt() },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = range.last - range.first - 1
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(value)
                onDismiss()
            }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}