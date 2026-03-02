package com.alian.assistant.presentation.ui.screens.online

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.alian.assistant.presentation.ui.theme.BaoziColors
import java.io.File
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.ui.theme.Primary
import com.alian.assistant.presentation.ui.theme.Secondary
import com.alian.assistant.common.utils.formatMessageTime
import com.alian.assistant.common.utils.performLightHaptic
import com.alian.assistant.presentation.viewmodel.ChatMessage
import com.alian.assistant.presentation.viewmodel.DeepThinkingSection
import kotlinx.coroutines.CancellationException

/**
 * 空聊天状态组件 - 简洁现代美观大气的设计
 */
@Composable
fun EmptyChatState(
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // 背景装饰光晕
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp),
            contentAlignment = Alignment.Center
        ) {
            // 外层光晕
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                colors.primary.copy(alpha = 0.08f),
                                colors.secondary.copy(alpha = 0.05f),
                                Color.Transparent
                            )
                        )
                    )
            )
            // 内层光晕
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                colors.primary.copy(alpha = 0.12f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 品牌Logo区域 - 呼吸动画
            val infiniteTransition = rememberInfiniteTransition(label = "alian")
            val logoScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2500, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "logoScale"
            )
            val glowAlpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 0.7f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2500, easing = EaseInOutCubic),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "glowAlpha"
            )

            // Logo光晕效果
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(logoScale),
                contentAlignment = Alignment.Center
            ) {
                // 外层光晕
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    colors.primary.copy(alpha = glowAlpha * 0.3f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                // 中层光晕
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    colors.primary.copy(alpha = glowAlpha * 0.5f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                // Logo主体
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .shadow(
                            elevation = 24.dp,
                            shape = RoundedCornerShape(28.dp),
                            spotColor = colors.primary.copy(alpha = 0.6f),
                            ambientColor = colors.primary.copy(alpha = 0.3f)
                        )
                        .clip(RoundedCornerShape(28.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    colors.primary,
                                    colors.primaryDark
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "A",
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // 品牌名称
            Text(
                text = "Alian",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                letterSpacing = 1.5.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 副标题/引导语
            Text(
                text = "随时准备，为您服务",
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = colors.textSecondary,
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 提示文字
            Text(
                text = "输入消息或使用语音开始对话",
                fontSize = 13.sp,
                color = colors.textHint,
                letterSpacing = 0.3.sp
            )
        }
    }
}

/**
 * 聊天消息气泡组件（美化版）
 */
@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier.fillMaxWidth(),
    ttsEnabled: Boolean = false,
    isPlaying: Boolean = false,
    onPlayClick: ((String) -> Unit)? = null,
    onStopClick: (() -> Unit)? = null,
    onLinkClick: ((String, String) -> Unit)? = null,
    backendBaseUrl: String = "",
    userAvatar: String? = null,
    assistantAvatar: String? = null
) {
    val colors = BaoziTheme.colors
    Log.d("ChatMessageBubble", "渲染消息: isUser=${message.isUser}, content=${message.content.take(50)}...")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!message.isUser) {
            // AI 头像（带渐变和阴影）
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .shadow(
                        elevation = 4.dp,
                        shape = CircleShape,
                        spotColor = colors.primary.copy(alpha = 0.3f)
                    )
            ) {
                if (assistantAvatar != null && File(assistantAvatar).exists()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(File(assistantAvatar))
                            .crossfade(true)
                            .build(),
                        contentDescription = "Alian头像",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        colors.primary.copy(alpha = 0.2f),
                                        colors.primary.copy(alpha = 0.1f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "A",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Box {
                Card(
                    shape = RoundedCornerShape(
                        topStart = if (message.isUser) 16.dp else 4.dp,
                        topEnd = if (message.isUser) 4.dp else 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 16.dp
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .shadow(
                            elevation = 6.dp,
                            shape = RoundedCornerShape(
                                topStart = if (message.isUser) 16.dp else 4.dp,
                                topEnd = if (message.isUser) 4.dp else 16.dp,
                                bottomStart = 16.dp,
                                bottomEnd = 16.dp
                            ),
                            spotColor = if (message.isUser) {
                                colors.primary.copy(alpha = 0.4f)
                            } else {
                                Color.Black.copy(alpha = 0.2f)
                            },
                            ambientColor = if (message.isUser) {
                                colors.primary.copy(alpha = 0.2f)
                            } else {
                                Color.Black.copy(alpha = 0.1f)
                            }
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                brush = if (message.isUser) {
                                    Brush.linearGradient(
                                        colors = listOf(
                                            colors.primary,
                                            colors.primaryDark
                                        )
                                    )
                                } else {
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            colors.backgroundCard,
                                            colors.backgroundCardElevated
                                        )
                                    )
                                }
                            )
                            .drawWithContent {
                                drawContent()
                                // 顶部光泽效果
                                drawRoundRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = if (message.isUser) 0.15f else if (colors.isDark) 0.05f else 0.3f),
                                            Color.Transparent
                                        ),
                                        startY = 0f,
                                        endY = size.height * if (message.isUser) 0.4f else 0.3f
                                    ),
                                    style = Fill,
                                    cornerRadius = CornerRadius(16.dp.toPx())
                                )
                            }
                    ) {
                        if (message.isUser) {
                            Text(
                                text = message.content,
                                fontSize = 15.sp,
                                color = Color.White,
                                modifier = Modifier.padding(12.dp)
                            )
                        } else {
                            Column {
                                Markdown(
                                    content = message.content,
                                    modifier = Modifier.padding(
                                        start = 12.dp,
                                        top = 12.dp,
                                        end = 12.dp,
                                        bottom = if (ttsEnabled) 36.dp else 12.dp
                                    ),
                                    colors = markdownColor(
                                        text = colors.textPrimary,
                                        codeText = colors.textPrimary,
                                        inlineCodeText = colors.textPrimary,
                                        inlineCodeBackground = colors.primary.copy(alpha = 0.15f),
                                        codeBackground = colors.primary.copy(alpha = 0.15f),
                                        linkText = colors.primary,
                                        dividerColor = colors.textSecondary.copy(alpha = 0.3f)
                                    ),
                                    typography = markdownTypography(
                                        h1 = TextStyle(
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        h2 = TextStyle(
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        h3 = TextStyle(
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        h4 = TextStyle(
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        h5 = TextStyle(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        h6 = TextStyle(
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        paragraph = TextStyle(
                                            fontSize = 15.sp
                                        ),
                                        code = TextStyle(
                                            fontSize = 14.sp
                                        ),
                                        quote = TextStyle(
                                            fontSize = 15.sp
                                        ),
                                        list = TextStyle(
                                            fontSize = 15.sp
                                        )
                                    )
                                )

                                // 附件预览按钮区域
                                if (message.attachments.isNotEmpty() && onLinkClick != null) {
                                    Column(
                                        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(0.dp),
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        message.attachments.forEach { attachment ->
                                            val fileName = attachment.filename ?: attachment.name ?: "未知文件"
                                            val originalUrl = attachment.file_url ?: attachment.url ?: attachment.path ?: "#"
                                            // 如果 URL 是相对路径（以 / 开头），则拼接 backendBaseUrl
                                            val url = if (originalUrl.startsWith("/api/v1")) {
                                                backendBaseUrl + "/" + originalUrl.substring(8) // 去掉"/api/v1"，并确保有斜杠
                                            } else if (originalUrl.startsWith("/") && backendBaseUrl.isNotEmpty()) {
                                                backendBaseUrl + originalUrl
                                            } else {
                                                originalUrl
                                            }
                                            Log.d("ChatMessageBubble", "附件按钮: fileName=$fileName, originalUrl=$originalUrl, url=$url")
                                            
                                            // 检查是否为图片附件
                                            val isImage = fileName.lowercase().let { name ->
                                                listOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp").any { name.endsWith(it) }
                                            }
                                            
                                            // 检查是否为视频附件
                                            val isVideo = fileName.lowercase().let { name ->
                                                listOf(".mp4", ".avi", ".mov", ".wmv", ".flv", ".mkv", ".webm", ".m4v", ".3gp").any { name.endsWith(it) }
                                            }
                                            
                                            // 根据文件类型选择图标
                                            val iconVector = when {
                                                isImage -> Icons.Default.Image
                                                isVideo -> Icons.Default.Videocam
                                                else -> Icons.Default.Visibility
                                            }
                                            
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        Log.d("ChatMessageBubble", "附件按钮被点击: url=$url, fileName=$fileName")
                                                        onLinkClick(url, fileName)
                                                    },
                                                horizontalArrangement = Arrangement.Start,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = iconVector,
                                                    contentDescription = "预览",
                                                    modifier = Modifier.size(14.dp),
                                                    tint = colors.primary
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = fileName,
                                                    fontSize = 13.sp,
                                                    color = colors.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 播放按钮（仅对AI消息且TTS启用时显示，悬浮在气泡右下角）
                if (!message.isUser && ttsEnabled) {
                    val buttonContext = LocalContext.current
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = (-4).dp, y = (-4).dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        colors.backgroundCardElevated,
                                        colors.backgroundCard
                                    )
                                )
                            )
                            .shadow(
                                elevation = 4.dp,
                                shape = CircleShape,
                                spotColor = colors.primary.copy(alpha = 0.3f)
                            )
                            .clickable {
                                Log.d("ChatMessageBubble", "播放按钮被点击: isPlaying=$isPlaying, messageId=${message.id}")
                                performLightHaptic(buttonContext)
                                if (isPlaying) {
                                    onStopClick?.invoke()
                                } else {
                                    onPlayClick?.invoke(message.id)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Log.d("ChatMessageBubble", "渲染播放按钮: isPlaying=$isPlaying, messageId=${message.id}")
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "停止播放" else "播放",
                            tint = if (isPlaying) colors.error else colors.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 时间显示
            Text(
                text = formatMessageTime(message.timestamp),
                fontSize = 11.sp,
                color = colors.textSecondary.copy(alpha = 0.7f),
                modifier = Modifier.padding(
                    start = if (message.isUser) 0.dp else 4.dp,
                    top = 4.dp,
                    end = if (message.isUser) 4.dp else 0.dp
                )
            )
        }

        if (message.isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            // 用户头像（带渐变和阴影）
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .shadow(
                        elevation = 4.dp,
                        shape = CircleShape,
                        spotColor = colors.secondary.copy(alpha = 0.3f)
                    )
            ) {
                if (userAvatar != null && File(userAvatar).exists()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(File(userAvatar))
                            .crossfade(true)
                            .build(),
                        contentDescription = "用户头像",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        colors.secondary.copy(alpha = 0.2f),
                                        colors.secondary.copy(alpha = 0.1f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "👤",
                            fontSize = 20.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 加载气泡组件（美化版）
 */
@Composable
fun LoadingBubble() {
    val colors = BaoziTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        // AI 头像（带渐变和阴影）
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            colors.primary.copy(alpha = 0.2f),
                            colors.primary.copy(alpha = 0.1f)
                        )
                    )
                )
                .shadow(
                    elevation = 4.dp,
                    shape = CircleShape,
                    spotColor = colors.primary.copy(alpha = 0.3f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "A",
                fontSize = 20.sp,
                color = colors.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(8.dp))

        Card(
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent
            ),
            modifier = Modifier
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 16.dp
                    ),
                    spotColor = Color.Black.copy(alpha = 0.2f),
                    ambientColor = Color.Black.copy(alpha = 0.1f)
                )
        ) {
            Box(
                modifier = Modifier
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                colors.backgroundCard,
                                colors.backgroundCardElevated
                            )
                        )
                    )
                    .drawWithContent {
                        drawContent()
                        // 顶部光泽效果
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (colors.isDark) 0.05f else 0.3f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = size.height * 0.3f
                            ),
                            style = Fill,
                            cornerRadius = CornerRadius(16.dp.toPx())
                        )
                    }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "dots")

                    val dot1Scale by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1.7f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot1"
                    )
                    val dot1Alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot1Alpha"
                    )

                    val dot2Scale by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1.7f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            initialStartOffset = StartOffset(266),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot2"
                    )
                    val dot2Alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            initialStartOffset = StartOffset(266),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot2Alpha"
                    )

                    val dot3Scale by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1.7f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            initialStartOffset = StartOffset(533),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot3"
                    )
                    val dot3Alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            initialStartOffset = StartOffset(533),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot3Alpha"
                    )

                    listOf(
                        Triple(dot1Scale, dot1Alpha, "dot1"),
                        Triple(dot2Scale, dot2Alpha, "dot2"),
                        Triple(dot3Scale, dot3Alpha, "dot3")
                    ).forEach { (scale, alpha, label) ->
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            colors.primary.copy(alpha = alpha),
                                            colors.primary.copy(alpha = alpha * 0.6f)
                                        )
                                    )
                                )
                                .scale(scale)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                }
            }
        }
    }
}

