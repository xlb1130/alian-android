package com.alian.assistant.presentation.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.alian.assistant.R
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
import androidx.compose.material.icons.filled.Api
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.alian.assistant.presentation.ui.screens.settings.ModelConfigSettingsContent
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
import com.alian.assistant.presentation.ui.screens.settings.LanguageSelectDialog
import com.alian.assistant.presentation.ui.screens.settings.SuCommandWarningDialog
import com.alian.assistant.presentation.ui.screens.settings.TTSSettingsContent
import com.alian.assistant.presentation.ui.screens.settings.ThemeSelectDialog
import com.alian.assistant.presentation.ui.screens.settings.ConnectionStatusIndicator
import com.alian.assistant.common.utils.AvatarCacheManager
import com.alian.assistant.common.utils.LanguageManager
import com.alian.assistant.presentation.ui.screens.settings.SpeechProviderSettingsContent
import com.alian.assistant.data.model.SpeechProvider
import com.alian.assistant.data.model.SpeechProviderCredentials
import com.alian.assistant.data.model.SpeechModels

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
        delay(index * 25L)
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
    onUpdateTextModel: (String) -> Unit,
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
    onUpdateEnableFlowMode: (Boolean) -> Unit,
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
    onNavigateToSpeechProviderSettings: () -> Unit = {},
    onNavigateToFlowTemplate: () -> Unit = {},
    onBack: () -> Unit = {},
    mediaProjectionResultCode: Int? = null,
    mediaProjectionData: Intent? = null,
    onShowShizukuHelpDialog: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showTextModelDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
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
                        title = stringResource(currentSubScreen!!.titleResId),
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
                                    onUpdateEnableFlowMode = onUpdateEnableFlowMode,
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

                            SettingsSubScreen.VOICE_INTERACTION -> {
                                TTSSettingsContent(
                                    settings = settings,
                                    onUpdateTTSEnabled = onUpdateTTSEnabled,
                                    onUpdateTTSRealtime = onUpdateTTSRealtime,
                                    onUpdateTTSInterruptEnabled = onUpdateTTSInterruptEnabled,
                                    onUpdateEnableAEC = onUpdateEnableAEC,
                                    onUpdateEnableStreaming = onUpdateEnableStreaming
                                )
                            }

                            SettingsSubScreen.ALIAN -> {
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

                            SettingsSubScreen.THEME -> {
                                // 主题模式直接弹窗，不需要二级页面
                                // 这里保留占位，实际逻辑在一级页面处理
                            }

                            SettingsSubScreen.API -> {
                                APISettingsContent(
                                    settings = settings,
                                    onShowBackendUrlDialog = { showBackendUrlDialog = true },
                                    onUpdateUseBackend = onUpdateUseBackend
                                )
                            }

                            SettingsSubScreen.MODEL_CONFIG -> {
                                ModelConfigSettingsContent(
                                    settings = settings,
                                    onShowBaseUrlDialog = { showBaseUrlDialog = true },
                                    onShowApiKeyDialog = { showApiKeyDialog = true },
                                    onShowModelDialog = { showModelDialog = true },
                                    onShowTextModelDialog = { showTextModelDialog = true }
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

                            SettingsSubScreen.SPEECH_PROVIDER -> {
                                onNavigateToSpeechProviderSettings()
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
                    title = stringResource(R.string.settings_title),
                    onMenuClick = onBack,
                    menuIcon = Icons.Default.KeyboardArrowLeft,
                    showMoreMenu = true,
                    moreMenuContent = {
                        ConnectionStatusIndicator(
                            isConnected = shizukuAvailable,
                            text = if (shizukuAvailable) stringResource(R.string.shizuku_connected) else stringResource(R.string.shizuku_not_connected)
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

            // ==================== 🎨 外观与个性化 ====================
            item {
                Box(modifier = Modifier.staggeredFadeIn(1)) {
                    SettingsSection(title = stringResource(R.string.settings_section_appearance))
                }
            }
            item {
                Box(modifier = Modifier.staggeredFadeIn(2)) {
                    val context = LocalContext.current
                    val currentLanguageCode = remember { LanguageManager.getSelectedLanguageCode(context) }
                    val currentLanguageName = when (currentLanguageCode) {
                        "system" -> stringResource(R.string.language_system)
                        "zh" -> stringResource(R.string.language_zh)
                        "zh-TW" -> stringResource(R.string.language_zh_tw)
                        "en" -> stringResource(R.string.language_en)
                        "ja" -> stringResource(R.string.language_ja)
                        "ko" -> stringResource(R.string.language_ko)
                        else -> stringResource(R.string.language_system)
                    }
                    
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
                                title = stringResource(R.string.settings_item_theme),
                                subtitle = when (settings.themeMode) {
                                    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                    ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                },
                                onClick = { showThemeDialog = true }
                            )
                        }
                        // Alian 设置
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.PhoneAndroid,
                                title = stringResource(R.string.settings_item_alian),
                                subtitle = stringResource(R.string.settings_item_alian_subtitle),
                                onClick = { currentSubScreen = SettingsSubScreen.ALIAN }
                            )
                        }
                        // 语言设置
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Chat,
                                title = stringResource(R.string.settings_item_language),
                                subtitle = currentLanguageName,
                                onClick = { showLanguageDialog = true }
                            )
                        }
                    }
                }
            }

            // ==================== 🧠 AI 模型配置 ====================
            item {
                Box(modifier = Modifier.staggeredFadeIn(3)) {
                    SettingsSection(title = stringResource(R.string.settings_section_ai))
                }
            }
            item {
                Box(modifier = Modifier.staggeredFadeIn(4)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 模型配置
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Memory,
                                title = stringResource(R.string.settings_item_model_config),
                                subtitle = stringResource(R.string.settings_item_model_config_subtitle),
                                onClick = { currentSubScreen = SettingsSubScreen.MODEL_CONFIG }
                            )
                        }
                        // 语音服务商
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.RecordVoiceOver,
                                title = stringResource(R.string.settings_item_speech_provider),
                                subtitle = stringResource(R.string.settings_item_speech_provider_subtitle),
                                onClick = { currentSubScreen = SettingsSubScreen.SPEECH_PROVIDER }
                            )
                        }
                        // API 配置
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Api,
                                title = stringResource(R.string.settings_item_api_config),
                                subtitle = stringResource(R.string.settings_item_api_config_subtitle),
                                onClick = { currentSubScreen = SettingsSubScreen.API }
                            )
                        }
                    }
                }
            }

            // ==================== ⚙️ 执行与控制 ====================
            item {
                Box(modifier = Modifier.staggeredFadeIn(5)) {
                    SettingsSection(title = stringResource(R.string.settings_section_execution))
                }
            }
            item {
                Box(modifier = Modifier.staggeredFadeIn(6)) {
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
                                title = stringResource(R.string.settings_item_execution),
                                subtitle = stringResource(R.string.settings_item_execution_subtitle),
                                onClick = { currentSubScreen = SettingsSubScreen.EXECUTION }
                            )
                        }
                        // 语音交互
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.VolumeUp,
                                title = stringResource(R.string.settings_item_voice_interaction),
                                subtitle = stringResource(R.string.settings_item_voice_interaction_subtitle),
                                onClick = { currentSubScreen = SettingsSubScreen.VOICE_INTERACTION }
                            )
                        }
                        // 设备控制器
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.PhoneAndroid,
                                title = stringResource(R.string.settings_item_device_controller),
                                subtitle = stringResource(R.string.settings_item_device_controller_subtitle),
                                onClick = { currentSubScreen = SettingsSubScreen.DEVICE_CONTROLLER }
                            )
                        }
                    }
                }
            }

            // ==================== 🔐 权限与安全 ====================
            item {
                Box(modifier = Modifier.staggeredFadeIn(7)) {
                    SettingsSection(title = stringResource(R.string.settings_section_permission))
                }
            }
            item {
                Box(modifier = Modifier.staggeredFadeIn(8)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 权限管理
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Security,
                                title = stringResource(R.string.settings_item_permission),
                                subtitle = stringResource(R.string.settings_item_permission_subtitle),
                                onClick = { currentSubScreen = SettingsSubScreen.PERMISSION_MANAGEMENT }
                            )
                        }
                        // Shizuku 设置
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Hub,
                                title = stringResource(R.string.settings_item_shizuku),
                                subtitle = stringResource(R.string.settings_item_shizuku_subtitle),
                                onClick = { currentSubScreen = SettingsSubScreen.SHIZUKU }
                            )
                        }
                        // 占位
                        Box(modifier = Modifier.weight(1f)) {}
                    }
                }
            }

            // ==================== 🔌 扩展能力 ====================
            item {
                Box(modifier = Modifier.staggeredFadeIn(9)) {
                    SettingsSection(title = stringResource(R.string.settings_section_extension))
                }
            }
            item {
                Box(modifier = Modifier.staggeredFadeIn(10)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // MCP 管理
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Extension,
                                title = stringResource(R.string.settings_item_mcp),
                                subtitle = stringResource(R.string.settings_item_mcp_subtitle),
                                onClick = { currentSubScreen = SettingsSubScreen.MCP }
                            )
                        }
                        // 流程模板
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.AccountTree,
                                title = stringResource(R.string.flow_template),
                                subtitle = stringResource(R.string.flow_template_count, 0),
                                onClick = { onNavigateToFlowTemplate() }
                            )
                        }
                        // 能力
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.AutoAwesome,
                                title = stringResource(R.string.settings_item_capabilities),
                                subtitle = stringResource(R.string.settings_item_capabilities_subtitle),
                                onClick = { navigateToCapabilities() }
                            )
                        }
                    }
                }
            }

            // ==================== ❓ 帮助与反馈 ====================
            item {
                Box(modifier = Modifier.staggeredFadeIn(11)) {
                    SettingsSection(title = stringResource(R.string.settings_section_help))
                }
            }
            item {
                Box(modifier = Modifier.staggeredFadeIn(12)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 帮助
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.HelpOutline,
                                title = stringResource(R.string.settings_item_help),
                                onClick = { currentSubScreen = SettingsSubScreen.HELP }
                            )
                        }
                        // 反馈与调试
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Feedback,
                                title = stringResource(R.string.settings_item_feedback),
                                onClick = { currentSubScreen = SettingsSubScreen.FEEDBACK }
                            )
                        }
                        // 关于
                        Box(modifier = Modifier.weight(1f)) {
                            CompactGridItem(
                                icon = Icons.Default.Info,
                                title = stringResource(R.string.settings_item_about),
                                onClick = { currentSubScreen = SettingsSubScreen.ABOUT }
                            )
                        }
                    }
                }
            }

            // 登录/登出按钮（仅在Backend模式时显示）
            if (settings.useBackend) {
                item {
                    Box(modifier = Modifier.staggeredFadeIn(13)) {
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
                                        contentDescription = stringResource(R.string.login_logout),
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.login_logout),
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
                                            Text(stringResource(R.string.login_logout_confirm_title), color = colors.textPrimary)
                                        },
                                        text = {
                                            Text(
                                                stringResource(R.string.login_logout_confirm_desc),
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
                                                Text(stringResource(R.string.login_logout), color = colors.error)
                                            }
                                        },
                                        dismissButton = {
                                            val context = LocalContext.current
                                            TextButton(onClick = {
                                                performLightHaptic(context)
                                                showLogoutDialog = false
                                            }) {
                                                Text(stringResource(R.string.btn_cancel), color = colors.textSecondary)
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
                                    text = stringResource(R.string.login_click_to_login),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
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

    // 语言选择对话框
    if (showLanguageDialog) {
        val context = LocalContext.current
        val activity = context as? android.app.Activity
        val currentLangCode = LanguageManager.getSelectedLanguageCode(context)
        
        LanguageSelectDialog(
            currentLanguage = currentLangCode,
            onDismiss = { showLanguageDialog = false },
            onSelect = { languageCode ->
                showLanguageDialog = false
                if (languageCode != currentLangCode) {
                    // 保存语言设置
                    LanguageManager.setLanguage(context, languageCode)
                    // 立即重启应用
                    activity?.let { 
                        LanguageManager.changeLanguageAndRestart(it, languageCode)
                    }
                }
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

    // 文本模型选择对话框
    if (showTextModelDialog) {
        var textModelInput by remember { mutableStateOf(settings.textModel) }
        AlertDialog(
            onDismissRequest = { showTextModelDialog = false },
            containerColor = colors.backgroundCard,
            title = {
                Text(stringResource(R.string.dialog_text_model_title), color = colors.textPrimary)
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.dialog_text_model_hint),
                        fontSize = 14.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = textModelInput,
                        onValueChange = { textModelInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("qwen-max", color = colors.textHint)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.textHint.copy(alpha = 0.3f),
                            cursorColor = colors.primary,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.dialog_text_model_common),
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (textModelInput.isNotBlank()) {
                        onUpdateTextModel(textModelInput.trim())
                    }
                    showTextModelDialog = false
                }) {
                    Text(stringResource(R.string.btn_confirm), color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextModelDialog = false }) {
                    Text(stringResource(R.string.btn_cancel), color = colors.textSecondary)
                }
            }
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
                Text(stringResource(R.string.dialog_backend_url_title), color = colors.textPrimary)
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.dialog_backend_url_hint),
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
                    Text(stringResource(R.string.btn_confirm), color = colors.primary)
                }
            },
            dismissButton = {
                val context = LocalContext.current
                TextButton(onClick = {
                    performLightHaptic(context)
                    showBackendUrlDialog = false
                }) {
                    Text(stringResource(R.string.btn_cancel), color = colors.textSecondary)
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