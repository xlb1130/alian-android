package com.alian.assistant.presentation.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alian.assistant.R
import com.alian.assistant.data.AppSettings
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.common.utils.CrashHandler
import com.alian.assistant.BuildConfig

/**
 * 遮蔽 API Key，只显示前后各4个字符
 */
private fun maskApiKey(apiKey: String): String {
    return if (apiKey.length <= 8) {
        "****"
    } else {
        "${apiKey.take(4)}****${apiKey.takeLast(4)}"
    }
}

/**
 * 执行设置二级页面内容
 */
@Composable
fun ExecutionSettingsContent(
    settings: AppSettings,
    onShowMaxStepsDialog: () -> Unit,
    onUpdateEnableBatchExecution: (Boolean) -> Unit,
    onUpdateEnableImproveMode: (Boolean) -> Unit,
    onUpdateReactOnly: (Boolean) -> Unit,
    onUpdateEnableChatAgent: (Boolean) -> Unit,
    onUpdateEnableFlowMode: (Boolean) -> Unit,
    onUpdateVirtualDisplayExecutionEnabled: (Boolean) -> Unit,
    onNavigateToDeviceController: () -> Unit = {}
) {
    val colors = BaoziTheme.colors

    Column {
        Box(modifier = Modifier.staggeredFadeIn(0)) {
            SettingsCardWithIcon(
                icon = Icons.Default.Build,
                title = stringResource(R.string.execution_device_controller),
                subtitle = stringResource(R.string.execution_device_controller_desc),
                onClick = onNavigateToDeviceController
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(1)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.Speed,
                title = stringResource(R.string.execution_optimize_mode),
                subtitle = if (settings.enableImproveMode) stringResource(R.string.execution_optimize_mode_enabled) else stringResource(R.string.execution_optimize_mode_disabled),
                checked = settings.enableImproveMode,
                onCheckedChange = onUpdateEnableImproveMode
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(2)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.execution_batch_mode),
                subtitle = if (settings.enableBatchExecution) stringResource(R.string.execution_batch_mode_enabled) else stringResource(R.string.execution_batch_mode_disabled),
                checked = settings.enableBatchExecution,
                onCheckedChange = onUpdateEnableBatchExecution
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(3)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.PlayArrow,
                title = stringResource(R.string.execution_react_only_mode),
                subtitle = if (settings.reactOnly) stringResource(R.string.execution_react_only_enabled) else stringResource(R.string.execution_react_only_disabled),
                checked = settings.reactOnly,
                onCheckedChange = onUpdateReactOnly
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(4)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.Chat,
                title = stringResource(R.string.execution_chat_agent_mode),
                subtitle = if (settings.enableChatAgent) stringResource(R.string.execution_chat_agent_enabled) else stringResource(R.string.execution_chat_agent_disabled),
                checked = settings.enableChatAgent,
                onCheckedChange = onUpdateEnableChatAgent
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(5)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.Star,
                title = stringResource(R.string.execution_flow_mode),
                subtitle = if (settings.enableFlowMode) stringResource(R.string.execution_flow_mode_enabled) else stringResource(R.string.execution_flow_mode_disabled),
                checked = settings.enableFlowMode,
                onCheckedChange = onUpdateEnableFlowMode
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(6)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.execution_virtual_display_mode),
                subtitle = if (settings.virtualDisplayExecutionEnabled) {
                    stringResource(R.string.execution_virtual_display_mode_enabled)
                } else {
                    stringResource(R.string.execution_virtual_display_mode_disabled)
                },
                checked = settings.virtualDisplayExecutionEnabled,
                onCheckedChange = onUpdateVirtualDisplayExecutionEnabled
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(7)) {
            SettingsCardWithIcon(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.execution_max_steps),
                subtitle = stringResource(R.string.execution_max_steps_value, settings.maxSteps),
                onClick = onShowMaxStepsDialog
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(8)) {
            InfoCard(
                title = stringResource(R.string.execution_info_title),
                description = stringResource(R.string.execution_info_desc)
            )
        }

        PageBottomSpacing()
    }
}

/**
 * Shizuku 设置二级页面内容
 */
