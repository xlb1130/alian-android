package com.alian.assistant.presentation.ui.screens.local

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.data.ExecutionRecord
import com.alian.assistant.data.ExecutionStatus
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
 * 本地历史记录抽屉
 */
@Composable
fun AlianLocalHistoryDrawer(
    context: Context,
    records: List<ExecutionRecord>,
    onRecordClick: (ExecutionRecord) -> Unit,
    onClose: () -> Unit,
    onDeleteRecord: (ExecutionRecord) -> Unit = {}
) {
        val colors = BaoziTheme.colors

    // 渐变背景 - 增强区分度，使用主题颜色
    val gradientBrush = Brush.verticalGradient(
        colors = if (colors.isDark) {
            // 深色模式：抽屉背景更深，与卡片形成明显对比
            listOf(
                colors.backgroundDrawer,
                colors.background.copy(alpha = 0.9f),
                colors.background.copy(alpha = 0.95f)
            )
        } else {
            // 浅色模式：抽屉背景更浅，与卡片形成明显对比
            listOf(
                colors.surfaceVariant,
                colors.background.copy(alpha = 0.98f),
                colors.background
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
                            text = "执行记录",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "${records.size} 条记录",
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
                                contentDescription = "关闭",
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

        // 可滚动的内容区域
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // 执行记录列表
            if (records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "📝",
                            fontSize = 48.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "暂无执行记录",
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
                    items = records,
                    key = { it.id }
                ) { record ->
                    // 列表项进入动画
                    var isVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(record.id) {
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
                        LocalHistoryDrawerItem(
                            context = context,
                            record = record,
                            onClick = { onRecordClick(record) },
                            onDelete = { onDeleteRecord(record) }
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun LocalHistoryDrawerItem(
    context: Context,
    record: ExecutionRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = colors.backgroundCard,
            title = {
                Text("确认删除", color = colors.textPrimary)
            },
            text = {
                Text("确定要删除这条执行记录吗？", color = colors.textSecondary)
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("删除", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消", color = colors.textSecondary)
                }
            }
        )
    }
    val recordInteractionSource = remember { MutableInteractionSource() }
    val recordIsPressed by recordInteractionSource.collectIsPressedAsState()
    val recordIsHovered by recordInteractionSource.collectIsHoveredAsState()

    // 悬停动画
    val scale by animateFloatAsState(
        targetValue = when {
            recordIsPressed -> 0.98f
            recordIsHovered -> 1.02f
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(
                interactionSource = recordInteractionSource,
                indication = null,
                onClick = {
                    performLightHaptic(context)
                    onClick()
                }
            )
            .shadow(
                elevation = when {
                    recordIsPressed -> 2.dp
                    recordIsHovered -> 8.dp
                    else -> 6.dp
                },
                shape = RoundedCornerShape(16.dp),
                ambientColor = colors.primary.copy(alpha = 0.2f),
                spotColor = colors.primary.copy(alpha = 0.2f)
            )
            .drawBehind {
                // 微妙的边框描边，增强与背景的区分
                drawRoundRect(
                    color = colors.primary.copy(alpha = if (recordIsHovered) 0.15f else 0.08f),
                    style = Stroke(
                        width = 0.5.dp.toPx()
                    ),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                recordIsHovered -> colors.backgroundCardElevated
                recordIsPressed -> colors.backgroundCardHover
                else -> colors.backgroundCardElevated
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 顶部行：左侧状态+标题，右侧时间和删除按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态和标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // 状态标签 - 美化
                    val (statusText, statusBgColor, statusTextColor) = when (record.status) {
                        ExecutionStatus.COMPLETED -> Triple(
                            "已完成",
                            colors.success.copy(alpha = 0.2f),
                            colors.success
                        )

                        ExecutionStatus.FAILED -> Triple(
                            "失败",
                            colors.error.copy(alpha = 0.2f),
                            colors.error
                        )

                        ExecutionStatus.STOPPED -> Triple(
                            "已停止",
                            colors.warning.copy(alpha = 0.2f),
                            colors.warning
                        )

                        ExecutionStatus.RUNNING -> Triple(
                            "执行中",
                            colors.primary.copy(alpha = 0.2f),
                            colors.primary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusBgColor)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .shadow(
                                elevation = 2.dp,
                                shape = RoundedCornerShape(6.dp),
                                ambientColor = statusTextColor.copy(alpha = 0.3f),
                                spotColor = statusTextColor.copy(alpha = 0.3f)
                            )
                    ) {
                        Text(
                            text = statusText,
                            fontSize = 10.sp,
                            color = statusTextColor,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // 标题渐变效果
                    Text(
                        text = record.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 右侧：时间和删除按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 时间 - 美化，更柔和的颜色和更小的字号
                    Text(
                        text = formatTimestamp(record.startTime),
                        fontSize = 10.sp,
                        color = colors.textHint,
                        fontWeight = FontWeight.Medium
                    )

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
                            .size(32.dp)
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
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = when {
                                    deleteIsPressed -> colors.error
                                    deleteIsHovered -> colors.error.copy(alpha = 0.9f)
                                    else -> colors.error.copy(alpha = 0.7f)
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 底部：instruction
            Text(
                text = record.instruction,
                fontSize = 12.sp,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long?): String {
    if (timestamp == null) return "未知时间"

    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60000 -> "刚刚"
        diff < 3600000 -> "${diff / 60000}分钟前"
        diff < 86400000 -> "${diff / 3600000}小时前"
        diff < 604800000 -> "${diff / 86400000}天前"
        else -> {
            val date = Date(timestamp)
            val format = SimpleDateFormat("MM月dd日", Locale.getDefault())
            format.format(date)
        }
    }
}