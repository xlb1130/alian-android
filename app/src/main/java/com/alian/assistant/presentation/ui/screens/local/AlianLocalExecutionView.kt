package com.alian.assistant.presentation.ui.screens.local

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.data.ChatMessageData
import com.alian.assistant.data.ExecutionStep
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * 执行步骤视图 - 显示执行步骤列表和对话历史（按时间顺序混合展示）
 *
 * @param executionSteps 执行步骤列表
 * @param chatHistory 对话历史
 * @param instruction 用户初始输入
 * @param answer AI 最终回答
 * @param isRunning 是否正在运行
 * @param currentStep 当前步骤
 * @param currentModel 当前模型
 * @param listState 列表状态
 * @param modifier 修饰符
 */
@Composable
fun ExecutionStepsView(
    executionSteps: List<ExecutionStep>,
    chatHistory: List<ChatMessageData> = emptyList(),
    instruction: String = "",
    answer: String? = null,
    isRunning: Boolean,
    currentStep: Int,
    currentModel: String,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // 执行状态指示器
        if (isRunning) {
            ExecutingIndicator(currentStep = currentStep, currentModel = currentModel)
        }

        // 构建按时间顺序混合的时间线
        val timelineItems = buildTimelineItems(
            executionSteps = executionSteps,
            chatHistory = chatHistory,
            instruction = instruction,
            answer = answer
        )

        Log.d("ExecutionStepsView", "时间线项目数量: ${timelineItems.size}")

        // 显示时间线
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = timelineItems,
                key = { it.id }
            ) { item ->
                when (item) {
                    is TimelineItem.MessageItem -> ConversationMessageItem(item.message)
                    is TimelineItem.StepItem -> StepCard(item.step)
                }
            }
        }
    }
}

/**
 * 时间线项目
 */
sealed class TimelineItem {
    data class MessageItem(val message: ConversationMessage) : TimelineItem()
    data class StepItem(val step: ExecutionStep) : TimelineItem()
    
    val id: String
        get() = when (this) {
            is MessageItem -> "msg_${message.timestamp}_${message.hashCode()}"
            is StepItem -> "step_${step.id}"
        }
}

/**
 * 构建时间线项目（按时间顺序混合对话消息和执行步骤）
 */
fun buildTimelineItems(
    executionSteps: List<ExecutionStep>,
    chatHistory: List<ChatMessageData>,
    instruction: String,
    answer: String?
): List<TimelineItem> {
    val items = mutableListOf<TimelineItem>()

    // 添加初始输入
    if (instruction.isNotEmpty()) {
        items.add(TimelineItem.MessageItem(ConversationMessage(
            content = instruction,
            isUser = true,
            label = "👤 用户",
            timestamp = 0L  // 初始输入时间为 0，确保它在最前面
        )))
    }

    // 添加对话历史
    chatHistory.forEach { msg ->
        items.add(TimelineItem.MessageItem(ConversationMessage(
            content = msg.content,
            isUser = msg.role == "USER",
            label = if (msg.role == "USER") "👤 用户" else "🤖 AI",
            timestamp = msg.timestamp
        )))
    }

    // 添加执行步骤
    executionSteps.forEach { step ->
        items.add(TimelineItem.StepItem(step))
    }

    // 添加最终回答
    if (answer != null && answer.isNotEmpty()) {
        items.add(TimelineItem.MessageItem(ConversationMessage(
            content = answer,
            isUser = false,
            label = "🤖 AI",
            timestamp = Long.MAX_VALUE  // 最终回答时间为最大值，确保它在最后面
        )))
    }

    // 按时间戳排序
    items.sortBy { 
        when (it) {
            is TimelineItem.MessageItem -> it.message.timestamp
            is TimelineItem.StepItem -> it.step.timestamp
        }
    }

    return items
}

/**
 * 对话消息数据类
 */
data class ConversationMessage(
    val content: String,
    val isUser: Boolean,
    val label: String,
    val timestamp: Long
)

/**
 * 对话消息项
 *
 * @param message 消息内容
 */
@Composable
fun ConversationMessageItem(message: ConversationMessage) {
    val colors = BaoziTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (message.isUser) {
                        colors.primary.copy(alpha = 0.15f)
                    } else {
                        colors.backgroundCard.copy(alpha = 0.8f)
                    }
                )
                .padding(12.dp)
        ) {
            Column {
                // 发送者标识
                Text(
                    text = message.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (message.isUser) colors.primary else colors.textSecondary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // 消息内容
                Text(
                    text = message.content,
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

/**
 * 执行状态指示器
 * 
 * @param currentStep 当前步骤
 * @param currentModel 当前模型
 */
@Composable
fun ExecutingIndicator(
    currentStep: Int,
    currentModel: String
) {
    val colors = BaoziTheme.colors
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 横行来回跑动的进度条
        rememberInfiniteTransition(label = "progress").let { infiniteTransition ->
            val progress by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        1500,
                        easing = EaseInOutSine
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "progress"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        colors.primary.copy(alpha = 0.15f),
                        RoundedCornerShape(2.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(
                            colors.primary,
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }
        
        // 状态文本
        Text(
            text = "正在执行步骤 $currentStep · $currentModel",
            fontSize = 12.sp,
            color = colors.textSecondary
        )
    }
}

/**
 * 步骤卡片组件
 * 
 * @param step 执行步骤数据
 */
@Composable
fun StepCard(step: ExecutionStep) {
    val colors = BaoziTheme.colors
    var isExpanded by remember { mutableStateOf(step.isExpanded) }

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
        parts.add("文本: ${step.actionText.take(20)}")
    }
    
    if (!step.actionButton.isNullOrEmpty()) {
        parts.add("按键: ${step.actionButton}")
    }
    
    if (step.actionDuration != null) {
        parts.add("等待: ${step.actionDuration}秒")
    }
    
    if (!step.actionMessage.isNullOrEmpty()) {
        parts.add("消息: ${step.actionMessage.take(20)}")
    }
    
    return parts.joinToString(" | ")
}