@Composable
fun ShizukuSettingsContent(
    shizukuAvailable: Boolean,
    shizukuPrivilegeLevel: String,
    settings: AppSettings,
    onShowRootModeWarningDialog: () -> Unit,
    onShowSuCommandWarningDialog: () -> Unit,
    onUpdateRootModeEnabled: (Boolean) -> Unit,
    onUpdateSuCommandEnabled: (Boolean) -> Unit
) {
    val colors = BaoziTheme.colors

    Column {
        // 显示当前权限级别
        Box(modifier = Modifier.staggeredFadeIn(0)) {
            StatusCard(
                icon = Icons.Default.Info,
                title = stringResource(R.string.shizuku_privilege_level),
                status = when (shizukuPrivilegeLevel) {
                    "ROOT" -> stringResource(R.string.shizuku_root_mode_uid)
                    "ADB" -> stringResource(R.string.shizuku_adb_mode_uid)
                    else -> stringResource(R.string.shizuku_not_connected)
                },
                statusColor = when (shizukuPrivilegeLevel) {
                    "ROOT" -> colors.error
                    else -> colors.primary
                }
            )
        }

        // Root 模式开关
        val isShizukuRoot = shizukuPrivilegeLevel == "ROOT"
        Box(modifier = Modifier.staggeredFadeIn(1)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.Warning,
                title = stringResource(R.string.shizuku_root_mode),
                subtitle = when {
                    !isShizukuRoot -> stringResource(R.string.shizuku_root_requires)
                    settings.rootModeEnabled -> stringResource(R.string.shizuku_root_enabled)
                    else -> stringResource(R.string.shizuku_root_disabled)
                },
                checked = settings.rootModeEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        onShowRootModeWarningDialog()
                    } else {
                        onUpdateRootModeEnabled(false)
                    }
                },
                enabled = isShizukuRoot,
                iconTint = if (isShizukuRoot) colors.error else colors.textHint
            )
        }

        // su -c 开关（仅在 Root 模式开启时显示）
        if (settings.rootModeEnabled) {
            Box(modifier = Modifier.staggeredFadeIn(2)) {
                SettingsCardWithSwitch(
                    icon = Icons.Default.Lock,
                    title = stringResource(R.string.shizuku_su_command),
                    subtitle = if (settings.suCommandEnabled) stringResource(R.string.shizuku_su_enabled) else stringResource(R.string.shizuku_su_disabled),
                    checked = settings.suCommandEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            onShowSuCommandWarningDialog()
                        } else {
                            onUpdateSuCommandEnabled(false)
                        }
                    },
                    iconTint = colors.error
                )
            }
        }
        PageBottomSpacing()
    }
}


/**
 * TTS 语音设置二级页面内容
 */