/**
 * 深度思考章节组件
 */
@Composable
fun DeepThinkingSection(
    section: DeepThinkingSection,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.backgroundCard
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 章节标题
            if (section.title.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { section.isExpanded.value = !section.isExpanded.value },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = section.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Text(
                        text = if (section.isExpanded.value) "▼" else "▶",
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 章节内容 - 使用 Markdown 渲染（只渲染主段落，索引 0）
            if (section.isExpanded.value && section.paragraphs.isNotEmpty()) {
                val mainParagraph = section.paragraphs[0]
                if (mainParagraph.isNotBlank()) {
                    Markdown(
                        content = mainParagraph,
                        modifier = Modifier.padding(0.dp),
                        colors = markdownColor(
                            text = colors.textPrimary,
                            codeText = colors.textPrimary,
                            inlineCodeText = colors.textPrimary,
                            inlineCodeBackground = colors.primary.copy(alpha = 0.15f),
                            codeBackground = colors.primary.copy(alpha = 0.15f),
                            linkText = colors.primary,
                            dividerColor = colors.textSecondary.copy(alpha = 0.3f)
                        ),
                        typography = markdownTypography(
                            h1 = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            ),
                            h2 = TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            ),
                            h3 = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            ),
                            h4 = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            ),
                            h5 = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            ),
                            h6 = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            ),
                            paragraph = TextStyle(
                                fontSize = 14.sp,
                                color = colors.textPrimary,
                                lineHeight = 20.sp
                            ),
                            code = TextStyle(
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary
                            ),
                            quote = TextStyle(
                                fontSize = 14.sp,
                                color = colors.textSecondary,
                                fontStyle = FontStyle.Italic
                            ),
                            list = TextStyle(
                                fontSize = 14.sp,
                                color = colors.textPrimary
                            )
                        )
                    )
                }
            }
        }
    }
}

