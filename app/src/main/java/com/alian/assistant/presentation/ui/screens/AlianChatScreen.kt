package com.alian.assistant.presentation.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.alian.assistant.R
import com.alian.assistant.core.alian.backend.SessionData
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.presentation.ui.screens.components.AlianAppBar
import com.alian.assistant.presentation.ui.screens.components.AlianLogoutDialog
import com.alian.assistant.presentation.ui.screens.components.ImagePreviewScreen
import com.alian.assistant.presentation.ui.screens.components.VideoPreviewScreen
import com.alian.assistant.presentation.ui.screens.components.RightActionButton
import com.alian.assistant.presentation.ui.screens.online.AlianChatHistoryDrawer
import com.alian.assistant.presentation.ui.screens.online.AlianChatContent
import com.alian.assistant.presentation.ui.screens.online.buildAlianChatMenuItems
import com.alian.assistant.presentation.ui.screens.online.AlianInputArea
import com.alian.assistant.presentation.viewmodel.AlianViewModel
import com.alian.assistant.presentation.ui.screens.components.MarkdownWebViewScreen
import com.alian.assistant.presentation.ui.screens.online.VoiceRippleOverlay
import com.alian.assistant.presentation.ui.screens.components.isAttachmentUrl
import com.alian.assistant.presentation.ui.screens.voicecall.VoiceCallScreen
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.infrastructure.voice.VoiceRecognitionManager
import com.alian.assistant.infrastructure.ai.asr.StreamingVoiceRecognitionManager
import com.alian.assistant.presentation.ui.screens.components.HapticUtils
import com.alian.assistant.presentation.viewmodel.PlanItem
import com.alian.assistant.presentation.ui.screens.phonecall.PhoneCallScreen
import com.alian.assistant.presentation.ui.screens.videocall.VLMOnlyVideoCallScreen
import kotlinx.coroutines.launch
import java.io.File
import com.alian.assistant.presentation.viewmodel.PendingUploadAttachment

/**
 * Alian 聊天屏幕 - 聊天交互的核心界面
 */
