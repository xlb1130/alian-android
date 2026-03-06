package com.alian.assistant.presentation.ui.screens.online

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.viewmodel.AlianChatState
import com.alian.assistant.presentation.viewmodel.AlianViewModel
import com.alian.assistant.presentation.viewmodel.DeepThinkingItem
import com.alian.assistant.presentation.viewmodel.MessageItem
import com.alian.assistant.presentation.viewmodel.PlanItem
import com.alian.assistant.presentation.viewmodel.SessionLoadingState
import java.io.File

/**
 * Alian 聊天消息列表 - 统一的消息列表渲染组件
 * 
 * @param viewModel AlianViewModel
 * @param listState 列表状态
 * @param shouldPinPlanCard 是否吸顶 PlanCard
 * @param ttsEnabled 是否启用 TTS
 * @param currentPlayingMessageId 当前播放的消息 ID
 * @param onPlayClick 播放点击回调
 * @param onStopClick 停止点击回调
 * @param onLinkClick 链接点击回调（url, fileName, fileId）
 * @param userAvatar 用户头像
 * @param assistantAvatar 艾莲头像
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlianChatMessageList(
    viewModel: AlianViewModel,
    listState: LazyListState,
    shouldPinPlanCard: Boolean,
    ttsEnabled: Boolean,
    enableItemAnimations: Boolean,
    currentPlayingMessageId: State<String?>,
    onPlayClick: (String) -> Unit,
    onStopClick: () -> Unit,
    onLinkClick: (String, String, String?) -> Unit,
    userAvatar: String?,
    assistantAvatar: String?
) {
    val colors = BaoziTheme.colors

    // 过滤后的时间线和PlanItem（避免嵌套derivedStateOf导致的Snapshot错误）
    val timelineData by remember {
        derivedStateOf {
            val filtered = viewModel.unifiedChatTimeline.toList().filterNot { item ->
                item is MessageItem &&
                    !item.message.isUser &&
                    item.message.content.isBlank() &&
                    item.message.attachments.isEmpty()
            }
            val plan = filtered.filterIsInstance<PlanItem>().firstOrNull()
            Pair(filtered, plan)
        }
    }

    val filteredTimeline = timelineData.first
    val planItem: PlanItem? = timelineData.second

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // PlanCard吸顶显示
        if (planItem != null && shouldPinPlanCard) {
            stickyHeader {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.background)
                ) {
                    PlanCard(
                        steps = planItem!!.planEvent.steps,
                        modifier = Modifier.fillMaxWidth(),
                        toolCalls = viewModel.toolCalls
                    )
                }
            }
        }

        // 使用统一的时间线渲染所有内容
        itemsIndexed(filteredTimeline, key = { index, item ->
            when (item) {
                is MessageItem -> "msg_${item.message.id}"
                is DeepThinkingItem -> "thinking_${item.section.eventId}_$index"
                is PlanItem -> "plan_${item.planEvent.eventId}_$index"
            }
        }) { index, item ->
            // 当吸顶时跳过PlanItem
            if (shouldPinPlanCard && item is PlanItem) {
                return@itemsIndexed
            }

            when (item) {
                is MessageItem -> {
                    if (!enableItemAnimations) {
                        ChatMessageBubble(
                            message = item.message,
                            ttsEnabled = ttsEnabled,
                            isPlaying = currentPlayingMessageId.value == item.message.id,
                            onPlayClick = onPlayClick,
                            onStopClick = onStopClick,
                            onLinkClick = onLinkClick,
                            backendBaseUrl = viewModel.backendBaseUrl,
                            userAvatar = userAvatar,
                            assistantAvatar = assistantAvatar
                        )
                    } else {
                        var isVisible by remember { mutableStateOf(false) }
                        LaunchedEffect(item.message.id) {
                            isVisible = true
                        }

                        AnimatedVisibility(
                            visible = isVisible,
                            enter = slideInVertically(
                                initialOffsetY = { 30 },
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            ) + fadeIn(
                                animationSpec = tween(durationMillis = 300)
                            )
                        ) {
                            ChatMessageBubble(
                                message = item.message,
                                ttsEnabled = ttsEnabled,
                                isPlaying = currentPlayingMessageId.value == item.message.id,
                                onPlayClick = onPlayClick,
                                onStopClick = onStopClick,
                                onLinkClick = onLinkClick,
                                backendBaseUrl = viewModel.backendBaseUrl,
                                userAvatar = userAvatar,
                                assistantAvatar = assistantAvatar
                            )
                        }
                    }
                }

                is DeepThinkingItem -> {
                    var isVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(item.section.eventId) {
                        isVisible = true
                    }

                    AnimatedVisibility(
                        visible = isVisible,
                        enter = slideInVertically(
                            initialOffsetY = { 30 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + fadeIn(
                            animationSpec = tween(durationMillis = 300)
                        )
                    ) {
                        DeepThinkingSection(
                            section = item.section
                        )
                    }
                }

                is PlanItem -> {
                    var isVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(item.planEvent.eventId) {
                        isVisible = true
                    }

                    AnimatedVisibility(
                        visible = isVisible,
                        enter = slideInVertically(
                            initialOffsetY = { 30 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + fadeIn(
                            animationSpec = tween(durationMillis = 300)
                        )
                    ) {
                        PlanCard(
                            steps = item.planEvent.steps,
                            modifier = Modifier.fillMaxWidth(),
                            toolCalls = viewModel.toolCalls
                        )
                    }
                }
            }
        }
        // 加载气泡 - 当正在处理时显示
        if (viewModel.isProcessing.value) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.Top
                ) {
                    // AI 头像
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

                    // 加载气泡
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
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
val currentToolName = viewModel.currentToolName.value
                            val currentStepName = viewModel.currentStepName.value

                            // 调试日志
                            Log.d("AlianChatMessageList", "加载气泡渲染 - currentToolName: $currentToolName, currentStepName: $currentStepName, isProcessing: ${viewModel.isProcessing.value}")

                            // 根据当前状态显示不同的文本
                            val displayText = when {
                                currentToolName != null -> "正在执行 $currentToolName"
                                currentStepName != null -> "正在 $currentStepName"
                                else -> "思考中"
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = displayText,
                                    fontSize = 13.sp,
                                    color = colors.textSecondary
                                )
                                // 三个点动画
                                rememberInfiniteTransition(label = "loadingDots").let { infiniteTransition ->
                                    val dot1Scale by infiniteTransition.animateFloat(
                                        initialValue = 0.6f,
                                        targetValue = 1.0f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(
                                                400,
                                                easing = FastOutSlowInEasing
                                            ),
                                            repeatMode = RepeatMode.Reverse,
                                            initialStartOffset = StartOffset(0)
                                        ),
                                        label = "dot1"
                                    )

                                    val dot2Scale by infiniteTransition.animateFloat(
                                        initialValue = 0.6f,
                                        targetValue = 1.0f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(
                                                400,
                                                easing = FastOutSlowInEasing
                                            ),
                                            repeatMode = RepeatMode.Reverse,
                                            initialStartOffset = StartOffset(133)
                                        ),
                                        label = "dot2"
                                    )

                                    val dot3Scale by infiniteTransition.animateFloat(
                                        initialValue = 0.6f,
                                        targetValue = 1.0f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(
                                                400,
                                                easing = FastOutSlowInEasing
                                            ),
                                            repeatMode = RepeatMode.Reverse,
                                            initialStartOffset = StartOffset(266)
                                        ),
                                        label = "dot3"
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        listOf(dot1Scale, dot2Scale, dot3Scale).forEach { scale ->
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .scale(scale)
                                                    .background(
                                                        colors.textSecondary.copy(alpha = 0.6f),
                                                        CircleShape
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Alian 聊天内容区域 - 包含状态通知和消息列表
 * 
 * @param viewModel AlianViewModel
 * @param listState 列表状态
 * @param shouldPinPlanCard 是否吸顶 PlanCard
 * @param ttsEnabled 是否启用 TTS
 * @param onPlayClick 播放点击回调
 * @param onStopClick 停止点击回调
 * @param onLinkClick 链接点击回调（url, fileName, fileId）
 * @param userAvatar 用户头像
 * @param assistantAvatar 艾莲头像
 */
