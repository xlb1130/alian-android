package com.alian.assistant.presentation.ui.screens.online

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.alian.assistant.R
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.core.alian.backend.SessionData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlianOnlineHistoryScreen(
    sessions: List<SessionData>,
    onSessionClick: (SessionData) -> Unit,
    onDeleteSession: (SessionData) -> Unit,
    onCreateNewSession: () -> Unit,
    onBack: () -> Unit = {}
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    // 支持侧边屏划屏返回
    BackHandler(enabled = true) {
        performLightHaptic(context)
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部标题 - 参考输入框风格，使用 Surface 组件
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = colors.backgroundCard.copy(alpha = 0.95f),
            shadowElevation = 4.dp,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回按钮 - 简化设计，去掉容器背景
                IconButton(
                    onClick = {
                        performLightHaptic(context)
                        onBack()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = context.getString(R.string.cd_back),
                        tint = colors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = context.getString(R.string.chat_history_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Text(
                        text = context.getString(R.string.chat_history_count, sessions.size),
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }

        if (sessions.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "💬",
                        fontSize = 64.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = context.getString(R.string.chat_history_empty),
                        fontSize = 16.sp,
                        color = colors.textSecondary
                    )
                    Text(
                        text = context.getString(R.string.chat_history_empty_hint),
                        fontSize = 14.sp,
                        color = colors.textHint
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onCreateNewSession,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                    ) {
                        Text(
                            text = context.getString(R.string.chat_new_session),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        } else {
            // 对话列表
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 创建新对话按钮
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCreateNewSession() },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = colors.primary.copy(alpha = 0.1f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(colors.primary.copy(alpha = 0.2f)),
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
                }

                // 对话列表
                items(
                    items = sessions,
                    key = { it.session_id }
                ) { session ->
                    var showDeleteDialog by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSessionClick(session) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                                containerColor = colors.backgroundCard
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 对话图标
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(colors.primary.copy(alpha = 0.1f)),
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
                                    session.latest_message?.take(30) + if ((session.latest_message?.length ?: 0) > 30) "..." else ""
                                } else {
                                    session.title
                                }
                                Text(
                                    text = displayTitle,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = formatTimestamp(session.latest_message_at, context),
                                    fontSize = 14.sp,
                                    color = colors.textSecondary
                                )
                            }

                            // 删除按钮
                            IconButton(
                                onClick = { showDeleteDialog = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = context.getString(R.string.cd_delete),
                                    tint = colors.textHint
                                )
                            }
                        }
                    }

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
                                    onDeleteSession(session)
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
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long?, context: Context): String {
    if (timestamp == null) return context.getString(R.string.time_unknown)

    val now = System.currentTimeMillis()
    val diff = now - (timestamp * 1000)  // 转换为毫秒

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