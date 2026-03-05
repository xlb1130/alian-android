package com.alian.assistant.presentation.ui.screens.local

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Send
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
import com.alian.assistant.data.ExecutionMetricsData
import com.alian.assistant.data.ExecutionRecord
import com.alian.assistant.data.ExecutionStep
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlianLocalHistoryScreen(
    records: List<ExecutionRecord>,
    onRecordClick: (ExecutionRecord) -> Unit,
    onDeleteRecord: (String) -> Unit,
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
        // 顶部标题 - 与 AlianLocalScreen 保持一致的样式
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .drawWithContent {
                    drawContent()
                    // 微妙的底部边框
                    drawRect(
                        color = colors.primary.copy(alpha = 0.1f),
                        size = Size(size.width, 1.dp.toPx()),
                        topLeft = Offset(0f, size.height - 1.dp.toPx())
                    )
                }
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 返回按钮
                    val backInteractionSource = remember { MutableInteractionSource() }
                    val backIsPressed by backInteractionSource.collectIsPressedAsState()

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (backIsPressed) colors.primary.copy(alpha = 0.1f)
                                else Color.Transparent
                            )
                            .scale(if (backIsPressed) 0.95f else 1f)
                            .clickable(
                                interactionSource = backInteractionSource,
                                indication = null,
                                onClick = {
                                    performLightHaptic(context)
                                    onBack()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowLeft,
                            contentDescription = "返回",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "执行记录",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 右上角显示任务指令
                Text(
                    text = "${records.size} 条记录",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            }
        }

        if (records.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "📝",
                        fontSize = 64.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无执行记录",
                        fontSize = 16.sp,
                        color = colors.textSecondary
                    )
                    Text(
                        text = "执行任务后记录会显示在这里",
                        fontSize = 14.sp,
                        color = colors.textHint
                    )
                }
            }
        } else {
            // 记录列表
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = records,
                    key = { it.id }
                ) { record ->
                    HistoryRecordCard(
                        record = record,
                        onClick = { onRecordClick(record) },
                        onDelete = { onDeleteRecord(record.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryRecordCard(
    record: ExecutionRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
            // 图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📝",
                    fontSize = 24.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 内容
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTimestamp(record.startTime),
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
                    contentDescription = "删除",
                    tint = colors.textHint
                )
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    record: ExecutionRecord,
    onBack: () -> Unit,
    onContinueAsk: (ExecutionRecord) -> Unit = {}
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    // Tab 状态：0 = 时间线，1 = 日志，2 = 指标
    var selectedTab by remember { mutableStateOf(0) }

    // 添加调试日志
    LaunchedEffect(record) {
        Log.d("HistoryDetailScreen", "接收到的记录 - ID: ${record.id}, steps 数量: ${record.steps.size}, logs 数量: ${record.logs.size}")
        record.steps.forEachIndexed { index, step ->
            Log.d("HistoryDetailScreen", "Step $index: ${step.stepNumber} - ${step.description} - outcome: ${step.outcome}")
        }
    }

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
        // 顶部标题 - 与 AlianLocalScreen 保持一致的样式
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .drawWithContent {
                    drawContent()
                    // 微妙的底部边框
                    drawRect(
                        color = colors.primary.copy(alpha = 0.1f),
                        size = Size(size.width, 1.dp.toPx()),
                        topLeft = Offset(0f, size.height - 1.dp.toPx())
                    )
                }
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 返回按钮
                    val backInteractionSource = remember { MutableInteractionSource() }
                    val backIsPressed by backInteractionSource.collectIsPressedAsState()

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (backIsPressed) colors.primary.copy(alpha = 0.1f)
                                else Color.Transparent
                            )
                            .scale(if (backIsPressed) 0.95f else 1f)
                            .clickable(
                                interactionSource = backInteractionSource,
                                indication = null,
                                onClick = {
                                    performLightHaptic(context)
                                    onBack()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowLeft,
                            contentDescription = "返回",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "执行详情",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 右上角显示任务指令
                Text(
                    text = record.title,
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 任务信息卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
            containerColor = if (colors.isDark) Color(0xFF141414) else Color(0xFFEEEEEE)
        )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "任务指令",
                            fontSize = 12.sp,
                            color = colors.textHint
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = record.instruction,
                            fontSize = 15.sp,
                            color = colors.textPrimary
                        )
                    }

                    // 继续追问按钮
                    val continueInteractionSource = remember { MutableInteractionSource() }
                    val continueIsPressed by continueInteractionSource.collectIsPressedAsState()

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (continueIsPressed) colors.primary.copy(alpha = 0.3f)
                                else colors.primary.copy(alpha = 0.1f)
                            )
                            .clickable(
                                interactionSource = continueInteractionSource,
                                indication = null,
                                onClick = {
                                    Log.d("HistoryDetailScreen", "继续追问按钮被点击 - 记录ID: ${record.id}")
                                    performLightHaptic(context)
                                    onContinueAsk(record)
                                }
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "继续追问",
                                tint = colors.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "继续追问",
                                fontSize = 12.sp,
                                color = colors.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 底部信息行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 开始时间
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "开始时间: ",
                            fontSize = 11.sp,
                            color = colors.textHint
                        )
                        Text(
                            text = record.formattedStartTime,
                            fontSize = 11.sp,
                            color = colors.textSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // 执行时长
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "执行时长: ",
                            fontSize = 11.sp,
                            color = colors.textHint
                        )
                        Text(
                            text = record.formattedDuration,
                            fontSize = 11.sp,
                            color = colors.textSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Tab 切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HistoryDetailTab(
                title = "执行时间线",
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 }
            )
            HistoryDetailTab(
                title = "执行日志",
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 }
            )
            HistoryDetailTab(
                title = "执行指标",
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 内容区域
        when (selectedTab) {
            0 -> {
                // 时间线列表 - 使用与 AlianLocalScreen 相同的 StepCard 样式
                if (record.steps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无执行步骤",
                            fontSize = 14.sp,
                            color = colors.textHint
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = record.steps,
                            key = { it.id }
                        ) { step ->
                            HistoryStepCard(step = step)
                        }
                    }
                }
            }
            1 -> {
                // 日志列表
                if (record.logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无执行日志",
                            fontSize = 14.sp,
                            color = colors.textHint
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(
                            items = record.logs,
                            key = { it.hashCode() }
                        ) { log ->
                            val logColor = when {
                                log.contains("❌") -> colors.error
                                log.contains("✅") -> colors.success
                                log.contains("📋") || log.contains("🎬") -> colors.secondary
                                log.contains("Step") || log.contains("=====") -> colors.primary
                                log.contains("⛔") -> colors.error
                                else -> colors.textSecondary
                            }
                            Text(
                                text = log,
                                fontSize = 12.sp,
                                color = logColor,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            2 -> {
                HistoryMetricsContent(record.executionMetrics)
            }
        }
    }
}

@Composable
private fun RowScope.HistoryDetailTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = BaoziTheme.colors
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.primary else colors.backgroundCard)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Color.White else colors.textSecondary
        )
    }
}

