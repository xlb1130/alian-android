package com.alian.assistant.presentation.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.composed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.data.AppSettings
import com.alian.assistant.data.ApiProvider
import com.alian.assistant.presentation.ui.screens.components.AlianAppBar
import com.alian.assistant.presentation.ui.screens.settings.APISettingsContent
import com.alian.assistant.presentation.ui.screens.settings.AboutSettingsContent
import com.alian.assistant.presentation.ui.screens.settings.AlianSettingsContent
import com.alian.assistant.presentation.ui.screens.settings.ApiKeyDialog
import com.alian.assistant.presentation.ui.screens.settings.CompactGridItem
import com.alian.assistant.presentation.ui.screens.settings.DeviceControllerSettingsContent
import com.alian.assistant.presentation.ui.screens.settings.ExecutionSettingsContent
import com.alian.assistant.presentation.ui.screens.settings.FeedbackSettingsContent
import com.alian.assistant.presentation.ui.screens.settings.HelpSettingsContent
import com.alian.assistant.presentation.ui.screens.settings.MCPSettingsContent
import com.alian.assistant.presentation.ui.screens.settings.LoginStatusCard
import com.alian.assistant.presentation.ui.screens.settings.MaxStepsDialog
import com.alian.assistant.presentation.ui.screens.settings.ModelSelectDialogWithFetch
import com.alian.assistant.presentation.ui.screens.settings.OverlayHelpDialog
import com.alian.assistant.presentation.ui.screens.settings.PermissionManagementSettingsContent
import com.alian.assistant.presentation.ui.screens.settings.SettingsSection
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.ui.theme.ThemeMode
import com.alian.assistant.presentation.ui.screens.settings.PageBottomSpacing
import com.alian.assistant.presentation.ui.screens.settings.ProviderSelectDialog
import com.alian.assistant.presentation.ui.screens.settings.RootModeWarningDialog
import com.alian.assistant.presentation.ui.screens.settings.SettingsSubScreen
import com.alian.assistant.presentation.ui.screens.settings.ShizukuHelpDialog
import com.alian.assistant.presentation.ui.screens.settings.ShizukuSettingsContent
import com.alian.assistant.presentation.ui.screens.settings.SuCommandWarningDialog
import com.alian.assistant.presentation.ui.screens.settings.TTSSettingsContent
import com.alian.assistant.presentation.ui.screens.settings.ThemeSelectDialog
import com.alian.assistant.presentation.ui.screens.settings.ConnectionStatusIndicator
import com.alian.assistant.common.utils.AvatarCacheManager

// 轻微震动效果
private fun performLightHaptic(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    if (vibrator?.hasVibrator() == true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(50, 150)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }
}

/**
 * 交错淡入动画修饰符
 */
