package com.alian.assistant.presentation.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.alian.assistant.data.model.SpeechProvider
import com.alian.assistant.data.model.SpeechProviderCredentials
import com.alian.assistant.data.model.SpeechModels
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.ui.screens.components.AlianAppBar

/**
 * 语音服务商设置页面
 */
@Composable
fun SpeechProviderSettingsScreen(
    currentProvider: SpeechProvider,
    credentials: Map<SpeechProvider, SpeechProviderCredentials>,
    models: Map<SpeechProvider, SpeechModels>,
    offlineAsrEnabled: Boolean,
    offlineAsrAutoFallbackToCloud: Boolean,
    onBack: () -> Unit,
    onSelectProvider: (SpeechProvider) -> Unit,
    onUpdateCredentials: (SpeechProvider, SpeechProviderCredentials) -> Unit,
    onUpdateModels: (SpeechProvider, SpeechModels) -> Unit,
    onUpdateOfflineAsrEnabled: (Boolean) -> Unit,
    onUpdateOfflineAsrAutoFallbackToCloud: (Boolean) -> Unit
) {
    val colors = BaoziTheme.colors
    var isPageVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isPageVisible = true
    }

    // 支持系统返回键
    BackHandler(enabled = true) {
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部导航栏
        AlianAppBar(
            title = "语音服务商",
            onMenuClick = onBack,
            menuIcon = Icons.Default.KeyboardArrowLeft,
            showMoreMenu = false
        )

        // 内容
        SpeechProviderSettingsContent(
            currentProvider = currentProvider,
            credentials = credentials,
            models = models,
            offlineAsrEnabled = offlineAsrEnabled,
            offlineAsrAutoFallbackToCloud = offlineAsrAutoFallbackToCloud,
            onSelectProvider = onSelectProvider,
            onUpdateCredentials = onUpdateCredentials,
            onUpdateModels = onUpdateModels,
            onUpdateOfflineAsrEnabled = onUpdateOfflineAsrEnabled,
            onUpdateOfflineAsrAutoFallbackToCloud = onUpdateOfflineAsrAutoFallbackToCloud
        )
    }
}
