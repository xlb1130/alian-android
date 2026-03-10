package com.alian.assistant.presentation.ui.screens.settings

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.alian.assistant.R
import com.alian.assistant.data.AppSettings
import com.alian.assistant.data.VoicePreset
import com.alian.assistant.infrastructure.ai.provider.VoicePresetManager
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.common.utils.AvatarCacheManager
import java.io.IOException

/**
 * 艾莲设置二级页面内容
 */
@Composable
fun AlianSettingsContent(
    settings: AppSettings,
    onUpdateVoiceCallSystemPrompt: (String) -> Unit,
    onUpdateVideoCallSystemPrompt: (String) -> Unit,
    onUpdateTTSVoice: (String) -> Unit,
    onUpdateTTSSpeed: (Float) -> Unit,
    onUpdateVolume: (Int) -> Unit,
    onUpdateAssistantAvatar: (String) -> Unit,
    onNavigateToVoiceSelection: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    // 头像状态（空字符串视为未设置）
    var avatarUri by remember { mutableStateOf(settings.assistantAvatar.takeIf { it.isNotEmpty() }) }
    var isSavingAvatar by remember { mutableStateOf(false) }
    val avatarCacheManager = remember { AvatarCacheManager(context) }
    val coroutineScope = rememberCoroutineScope()
    
    // 获取当前语音音色的头像URL（用户没配置头像时使用）
    val voicePresetManager = remember { VoicePresetManager.getInstance() }
    val currentVoice = remember(settings.speechProvider, settings.ttsVoice) {
        voicePresetManager.findByVoiceParam(settings.speechProvider, settings.ttsVoice)
            ?: VoicePreset.findByVoiceParam(settings.ttsVoice)
    }
    val voiceAvatarUrl = currentVoice?.avatarUrl

    // 音频播放状态
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentPlayingUrl by remember { mutableStateOf<String?>(null) }

    // 提示词对话框状态
    var showVoiceCallPromptDialog by remember { mutableStateOf(false) }
    var showVideoCallPromptDialog by remember { mutableStateOf(false) }

    // 清理MediaPlayer资源
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }

    // 播放/停止音频
    fun playAudio(url: String) {
        if (currentPlayingUrl == url) {
            // 如果正在播放，则停止
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            currentPlayingUrl = null
            return
        }

        // 播放新音频
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(url)
                prepareAsync()
                setOnPreparedListener { start() }
                setOnCompletionListener {
                    currentPlayingUrl = null
                }
                currentPlayingUrl = url
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    // 图片选择器
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isSavingAvatar = true
            coroutineScope.launch {
                val cachedPath = avatarCacheManager.saveAssistantAvatar(uri)
                if (cachedPath != null) {
                    avatarUri = cachedPath
                    onUpdateAssistantAvatar(cachedPath)
                }
                isSavingAvatar = false
            }
        }
    }

    Column {
        // 头像区域
        Box(
            modifier = Modifier
                .staggeredFadeIn(0)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 头像
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.1f))
                        .then(
                            if (!isSavingAvatar) {
                                Modifier.clickable { imagePickerLauncher.launch("image/*") }
                            } else {
                                Modifier
                            }
                        )
                        .border(
                            width = 2.dp,
                            color = colors.primary,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSavingAvatar) {
                        // 加载状态
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = colors.primary,
                            strokeWidth = 3.dp
                        )
                    } else if (avatarUri != null) {
                        // 用户自定义头像
                        AsyncImage(
                            model = avatarUri,
                            contentDescription = stringResource(R.string.alian_avatar_section),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else if (!voiceAvatarUrl.isNullOrEmpty()) {
                        // 使用语音音色的默认头像
                        AsyncImage(
                            model = voiceAvatarUrl,
                            contentDescription = stringResource(R.string.alian_avatar_section),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = stringResource(R.string.alian_avatar_upload),
                            tint = colors.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 上传按钮
                Button(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    enabled = !isSavingAvatar,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(40.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary
                    )
                ) {
                    if (isSavingAvatar) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = stringResource(R.string.alian_avatar_upload),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSavingAvatar) stringResource(R.string.alian_avatar_saving) else stringResource(R.string.alian_avatar_upload),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (avatarUri != null) stringResource(R.string.alian_avatar_change_hint) else stringResource(R.string.alian_avatar_default_hint),
                    fontSize = 12.sp,
                    color = colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 语音设置区域
        Box(modifier = Modifier.staggeredFadeIn(1)) {
            SettingsSection(title = stringResource(R.string.alian_voice_settings), modifier = Modifier.padding(horizontal = 16.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 音色选择（离线 TTS 模式下隐藏）
        if (!settings.offlineTtsEnabled) {
            Box(modifier = Modifier.staggeredFadeIn(2)) {
                val hasAvatar = !voiceAvatarUrl.isNullOrEmpty()
                val isPlaying = currentPlayingUrl == currentVoice?.auditionUrl

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(
                            color = colors.backgroundCard,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onNavigateToVoiceSelection() }
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左侧图标或头像
                        if (hasAvatar && currentVoice != null) {
                            AsyncImage(
                                model = currentVoice.avatarUrl,
                                contentDescription = currentVoice.name,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            IconContainer(
                                icon = Icons.Default.MusicNote,
                                tint = colors.primary
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // 中间标题和副标题
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.alian_voice_select),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = currentVoice?.name ?: settings.ttsVoice,
                                fontSize = 14.sp,
                                color = colors.textSecondary
                            )
                        }

                        // 右侧试听按钮
                        if (!currentVoice?.auditionUrl.isNullOrEmpty()) {
                            val playInteractionSource = remember { MutableInteractionSource() }
                            val playIsPressed by playInteractionSource.collectIsPressedAsState()

                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isPlaying) colors.primary
                                        else if (playIsPressed) colors.primary.copy(alpha = 0.2f)
                                        else colors.primary.copy(alpha = 0.1f)
                                    )
                                    .scale(if (playIsPressed) 0.95f else 1f)
                                    .clickable(
                                        interactionSource = playInteractionSource,
                                        indication = null,
                                        onClick = {
                                            currentVoice?.auditionUrl?.let { playAudio(it) }
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isPlaying) {
                                    // 播放中显示停止图标
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.alian_stop),
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    // 未播放显示音量图标
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = stringResource(R.string.alian_audition),
                                        tint = colors.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.staggeredFadeIn(2)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(
                            color = colors.backgroundCard,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "离线 TTS 已启用",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "当前模式下音色配置已隐藏，使用本地模型默认音色。",
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }

        // 语速设置
        Box(modifier = Modifier.staggeredFadeIn(3)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.alian_speed),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "${String.format("%.1f", settings.ttsSpeed)}x",
                        fontSize = 14.sp,
                        color = colors.textSecondary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = settings.ttsSpeed,
                    onValueChange = { onUpdateTTSSpeed(it) },
                    valueRange = 0.5f..2.0f,
                    steps = 14,
                    colors = SliderDefaults.colors(
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.primary.copy(alpha = 0.3f),
                        thumbColor = colors.primary
                    )
                )
                Text(
                    text = when {
                        settings.ttsSpeed < 0.8f -> stringResource(R.string.alian_speed_slow)
                        settings.ttsSpeed < 1.2f -> stringResource(R.string.alian_speed_normal)
                        settings.ttsSpeed < 1.6f -> stringResource(R.string.alian_speed_fast)
                        else -> stringResource(R.string.alian_speed_very_fast)
                    },
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // 音量设置
        Box(modifier = Modifier.staggeredFadeIn(4)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.alian_volume),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "${settings.volume}%",
                        fontSize = 14.sp,
                        color = colors.textSecondary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = settings.volume.toFloat(),
                    onValueChange = { onUpdateVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    steps = 99,
                    colors = SliderDefaults.colors(
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.primary.copy(alpha = 0.3f),
                        thumbColor = colors.primary
                    )
                )
                Text(
                    text = when {
                        settings.volume == 0 -> stringResource(R.string.alian_volume_mute)
                        settings.volume < 30 -> stringResource(R.string.alian_volume_low)
                        settings.volume < 70 -> stringResource(R.string.alian_volume_normal)
                        else -> stringResource(R.string.alian_volume_high)
                    },
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 提示词设置区域
        Box(modifier = Modifier.staggeredFadeIn(5)) {
            SettingsSection(title = stringResource(R.string.alian_prompt_settings), modifier = Modifier.padding(horizontal = 16.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 语音通话系统提示词
        Box(modifier = Modifier.staggeredFadeIn(6)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(
                        color = colors.backgroundCard,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { showVoiceCallPromptDialog = true }
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧图标
                    IconContainer(
                        icon = if (settings.voiceCallSystemPrompt.isNotEmpty()) {
                            Icons.Default.MusicNote
                        } else {
                            Icons.Default.MusicNote
                        },
                        tint = colors.primary
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // 中间标题和副标题
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.alian_voice_call_prompt),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (settings.voiceCallSystemPrompt.length > 30) {
                                settings.voiceCallSystemPrompt.take(30) + "..."
                            } else if (settings.voiceCallSystemPrompt.isNotEmpty()) {
                                settings.voiceCallSystemPrompt
                            } else {
                                stringResource(R.string.alian_not_set)
                            },
                            fontSize = 14.sp,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 视频通话系统提示词
        Box(modifier = Modifier.staggeredFadeIn(7)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(
                        color = colors.backgroundCard,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { showVideoCallPromptDialog = true }
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧图标
                    IconContainer(
                        icon = if (settings.videoCallSystemPrompt.isNotEmpty()) {
                            Icons.Default.MusicNote
                        } else {
                            Icons.Default.MusicNote
                        },
                        tint = colors.primary
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // 中间标题和副标题
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.alian_video_call_prompt),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (settings.videoCallSystemPrompt.length > 30) {
                                settings.videoCallSystemPrompt.take(30) + "..."
                            } else if (settings.videoCallSystemPrompt.isNotEmpty()) {
                                settings.videoCallSystemPrompt
                            } else {
                                stringResource(R.string.alian_not_set)
                            },
                            fontSize = 14.sp,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        PageBottomSpacing()
    }

    // 语音通话系统提示词编辑对话框
    if (showVoiceCallPromptDialog) {
        var promptText by remember { mutableStateOf(settings.voiceCallSystemPrompt) }

        AlertDialog(
            onDismissRequest = { showVoiceCallPromptDialog = false },
            containerColor = colors.backgroundCard,
            title = {
                Text(stringResource(R.string.alian_voice_call_prompt), color = colors.textPrimary)
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.alian_voice_call_prompt_desc),
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.alian_voice_call_prompt_hint),
                                fontSize = 14.sp,
                                color = colors.textSecondary
                            )
                        },
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = colors.textPrimary
                        ),
                        maxLines = 8,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.primary.copy(alpha = 0.3f),
                            cursorColor = colors.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateVoiceCallSystemPrompt(promptText)
                        showVoiceCallPromptDialog = false
                    }
                ) {
                    Text(stringResource(R.string.alian_save), color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showVoiceCallPromptDialog = false }) {
                    Text(stringResource(R.string.btn_cancel), color = colors.textSecondary)
                }
            }
        )
    }

    // 视频通话系统提示词编辑对话框
    if (showVideoCallPromptDialog) {
        var promptText by remember { mutableStateOf(settings.videoCallSystemPrompt) }

        AlertDialog(
            onDismissRequest = { showVideoCallPromptDialog = false },
            containerColor = colors.backgroundCard,
            title = {
                Text(stringResource(R.string.alian_video_call_prompt), color = colors.textPrimary)
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.alian_video_call_prompt_desc),
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.alian_video_call_prompt_hint),
                                fontSize = 14.sp,
                                color = colors.textSecondary
                            )
                        },
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = colors.textPrimary
                        ),
                        maxLines = 8,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.primary.copy(alpha = 0.3f),
                            cursorColor = colors.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateVideoCallSystemPrompt(promptText)
                        showVideoCallPromptDialog = false
                    }
                ) {
                    Text(stringResource(R.string.alian_save), color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showVideoCallPromptDialog = false }) {
                    Text(stringResource(R.string.btn_cancel), color = colors.textSecondary)
                }
            }
        )
    }
}

/**
 * 设置分组标题
 */
@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = BaoziTheme.colors.textSecondary,
        modifier = modifier
    )
}