@OptIn(
    ExperimentalComposeUiApi::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun AlianChatScreen(
    context: Context,
    viewModel: AlianViewModel,
    apiKey: String = "",
    baseUrl: String = "",
    model: String = "",
    voiceCallSystemPrompt: String = "",
    videoCallSystemPrompt: String = "",
    useBackend: Boolean = false,
    ttsEnabled: Boolean = false,
    ttsRealtime: Boolean = false,
    onTtsRealtimeChanged: (Boolean) -> Unit = {},
    ttsVoice: String = "longyingmu_v3",
    ttsSpeed: Float = 1.0f,
    ttsInterruptEnabled: Boolean = false,
    enableAEC: Boolean = false,
    enableStreaming: Boolean = false,
    offlineTtsEnabled: Boolean = false,
    offlineTtsAutoFallbackToCloud: Boolean = true,
    volume: Int = 50,
    voiceRecognitionManager: VoiceRecognitionManager? = null,
    streamingVoiceRecognitionManager: StreamingVoiceRecognitionManager? = null,
    onHideBottomBarChanged: (Boolean) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onLogout: () -> Unit = {},
    onShowHistory: () -> Unit = {},
    showHistoryDrawer: Boolean = false,
    onHistoryDrawerChanged: (Boolean) -> Unit = {},
    showSwitchButton: Boolean = false,
    onSwitchToLocal: (() -> Unit)? = null,
    userAvatar: String? = null,
    assistantAvatar: String? = null,
    deviceController: IDeviceController? = null,
    onRequireMediaProjection: (() -> Unit)? = null,
    mediaProjectionResultCode: Int? = null,
    mediaProjectionData: Intent? = null
) {
    val colors = BaoziTheme.colors
    val scope = rememberCoroutineScope()
    
    // 状态管理
    var showVoiceCall by remember { mutableStateOf(false) }
    var showVideoCall by remember { mutableStateOf(false) }
    var showPhoneCall by remember { mutableStateOf(false) }
    var showMarkdownWebView by remember { mutableStateOf(false) }
    var markdownWebViewUrl by remember { mutableStateOf("") }
    var markdownWebViewTitle by remember { mutableStateOf("") }
    var showImagePreview by remember { mutableStateOf(false) }
    var imagePreviewUrl by remember { mutableStateOf("") }
    var imagePreviewTitle by remember { mutableStateOf("") }
    var showVideoPreview by remember { mutableStateOf(false) }
    var videoPreviewUrl by remember { mutableStateOf("") }
    var videoPreviewTitle by remember { mutableStateOf("") }
    var showLogoutDialog by remember { mutableStateOf(false) }
    
    var inputText by remember { mutableStateOf("") }
    var isVoiceInputMode by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordedText by remember { mutableStateOf("") }
    var showVoiceRipple by remember { mutableStateOf(false) }
    var voiceRipplePosition by remember { mutableStateOf<Offset?>(null) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    
    // PlanCard吸顶控制
    var shouldPinPlanCard by remember { mutableStateOf(false) }
    var planItemOriginalIndex by remember { mutableStateOf(-1) }

    // 抽屉状态
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.addPendingAttachment(it) }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            pendingCameraUri?.let { viewModel.addPendingAttachment(it) }
        }
    }

    fun launchTakePicture() {
        val file = File(context.cacheDir, "chat_photo_${System.currentTimeMillis()}.jpg")
        if (!file.exists()) {
            file.createNewFile()
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchTakePicture()
        } else {
            Toast.makeText(context, "未授予相机权限", Toast.LENGTH_SHORT).show()
        }
    }

    // 监听 PlanItem 索引
    LaunchedEffect(viewModel.unifiedChatTimeline.size) {
        val newIndex = viewModel.unifiedChatTimeline.indexOfFirst {
            it is PlanItem
        }
        Log.d("AlianChatScreen", "PlanItem 索引变化: oldIndex=$planItemOriginalIndex, newIndex=$newIndex, 时间线大小=${viewModel.unifiedChatTimeline.size}")
        planItemOriginalIndex = newIndex
    }

    // 监听滚动位置，控制PlanCard吸顶状态
    LaunchedEffect(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        planItemOriginalIndex
    ) {
        val newShouldPin = planItemOriginalIndex >= 0 &&
                (listState.firstVisibleItemIndex > planItemOriginalIndex ||
                        (listState.firstVisibleItemIndex == planItemOriginalIndex && listState.firstVisibleItemScrollOffset > 0))
        if (newShouldPin != shouldPinPlanCard) {
            shouldPinPlanCard = newShouldPin
        }
    }

    // 同步抽屉状态
    LaunchedEffect(showHistoryDrawer) {
        if (showHistoryDrawer && !drawerState.isOpen) {
            drawerState.open()
        } else if (!showHistoryDrawer && drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
    }

    LaunchedEffect(drawerState.isOpen) {
        onHistoryDrawerChanged(drawerState.isOpen)
        if (drawerState.isOpen) {
            HapticUtils.performLightHaptic(context)
            viewModel.loadSessions()
        } else {
            HapticUtils.performLightHaptic(context)
        }
    }

    // 更新 TTS 配置
    LaunchedEffect(ttsEnabled, ttsRealtime, ttsVoice, apiKey, offlineTtsEnabled, offlineTtsAutoFallbackToCloud) {
        viewModel.updateTTSConfig(
            enabled = ttsEnabled,
            realtime = ttsRealtime,
            voice = ttsVoice,
            offlineEnabled = offlineTtsEnabled,
            offlineAutoFallbackToCloud = offlineTtsAutoFallbackToCloud
        )
    }

    // 监听语音通话状态
    LaunchedEffect(showVoiceCall) {
        onHideBottomBarChanged(showVoiceCall)
    }

    // 自动滚动到底部（历史切换期间禁用动画滚动，避免大量消息恢复时卡顿）
    val sessionLoadingState = viewModel.sessionLoadingState.value
    LaunchedEffect(viewModel.unifiedChatTimeline.size, sessionLoadingState) {
        if (viewModel.unifiedChatTimeline.isEmpty()) {
            return@LaunchedEffect
        }
        val lastIndex = viewModel.unifiedChatTimeline.size - 1
        if (sessionLoadingState is com.alian.assistant.presentation.viewmodel.SessionLoadingState.Switching) {
            return@LaunchedEffect
        }
        listState.scrollToItem(lastIndex)
    }

    val context = LocalContext.current
    
    // 构建更多菜单项
    val moreMenuItems = buildAlianChatMenuItems(
        context = context,
        useBackend = useBackend,
        isLoggedIn = viewModel.isLoggedIn.value,
        colors = BaoziTheme.colors,
        onVideoCall = { showVideoCall = true },
        onVoiceCall = { showVoiceCall = true },
        onPhoneCall = { showPhoneCall = true },
        onCreateNewSession = { viewModel.createNewSession() },
        onNavigateToSettings = onNavigateToSettings,
        onLogout = { showLogoutDialog = true }
    )

    // 登出确认对话框
    if (showLogoutDialog) {
        AlianLogoutDialog(
            onLogout = {
                onLogout()
                showLogoutDialog = false
            },
            onDismiss = {
                showLogoutDialog = false
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !showMarkdownWebView && !showImagePreview,
        drawerContent = {
            AlianChatHistoryDrawer(
                context = context,
                sessions = viewModel.sessions.toList(),
                selectedSessionId = viewModel.currentSessionIdUi.value,
                onSessionClick = { session: SessionData ->
                    viewModel.selectSessionAsync(session.session_id)
                    scope.launch { drawerState.close() }
                },
                onDeleteSession = { session: SessionData ->
                    scope.launch {
                        viewModel.deleteSession(session.session_id)
                    }
                },
                onCreateNewSession = {
                    viewModel.createNewSession()
                    scope.launch { drawerState.close() }
                },
                onClose = {
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                // 顶部标题栏 - 使用公共组件
                AlianAppBar(
                    title = stringResource(R.string.tab_home),
                    onMenuClick = {
                        scope.launch { drawerState.open() }
                    },
                    showSwitchButton = showSwitchButton,
                    onSwitchClick = onSwitchToLocal,
                    rightActions = listOf(
                        RightActionButton(
                            icon = if (ttsRealtime) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = if (ttsRealtime) stringResource(R.string.tts_realtime_off) else stringResource(R.string.tts_realtime_on),
                            onClick = {
                                onTtsRealtimeChanged(!ttsRealtime)
                                HapticUtils.performLightHaptic(context)
                            }
                        )
                    ),
                    showMoreMenu = true,
                    moreMenuItems = moreMenuItems
                )

                // 聊天消息区域
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        )
                ) {
                    val isLoggedIn = viewModel.isLoggedIn.value

                    if (isLoggedIn) {
                        AlianChatContent(
                            viewModel = viewModel,
                            listState = listState,
                            shouldPinPlanCard = shouldPinPlanCard,
                            ttsEnabled = ttsEnabled,
                            onPlayClick = { messageId ->
                                scope.launch {
                                    viewModel.playMessageTTS(messageId)
                                }
                            },
                            onStopClick = {
                                viewModel.stopTTS()
                            },
                            onLinkClick = { url, fileName, fileId ->
                                scope.launch {
                                    var resolvedUrl = url
                                    val isRemoteUrl = resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://")
                                    val isLocalUri = resolvedUrl.startsWith("content://") || resolvedUrl.startsWith("file://")

                                    if (!isRemoteUrl && !isLocalUri && !fileId.isNullOrBlank()) {
                                        val backendClient = viewModel.getBackendClient()
                                        if (backendClient != null) {
                                            val signedUrlResult = backendClient.getFileSignedUrl(fileId)
                                            if (signedUrlResult.isSuccess) {
                                                resolvedUrl = signedUrlResult.getOrNull().orEmpty()
                                                Log.d("AlianChatScreen", "按需签名附件成功: fileId=$fileId")
                                            } else {
                                                Log.w("AlianChatScreen", "按需签名附件失败: fileId=$fileId, error=${signedUrlResult.exceptionOrNull()?.message}")
                                            }
                                        }
                                    }

                                    val finalValidUrl = resolvedUrl.startsWith("http://") ||
                                        resolvedUrl.startsWith("https://") ||
                                        resolvedUrl.startsWith("content://") ||
                                        resolvedUrl.startsWith("file://")
                                    if (!finalValidUrl) {
                                        Toast.makeText(context, "附件链接无效，无法获取可访问 URL", Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }

                                    // 检查是否为图片文件
                                    val isImage = listOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp").any {
                                        fileName.lowercase().endsWith(it)
                                    }

                                    // 检查是否为视频文件
                                    val isVideo = listOf(".mp4", ".avi", ".mov", ".wmv", ".flv", ".mkv", ".webm", ".m4v", ".3gp").any {
                                        fileName.lowercase().endsWith(it)
                                    }

                                    Log.d("AlianChatScreen", "onLinkClick: url=$resolvedUrl, fileName=$fileName, isImage=$isImage, isVideo=$isVideo, fileId=$fileId")

                                    when {
                                        isImage -> {
                                            imagePreviewUrl = resolvedUrl
                                            imagePreviewTitle = fileName
                                            showImagePreview = true
                                            Log.d("AlianChatScreen", "打开图片预览: $resolvedUrl")
                                        }
                                        isVideo -> {
                                            videoPreviewUrl = resolvedUrl
                                            videoPreviewTitle = fileName
                                            showVideoPreview = true
                                            Log.d("AlianChatScreen", "打开视频预览: $resolvedUrl")
                                        }
                                        else -> {
                                            markdownWebViewUrl = resolvedUrl
                                            markdownWebViewTitle = fileName
                                            showMarkdownWebView = true
                                            Log.d("AlianChatScreen", "打开 Markdown 预览: $resolvedUrl")
                                        }
                                    }
                                }
                            },
                            userAvatar = userAvatar,
                            assistantAvatar = assistantAvatar
                        )
                    }
                }

                if (viewModel.pendingUploadAttachments.isNotEmpty()) {
                    PendingAttachmentStrip(
                        attachments = viewModel.pendingUploadAttachments.toList(),
                        onRemove = { attachmentId ->
                            viewModel.removePendingAttachment(attachmentId)
                        }
                    )
                }

                // 底部输入区域
                AlianInputArea(
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSend = {
                        val hasPendingAttachment = viewModel.pendingUploadAttachments.isNotEmpty()
                        if (inputText.isNotBlank() || hasPendingAttachment) {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    },
                    isVoiceInputMode = isVoiceInputMode,
                    onVoiceInputModeChanged = { newValue -> isVoiceInputMode = newValue },
                    onVoiceInput = { recognizedText ->
                        if (recognizedText.isNotBlank()) {
                            viewModel.sendMessage(recognizedText)
                        }
                    },
                    isRecording = isRecording,
                    recordedText = recordedText,
                    onRecordingChange = { newValue -> isRecording = newValue },
                    onRecordedTextChanged = { newValue -> recordedText = newValue },
                    voiceRecognitionManager = voiceRecognitionManager,
                    streamingVoiceRecognitionManager = streamingVoiceRecognitionManager,
                    isProcessing = viewModel.isProcessing.value,
                    onCancel = {
                        viewModel.cancelMessage()
                    },
                    canSend = inputText.isNotBlank() || viewModel.pendingUploadAttachments.isNotEmpty(),
                    onAttachmentClick = {
                        showAttachmentSheet = true
                    },
                    sessionId = viewModel.getCurrentSessionId(),
                    backendClient = viewModel.getBackendClient(),
                    onVoiceRippleChanged = { showVoiceRipple = it },
                    onVoiceRipplePositionChanged = { voiceRipplePosition = it }
                )
            }

            // 语音按钮波纹效果层
            if (showVoiceRipple) {
                Log.d(
                    "VoiceRipple",
                    "显示波纹效果，位置: $voiceRipplePosition, 录音文本: $recordedText"
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f)
                ) {
                    VoiceRippleOverlay(
                        position = voiceRipplePosition,
                        colors = colors,
                        recordedText = recordedText
                    )
                }
            } else {
                Log.d("VoiceRipple", "不显示波纹效果，showVoiceRipple: $showVoiceRipple")
            }

            // 语音通话页面（覆盖显示，覆盖整个 Alian 页面，包括 plan 弹层）
            AnimatedVisibility(
                visible = showVoiceCall,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                ) {
                    VoiceCallScreen(
                        context = context,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = model,
                        systemPrompt = voiceCallSystemPrompt,
                        ttsVoice = ttsVoice,
                        ttsSpeed = ttsSpeed,
                        ttsInterruptEnabled = ttsInterruptEnabled,
                        enableAEC = enableAEC,
                        enableStreaming = enableStreaming,  // 启用流式 LLM + 流式 TTS
                        volume = volume,
                        onBackClick = {
                            showVoiceCall = false
                        }
                    )
                }
            }

            // 视频通话页面（覆盖显示，覆盖整个 Alian 页面，包括 plan 弹层）
            AnimatedVisibility(
                visible = showVideoCall,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                ) {
                    VLMOnlyVideoCallScreen(
                        context = context,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = "qwen-vl-max",
                        systemPrompt = videoCallSystemPrompt,
                        ttsVoice = ttsVoice,
                        ttsSpeed = ttsSpeed,
                        ttsInterruptEnabled = ttsInterruptEnabled,
                        enableAEC = enableAEC,
                        enableStreaming = enableStreaming,
                        volume = volume,
                        onBackClick = {
                            showVideoCall = false
                        }
                    )
                }
            }

            // 手机通话页面（覆盖显示，覆盖整个 Alian 页面，包括 plan 弹层）
            AnimatedVisibility(
                visible = showPhoneCall,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                ) {
                    PhoneCallScreen(
                        context = context,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = model,
                        systemPrompt = voiceCallSystemPrompt,
                        ttsVoice = ttsVoice,
                        ttsSpeed = ttsSpeed,
                        ttsInterruptEnabled = ttsInterruptEnabled,
                        enableAEC = enableAEC,
                        enableStreaming = enableStreaming,
                        volume = volume,
                        deviceController = deviceController,
                        onRequireMediaProjection = onRequireMediaProjection,
                        mediaProjectionResultCode = mediaProjectionResultCode,
                        mediaProjectionData = mediaProjectionData,
                        onBackClick = {
                            showPhoneCall = false
                        }
                    )
                }
            }

            // Markdown WebView 预览屏幕
            AnimatedVisibility(
                visible = showMarkdownWebView,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                ) {
                    MarkdownWebViewScreen(
                        url = markdownWebViewUrl,
                        title = markdownWebViewTitle,
                        onBackClick = {
                            showMarkdownWebView = false
                        },
                        onLinkClick = { url ->
                            // 处理 WebView 中的链接点击
                            if (isAttachmentUrl(url)) {
                                // 如果是附件，提取文件名并打开新预览
                                val fileName = url.substringAfterLast("/")
                                
                                // 检查是否为图片文件
                                val isImage = listOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp").any {
                                    fileName.lowercase().endsWith(it)
                                }
                                
                                if (isImage) {
                                    // 图片文件使用独立的图片预览
                                    imagePreviewUrl = url
                                    imagePreviewTitle = fileName
                                    showImagePreview = true
                                } else {
                                    // 其他文件使用 Markdown WebView
                                    markdownWebViewUrl = url
                                    markdownWebViewTitle = fileName
                                    // 保持 showMarkdownWebView 为 true，重新加载
                                }
                            } else {
                                // 其他链接，交由系统浏览器处理
                                try {
                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    context.startActivity(browserIntent)
                                } catch (e: Exception) {
                                    Log.e("AlianChatScreen", "打开外链失败: $url", e)
                                    Toast.makeText(context, "无法打开该链接", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }

            // 图片预览屏幕
            AnimatedVisibility(
                visible = showImagePreview,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                ) {
                    ImagePreviewScreen(
                        imageUrl = imagePreviewUrl,
                        title = imagePreviewTitle,
                        onBackClick = {
                            showImagePreview = false
                        }
                    )
                }
            }

            // 视频预览对话框
            AnimatedVisibility(
                visible = showVideoPreview,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                ) {
                    VideoPreviewScreen(
                        videoUrl = videoPreviewUrl,
                        title = videoPreviewTitle,
                        onBackClick = {
                            showVideoPreview = false
                        }
                    )
                }
            }

            if (showAttachmentSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showAttachmentSheet = false },
                    containerColor = colors.backgroundCard
                ) {
                    AttachmentActionItem(
                        icon = Icons.Default.PhotoCamera,
                        title = "拍照上传",
                        onClick = {
                            showAttachmentSheet = false
                            val hasCameraPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasCameraPermission) {
                                launchTakePicture()
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    )
                    AttachmentActionItem(
                        icon = Icons.Default.Image,
                        title = "选择图片",
                        onClick = {
                            showAttachmentSheet = false
                            imagePickerLauncher.launch("image/*")
                        }
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                }
            }

        }
    }
}

@Composable
private fun PendingAttachmentStrip(
    attachments: List<PendingUploadAttachment>,
    onRemove: (String) -> Unit
) {
    if (attachments.isEmpty()) return

    val colors = BaoziTheme.colors

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentPadding = PaddingValues(end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(attachments, key = { it.id }) { attachment ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = colors.backgroundCardElevated
            ) {
                Box(
                    modifier = Modifier.size(68.dp)
                ) {
                    AsyncImage(
                        model = Uri.parse(attachment.uriString),
                        contentDescription = attachment.fileName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    IconButton(
                        onClick = { onRemove(attachment.id) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "删除附件",
                            tint = colors.textPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentActionItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    val colors = BaoziTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = colors.textPrimary
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            color = colors.textPrimary
        )
    }
}
