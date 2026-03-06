package com.alian.assistant.presentation.ui.screens.online

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.R
import com.alian.assistant.core.alian.backend.SessionData
import com.alian.assistant.presentation.ui.theme.BaoziColors
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

// 毛玻璃效果修饰符
fun Modifier.glassEffect(
    alpha: Float = 0.6f,
    blurRadius: Float = 16f
): Modifier = this then Modifier.alpha(alpha)

// 光泽渐变
@Composable
fun glossyGradient(colors: BaoziColors): Brush {
    return Brush.verticalGradient(
        colors = listOf(
            colors.backgroundCard.copy(alpha = 0.3f),
            Color.Transparent,
            colors.backgroundCard.copy(alpha = 0.1f)
        )
    )
}

/**
 * 在线对话历史抽屉
 */
@Composable
fun AlianChatHistoryDrawer(
    context: Context,
    sessions: List<SessionData>,
    selectedSessionId: String? = null,
    onSessionClick: (SessionData) -> Unit,
    onDeleteSession: (SessionData) -> Unit,
    onCreateNewSession: () -> Unit,
    onClose: () -> Unit
) {
        val colors = BaoziTheme.colors

    // 渐变背景 - 增强区分度，使用主题颜色
    val gradientBrush = Brush.verticalGradient(
        colors = if (colors.isDark) {
            // 深色模式：抽屉背景更深
            listOf(
                colors.backgroundDrawer,
                colors.background,
                colors.background.copy(alpha = 0.95f)
            )
        } else {
            // 浅色模式：抽屉背景更浅
            listOf(
                colors.backgroundDrawer,
                colors.background.copy(alpha = 0.98f),
                colors.background.copy(alpha = 0.96f)
            )
        }
    )

    // 光泽渐变 Brush
    val glossyGradientBrush = glossyGradient(colors)

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(gradientBrush)
    ) {
        // 固定在顶部的 Header 区域
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(glossyGradientBrush)
                }
        ) {            // 抽屉标题 - 优化版，添加毛玻璃效果
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .glassEffect(alpha = 0.7f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 标题区域优化 - 渐变文字
                    Column {
                        Text(
                            text = context.getString(R.string.chat_history_title),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = context.getString(R.string.chat_history_count, sessions.size),
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                    val closeInteractionSource = remember { MutableInteractionSource() }
                    val closeIsPressed by closeInteractionSource.collectIsPressedAsState()
                    val closeIsHovered by closeInteractionSource.collectIsHoveredAsState()

                    // 图标容器美化 - 添加悬停效果
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    closeIsPressed -> colors.error.copy(alpha = 0.2f)
                                    closeIsHovered -> colors.backgroundInput.copy(alpha = 0.8f)
                                    else -> colors.backgroundInput
                                }
                            )
                            .shadow(
                                elevation = if (closeIsHovered) 6.dp else 4.dp,
                                shape = CircleShape,
                                ambientColor = colors.primary.copy(alpha = 0.3f),
                                spotColor = colors.primary.copy(alpha = 0.3f)
                            )
                    ) {
                        IconButton(
                            onClick = {
                                performLightHaptic(context)
                                onClose()
                            },
                            interactionSource = closeInteractionSource,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = context.getString(R.string.cd_close),
                                tint = if (closeIsPressed) colors.error else colors.textSecondary
                            )
                        }
                    }
                }
            }

            // 分隔线优化
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                color = colors.backgroundInput.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
        }

            // 创建新对话按钮 - 添加阴影和优化
        val createInteractionSource = remember { MutableInteractionSource() }
        val createIsPressed by createInteractionSource.collectIsPressedAsState()
        val createIsHovered by createInteractionSource.collectIsHoveredAsState()

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clickable(
                    interactionSource = createInteractionSource,
                    indication = null,
                    onClick = {
                        performLightHaptic(context)
                        onCreateNewSession()
                    }
                )
                .shadow(
                    elevation = when {
                        createIsPressed -> 2.dp
                        createIsHovered -> 8.dp
                        else -> 6.dp
                    },
                    shape = RoundedCornerShape(16.dp),
                    ambientColor = colors.primary.copy(alpha = 0.2f),
                    spotColor = colors.primary.copy(alpha = 0.2f)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    createIsPressed -> colors.primary.copy(alpha = 0.3f)
                    createIsHovered -> colors.primary.copy(alpha = 0.15f)
                    else -> colors.backgroundCardHover
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 图标容器美化
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.2f))
                        .shadow(
                            elevation = 4.dp,
                            shape = CircleShape,
                            ambientColor = colors.primary.copy(alpha = 0.3f),
                            spotColor = colors.primary.copy(alpha = 0.3f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = context.getString(R.string.chat_new_session),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary
                    )
                    Text(
                        text = context.getString(R.string.chat_new_session_hint),
                        fontSize = 14.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }

        // 可滚动的内容区域
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // 对话列表
            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "💬",
                            fontSize = 48.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = context.getString(R.string.chat_history_empty),
                            fontSize = 14.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                items(
                    items = sessions,
                    key = { it.session_id }
                ) { session ->
                    // 列表项进入动画
                    var isVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(session.session_id) {
                        isVisible = true
                    }

                    AnimatedVisibility(
                        visible = isVisible,
                        enter = slideInHorizontally(
                            initialOffsetX = { -300 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + fadeIn(
                            animationSpec = tween(durationMillis = 300)
                        ),
                        exit = slideOutHorizontally(
                            targetOffsetX = { -300 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + fadeOut(
                            animationSpec = tween(durationMillis = 200)
                        )
                    ) {
                        SessionDrawerItem(
                            context = context,
                            session = session,
                            isSelected = session.session_id == selectedSessionId,
                            onClick = { onSessionClick(session) },
                            onDelete = { onDeleteSession(session) }
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * 抽屉中的对话项
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDrawerItem(
    context: Context,
    session: SessionData,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    isSelected: Boolean = false
) {
    val colors = BaoziTheme.colors
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = colors.backgroundCard,
            title = {
                Text(context.getString(R.string.chat_delete_title), color = colors.textPrimary)
            },
            text = {
                Text(context.getString(R.string.chat_delete_confirm), color = colors.textSecondary)
            },
            confirmButton = {
                TextButton(onClick = {
                    isDeleting = true
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text(context.getString(R.string.btn_delete), color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(context.getString(R.string.btn_cancel), color = colors.textSecondary)
                }
            }
        )
    }

    val sessionInteractionSource = remember { MutableInteractionSource() }
    val sessionIsPressed by sessionInteractionSource.collectIsPressedAsState()
    val sessionIsHovered by sessionInteractionSource.collectIsHoveredAsState()

    // 悬停动画
    val scale by animateFloatAsState(
        targetValue = when {
            sessionIsPressed -> 0.98f
            sessionIsHovered -> 1.02f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    // 悬停渐变背景
    val hoverGradient = Brush.horizontalGradient(
        colors = listOf(
            colors.background.copy(alpha = 0.5f),
            colors.primary.copy(alpha = 0.05f)
        )
    )

    // 删除动画
    val deleteOffset by animateFloatAsState(
        targetValue = if (isDeleting) 300f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "deleteOffset"
    )
    val deleteAlpha by animateFloatAsState(
        targetValue = if (isDeleting) 0f else 1f,
        animationSpec = tween(durationMillis = 300),
        label = "deleteAlpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .offset(x = deleteOffset.dp)
            .alpha(deleteAlpha)
            .clickable(
                interactionSource = sessionInteractionSource,
                indication = null,
                onClick = {
                    performLightHaptic(context)
                    onClick()
                }
            )
            .shadow(
                elevation = when {
                    isSelected -> 8.dp
                    sessionIsPressed -> 2.dp
                    sessionIsHovered -> 6.dp
                    else -> 4.dp
                },
                shape = RoundedCornerShape(16.dp),
                ambientColor = if (isSelected) colors.primary.copy(alpha = 0.4f) else colors.primary.copy(alpha = 0.15f),
                spotColor = if (isSelected) colors.primary.copy(alpha = 0.4f) else colors.primary.copy(alpha = 0.15f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> colors.primary.copy(alpha = 0.15f)
                sessionIsHovered -> colors.backgroundCardHover
                sessionIsPressed -> colors.backgroundCardPressed
                else -> colors.backgroundCard
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 对话图标 - 美化
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected)
                            colors.primary.copy(alpha = 0.3f)
                        else
                            colors.primary.copy(alpha = 0.1f)
                    )
                    .shadow(
                        elevation = 4.dp,
                        shape = CircleShape,
                        ambientColor = colors.primary.copy(alpha = 0.3f),
                        spotColor = colors.primary.copy(alpha = 0.3f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "💬",
                    fontSize = 24.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 对话信息
            Column(modifier = Modifier.weight(1f)) {
                val displayTitle = if (session.title.isNullOrBlank()) {
                    session.latest_message?.take(30) + if ((session.latest_message?.length
                            ?: 0) > 30
                    ) "..." else ""
                } else {
                    session.title
                }
                // 对话标题优化 - 渐变效果
                Text(
                    text = displayTitle,
                    fontSize = 16.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) colors.primary else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 时间戳美化 - 更柔和的颜色和更小的字号
                Text(
                    text = formatTimestamp(session.latest_message_at, context),
                    fontSize = 12.sp,
                    color = if (isSelected) colors.primary.copy(alpha = 0.7f) else colors.textHint,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                )
            }

            // 删除按钮 - 美化，添加红色渐变
            val deleteInteractionSource = remember { MutableInteractionSource() }
            val deleteIsPressed by deleteInteractionSource.collectIsPressedAsState()
            val deleteIsHovered by deleteInteractionSource.collectIsHoveredAsState()

            val deleteGradient = Brush.radialGradient(
                colors = listOf(
                    when {
                        deleteIsPressed -> colors.error.copy(alpha = 0.4f)
                        deleteIsHovered -> colors.error.copy(alpha = 0.25f)
                        else -> colors.error.copy(alpha = 0.15f)
                    },
                    colors.error.copy(alpha = 0.05f)
                )
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(deleteGradient)
                    .shadow(
                        elevation = if (deleteIsHovered) 4.dp else 2.dp,
                        shape = CircleShape,
                        ambientColor = colors.error.copy(alpha = 0.3f),
                        spotColor = colors.error.copy(alpha = 0.3f)
                    )
            ) {
                IconButton(
                    onClick = {
                        performLightHaptic(context)
                        showDeleteDialog = true
                    },
                    interactionSource = deleteInteractionSource,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = context.getString(R.string.cd_delete),
                        tint = when {
                            deleteIsPressed -> colors.error
                            deleteIsHovered -> colors.error.copy(alpha = 0.9f)
                            else -> colors.error.copy(alpha = 0.7f)
                        }
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long?, context: Context): String {
    if (timestamp == null) return context.getString(R.string.time_unknown)

    val now = System.currentTimeMillis()
    val diff = now - (timestamp * 1000)

    return when {
        diff < 60000 -> context.getString(R.string.time_just_now)
        diff < 3600000 -> context.getString(R.string.time_minutes_ago, diff / 60000)
        diff < 86400000 -> context.getString(R.string.time_hours_ago, diff / 3600000)
        diff < 604800000 -> context.getString(R.string.time_days_ago, diff / 86400000)
        else -> {
            val date = Date(timestamp * 1000)
            val format = SimpleDateFormat("MM月dd日", Locale.getDefault())
            format.format(date)
        }
    }
}