/**
 * 语音按钮波纹效果层（在屏幕最上层显示）
 * 在屏幕最上层显示波纹效果，并显示识别的文本
 */
@Composable
fun VoiceRippleOverlay(
    position: Offset?,
    colors: BaoziColors,
    recordedText: String = ""
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    
    Log.d("VoiceRipple", "VoiceRippleOverlay 被调用，位置: $position")
    
    if (position == null) return
    
    // 屏幕宽度
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenCenterX = screenWidthPx / 2
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
    
        // 多层扩散波纹 - 3层统一蓝色系，最内层最小最亮，最外层最大最淡
        repeat(3) { index ->
            val delay = index * 250L
            val currentScale = remember(delay) {
                Animatable(1.2f)
            }
            val currentAlpha = remember(delay) {
                Animatable(1.5f)
            }

            // 每层波纹有不同的最大缩放和动画时长
            val maxScale = when (index) {
                0 -> 1.8f
                1 -> 2.0f
                else -> 2.2f
            }
            
            val animationDuration = when (index) {
                0 -> 2500
                1 -> 2800
                else -> 3100
            }
            
            // 统一使用蓝色
            val rippleColor = Color(0xFF3B82F6)

            LaunchedEffect(position.x, position.y, index) {
                if (position == null) return@LaunchedEffect

                Log.d("VoiceRipple", "开始波纹动画，index: $index, 初始scale: ${currentScale.value}, 初始alpha: ${currentAlpha.value}")

                // 根据层级设置不同的目标透明度
                val first扩散TargetAlpha = when (index) {
                    0 -> 1.3f
                    1 -> 1.15f
                    else -> 1.0f
                }
                
                val pulseAlphaHigh = when (index) {
                    0 -> 1.4f
                    1 -> 1.25f
                    else -> 1.1f
                }
                
                val pulseAlphaLow = when (index) {
                    0 -> 1.3f
                    1 -> 1.15f
                    else -> 1.0f
                }

                // 第一次扩散
                currentScale.animateTo(
                    targetValue = maxScale,
                    animationSpec = tween(animationDuration, delayMillis = delay.toInt(), easing = EaseOutCubic)
                )
                currentAlpha.animateTo(
                    targetValue = first扩散TargetAlpha,
                    animationSpec = tween(animationDuration, delayMillis = delay.toInt(), easing = EaseOutCubic)
                )
                Log.d("VoiceRipple", "第一次扩散完成，index: $index, scale: ${currentScale.value}, alpha: ${currentAlpha.value}")
                
                // 保持在外层来回扩散
                try {
                    while (true) {
                        // 从最大稍微缩小
                        currentScale.animateTo(
                            targetValue = maxScale - 0.2f,
                            animationSpec = tween(300, easing = EaseInOutSine)
                        )
                        currentAlpha.animateTo(
                            targetValue = pulseAlphaHigh,
                            animationSpec = tween(300, easing = EaseInOutSine)
                        )
                        Log.d("VoiceRipple", "缩小完成，index: $index, scale: ${currentScale.value}, alpha: ${currentAlpha.value}")
                        
                        // 扩散回最大
                        currentScale.animateTo(
                            targetValue = maxScale,
                            animationSpec = tween(300, easing = EaseInOutSine)
                        )
                        currentAlpha.animateTo(
                            targetValue = pulseAlphaLow,
                            animationSpec = tween(300, easing = EaseInOutSine)
                        )
                        Log.d("VoiceRipple", "扩散完成，index: $index, scale: ${currentScale.value}, alpha: ${currentAlpha.value}")
                    }
                } catch (e: CancellationException) {
                    Log.d("VoiceRipple", "协程被取消，index: $index")
                }
            }
            
            // 最内层最小，最外层最大
            val rippleSizeDp = when (index) {
                0 -> 320.dp
                1 -> 330.dp
                else -> 340.dp
            }
            val rippleSizePx = with(density) { rippleSizeDp.toPx() }
            
            // 最内层alpha最大，最外层alpha最小
            val alphaMultiplier = when (index) {
                0 -> 1.0f
                1 -> 0.85f
                else -> 0.7f
            }
            
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (screenCenterX - rippleSizePx / 2).toInt(),
                            y = (position.y - rippleSizePx / 2).toInt()
                        )
                    }
                    .size(rippleSizeDp)
                    .scale(currentScale.value)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                rippleColor.copy(alpha = currentAlpha.value * 0.8f * alphaMultiplier),
                                rippleColor.copy(alpha = currentAlpha.value * 0.5f * alphaMultiplier),
                                rippleColor.copy(alpha = currentAlpha.value * 0.3f * alphaMultiplier),
                                Color.Transparent
                            ),
                            radius = rippleSizePx / 2
                        ),
                        shape = CircleShape
                    )
            )
        }

        // 中心高亮效果
        val centerSizeDp = 120.dp
        val centerSizePx = with(density) { centerSizeDp.toPx() }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (screenCenterX - centerSizePx / 2).toInt(),
                        y = (position.y - centerSizePx / 2).toInt()
                    )
                }
                .size(centerSizeDp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF60A5FA).copy(alpha = 0.5f),
                            Color.Transparent
                        ),
                        radius = centerSizePx / 2
                    ),
                    shape = CircleShape
                )
        )

        // 在波纹中心显示识别的文本
        if (recordedText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (screenCenterX - 200.dp.toPx() / 2).toInt(),
                            y = (position.y - centerSizePx / 2 - 60.dp.toPx()).toInt()
                        )
                    }
                    .width(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = recordedText,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/**
 * 美化的下拉菜单组件
 * 带有渐变背景、阴影、光泽效果等现代化设计
 *
 * @param expanded 是否展开
 * @param onDismissRequest 关闭回调
 * @param modifier 修饰符
 * @param content 菜单项内容
 */
@Composable
fun StyledDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = BaoziTheme.colors

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
                spotColor = Color.Black.copy(alpha = 0.15f),
                ambientColor = Color.Black.copy(alpha = 0.08f)
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colors.backgroundCard.copy(alpha = 0.95f),
                        colors.backgroundCard.copy(alpha = 0.85f)
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .drawWithContent {
                drawContent()
                // 顶部光泽渐变
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = size.height * 0.3f
                    ),
                    style = Fill,
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            }
            .clip(RoundedCornerShape(16.dp))
            .padding(4.dp)
    ) {
        content()
    }
}

/**
 * 美化的下拉菜单项组件
 *
 * @param text 菜单项文本
 * @param icon 图标
 * @param iconColor 图标颜色
 * @param onClick 点击回调
 * @param modifier 修饰符
 */
@Composable
fun StyledDropdownMenuItem(
    text: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = text,
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        onClick = {
            performLightHaptic(context)
            onClick()
        },
        modifier = modifier.fillMaxWidth()
    )
}