package com.alian.assistant.presentation.ui.screens.online

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.core.alian.backend.ToolInfo
import com.alian.assistant.core.alian.backend.UIStep
import com.alian.assistant.core.alian.backend.UIToolCall
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * 跑马灯文本组件
 * 当文本过长时，自动从右到左循环滚动
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    scrollDuration: Int = 8000,  // 完整滚动一次的时长（毫秒）
    maxScrollOffset: Dp = Dp.Unspecified  // 最大滚动距离，防止超过工具图标
) {
    val density = LocalDensity.current

    // 容器宽度
    val containerWidth = remember { mutableStateOf(0) }

    // 获取文本实际宽度（使用一个固定估算值，简化处理）
    // 每个字符大约占 7dp，这是一个粗略估计
    val estimatedTextWidth = remember(text, fontSize) {
        (text.length * 7 * density.density).toInt()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "marquee")

    // 只有当文本宽度大于容器宽度时才滚动
    val shouldScroll = estimatedTextWidth > containerWidth.value && containerWidth.value > 0

    // 滚动距离：滚动到最后一个字过中线，然后第一个字从最后出现
    val scrollDistance = remember(containerWidth.value, estimatedTextWidth) {
        if (shouldScroll) {
            // 滚动距离 = 文本宽度 + 容器宽度的一半（让最后一个字过中线）
            val distance = (estimatedTextWidth + containerWidth.value / 2).toFloat()
            // 如果指定了最大滚动距离，则限制滚动距离
            if (maxScrollOffset != Dp.Unspecified) {
                val maxOffsetPx = with(density) { maxScrollOffset.toPx() }
                distance.coerceAtMost(maxOffsetPx)
            } else {
                distance
            }
        } else {
            0f
        }
    }

    val offsetX by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -scrollDistance,
        animationSpec = infiniteRepeatable(
            animation = tween(scrollDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "marquee_offset"
    )

    Box(
        modifier = modifier
            .clip(RectangleShape)  // 裁剪超出边界的内容
            .onSizeChanged { size ->
                containerWidth.value = size.width
            }
    ) {
        Row(
            modifier = Modifier.offset(x = if (shouldScroll) offsetX.dp else 0.dp)
        ) {
            Text(
                text = text,
                fontSize = fontSize,
                color = color,
                maxLines = maxLines,
                softWrap = false
            )
            // 添加重复的文本以实现无缝循环（使用最小间距）
            if (shouldScroll) {
                Text(
                    text = "  $text",
                    fontSize = fontSize,
                    color = color,
                    maxLines = maxLines,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * 工具调用卡片组件
 */
@Composable
fun ToolCallCard(
    toolCall: UIToolCall,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors

    // 根据状态选择颜色
    val statusColor = when (toolCall.status.lowercase()) {
        "pending" -> colors.textHint
        "in_progress" -> colors.primary
        "completed" -> colors.success
        "failed" -> colors.error
        else -> colors.textSecondary
    }

    val statusText = when (toolCall.status.lowercase()) {
        "pending" -> "等待中"
        "in_progress" -> "执行中"
        "completed" -> "已完成"
        "failed" -> "失败"
        else -> toolCall.status
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.backgroundCard
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 工具名称和状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = toolCall.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                }

                // 状态标签
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 函数名
            if (toolCall.function.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "函数: ${toolCall.function}",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }

            // 参数
            if (toolCall.args.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "参数: ${toolCall.args.entries.joinToString(", ") { "${it.key}=${it.value}" }}",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * 执行步骤卡片组件
 */
@Composable
fun StepCard(
    step: UIStep,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = true,
    onExpandChanged: (Boolean) -> Unit = {},
    toolCalls: List<UIToolCall> = emptyList()
) {
    val colors = BaoziTheme.colors

    // 根据toolCallIds查找对应的工具信息
    val stepTools = step.toolCallIds.mapNotNull { toolCallId ->
        toolCalls.find { it.toolCallId == toolCallId }
    }.map { toolCall ->
        ToolInfo(
            name = toolCall.name,
            status = toolCall.status,
            function = toolCall.function,
            args = toolCall.args
        )
    }

    Log.d(
        "StepCard",
        "渲染 StepCard: id=${step.id}, description=${step.description}, status=${step.status}, toolCallIds=${step.toolCallIds}, 查找到的tools数量=${stepTools.size}, isExpanded=$isExpanded"
    )
    System.out.flush()

    // 根据状态选择颜色
    val statusColor = when (step.status.lowercase()) {
        "pending" -> colors.textHint
        "in_progress", "running" -> colors.primary
        "completed" -> colors.success
        "failed" -> colors.error
        else -> colors.textSecondary
    }

    val statusIcon = when (step.status.lowercase()) {
        "pending" -> Icons.Default.History
        "in_progress", "running" -> Icons.Default.Add
        "completed" -> Icons.Default.CheckCircle
        "failed" -> Icons.Default.Close
        else -> Icons.Default.Info
    }

    // 生成工具描述文本
    fun getToolDescription(tool: ToolInfo): String {
        return when (tool.name.lowercase()) {
            "shell" -> {
                // shell工具：取命令和参数，太长用省略号
                val command = tool.args["command"]?.toString() ?: ""
                val maxLength = 30
                if (command.length > maxLength) {
                    command.take(maxLength) + "..."
                } else {
                    command
                }
            }

            "file" -> {
                // file工具：取路径
                tool.args["path"]?.toString() ?: tool.args["file"]?.toString() ?: ""
            }

            "read_file" -> {
                // read_file工具：取路径
                tool.args["file_path"]?.toString() ?: tool.args["path"]?.toString() ?: ""
            }

            "write_file" -> {
                // write_file工具：取路径
                tool.args["file_path"]?.toString() ?: tool.args["path"]?.toString() ?: ""
            }

            else -> {
                // 其他工具：尝试从args中获取有意义的描述
                tool.args.entries.firstOrNull()?.value?.toString() ?: ""
            }
        }
    }


    Column(
        modifier = Modifier.padding(8.dp)
    ) {
        // 步骤描述行（可点击展开/折叠）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = {
                        Log.d(
                            "StepCard",
                            "点击Step ${step.id}, tools数量: ${step.tools.size}, 当前isExpanded: $isExpanded"
                        )
                        onExpandChanged(!isExpanded)
                        Log.d("StepCard", "点击后isExpanded: ${!isExpanded}")
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(14.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 步骤描述
            Text(
                text = step.description,
                fontSize = 16.sp,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )

            // 展开/折叠图标（仅在有工具时显示）
            if (stepTools.isNotEmpty()) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = if (isExpanded) "折叠" else "展开",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        // 工具列表（仅在展开时显示）
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(
                expandFrom = Alignment.Top
            ),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top
            )
        ) {
            Log.d(
                "StepCard",
                "显示工具列表，条件: isExpanded=$isExpanded, tools数量=${stepTools.size}"
            )
            System.out.flush()
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (stepTools.isNotEmpty()) {
                    // 显示工具列表
                    stepTools.forEach { tool ->
                        // 根据工具状态选择颜色
                        val toolStatusColor = when (tool.status.lowercase()) {
                            "calling" -> colors.primary
                            "called", "completed" -> colors.success
                            "failed" -> colors.error
                            else -> colors.textSecondary
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(modifier = Modifier.width(18.dp))  // 缩进，对齐步骤描述
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = toolStatusColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))

                            val toolText = if (tool.function.isNotBlank()) {
                                "${tool.name} (${tool.function})：${getToolDescription(tool)}"
                            } else {
                                "${tool.name}：${getToolDescription(tool)}"
                            }

                            // 使用跑马灯效果显示工具文本
                            Box(
                                modifier = Modifier.weight(1f)
                            ) {
                                MarqueeText(
                                    text = toolText,
                                    fontSize = 12.sp,
                                    color = colors.textPrimary,  // 使用和 step 文本一样的颜色
                                    maxLines = 1,
                                    scrollDuration = 4000,  // 减少滚动时长，让滚动速度更快
                                    maxScrollOffset = 10.dp  // 限制最大滚动距离，防止超过工具图标
                                )
                            }
                        }
                    }
                } else {
                    // 显示提示信息
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(18.dp))  // 缩进，对齐步骤描述
                        Text(
                            text = "暂无工具调用",
                            fontSize = 12.sp,
                            color = colors.textHint
                        )
                    }
                }
            }
        }
    }
}

/**
 * 计划卡片组件（参考 DeepThinkingSection 样式）
 */
@Composable
fun PlanCard(
    steps: List<UIStep>,
    modifier: Modifier = Modifier,
    toolCalls: List<UIToolCall> = emptyList()
) {
    val colors = BaoziTheme.colors
    var isExpanded by remember { mutableStateOf(false) }

    // 存储每个step的展开状态
    val expandedStates = remember { mutableStateMapOf<Int, Boolean>() }

    Log.d(
        "PlanCard",
        "渲染 PlanCard, steps数量: ${steps.size}, expandedStates: $expandedStates"
    )

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
            // 标题行（可点击折叠/展开）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "执行计划",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${steps.size} 个步骤",
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 状态标识
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = when {
                                    steps.isEmpty() -> colors.textHint
                                    steps.any { it.status.lowercase() == "failed" } -> colors.error
                                    steps.any { it.status.lowercase() in listOf("in_progress", "running") } -> colors.primary
                                    steps.all { it.status.lowercase() == "completed" } -> colors.success
                                    steps.all { it.status.lowercase() == "pending" } -> colors.textHint
                                    else -> colors.primary
                                },
                                shape = CircleShape
                            )
                    )
                }
                Text(
                    text = if (isExpanded) "▼" else "▶",
                    fontSize = 12.sp,
                    color = colors.textSecondary
                )
            }

            // 步骤列表（仅在展开时显示）
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    expandFrom = Alignment.Top
                ),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Top
                )
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))

                    steps.forEachIndexed { index, step ->
                        key(step.id) {
                            StepCard(
                                step = step,
                                modifier = Modifier.fillMaxWidth(),
                                isExpanded = expandedStates[step.id] == true,
                                onExpandChanged = { expanded ->
                                    Log.d(
                                        "PlanCard",
                                        "Step ${step.id} 展开/折叠: $expanded"
                                    )
                                    expandedStates[step.id] = expanded
                                    Log.d(
                                        "PlanCard",
                                        "更新后 expandedStates: $expandedStates"
                                    )
                                },
                                toolCalls = toolCalls
                            )

                            if (index < steps.size - 1) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 计划悬浮球组件
 * 显示在屏幕右侧中间，点击后展开显示完整计划
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanFloatingButton(
    steps: List<UIStep>,
    modifier: Modifier = Modifier,
    toolCalls: List<UIToolCall> = emptyList()
) {
    val colors = BaoziTheme.colors
    var isExpanded by remember { mutableStateOf(false) }

    // 存储每个step的展开状态
    val expandedStates = remember { mutableStateMapOf<Int, Boolean>() }

    Log.d(
        "PlanFloatingButton",
        "渲染 PlanFloatingButton, steps数量: ${steps.size}, expandedStates: $expandedStates"
    )

    // 根据步骤状态计算悬浮球颜色
    val buttonColor = when {
        steps.isEmpty() -> colors.textHint
        steps.any { it.status.lowercase() == "failed" } -> colors.error
        steps.any { it.status.lowercase() in listOf("in_progress", "running") } -> colors.primary
        steps.all { it.status.lowercase() == "completed" } -> colors.success
        steps.all { it.status.lowercase() == "pending" } -> colors.textHint
        else -> colors.primary
    }

    // 展开的计划面板
    AnimatedVisibility(
        visible = isExpanded,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(300)
        ) + fadeIn(animationSpec = tween(300)),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(300)
        ) + fadeOut(animationSpec = tween(300))
    ) {
        Box(
            modifier = Modifier
                .width(340.dp)
                .heightIn(max = 600.dp)
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(colors.backgroundCard)
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // 标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(buttonColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = buttonColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "执行计划",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "${steps.size} 个步骤",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }
                    }
                    IconButton(
                        onClick = { isExpanded = false },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.backgroundInput.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                HorizontalDivider(
                    color = colors.backgroundInput,
                    thickness = 1.dp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 步骤列表
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(steps, key = { it.id }) { step ->
                        StepCard(
                            step = step,
                            modifier = Modifier.fillMaxWidth(),
                            isExpanded = expandedStates[step.id] == true,
                            onExpandChanged = { expanded ->
                                Log.d(
                                    "PlanFloatingButton",
                                    "Step ${step.id} 展开/折叠: $expanded"
                                )
                                expandedStates[step.id] = expanded
                                Log.d(
                                    "PlanFloatingButton",
                                    "更新后 expandedStates: $expandedStates"
                                )
                            },
                            toolCalls = toolCalls
                        )
                    }
                }
            }
        }
    }

    // 悬浮球
    Box(
        modifier = modifier
    ) {
        // 阴影层
        Box(
            modifier = Modifier
                .size(48.dp)
                .offset(x = 1.dp, y = 2.dp)
                .clip(CircleShape)
                .background(buttonColor.copy(alpha = 0.3f))
        )

        // 主按钮
        FloatingActionButton(
            onClick = { isExpanded = !isExpanded },
            containerColor = buttonColor,
            modifier = Modifier.size(48.dp),
            shape = CircleShape
        ) {
            AnimatedContent(
                targetState = isExpanded,
                transitionSpec = {
                    fadeIn(animationSpec = tween(150)) togetherWith fadeOut(
                        animationSpec = tween(
                            150
                        )
                    )
                },
                label = "icon"
            ) { expanded ->
                Icon(
                    imageVector = if (expanded) Icons.Default.Close else Icons.Default.Star,
                    contentDescription = if (expanded) "关闭计划" else "查看计划",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // 步骤数量徽章
        if (!isExpanded && steps.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-8).dp, y = (-4).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(colors.error)
                    .shadow(elevation = 4.dp, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${steps.size}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * 错误提示组件
 */
@Composable
fun ErrorCard(
    error: String,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.error.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = error,
                fontSize = 14.sp,
                color = colors.error
            )
        }
    }
}