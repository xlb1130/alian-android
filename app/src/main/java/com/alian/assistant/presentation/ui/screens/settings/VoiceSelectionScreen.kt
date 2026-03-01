package com.alian.assistant.presentation.ui.screens.settings

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.alian.assistant.data.VoiceCategory
import com.alian.assistant.data.VoicePreset
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.ui.screens.components.AlianAppBar
import android.media.MediaPlayer
import android.os.Build
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.ContentScale
import java.io.IOException

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

@Composable
fun VoiceSelectionScreen(
    currentVoice: String,
    onBack: () -> Unit,
    onSelectVoice: (String, String) -> Unit,
    onPlayVoice: (String) -> Unit = {}
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf<VoiceCategory?>(null) }
    var isPageVisible by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentPlayingUrl by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isPageVisible = true
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }

    fun playAudio(url: String) {
        if (currentPlayingUrl == url) {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            currentPlayingUrl = null
            return
        }

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

    AnimatedVisibility(
        visible = isPageVisible,
        enter = fadeIn(
            animationSpec = tween(400, easing = EaseOut)
        ),
        exit = fadeOut(
            animationSpec = tween(300, easing = EaseIn)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            // 系统后退键处理
            BackHandler {
                performLightHaptic(context)
                onBack()
            }
            // 顶部导航栏 - 使用与 AlianLocalScreen 一致的 AlianAppBar
            AlianAppBar(
                title = "音色选择",
                onMenuClick = onBack,
                menuIcon = Icons.Default.KeyboardArrowLeft,
                showMoreMenu = false
            )

            // 分类标签
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 全部选项
                item {
                    CategoryChip(
                        text = "全部",
                        isSelected = selectedCategory == null,
                        onClick = {
                            performLightHaptic(context)
                            selectedCategory = null
                        }
                    )
                }
                // 各个分类
                items(VoiceCategory.values()) { category ->
                    CategoryChip(
                        text = category.displayName,
                        isSelected = selectedCategory == category,
                        onClick = {
                            performLightHaptic(context)
                            selectedCategory = category
                        }
                    )
                }
            }

            // 音色列表
            val voices = if (selectedCategory != null) {
                VoicePreset.getByCategory(selectedCategory!!)
            } else {
                VoicePreset.ALL
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(voices) { voice ->
                    VoiceItem(
                        voice = voice,
                        isSelected = voice.voiceParam == currentVoice,
                        isPlaying = currentPlayingUrl == voice.auditionUrl,
                        onClick = {
                            performLightHaptic(context)
                            onSelectVoice(voice.voiceParam, voice.auditionUrl)
                        },
                        onPlayClick = {
                            performLightHaptic(context)
                            if (voice.auditionUrl.isNotEmpty()) {
                                playAudio(voice.auditionUrl)
                            }
                        }
                    )
                }

                // 底部间距
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = BaoziTheme.colors

    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(36.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) colors.primary else colors.backgroundCard,
        border = if (!isSelected) {
            BorderStroke(
                width = 1.dp,
                color = colors.textHint.copy(alpha = 0.3f)
            )
        } else null
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color.White else colors.textPrimary
            )
        }
    }
}

@Composable
private fun VoiceItem(
    voice: VoicePreset,
    isSelected: Boolean,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val colors = BaoziTheme.colors

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            colors.primary.copy(alpha = 0.1f)
        } else {
            colors.backgroundCard
        },
        border = if (isSelected) {
            BorderStroke(
                width = 2.dp,
                color = colors.primary
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像
            if (voice.avatarUrl.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.primary.copy(alpha = 0.1f))
                ) {
                    AsyncImage(
                        model = voice.avatarUrl,
                        contentDescription = voice.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            // 音色信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = voice.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = voice.trait,
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${voice.age} · ${voice.language}",
                    fontSize = 11.sp,
                    color = colors.textHint
                )
            }

            // 试听按钮
            IconButton(
                onClick = onPlayClick,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPlaying) colors.primary
                        else colors.primary.copy(alpha = 0.1f)
                    )
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Check else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "停止" else "试听",
                    tint = if (isPlaying) Color.White else colors.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}