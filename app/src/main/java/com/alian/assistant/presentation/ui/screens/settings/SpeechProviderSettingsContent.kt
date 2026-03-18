package com.alian.assistant.presentation.ui.screens.settings

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.data.model.SpeechProvider
import com.alian.assistant.data.model.SpeechProviderConfig
import com.alian.assistant.data.model.SpeechProviderCredentials
import com.alian.assistant.data.model.SpeechModels
import com.alian.assistant.presentation.ui.theme.BaoziTheme

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
 * 语音服务商设置内容
 */
@Composable
fun SpeechProviderSettingsContent(
    currentProvider: SpeechProvider,
    credentials: Map<SpeechProvider, SpeechProviderCredentials>,
    models: Map<SpeechProvider, SpeechModels>,
    offlineAsrEnabled: Boolean,
    offlineAsrAutoFallbackToCloud: Boolean,
    offlineTtsEnabled: Boolean,
    offlineTtsUseAndroidNative: Boolean,
    offlineTtsAutoFallbackToCloud: Boolean,
    onSelectProvider: (SpeechProvider) -> Unit,
    onUpdateCredentials: (SpeechProvider, SpeechProviderCredentials) -> Unit,
    onUpdateModels: (SpeechProvider, SpeechModels) -> Unit,
    onUpdateOfflineAsrEnabled: (Boolean) -> Unit,
    onUpdateOfflineAsrAutoFallbackToCloud: (Boolean) -> Unit,
    onUpdateOfflineTtsEnabled: (Boolean) -> Unit,
    onUpdateOfflineTtsUseAndroidNative: (Boolean) -> Unit,
    onUpdateOfflineTtsAutoFallbackToCloud: (Boolean) -> Unit
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    var expandedProvider by remember { mutableStateOf<SpeechProvider?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题说明
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = "语音服务配置",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "配置 ASR（语音识别）和 TTS（语音合成）服务商。不同服务商使用独立的音色库。",
                    fontSize = 12.sp,
                    color = colors.textHint
                )
            }
        }

        item {
            OfflineAsrSettingsCard(
                offlineAsrEnabled = offlineAsrEnabled,
                offlineAsrAutoFallbackToCloud = offlineAsrAutoFallbackToCloud,
                onUpdateOfflineAsrEnabled = onUpdateOfflineAsrEnabled,
                onUpdateOfflineAsrAutoFallbackToCloud = onUpdateOfflineAsrAutoFallbackToCloud
            )
        }

        item {
            OfflineTtsSettingsCard(
                offlineTtsEnabled = offlineTtsEnabled,
                offlineTtsUseAndroidNative = offlineTtsUseAndroidNative,
                offlineTtsAutoFallbackToCloud = offlineTtsAutoFallbackToCloud,
                onUpdateOfflineTtsEnabled = onUpdateOfflineTtsEnabled,
                onUpdateOfflineTtsUseAndroidNative = onUpdateOfflineTtsUseAndroidNative,
                onUpdateOfflineTtsAutoFallbackToCloud = onUpdateOfflineTtsAutoFallbackToCloud
            )
        }

        // 服务商选择
        item {
            Text(
                text = "选择服务商",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        // 服务商列表
        items(SpeechProviderConfig.ALL) { config ->
            ProviderCard(
                config = config,
                isSelected = currentProvider == config.provider,
                isExpanded = expandedProvider == config.provider,
                credentials = credentials[config.provider] ?: SpeechProviderCredentials(),
                models = models[config.provider] ?: SpeechModels(),
                onSelect = {
                    performLightHaptic(context)
                    onSelectProvider(config.provider)
                },
                onToggleExpand = {
                    performLightHaptic(context)
                    expandedProvider = if (expandedProvider == config.provider) null else config.provider
                },
                onUpdateCredentials = { newCreds ->
                    onUpdateCredentials(config.provider, newCreds)
                },
                onUpdateModels = { newModels ->
                    onUpdateModels(config.provider, newModels)
                },
                offlineTtsEnabled = offlineTtsEnabled
            )
        }

        // 底部间距
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun OfflineAsrSettingsCard(
    offlineAsrEnabled: Boolean,
    offlineAsrAutoFallbackToCloud: Boolean,
    onUpdateOfflineAsrEnabled: (Boolean) -> Unit,
    onUpdateOfflineAsrAutoFallbackToCloud: (Boolean) -> Unit
) {
    val colors = BaoziTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                color = colors.textHint.copy(alpha = 0.2f),
                shape = RoundedCornerShape(16.dp)
            )
            .background(colors.backgroundCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "离线 ASR",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "本地离线语音识别（Sherpa）",
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (offlineAsrEnabled) {
                        "按住说话优先使用本地识别，不依赖网络"
                    } else {
                        "关闭后使用当前服务商的在线 ASR"
                    },
                    fontSize = 11.sp,
                    color = colors.textHint
                )
            }
            Switch(
                checked = offlineAsrEnabled,
                onCheckedChange = onUpdateOfflineAsrEnabled
            )
        }
        if (offlineAsrEnabled) {
            HorizontalDivider(color = colors.textHint.copy(alpha = 0.2f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "离线失败自动回退在线识别",
                        fontSize = 13.sp,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "关闭后若离线初始化失败，将直接报错",
                        fontSize = 11.sp,
                        color = colors.textHint
                    )
                }
                Switch(
                    checked = offlineAsrAutoFallbackToCloud,
                    onCheckedChange = onUpdateOfflineAsrAutoFallbackToCloud
                )
            }
        }
    }
}

