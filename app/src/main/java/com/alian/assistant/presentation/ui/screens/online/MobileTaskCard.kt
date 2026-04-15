package com.alian.assistant.presentation.ui.screens.online

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.core.alian.backend.MobileTaskStatus
import com.alian.assistant.core.alian.backend.RemoteMobileTask
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * 移动端任务卡片组件
 * 
 * @param task 远端移动端任务数据
 * @param modifier 修饰符
 * @param onConfirmClick 确认执行点击回调
 * @param onRetryClick 重试点击回调
 * @param onViewDetailsClick 查看详情点击回调
 */
@Composable
fun MobileTaskCard(
    task: RemoteMobileTask,
    modifier: Modifier = Modifier,
    onConfirmClick: () -> Unit = {},
    onRetryClick: () -> Unit = {},
    onViewDetailsClick: () -> Unit = {}
) {
    val colors = BaoziTheme.colors

    Card(
        modifier = modifier
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                spotColor = Color.Black.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.backgroundCard
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 任务图标和标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // 状态图标
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(getStatusBackgroundColor(task.status, colors)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getStatusIcon(task.status),
                            contentDescription = "任务状态",
                            tint = getStatusIconColor(task.status, colors),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // 标题
                    Text(
                        text = task.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 状态标签
                StatusBadge(
                    status = task.status,
                    colors = colors
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 指令内容
            if (task.instruction.isNotBlank()) {
                Text(
                    text = task.instruction,
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 进度条（执行中时显示）
            if (task.status == MobileTaskStatus.EXECUTING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(
                        progress = { task.progress / 100f },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = colors.primary,
                        trackColor = colors.primary.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${task.progress}%",
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 状态消息
            task.statusMessage?.let { message ->
                Text(
                    text = message,
                    fontSize = 12.sp,
                    color = colors.textHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 错误信息
            task.errorMessage?.let { error ->
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = colors.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = error,
                        fontSize = 12.sp,
                        color = colors.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 结果摘要
            task.resultSummary?.let { summary ->
                Text(
                    text = "结果: $summary",
                    fontSize = 12.sp,
                    color = colors.success,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    task.canExecute() -> {
                        // 确认执行按钮
                        Button(
                            onClick = onConfirmClick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("确认执行")
                        }
                    }
                    task.isExecuting() -> {
                        // 执行中状态
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = colors.primary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "执行中...",
                                fontSize = 14.sp,
                                color = colors.primary
                            )
                        }
                    }
                    task.canRetry() -> {
                        // 重试按钮
                        Button(
                            onClick = onRetryClick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.warning
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重试")
                        }
                    }
                    task.isCompleted() -> {
                        // 查看详情按钮
                        OutlinedButton(
                            onClick = onViewDetailsClick,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("查看详情")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 状态徽章
 */
@Composable
private fun StatusBadge(
    status: MobileTaskStatus,
    colors: com.alian.assistant.presentation.ui.theme.BaoziColors
) {
    val (text, backgroundColor, textColor) = when (status) {
        MobileTaskStatus.PENDING -> Triple("待确认", colors.warning.copy(alpha = 0.15f), colors.warning)
        MobileTaskStatus.CONFIRMED -> Triple("已确认", colors.info.copy(alpha = 0.15f), colors.info)
        MobileTaskStatus.EXECUTING -> Triple("执行中", colors.primary.copy(alpha = 0.15f), colors.primary)
        MobileTaskStatus.SUCCEEDED -> Triple("成功", colors.success.copy(alpha = 0.15f), colors.success)
        MobileTaskStatus.FAILED -> Triple("失败", colors.error.copy(alpha = 0.15f), colors.error)
        MobileTaskStatus.CANCELLED -> Triple("已取消", colors.textHint.copy(alpha = 0.15f), colors.textHint)
        MobileTaskStatus.TIMEOUT -> Triple("超时", colors.warning.copy(alpha = 0.15f), colors.warning)
        MobileTaskStatus.RESOLVED -> Triple("已解决", colors.success.copy(alpha = 0.15f), colors.success)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

/**
 * 获取状态图标
 */
private fun getStatusIcon(status: MobileTaskStatus): ImageVector {
    return when (status) {
        MobileTaskStatus.PENDING -> Icons.Default.Pending
        MobileTaskStatus.CONFIRMED -> Icons.Default.SettingsRemote
        MobileTaskStatus.EXECUTING -> Icons.Default.SettingsRemote
        MobileTaskStatus.SUCCEEDED -> Icons.Default.CheckCircle
        MobileTaskStatus.FAILED -> Icons.Default.Error
        MobileTaskStatus.CANCELLED -> Icons.Default.Error
        MobileTaskStatus.TIMEOUT -> Icons.Default.Error
        MobileTaskStatus.RESOLVED -> Icons.Default.CheckCircle
    }
}

/**
 * 获取状态背景色
 */
private fun getStatusBackgroundColor(status: MobileTaskStatus, colors: com.alian.assistant.presentation.ui.theme.BaoziColors): Color {
    return when (status) {
        MobileTaskStatus.PENDING -> colors.warning.copy(alpha = 0.15f)
        MobileTaskStatus.CONFIRMED -> colors.info.copy(alpha = 0.15f)
        MobileTaskStatus.EXECUTING -> colors.primary.copy(alpha = 0.15f)
        MobileTaskStatus.SUCCEEDED -> colors.success.copy(alpha = 0.15f)
        MobileTaskStatus.FAILED -> colors.error.copy(alpha = 0.15f)
        MobileTaskStatus.CANCELLED -> colors.textHint.copy(alpha = 0.15f)
        MobileTaskStatus.TIMEOUT -> colors.warning.copy(alpha = 0.15f)
        MobileTaskStatus.RESOLVED -> colors.success.copy(alpha = 0.15f)
    }
}

/**
 * 获取状态图标颜色
 */
private fun getStatusIconColor(status: MobileTaskStatus, colors: com.alian.assistant.presentation.ui.theme.BaoziColors): Color {
    return when (status) {
        MobileTaskStatus.PENDING -> colors.warning
        MobileTaskStatus.CONFIRMED -> colors.info
        MobileTaskStatus.EXECUTING -> colors.primary
        MobileTaskStatus.SUCCEEDED -> colors.success
        MobileTaskStatus.FAILED -> colors.error
        MobileTaskStatus.CANCELLED -> colors.textHint
        MobileTaskStatus.TIMEOUT -> colors.warning
        MobileTaskStatus.RESOLVED -> colors.success
    }
}
