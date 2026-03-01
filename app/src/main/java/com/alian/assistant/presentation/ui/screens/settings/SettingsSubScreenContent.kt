package com.alian.assistant.presentation.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.unit.dp
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
    onNavigateToDeviceController: () -> Unit = {}
) {
    val colors = BaoziTheme.colors

    Column {
        Box(modifier = Modifier.staggeredFadeIn(0)) {
            SettingsCardWithIcon(
                icon = Icons.Default.Build,
                title = "设备控制器",
                subtitle = "配置设备控制器的执行策略和性能设置",
                onClick = onNavigateToDeviceController
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(1)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.Speed,
                title = "优化模式",
                subtitle = if (settings.enableImproveMode) "已启用（减少 60% VLM 调用，速度提升 2.5x）" else "已禁用（使用标准模式）",
                checked = settings.enableImproveMode,
                onCheckedChange = onUpdateEnableImproveMode
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(2)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.Settings,
                title = "多步执行模式",
                subtitle = if (settings.enableBatchExecution) "已启用（批量执行不冲突的操作）" else "已禁用（逐个执行）",
                checked = settings.enableBatchExecution,
                onCheckedChange = onUpdateEnableBatchExecution
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(3)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.PlayArrow,
                title = "ReactOnly 模式",
                subtitle = if (settings.reactOnly) "已启用（Manager 仅规划一次，后续全靠 Executor 执行）" else "已禁用（标准模式）",
                checked = settings.reactOnly,
                onCheckedChange = onUpdateReactOnly
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(4)) {
            SettingsCardWithSwitch(
                icon = Icons.Default.Chat,
                title = "ChatAgent 模式",
                subtitle = if (settings.enableChatAgent) "已启用（支持语音打断和实时交互）" else "已禁用（标准执行模式）",
                checked = settings.enableChatAgent,
                onCheckedChange = onUpdateEnableChatAgent
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(5)) {
            SettingsCardWithIcon(
                icon = Icons.Default.Settings,
                title = "最大执行步数",
                subtitle = "${settings.maxSteps} 步",
                onClick = onShowMaxStepsDialog
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(6)) {
            InfoCard(
                title = "说明",
                description = "• 优化模式：智能跳过不必要的 Manager/Reflector 调用，减少 VLM 调用次数，提升执行速度\n• 多步执行模式：AI 可一次性返回多个不冲突的操作，减少 VLM 调用次数，提升执行效率\n• ReactOnly 模式：Manager 仅在开始时规划一次，后续全靠 Executor 执行，大幅减少 VLM 调用次数\n• ChatAgent 模式：支持语音打断和实时交互，可在执行过程中通过语音与 AI 对话\n• 最大执行步数：决定 Agent 单次任务能够执行的操作数量上限"
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
                title = "当前权限级别",
                status = when (shizukuPrivilegeLevel) {
                    "ROOT" -> "Root 模式 (UID 0)"
                    "ADB" -> "ADB 模式 (UID 2000)"
                    else -> "未连接"
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
                title = "Root 模式",
                subtitle = when {
                    !isShizukuRoot -> "需要 Shizuku 以 Root 权限运行"
                    settings.rootModeEnabled -> "已启用高级权限"
                    else -> "启用后可使用 Root 功能"
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
                    title = "允许 su -c 命令",
                    subtitle = if (settings.suCommandEnabled) "AI 可执行 Root 命令" else "禁止执行 su -c",
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
                title = "启用语音播放",
                subtitle = if (settings.ttsEnabled) "已开启，机器人回复将支持语音播放" else "已关闭",
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
                    title = "实时播放",
                    subtitle = if (settings.ttsRealtime) "机器人输出时立即播放" else "点击消息旁的喇叭图标播放",
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
                    title = "实时语音打断",
                    subtitle = if (settings.ttsInterruptEnabled) "TTS播放时检测到用户说话自动停止" else "不检测用户语音",
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
                    title = "回声消除（AEC）",
                    subtitle = if (settings.enableAEC) "启用 AEC 技术实现更精准的语音打断" else "不使用回声消除",
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
                    title = "流式播放",
                    subtitle = if (settings.enableStreaming) "启用流式 LLM + 流式 TTS，降低延迟" else "使用传统播放模式",
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
    onShowBaseUrlDialog: () -> Unit,
    onShowApiKeyDialog: () -> Unit,
    onShowModelDialog: () -> Unit,
    onShowBackendUrlDialog: () -> Unit
) {
    Column {
        // Base URL 设置
        Box(modifier = Modifier.staggeredFadeIn(0)) {
            SettingsCardWithIcon(
                icon = Icons.Default.Settings,
                title = "API 服务商",
                subtitle = settings.currentProvider.name,
                onClick = onShowBaseUrlDialog
            )
        }

        // Backend 服务端地址设置
        Box(modifier = Modifier.staggeredFadeIn(1)) {
            SettingsCardWithIcon(
                icon = Icons.Default.Build,
                title = "Backend 服务端地址",
                subtitle = settings.backendBaseUrl,
                onClick = onShowBackendUrlDialog
            )
        }

        // API Key 设置
        Box(modifier = Modifier.staggeredFadeIn(2)) {
            SettingsCardWithIcon(
                icon = Icons.Default.Lock,
                title = "API Key",
                subtitle = if (settings.apiKey.isNotEmpty()) "已设置 (${maskApiKey(settings.apiKey)})" else "未设置",
                onClick = onShowApiKeyDialog
            )
        }

        // 模型设置
        Box(modifier = Modifier.staggeredFadeIn(3)) {
            SettingsCardWithIcon(
                icon = Icons.Default.Build,
                title = "模型",
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
                title = "云端崩溃上报",
                subtitle = if (settings.cloudCrashReportEnabled) "已开启，帮助我们改进应用" else "已关闭",
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
                title = "导出日志",
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
                title = "清除日志",
                subtitle = "删除所有本地日志文件",
                onClick = { showClearDialog.value = true }
            )
        }

        if (showClearDialog.value) {
            AlertDialog(
                onDismissRequest = { showClearDialog.value = false },
                containerColor = BaoziTheme.colors.backgroundCard,
                title = { Text("确认清除", color = BaoziTheme.colors.textPrimary) },
                text = {
                    Text(
                        "确定要删除所有日志文件吗？",
                        color = BaoziTheme.colors.textSecondary
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        CrashHandler.clearLogs(context)
                        showClearDialog.value = false
                        Toast.makeText(
                            context,
                            "日志已清除",
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Text("确定", color = BaoziTheme.colors.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog.value = false }) {
                        Text("取消", color = BaoziTheme.colors.textSecondary)
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
                title = "Shizuku 使用指南",
                subtitle = "了解如何安装和配置 Shizuku",
                onClick = onShowShizukuHelpDialog
            )
        }

        Box(modifier = Modifier.staggeredFadeIn(1)) {
            SettingsItem(
                icon = Icons.Default.Settings,
                title = "悬浮窗权限说明",
                subtitle = "了解为什么需要悬浮窗权限",
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
                title = "版本",
                subtitle = BuildConfig.VERSION_NAME,
                onClick = { }
            )

            Box(modifier = Modifier.staggeredFadeIn(1)) {
                SettingsItem(
                    icon = Icons.Default.Build,
                    title = "艾莲 Autopilot",
                    subtitle = "基于视觉语言模型的 Android 自动化工具",
                    onClick = { }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}