@Composable
fun Modifier.staggeredFadeIn(index: Int): Modifier = this then composed {
    var visible by remember {
        mutableStateOf(false)
    }
    val scaleAnimation = animateFloatAsState(
        targetValue = if (visible) 1f else 0.95f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )
    val alphaAnimation = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "alpha"
    )
    LaunchedEffect(Unit) {
        delay(index * 50L)
        visible = true
    }
    alpha(alphaAnimation.value)
        .scale(scaleAnimation.value)
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateApiKey: (String) -> Unit,
    onUpdateBaseUrl: (String) -> Unit,
    onUpdateModel: (String) -> Unit,
    onUpdateCachedModels: (List<String>) -> Unit,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    onUpdateMaxSteps: (Int) -> Unit,
    onUpdateCloudCrashReport: (Boolean) -> Unit,
    onUpdateRootModeEnabled: (Boolean) -> Unit,
    onUpdateSuCommandEnabled: (Boolean) -> Unit,
    onUpdateUseBackend: (Boolean) -> Unit,
    onUpdateTTSEnabled: (Boolean) -> Unit,
    onUpdateTTSRealtime: (Boolean) -> Unit,
    onUpdateTTSVoice: (String) -> Unit,
    onUpdateTTSSpeed: (Float) -> Unit,
    onUpdateTTSInterruptEnabled: (Boolean) -> Unit,
    onUpdateEnableAEC: (Boolean) -> Unit,
    onUpdateEnableStreaming: (Boolean) -> Unit,
    onUpdateVolume: (Int) -> Unit,
    onUpdateBackendUrl: (String) -> Unit,
    onUpdateVoiceCallSystemPrompt: (String) -> Unit,
    onUpdateVideoCallSystemPrompt: (String) -> Unit,
    onUpdateAssistantAvatar: (String) -> Unit,
    onUpdateUserAvatar: (String) -> Unit,
    onUpdateEnableBatchExecution: (Boolean) -> Unit,
    onUpdateEnableImproveMode: (Boolean) -> Unit,
    onUpdateReactOnly: (Boolean) -> Unit,
    onUpdateEnableChatAgent: (Boolean) -> Unit,
    onUpdateExecutionStrategy: (String) -> Unit,
    onUpdateFallbackStrategy: (String) -> Unit,
    onUpdateScreenshotCacheEnabled: (Boolean) -> Unit,
    onUpdateGestureDelayMs: (Int) -> Unit,
    onUpdateInputDelayMs: (Int) -> Unit,
    onRequestMediaProjectionPermission: () -> Unit = {},
    onSelectProvider: (ApiProvider) -> Unit,
    shizukuAvailable: Boolean,
    shizukuPrivilegeLevel: String = "ADB", // "ADB", "ROOT", "NONE"
    onFetchModels: ((onSuccess: (List<String>) -> Unit, onError: (String) -> Unit) -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
    onLogin: (() -> Unit)? = null,
    isLoggedIn: Boolean = false,
    userEmail: String? = null,
    navigateToCapabilities: () -> Unit = {},
    onNavigateToVoiceSelection: () -> Unit = {},
    onBack: () -> Unit = {},
    mediaProjectionResultCode: Int? = null,
    mediaProjectionData: Intent? = null,
    onShowShizukuHelpDialog: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showMaxStepsDialog by remember { mutableStateOf(false) }
    var showBaseUrlDialog by remember { mutableStateOf(false) }
    var showBackendUrlDialog by remember { mutableStateOf(false) }
    var showShizukuHelpDialog by remember { mutableStateOf(false) }
    var showOverlayHelpDialog by remember { mutableStateOf(false) }
    var showRootModeWarningDialog by remember { mutableStateOf(false) }
    var showSuCommandWarningDialog by remember { mutableStateOf(false) }

    // 二级页面导航状态
    var currentSubScreen by remember { mutableStateOf<SettingsSubScreen?>(null) }

    // 页面可见性状态（用于动画）
    var isPageVisible by remember { mutableStateOf(false) }

    LaunchedEffect(currentSubScreen) {
        isPageVisible = true
    }

    // 根据当前显示的页面返回相应内容
    AnimatedVisibility(
        visible = isPageVisible,
        enter = fadeIn(
            animationSpec = tween(400, easing = EaseOut)
        ),
        exit = fadeOut(
            animationSpec = tween(300, easing = EaseIn)
        )
    ) {
        if (currentSubScreen != null) {
            // 显示二级页面
            // 支持系统返回键
            BackHandler(enabled = true) {
                currentSubScreen = null
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
            ) {
                // 顶部导航栏 - 使用与 AlianLocalScreen 一致的 AlianAppBar
                item {
                    AlianAppBar(
                        title = currentSubScreen!!.title,
                        onMenuClick = { currentSubScreen = null },
                        menuIcon = Icons.Default.KeyboardArrowLeft,
                        showMoreMenu = false
                    )
                }

                // 根据子页面类型显示相应内容
                item {
                    // 为二级页面内容添加动画
                    var isSubScreenVisible by remember { mutableStateOf(false) }

                    LaunchedEffect(currentSubScreen) {
                        isSubScreenVisible = true
                    }

                    AnimatedVisibility(
                        visible = isSubScreenVisible,
                        enter = fadeIn(
                            animationSpec = tween(400, easing = EaseOut)
                        ),
                        exit = fadeOut(
                            animationSpec = tween(300, easing = EaseIn)
                        )
                    ) {
                        when (currentSubScreen) {
                            SettingsSubScreen.EXECUTION -> {
                                ExecutionSettingsContent(
                                    settings = settings,
                                    onShowMaxStepsDialog = { showMaxStepsDialog = true },
                                    onUpdateEnableBatchExecution = onUpdateEnableBatchExecution,
                                    onUpdateEnableImproveMode = onUpdateEnableImproveMode,
                                    onUpdateReactOnly = onUpdateReactOnly,
                                    onUpdateEnableChatAgent = onUpdateEnableChatAgent,
                                    onNavigateToDeviceController = {
                                        currentSubScreen = SettingsSubScreen.DEVICE_CONTROLLER
                                    }
                                )
                            }

                            SettingsSubScreen.SHIZUKU -> {
                                ShizukuSettingsContent(
                                    shizukuAvailable = shizukuAvailable,
                                    shizukuPrivilegeLevel = shizukuPrivilegeLevel,
                                    settings = settings,
                                    onShowRootModeWarningDialog = {
                                        showRootModeWarningDialog = true
                                    },
                                    onShowSuCommandWarningDialog = {
                                        showSuCommandWarningDialog = true
                                    },
                                    onUpdateRootModeEnabled = onUpdateRootModeEnabled,
                                    onUpdateSuCommandEnabled = onUpdateSuCommandEnabled
                                )
                            }

                            SettingsSubScreen.DEVICE_CONTROLLER -> {
                                DeviceControllerSettingsContent(
                                    settings = settings,
                                    onUpdateExecutionStrategy = onUpdateExecutionStrategy,
                                    onUpdateFallbackStrategy = onUpdateFallbackStrategy,
                                    onUpdateScreenshotCacheEnabled = onUpdateScreenshotCacheEnabled,
                                    onUpdateGestureDelayMs = onUpdateGestureDelayMs,
                                    onUpdateInputDelayMs = onUpdateInputDelayMs,
                                    onRequestMediaProjectionPermission = onRequestMediaProjectionPermission,
                                    onShowShizukuHelpDialog = onShowShizukuHelpDialog,
                                    mediaProjectionResultCode = mediaProjectionResultCode,
                                    mediaProjectionData = mediaProjectionData,
                                    shizukuAvailable = shizukuAvailable
                                )
                            }

                            SettingsSubScreen.TTS -> {
                                TTSSettingsContent(
                                    settings = settings,
                                    onUpdateTTSEnabled = onUpdateTTSEnabled,
                                    onUpdateTTSRealtime = onUpdateTTSRealtime,
                                    onUpdateTTSInterruptEnabled = onUpdateTTSInterruptEnabled,
                                    onUpdateEnableAEC = onUpdateEnableAEC,
                                    onUpdateEnableStreaming = onUpdateEnableStreaming
                                )
                            }

                            SettingsSubScreen.Alian -> {
                                AlianSettingsContent(
                                    settings = settings,
                                    onUpdateVoiceCallSystemPrompt = onUpdateVoiceCallSystemPrompt,
                                    onUpdateVideoCallSystemPrompt = onUpdateVideoCallSystemPrompt,
                                    onUpdateTTSVoice = onUpdateTTSVoice,
                                    onUpdateTTSSpeed = onUpdateTTSSpeed,
                                    onUpdateVolume = onUpdateVolume,
                                    onUpdateAssistantAvatar = onUpdateAssistantAvatar,
                                    onNavigateToVoiceSelection = onNavigateToVoiceSelection
                                )
                            }

                            SettingsSubScreen.API -> {
                                APISettingsContent(
                                    settings = settings,
                                    onShowBaseUrlDialog = { showBaseUrlDialog = true },
                                    onShowBackendUrlDialog = { showBackendUrlDialog = true },
                                    onShowApiKeyDialog = { showApiKeyDialog = true },
                                    onShowModelDialog = { showModelDialog = true }
                                )
                            }

                            SettingsSubScreen.FEEDBACK -> {
                                FeedbackSettingsContent(
                                    settings = settings,
                                    onUpdateCloudCrashReport = onUpdateCloudCrashReport
                                )
                            }

                            SettingsSubScreen.HELP -> {
                                HelpSettingsContent(
                                    onShowShizukuHelpDialog = { showShizukuHelpDialog = true },
                                    onShowOverlayHelpDialog = { showOverlayHelpDialog = true }
                                )
                            }

                            SettingsSubScreen.ABOUT -> {
                                AboutSettingsContent()
                            }

                            SettingsSubScreen.MCP -> {
                                MCPSettingsContent(
                                    onBack = { currentSubScreen = null }
                                )
                            }

                            SettingsSubScreen.PERMISSION_MANAGEMENT -> {
                                PermissionManagementSettingsContent(
                                    shizukuAvailable = shizukuAvailable,
                                    onNavigateToShizuku = {
                                        currentSubScreen = SettingsSubScreen.SHIZUKU
                                    }
                                )
                            }

                            null -> {}
                        }
                    }
                }
            }
    } else {
        // 显示一级页面
        // 支持系统返回键
        BackHandler(enabled = true) {
            onBack()
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            // 顶部标题 - 使用与 AlianLocalScreen 一致的 AlianAppBar
            item {
                AlianAppBar(
                    title = "设置",
                    onMenuClick = onBack,
                    menuIcon = Icons.Default.KeyboardArrowLeft,
                    showMoreMenu = true,
                    moreMenuContent = {
                        ConnectionStatusIndicator(
                            isConnected = shizukuAvailable,
                            text = if (shizukuAvailable) "Shizuku 已连接" else "Shizuku 未连接"
                        )
                    }
                )
            }

            // 登录状态卡片（独占一行）
            item {
                // 用户头像状态
                var userAvatarUri by remember(settings.userAvatar) { mutableStateOf<String?>(settings.userAvatar) }
                val context = LocalContext.current
                val avatarCacheManager = remember { AvatarCacheManager(context) }
                var isSavingAvatar by remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()

                // 图片选择器
                val imagePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    uri?.let {
                        isSavingAvatar = true
                        coroutineScope.launch {
                            val cachedPath = avatarCacheManager.saveUserAvatar(uri)
                            if (cachedPath != null) {
                                userAvatarUri = cachedPath
                                onUpdateUserAvatar(cachedPath)
                            }
                            isSavingAvatar = false
                        }
                    }
                }

                Box(modifier = Modifier.staggeredFadeIn(0)) {
                    LoginStatusCard(
                        isLoggedIn = isLoggedIn,
                        userEmail = userEmail,
                        useBackend = settings.useBackend,
                        avatarUri = userAvatarUri,
                        isSavingAvatar = isSavingAvatar,
                        onAvatarClick = {
                            if (isLoggedIn) {
                                imagePickerLauncher.launch("image/*")
                            } else {
                                onLogin?.invoke()
                            }
                        }
                    )
                }
            }

            // 外观与交互分组
            item {
                Box(modifier = Modifier.staggeredFadeIn(1)) {
                    SettingsSection(title = "🎨 外观与交互")
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 主题模式
                    Box(modifier = Modifier.weight(1f)) {
                        CompactGridItem(
                            icon = Icons.Default.Palette,
                            title = "主题模式",
                            subtitle = when (settings.themeMode) {
                                ThemeMode.LIGHT -> "浅色模式"
                                ThemeMode.DARK -> "深色模式"
                                ThemeMode.SYSTEM -> "跟随系统"
                            },
                            onClick = { showThemeDialog = true }
                        )
                    }
                    // Alian
                    Box(modifier = Modifier.weight(1f)) {
                        CompactGridItem(
                            icon = Icons.Default.Chat,
                            title = "Alian",
                            onClick = { currentSubScreen = SettingsSubScreen.Alian }
                        )
                    }
                    // 权限管理
                    Box(modifier = Modifier.weight(1f)) {
                        CompactGridItem(
                            icon = Icons.Default.Security,
                            title = "权限管理",
                            onClick = { currentSubScreen = SettingsSubScreen.PERMISSION_MANAGEMENT }
                        )
                    }
                }
            }

            // 核心功能分组（单行 3 列）
            item {
                Box(modifier = Modifier.staggeredFadeIn(2)) {
                    SettingsSection(title = "⚙️ 核心功能")
                }
            }
            item {
                Box(modifier = Modifier.staggeredFadeIn(3)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 执行设置
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Settings,
                                title = "执行",
                                onClick = { currentSubScreen = SettingsSubScreen.EXECUTION }
                            )
                        }
                        // 语音设置
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Notifications,
                                title = "语音",
                                onClick = { currentSubScreen = SettingsSubScreen.TTS }
                            )
                        }
                        // 能力
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Extension,
                                title = "能力",
                                onClick = { navigateToCapabilities() }
                            )
                        }
                    }
                }
            }

            // API 与支持分组（2 行 3 列）
            item {
                Box(modifier = Modifier.staggeredFadeIn(4)) {
                    SettingsSection(title = "🔌 API 与支持")
                }
            }
            // 第一行
            item {
                Box(modifier = Modifier.staggeredFadeIn(5)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 使用 Backend API
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.CloudSync,
                                title = "Backend",
                                subtitle = if (settings.useBackend) "已启用" else "未启用",
                                onClick = {
                                    onUpdateUseBackend(!settings.useBackend)
                                }
                            )
                        }
                        // API 配置
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Lock,
                                title = "API配置",
                                onClick = { currentSubScreen = SettingsSubScreen.API }
                            )
                        }
                        // 帮助
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.HelpOutline,
                                title = "帮助",
                                onClick = { currentSubScreen = SettingsSubScreen.HELP }
                            )
                        }
                    }
                }
            }
            // 第二行
            item {
                Box(modifier = Modifier.staggeredFadeIn(6)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 反馈与调试
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Feedback,
                                title = "反馈",
                                onClick = { currentSubScreen = SettingsSubScreen.FEEDBACK }
                            )
                        }
                        // 关于
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Build,
                                title = "关于",
                                onClick = { currentSubScreen = SettingsSubScreen.ABOUT }
                            )
                        }
                        // MCP 管理
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.CloudSync,
                                title = "MCP",
                                onClick = { currentSubScreen = SettingsSubScreen.MCP }
                            )
                        }
                    }
                }
            }

            // 登录/登出按钮（仅在Backend模式时显示）
            if (settings.useBackend) {
                item {
                    var showLogoutDialog by remember { mutableStateOf(false) }

                    if (isLoggedIn) {
                        // 已登录状态 - 显示登出按钮
                        if (onLogout != null) {
                            val context = LocalContext.current
                            Button(
                                onClick = {
                                    performLightHaptic(context)
                                    showLogoutDialog = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.error
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "登出",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "登出",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            // 登出确认对话框
                            if (showLogoutDialog) {
                                AlertDialog(
                                    onDismissRequest = { showLogoutDialog = false },
                                    containerColor = colors.backgroundCard,
                                    title = {
                                        Text("确认登出", color = colors.textPrimary)
                                    },
                                    text = {
                                        Text(
                                            "确定要登出吗？登出后需要重新登录才能使用 Alian 功能。",
                                            color = colors.textSecondary
                                        )
                                    },
                                    confirmButton = {
                                        val context = LocalContext.current
                                        TextButton(onClick = {
                                            performLightHaptic(context)
                                            onLogout()
                                            showLogoutDialog = false
                                            onBack()
                                        }) {
                                            Text("登出", color = colors.error)
                                        }
                                    },
                                    dismissButton = {
                                        val context = LocalContext.current
                                        TextButton(onClick = {
                                            performLightHaptic(context)
                                            showLogoutDialog = false
                                        }) {
                                            Text("取消", color = colors.textSecondary)
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        // 未登录状态 - 显示登录按钮
                        val context = LocalContext.current
                        Button(
                            onClick = {
                                performLightHaptic(context)
                                onLogin?.invoke()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary
                            )
                        ) {
                            Text(
                                text = "点击登录",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // 底部间距
            item {
                PageBottomSpacing()
            }
        }
        }
    }

    // 主题选择对话框
    if (showThemeDialog) {
        ThemeSelectDialog(
            currentTheme = settings.themeMode,
            onDismiss = { showThemeDialog = false },
            onSelect = {
                onUpdateThemeMode(it)
                showThemeDialog = false
            }
        )
    }

    // 最大步数设置对话框
    if (showMaxStepsDialog) {
        MaxStepsDialog(
            currentSteps = settings.maxSteps,
            onDismiss = { showMaxStepsDialog = false },
            onConfirm = {
                onUpdateMaxSteps(it)
                showMaxStepsDialog = false
            }
        )
    }

    // API Key 编辑对话框
    if (showApiKeyDialog) {
        ApiKeyDialog(
            currentKey = settings.apiKey,
            onDismiss = { showApiKeyDialog = false },
            onConfirm = {
                onUpdateApiKey(it)
                showApiKeyDialog = false
            }
        )
    }

    // 模型选择对话框（合并了自定义输入和从 API 获取）
    if (showModelDialog) {
        ModelSelectDialogWithFetch(
            currentModel = settings.model,
            cachedModels = settings.cachedModels,
            hasApiKey = settings.apiKey.isNotEmpty(),
            onDismiss = { showModelDialog = false },
            onSelect = {
                onUpdateModel(it)
                showModelDialog = false
            },
            onFetchModels = onFetchModels,
            onUpdateCachedModels = onUpdateCachedModels
        )
    }

    // 服务商选择对话框
    if (showBaseUrlDialog) {
        ProviderSelectDialog(
            currentProviderId = settings.currentProviderId,
            customBaseUrl = settings.currentConfig.customBaseUrl,
            onDismiss = { showBaseUrlDialog = false },
            onSelectProvider = { provider ->
                onSelectProvider(provider)
                showBaseUrlDialog = false
            },
            onUpdateCustomUrl = { url ->
                onUpdateBaseUrl(url)
            }
        )
    }

    // Backend 服务端地址编辑对话框
    if (showBackendUrlDialog) {
        var backendUrl by remember { mutableStateOf(settings.backendBaseUrl) }
        AlertDialog(
            onDismissRequest = { showBackendUrlDialog = false },
            containerColor = colors.backgroundCard,
            title = {
                Text("Backend 服务端地址", color = colors.textPrimary)
            },
            text = {
                Column {
                    Text(
                        text = "请输入 Backend 服务器的完整地址",
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = { backendUrl = it },
                        placeholder = {
                            Text("http://39.98.113.244:5173/api/v1", color = colors.textHint)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.textHint,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                }
            },
            confirmButton = {
                val context = LocalContext.current
                TextButton(
                    onClick = {
                        performLightHaptic(context)
                        onUpdateBackendUrl(backendUrl)
                        showBackendUrlDialog = false
                    }
                ) {
                    Text("确定", color = colors.primary)
                }
            },
            dismissButton = {
                val context = LocalContext.current
                TextButton(onClick = {
                    performLightHaptic(context)
                    showBackendUrlDialog = false
                }) {
                    Text("取消", color = colors.textSecondary)
                }
            }
        )
    }

    // Shizuku 帮助对话框
    if (showShizukuHelpDialog) {
        ShizukuHelpDialog(onDismiss = { showShizukuHelpDialog = false })
    }

    // 悬浮窗权限帮助对话框
    if (showOverlayHelpDialog) {
        OverlayHelpDialog(onDismiss = { showOverlayHelpDialog = false })
    }

    // Root 模式警告对话框
    if (showRootModeWarningDialog) {
        RootModeWarningDialog(
            onDismiss = { showRootModeWarningDialog = false },
            onConfirm = {
                onUpdateRootModeEnabled(true)
                showRootModeWarningDialog = false
            }
        )
    }

    // su -c 命令警告对话框
    if (showSuCommandWarningDialog) {
        SuCommandWarningDialog(
            onDismiss = { showSuCommandWarningDialog = false },
            onConfirm = {
                onUpdateSuCommandEnabled(true)
                showSuCommandWarningDialog = false
            }
        )
    }

    }