@Composable
fun TTSSettingsContent(
    settings: AppSettings,
    onUpdateTTSEnabled: (Boolean) -> Unit,
    onUpdateTTSRealtime: (Boolean) -> Unit,
    onUpdateTTSInterruptEnabled: (Boolean) -> Unit,
    onUpdateEnableAEC: (Boolean) -> Unit,
    onUpdateEnableStreaming: (Boolean) -> Unit
) {
    val colors = BaoziTheme.colors

    Column {
        // TTS 启用开关
        Box(modifier = Modifier.staggeredFadeIn(0)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.Star,
                title = stringResource(R.string.tts_enable),
                subtitle = if (settings.ttsEnabled) stringResource(R.string.tts_enable_enabled) else stringResource(R.string.tts_enable_disabled),
                checked = settings.ttsEnabled,
                onCheckedChange = { enabled ->
                    onUpdateTTSEnabled(enabled)
                }
            )
        }

        // TTS 实时播放开关（仅在TTS启用时显示）
        if (settings.ttsEnabled) {
            Box(modifier = Modifier.staggeredFadeIn(1)) {
                SettingsCardWithSwitch(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.tts_realtime_playback),
                    subtitle = if (settings.ttsRealtime) stringResource(R.string.tts_realtime_enabled) else stringResource(R.string.tts_realtime_disabled),
                    checked = settings.ttsRealtime,
                    onCheckedChange = { enabled ->
                        onUpdateTTSRealtime(enabled)
                    }
                )
            }

            // TTS 实时语音打断开关
            Box(modifier = Modifier.staggeredFadeIn(2)) {
                SettingsCardWithSwitch(
                    icon = Icons.Default.Warning,
                    title = stringResource(R.string.tts_interrupt),
                    subtitle = if (settings.ttsInterruptEnabled) stringResource(R.string.tts_interrupt_enabled) else stringResource(R.string.tts_interrupt_disabled),
                    checked = settings.ttsInterruptEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateTTSInterruptEnabled(enabled)
                    }
                )
            }

            // AEC（回声消除）开关
            Box(modifier = Modifier.staggeredFadeIn(3)) {
                SettingsCardWithSwitch(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.tts_aec),
                    subtitle = if (settings.enableAEC) stringResource(R.string.tts_aec_enabled) else stringResource(R.string.tts_aec_disabled),
                    checked = settings.enableAEC,
                    onCheckedChange = { enabled ->
                        onUpdateEnableAEC(enabled)
                    }
                )
            }

            // 流式 LLM + 流式 TTS 开关
            Box(modifier = Modifier.staggeredFadeIn(4)) {
                SettingsCardWithSwitch(
                    icon = Icons.Default.Speed,
                    title = stringResource(R.string.tts_streaming),
                    subtitle = if (settings.enableStreaming) stringResource(R.string.tts_streaming_enabled) else stringResource(R.string.tts_streaming_disabled),
                    checked = settings.enableStreaming,
                    onCheckedChange = { enabled ->
                        onUpdateEnableStreaming(enabled)
                    }
                )
            }
        }

        PageBottomSpacing()
    }
}

/**
 * API 配置二级页面内容
 */
@Composable
fun APISettingsContent(
    settings: AppSettings,
    onShowBackendUrlDialog: () -> Unit,
    onUpdateUseBackend: (Boolean) -> Unit = {}
) {
    Column {
        // Backend 开关
        Box(modifier = Modifier.staggeredFadeIn(0)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.CloudSync,
                title = stringResource(R.string.api_backend_mode),
                subtitle = if (settings.useBackend) stringResource(R.string.api_backend_enabled) else stringResource(R.string.api_backend_disabled),
                checked = settings.useBackend,
                onCheckedChange = onUpdateUseBackend
            )
        }

        // Backend 服务端地址设置（仅 Backend 模式下显示）
        if (settings.useBackend) {
            Box(modifier = Modifier.staggeredFadeIn(1)) {
                SettingsCardWithIcon(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.api_backend_url),
                    subtitle = settings.backendBaseUrl,
                    onClick = onShowBackendUrlDialog
                )
            }
        }

        PageBottomSpacing()
    }
}

/**
 * 模型配置二级页面内容
 */
@Composable
fun ModelConfigSettingsContent(
    settings: AppSettings,
    onShowBaseUrlDialog: () -> Unit,
    onShowApiKeyDialog: () -> Unit,
    onShowModelDialog: () -> Unit,
    onShowTextModelDialog: () -> Unit = {}
) {
    Column {
        // API 服务商设置
        Box(modifier = Modifier.staggeredFadeIn(0)) {
            SettingsCardWithIcon(
                icon = Icons.Default.CloudSync,
                title = stringResource(R.string.model_provider),
                subtitle = settings.currentProvider.name,
                onClick = onShowBaseUrlDialog
            )
        }

        // API Key 设置
        Box(modifier = Modifier.staggeredFadeIn(1)) {
            SettingsCardWithIcon(
                icon = Icons.Default.Lock,
                title = stringResource(R.string.settings_api_key),
                subtitle = if (settings.apiKey.isNotEmpty()) stringResource(R.string.model_api_key_set, maskApiKey(settings.apiKey)) else stringResource(R.string.model_api_key_not_set),
                onClick = onShowApiKeyDialog
            )
        }

        // 文本模型设置
        Box(modifier = Modifier.staggeredFadeIn(2)) {
            SettingsCardWithIcon(
                icon = Icons.Default.Chat,
                title = stringResource(R.string.model_text_model),
                subtitle = settings.textModel,
                onClick = onShowTextModelDialog
            )
        }

        // 视觉模型设置
        Box(modifier = Modifier.staggeredFadeIn(3)) {
            SettingsCardWithIcon(
                icon = Icons.Default.Build,
                title = stringResource(R.string.model_vision_model),
                subtitle = settings.model,
                onClick = onShowModelDialog
            )
        }

        PageBottomSpacing()
    }
}