@Composable
private fun HistoryMetricsContent(metrics: ExecutionMetricsData?) {
    val colors = BaoziTheme.colors
    if (metrics == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无执行指标",
                fontSize = 14.sp,
                color = colors.textHint
            )
        }
        return
    }

    val groups = listOf(
        MetricsGroup(
            title = "运行时",
            items = listOf(
                "运行时选择" to metrics.runtimeSelected,
                "运行时健康异常" to metrics.runtimeHealthAnomalyCount.toString(),
                "运行时降级次数" to metrics.runtimeFallbackCount.toString(),
                "总耗时(ms)" to metrics.durationMs.toString()
            )
        ),
        MetricsGroup(
            title = "截图效率",
            items = listOf(
                "截图请求总数" to metrics.snapshotTotalRequests.toString(),
                "截图强制刷新" to metrics.snapshotForceRefreshRequests.toString(),
                "截图真实采集" to metrics.snapshotFreshCaptureCount.toString(),
                "截图缓存命中" to metrics.snapshotCacheHitCount.toString(),
                "截图节流复用" to metrics.snapshotThrottleReuseCount.toString(),
                "截图命中率" to metrics.snapshotHitRate
            )
        ),
        MetricsGroup(
            title = "降级恢复",
            items = listOf(
                "仓储降级次数" to metrics.snapshotRepoFallbackCount.toString(),
                "运行时兜底次数" to metrics.snapshotRuntimeFallbackCount.toString(),
                "运行时兜底恢复" to metrics.snapshotRuntimeRecoveredCount.toString(),
                "兜底恢复率" to metrics.snapshotRecoverRate
            )
        )
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(groups) { group ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = colors.backgroundCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        text = group.title,
                        fontSize = 13.sp,
                        color = colors.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    group.items.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.first,
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                            Text(
                                text = item.second,
                                fontSize = 13.sp,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (index != group.items.lastIndex) {
                            HorizontalDivider(
                                color = colors.primary.copy(alpha = 0.08f),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class MetricsGroup(
    val title: String,
    val items: List<Pair<String, String>>
)

/**
 * 步骤卡片组件 - 与 AlianLocalScreen 中的 StepCard 样式保持一致
 */
@Composable
fun HistoryStepCard(step: ExecutionStep) {
    val colors = BaoziTheme.colors
    var isExpanded by remember { mutableStateOf(step.isExpanded) }

    // 调试日志
    LaunchedEffect(step) {
        Log.d("HistoryStepCard", "步骤 ${step.stepNumber} - action: ${step.action}, actionText: ${step.actionText}, actionButton: ${step.actionButton}, actionDuration: ${step.actionDuration}, actionMessage: ${step.actionMessage}")
    }

    // 获取结果信息
    val (outcomeText, outcomeColor) = when (step.outcome) {
        "A" -> "成功" to Color(0xFF10B981)
        "B" -> "部分成功" to Color(0xFFF59E0B)
        "C" -> "失败" to Color(0xFFEF4444)
        "?" -> "进行中" to Color(0xFF3B82F6)
        else -> "未知" to Color(0xFF9CA3AF)
    }

    // 格式化耗时
    val formattedDuration = if (step.duration > 0) {
        val seconds = step.duration / 1000.0
        if (seconds < 1) "${step.duration}ms"
        else if (seconds < 60) String.format("%.1fs", seconds)
        else String.format("%.1fm", seconds / 60)
    } else {
        ""
    }

    // 动作类型中文转换
    val actionChinese = when (step.action) {
        "click" -> "点击"
        "double_tap" -> "双击"
        "long_press" -> "长按"
        "swipe" -> "滑动"
        "drag" -> "拖拽"
        "type" -> "输入"
        "system_button" -> "系统按键"
        "open_app", "open" -> "打开应用"
        "answer" -> "回答"
        "wait" -> "等待"
        "take_over", "ask_user" -> "人机交互"
        "terminate" -> "任务结束"
        "finish" -> "完成"
        else -> step.action
    }

    // 卡片背景色
    val cardBackgroundColor = when (step.outcome) {
        "A" -> Color(0xFF10B981).copy(alpha = 0.05f)
        "B" -> Color(0xFFF59E0B).copy(alpha = 0.05f)
        "C" -> Color(0xFFEF4444).copy(alpha = 0.05f)
        "?" -> Color(0xFF3B82F6).copy(alpha = 0.05f)
        else -> colors.backgroundCard
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBackgroundColor
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            // 卡片头部
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 图标
                Text(
                    text = step.icon ?: "⚙️",
                    fontSize = 22.sp,
                    modifier = Modifier.padding(end = 10.dp)
                )

                // 步骤信息
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "步骤 ${step.stepNumber}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // 状态标签
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = outcomeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = outcomeText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = outcomeColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // 描述
                    Text(
                        text = step.description,
                        fontSize = 13.sp,
                        color = colors.textPrimary,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 额外信息预览（未展开时）
                    if (!isExpanded) {
                        val extraInfoPreview = buildExtraInfoPreview(step)
                        if (extraInfoPreview.isNotEmpty()) {
                            Text(
                                text = extraInfoPreview,
                                fontSize = 11.sp,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }

                // 耗时和展开图标
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    if (formattedDuration.isNotEmpty()) {
                        Text(
                            text = formattedDuration,
                            fontSize = 11.sp,
                            color = colors.textSecondary
                        )
                    }
                    Text(
                        text = if (isExpanded) "▼" else "▶",
                        fontSize = 10.sp,
                        color = colors.textHint
                    )
                }
            }

            // 展开的详情
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    // 分隔线
                    HorizontalDivider(
                        color = colors.primary.copy(alpha = 0.1f),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    // 思考过程
                    if (step.thought.isNotEmpty()) {
                        Text(
                            text = "💭 思考",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = step.thought,
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // 动作类型
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🎯 动作",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = actionChinese,
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // 额外信息展示
                    val hasExtraInfo = !step.actionText.isNullOrEmpty() ||
                                       !step.actionButton.isNullOrEmpty() ||
                                       step.actionDuration != null ||
                                       !step.actionMessage.isNullOrEmpty()
                    
                    if (hasExtraInfo) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "📋 详情",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        
                        // text 字段（用于 type, answer 等动作）
                        if (!step.actionText.isNullOrEmpty()) {
                            Text(
                                text = "文本: ${step.actionText}",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        
                        // button 字段（用于 system_button 等动作）
                        if (!step.actionButton.isNullOrEmpty()) {
                            Text(
                                text = "按键: ${step.actionButton}",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        
                        // duration 字段（用于 wait 动作）
                        if (step.actionDuration != null) {
                            Text(
                                text = "等待时长: ${step.actionDuration}秒",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        
                        // message 字段（用于 take_over, ask_user 等动作）
                        if (!step.actionMessage.isNullOrEmpty()) {
                            Text(
                                text = "消息: ${step.actionMessage}",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 构建额外信息的预览文本
 */
private fun buildExtraInfoPreview(step: ExecutionStep): String {
    val parts = mutableListOf<String>()
    
    if (!step.actionText.isNullOrEmpty()) {
        parts.add("文本: ${step.actionText}")
    }
    if (!step.actionButton.isNullOrEmpty()) {
        parts.add("按键: ${step.actionButton}")
    }
    if (step.actionDuration != null) {
        parts.add("等待: ${step.actionDuration}秒")
    }
    if (!step.actionMessage.isNullOrEmpty()) {
        parts.add("消息: ${step.actionMessage}")
    }
    
    return parts.joinToString(" | ")
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