@Composable
fun AlianChatContent(
    viewModel: AlianViewModel,
    listState: LazyListState,
    shouldPinPlanCard: Boolean,
    ttsEnabled: Boolean,
    onPlayClick: (String) -> Unit,
    onStopClick: () -> Unit,
    onLinkClick: (String, String, String?) -> Unit,
    userAvatar: String?,
    assistantAvatar: String?
) {
    val colors = BaoziTheme.colors
    val sessionLoadingState = viewModel.sessionLoadingState.value
    val hasRenderableItems = viewModel.unifiedChatTimeline.any { item ->
        when (item) {
            is MessageItem -> {
                item.message.isUser ||
                    item.message.content.isNotBlank() ||
                    item.message.attachments.isNotEmpty()
            }
            else -> true
        }
    }

    // 状态通知文本
    val statusText = when {
        viewModel.chatState.value is AlianChatState.Loading -> "正在思考中..."
        viewModel.isProcessing.value -> "正在处理..."
        else -> null
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 系统通知样式的小灰字
        if (statusText != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    color = colors.textHint,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        when (sessionLoadingState) {
            is SessionLoadingState.Switching -> {
                if (!hasRenderableItems) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = colors.primary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "正在加载历史消息...",
                                fontSize = 13.sp,
                                color = colors.textSecondary
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = colors.primary,
                                strokeWidth = 1.5.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "历史仍在补充加载中...",
                                fontSize = 12.sp,
                                color = colors.textHint
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            AlianChatMessageList(
                                viewModel = viewModel,
                                listState = listState,
                                shouldPinPlanCard = shouldPinPlanCard,
                                ttsEnabled = ttsEnabled,
                                enableItemAnimations = false,
                                currentPlayingMessageId = viewModel.currentPlayingMessageId,
                                onPlayClick = onPlayClick,
                                onStopClick = onStopClick,
                                onLinkClick = onLinkClick,
                                userAvatar = userAvatar,
                                assistantAvatar = assistantAvatar
                            )
                        }
                    }
                }
            }

            is SessionLoadingState.Failed -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = sessionLoadingState.message,
                            fontSize = 13.sp,
                            color = colors.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.retryCurrentSessionLoad() }) {
                            Text("重试", color = colors.primary)
                        }
                    }
                }
            }

            else -> {
                if (!hasRenderableItems) {
                    // 空状态
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        EmptyChatState(modifier = Modifier.fillMaxSize())
                    }
                } else {
                    // 消息列表
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        AlianChatMessageList(
                            viewModel = viewModel,
                            listState = listState,
                            shouldPinPlanCard = shouldPinPlanCard,
                            ttsEnabled = ttsEnabled,
                            enableItemAnimations = true,
                            currentPlayingMessageId = viewModel.currentPlayingMessageId,
                            onPlayClick = onPlayClick,
                            onStopClick = onStopClick,
                            onLinkClick = onLinkClick,
                            userAvatar = userAvatar,
                            assistantAvatar = assistantAvatar
                        )
                    }
                }
            }
        }
    }
}