/**
 * 反馈与调试二级页面内容
 */
@Composable
fun FeedbackSettingsContent(
    settings: AppSettings,
    onUpdateCloudCrashReport: (Boolean) -> Unit
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    Column {
        // 云端崩溃上报开关
        Box(modifier = Modifier.staggeredFadeIn(0)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.Info,
                title = stringResource(R.string.feedback_crash_report),
                subtitle = if (settings.cloudCrashReportEnabled) stringResource(R.string.feedback_crash_report_enabled) else stringResource(R.string.feedback_crash_report_disabled),
                checked = settings.cloudCrashReportEnabled,
                onCheckedChange = {
                    // 暂时禁用
                    // onUpdateCloudCrashReport(it)
                }
            )
        }

        val logStats: State<String> =
            remember { mutableStateOf(CrashHandler.getLogStats(context)) }

        Box(modifier = Modifier.staggeredFadeIn(1)) {
            SettingsItem(
                icon = Icons.Default.Info,
                title = stringResource(R.string.feedback_export_logs),
                subtitle = logStats.value,
                onClick = {
                    CrashHandler.shareLogs(context)
                }
            )
        }

        val showClearDialog = remember { mutableStateOf(false) }

        Box(modifier = Modifier.staggeredFadeIn(2)) {
            SettingsItem(
                icon = Icons.Default.Close,
                title = stringResource(R.string.feedback_clear_logs),
                subtitle = stringResource(R.string.feedback_clear_logs_desc),
                onClick = { showClearDialog.value = true }
            )
        }

        if (showClearDialog.value) {
            AlertDialog(
                onDismissRequest = { showClearDialog.value = false },
                containerColor = BaoziTheme.colors.backgroundCard,
                title = { Text(stringResource(R.string.feedback_clear_confirm_title), color = BaoziTheme.colors.textPrimary) },
                text = {
                    Text(
                        stringResource(R.string.feedback_clear_confirm_desc),
                        color = BaoziTheme.colors.textSecondary
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        CrashHandler.clearLogs(context)
                        showClearDialog.value = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.feedback_logs_cleared),
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Text(stringResource(R.string.btn_confirm), color = BaoziTheme.colors.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog.value = false }) {
                        Text(stringResource(R.string.btn_cancel), color = BaoziTheme.colors.textSecondary)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * 帮助二级页面内容
 */
@Composable
fun HelpSettingsContent(
    onShowShizukuHelpDialog: () -> Unit,
    onShowOverlayHelpDialog: () -> Unit
) {
    Column {
        Box(modifier = Modifier.staggeredFadeIn(0)) {
            SettingsItem(
                icon = Icons.Default.Info,
                title = stringResource(R.string.help_shizuku_guide),
                subtitle = stringResource(R.string.help_shizuku_guide_desc),
                onClick = onShowShizukuHelpDialog
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(1)) {
            SettingsItem(
                icon = Icons.Default.Settings,
                title = stringResource(R.string.help_overlay_permission),
                subtitle = stringResource(R.string.help_overlay_permission_desc),
                onClick = onShowOverlayHelpDialog
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * 关于二级页面内容
 */
@Composable
fun AboutSettingsContent() {
    Column {
        Box(modifier = Modifier.staggeredFadeIn(0)) {
            SettingsItem(
                icon = Icons.Default.Info,
                title = stringResource(R.string.about_version),
                subtitle = BuildConfig.VERSION_NAME,
                onClick = { }
            )

            Box(modifier = Modifier.staggeredFadeIn(1)) {
                SettingsItem(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.about_app_name),
                    subtitle = stringResource(R.string.about_app_desc),
                    onClick = { }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