@Composable
private fun OfflineTtsSettingsCard(
    offlineTtsEnabled: Boolean,
    offlineTtsUseAndroidNative: Boolean,
    offlineTtsAutoFallbackToCloud: Boolean,
    onUpdateOfflineTtsEnabled: (Boolean) -> Unit,
    onUpdateOfflineTtsUseAndroidNative: (Boolean) -> Unit,
    onUpdateOfflineTtsAutoFallbackToCloud: (Boolean) -> Unit
) {
    val colors = BaoziTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                color = colors.textHint.copy(alpha = 0.2f),
                shape = RoundedCornerShape(16.dp)
            )
            .background(colors.backgroundCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "离线 TTS",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "本地离线语音合成",
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (offlineTtsEnabled) {
                        "启用后优先使用离线合成（可选 sherpa 或安卓原生）"
                    } else {
                        "关闭后使用当前服务商在线 TTS"
                    },
                    fontSize = 11.sp,
                    color = colors.textHint
                )
            }
            Switch(
                checked = offlineTtsEnabled,
                onCheckedChange = onUpdateOfflineTtsEnabled
            )
        }

        if (offlineTtsEnabled) {
            HorizontalDivider(color = colors.textHint.copy(alpha = 0.2f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "使用安卓原生离线 TTS",
                        fontSize = 13.sp,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (offlineTtsUseAndroidNative) {
                            "已启用 Android TextToSpeech 离线合成（无需 sherpa 模型）"
                        } else {
                            "已关闭，继续使用 sherpa-onnx 离线模型"
                        },
                        fontSize = 11.sp,
                        color = colors.textHint
                    )
                }
                Switch(
                    checked = offlineTtsUseAndroidNative,
                    onCheckedChange = onUpdateOfflineTtsUseAndroidNative
                )
            }

            HorizontalDivider(color = colors.textHint.copy(alpha = 0.2f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "离线失败自动回退在线合成",
                        fontSize = 13.sp,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "关闭后若离线模型不可用，将直接报错",
                        fontSize = 11.sp,
                        color = colors.textHint
                    )
                }
                Switch(
                    checked = offlineTtsAutoFallbackToCloud,
                    onCheckedChange = onUpdateOfflineTtsAutoFallbackToCloud
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    config: SpeechProviderConfig,
    isSelected: Boolean,
    isExpanded: Boolean,
    credentials: SpeechProviderCredentials,
    models: SpeechModels,
    onSelect: () -> Unit,
    onToggleExpand: () -> Unit,
    onUpdateCredentials: (SpeechProviderCredentials) -> Unit,
    onUpdateModels: (SpeechModels) -> Unit,
    offlineTtsEnabled: Boolean
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (isPressed) 0.98f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) colors.primary else colors.textHint.copy(alpha = 0.2f),
                shape = RoundedCornerShape(16.dp)
            )
            .background(colors.backgroundCard)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    performLightHaptic(context)
                    if (!isSelected) {
                        onSelect()
                    } else {
                        onToggleExpand()
                    }
                }
            )
    ) {
        // 头部：服务商名称和状态
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) colors.primary
                        else colors.textHint.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (config.provider == SpeechProvider.VOLCANO) {
                        Icons.Default.Cloud
                    } else {
                        Icons.Default.AudioFile
                    },
                    contentDescription = null,
                    tint = if (isSelected) Color.White else colors.textHint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 名称和状态
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = config.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colors.primary.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = "当前",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "ASR: ${config.asrDefaultModel} | TTS: ${config.ttsDefaultModel}",
                    fontSize = 11.sp,
                    color = colors.textHint
                )
            }

            // 展开/收起图标
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "收起" else "展开",
                tint = colors.textHint,
                modifier = Modifier.size(20.dp)
            )
        }

        // 展开内容
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(
                animationSpec = tween(300, easing = EaseOut)
            ) + fadeIn(),
            exit = shrinkVertically(
                animationSpec = tween(200, easing = EaseIn)
            ) + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                HorizontalDivider(
                    color = colors.textHint.copy(alpha = 0.1f),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // API Key
                var apiKeyVisible by remember { mutableStateOf(false) }
                CredentialField(
                    label = "API Key",
                    value = credentials.apiKey,
                    isPassword = true,
                    passwordVisible = apiKeyVisible,
                    onTogglePasswordVisibility = { apiKeyVisible = !apiKeyVisible },
                    onValueChange = { onUpdateCredentials(credentials.copy(apiKey = it)) },
                    placeholder = if (config.provider == SpeechProvider.VOLCANO) {
                        "输入 Access Token"
                    } else {
                        "输入 DashScope API Key"
                    }
                )

                // 火山引擎特有字段
                if (config.requiresAppId) {
                    Spacer(modifier = Modifier.height(12.dp))
                    CredentialField(
                        label = "App ID",
                        value = credentials.appId,
                        isPassword = false,
                        onValueChange = { onUpdateCredentials(credentials.copy(appId = it)) },
                        placeholder = "输入火山引擎 App ID"
                    )
                }

                if (config.requiresAsrResourceId) {
                    Spacer(modifier = Modifier.height(12.dp))
                    CredentialField(
                        label = "ASR Resource ID",
                        value = credentials.asrResourceId,
                        isPassword = false,
                        onValueChange = { onUpdateCredentials(credentials.copy(asrResourceId = it)) },
                        placeholder = "如: volc.bigasr.sauc.duration"
                    )
                }

                if (config.requiresTtsResourceId) {
                    Spacer(modifier = Modifier.height(12.dp))
                    CredentialField(
                        label = "TTS Resource ID",
                        value = credentials.ttsResourceId,
                        isPassword = false,
                        onValueChange = { onUpdateCredentials(credentials.copy(ttsResourceId = it)) },
                        placeholder = "如: seed-tts-2.0"
                    )
                }

                // ASR 模型选择
                Spacer(modifier = Modifier.height(16.dp))
                ModelSelectField(
                    label = "ASR 模型",
                    selectedModel = models.asrModel.ifEmpty { config.asrDefaultModel },
                    models = config.asrModels,
                    onModelSelected = { onUpdateModels(models.copy(asrModel = it)) }
                )

                // TTS 模型选择
                if (!offlineTtsEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ModelSelectField(
                        label = "TTS 模型",
                        selectedModel = models.ttsModel.ifEmpty { config.ttsDefaultModel },
                        models = config.ttsModels,
                        onModelSelected = { onUpdateModels(models.copy(ttsModel = it)) }
                    )
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "离线 TTS 已启用，当前服务商的 TTS 模型配置已隐藏",
                        fontSize = 11.sp,
                        color = colors.textHint
                    )
                }
            }
        }
    }
}

@Composable
private fun CredentialField(
    label: String,
    value: String,
    isPassword: Boolean,
    passwordVisible: Boolean = false,
    onTogglePasswordVisibility: () -> Unit = {},
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    val colors = BaoziTheme.colors

    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    fontSize = 14.sp,
                    color = colors.textHint
                )
            },
            visualTransformation = if (isPassword && !passwordVisible) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = onTogglePasswordVisibility) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "隐藏" else "显示",
                            tint = colors.textHint
                        )
                    }
                }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = colors.textHint.copy(alpha = 0.3f),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = colors.primary,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectField(
    label: String,
    selectedModel: String,
    models: List<String>,
    onModelSelected: (String) -> Unit
) {
    val colors = BaoziTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedModel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.textHint.copy(alpha = 0.3f),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = colors.primary,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary
                )
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(colors.backgroundCard)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = model,
                                fontSize = 14.sp,
                                color = if (model == selectedModel) colors.primary else colors.textPrimary
                            )
                        },
                        onClick = {
                            onModelSelected(model)
                            expanded = false
                        },
                        modifier = Modifier.background(
                            if (model == selectedModel) colors.primary.copy(alpha = 0.1f)
                            else Color.Transparent
                        )
                    )
                }
            }
        }
    }
}
