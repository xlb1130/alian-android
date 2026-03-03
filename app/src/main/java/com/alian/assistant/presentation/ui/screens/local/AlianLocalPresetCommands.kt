package com.alian.assistant.presentation.ui.screens.local

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.R
import com.alian.assistant.common.constant.AnimationConstants
import com.alian.assistant.presentation.ui.screens.components.HapticUtils
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.common.utils.AccessibilityUtils
import com.alian.assistant.common.utils.PermissionManager
import java.util.Calendar

/**
 * 预设命令数据类 - 使用字符串资源ID
 */
data class PresetCommand(
    val icon: String,
    @StringRes val titleResId: Int,
    @StringRes val commandResId: Int
)

/**
 * 完整预设命令列表 - 使用资源ID
 */
val allPresetCommands = listOf(
    PresetCommand("🍔", R.string.preset_burger, R.string.preset_burger_cmd),
    PresetCommand("📕", R.string.preset_xiaohongshu, R.string.preset_xiaohongshu_cmd),
    PresetCommand("📺", R.string.preset_bilibili, R.string.preset_bilibili_cmd),
    PresetCommand("✈️", R.string.preset_travel, R.string.preset_travel_cmd),
    PresetCommand("🎵", R.string.preset_music, R.string.preset_music_cmd),
    PresetCommand("🛒", R.string.preset_takeout, R.string.preset_takeout_cmd),
    PresetCommand("📱", R.string.preset_clean_background, R.string.preset_clean_background_cmd),
    PresetCommand("🔋", R.string.preset_power_saving, R.string.preset_power_saving_cmd),
    PresetCommand("🌙", R.string.preset_night_mode, R.string.preset_night_mode_cmd),
    PresetCommand("📸", R.string.preset_camera, R.string.preset_camera_cmd),
    PresetCommand("🏃", R.string.preset_exercise, R.string.preset_exercise_cmd),
    PresetCommand("☕", R.string.preset_coffee, R.string.preset_coffee_cmd),
    PresetCommand("🚗", R.string.preset_taxi, R.string.preset_taxi_cmd),
    PresetCommand("🏠", R.string.preset_smart_home, R.string.preset_smart_home_cmd),
    PresetCommand("📅", R.string.preset_schedule, R.string.preset_schedule_cmd),
    PresetCommand("🔔", R.string.preset_alarm, R.string.preset_alarm_cmd),
    PresetCommand("🗺️", R.string.preset_navigation, R.string.preset_navigation_cmd),
    PresetCommand("🌤️", R.string.preset_weather, R.string.preset_weather_cmd),
    PresetCommand("💬", R.string.preset_wechat, R.string.preset_wechat_cmd),
    PresetCommand("📝", R.string.preset_notes, R.string.preset_notes_cmd)
)

/**
 * 随机获取8个预设命令（适配轮播显示）
 */
fun getRandomPresetCommands(): List<PresetCommand> {
    return allPresetCommands.shuffled().take(8)
}

/**
 * 预设命令视图 - 显示预设命令卡片
 * 
 * @param context 上下文
 * @param onCommandClick 命令点击回调
 * @param modifier 修饰符
 * @param shizukuAvailable Shizuku 是否可用
 * @param onRefreshShizuku 刷新 Shizuku 回调
 * @param executionStrategy 执行策略
 * @param accessibilityEnabled 无障碍服务是否已启用
 * @param mediaProjectionAvailable MediaProjection 是否可用
 * @param isMediaProjectionAvailable MediaProjection 权限检查回调
 */
@Composable
fun PresetCommandsView(
    context: Context,
    onCommandClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    shizukuAvailable: Boolean = true,
    onRefreshShizuku: () -> Unit = {},
    executionStrategy: String = "auto",
    accessibilityEnabled: Boolean = false,
    mediaProjectionAvailable: Boolean = false,
    isMediaProjectionAvailable: () -> Boolean = { false }
) {
    val colors = BaoziTheme.colors
    val presetCommands = remember { getRandomPresetCommands() }

    // 实时检查无障碍服务权限状态
    val realAccessibilityEnabled = AccessibilityUtils.isAccessibilityServiceEnabled(context)
    // 实时检查 MediaProjection 权限状态
    val realMediaProjectionAvailable = isMediaProjectionAvailable()

    // 添加日志用于调试
    // Log.d("PresetCommandsView", "PresetCommandsView - executionStrategy: $executionStrategy, shizukuAvailable: $shizukuAvailable, accessibilityEnabled: $accessibilityEnabled, realAccessibilityEnabled: $realAccessibilityEnabled, mediaProjectionAvailable: $mediaProjectionAvailable, realMediaProjectionAvailable: $realMediaProjectionAvailable")

    // 使用 PermissionManager 获取执行提示信息（使用实时检查的权限状态）
    val promptInfo = PermissionManager.getExecutionPromptInfo(
        context = context,
        executionStrategy = executionStrategy,
        shizukuAvailable = shizukuAvailable,
        accessibilityEnabled = realAccessibilityEnabled,
        mediaProjectionAvailable = realMediaProjectionAvailable
    )
    
    // 获取时间感知问候语 - 使用 Composable 函数
    val greeting = rememberTimeBasedGreeting()
    
    // 呼吸动画
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingAlpha"
    )
    
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // 背景装饰 - 淡雅的渐变圆形
        BackgroundDecoration(colors = colors, breathingAlpha = breathingAlpha)
        
        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            
            // 时间感知问候语 - 更自然亲切（单独加 padding，不影响轮播区域）
            if (promptInfo.canExecute) {
                Text(
                    text = greeting.emoji,
                    fontSize = 36.sp,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .graphicsLayer { alpha = breathingAlpha }
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = greeting.text(),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = stringResource(R.string.greeting_prompt),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    color = colors.textSecondary.copy(alpha = 0.7f),
                    letterSpacing = 0.3.sp,
                    modifier = Modifier.padding(horizontal = 24.dp)   
                )
            } else {
                // 权限缺失时的友好提示卡片
                PermissionHintCard(
                    title = promptInfo.title,
                    description = promptInfo.description,
                    showRefreshButton = promptInfo.showRefreshButton,
                    onRefresh = {
                        HapticUtils.performLightHaptic(context)
                        onRefreshShizuku()
                    },
                    colors = colors
                )
            }
            
            Spacer(modifier = Modifier.height(28.dp))

            // 使用堆叠轮播视图显示预设命令（不受外层 padding 限制，贴满屏幕边缘）
            StackedCarouselView(
                presetCommands = presetCommands,
                onCommandClick = onCommandClick,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 预设命令卡片 - 单个预设命令卡片
 * 
 * @param context 上下文
 * @param preset 预设命令数据
 * @param onClick 点击回调
 * @param modifier 修饰符
 */
@Composable
fun PresetCommandCard(
    context: Context,
    preset: PresetCommand,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors
    val presetInteractionSource = remember { MutableInteractionSource() }
    val presetIsPressed by presetInteractionSource.collectIsPressedAsState()
    val presetIsHovered by presetInteractionSource.collectIsHoveredAsState()

    // 悬停上浮动画
    val translationY by animateDpAsState(
        targetValue = when {
            presetIsPressed -> 2.dp
            presetIsHovered -> (-2).dp
            else -> 0.dp
        },
        animationSpec = spring(
            dampingRatio = AnimationConstants.SpringDampingRatioMediumBouncy,
            stiffness = AnimationConstants.SpringStiffnessLow
        ),
        label = "translationY"
    )

    Card(
        modifier = modifier
            .offset(y = translationY)
            .drawBehind {
                // 微妙的边框描边（悬停时增强主色调效果）
                drawRoundRect(
                    color = colors.primary.copy(
                        alpha = if (presetIsHovered) 0.25f else 0.08f
                    ),
                    style = Stroke(
                        width = if (presetIsHovered) 1.dp.toPx() else 0.5.dp.toPx()
                    ),
                    cornerRadius = CornerRadius(20.dp.toPx())
                )
            }
            .clickable(
                interactionSource = presetInteractionSource,
                indication = null,
                onClick = {
                    HapticUtils.performLightHaptic(context)
                    onClick()
                }
            ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 12.dp,
            pressedElevation = 8.dp,
            hoveredElevation = 20.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                presetIsPressed -> colors.primary.copy(alpha = 0.12f)
                presetIsHovered -> colors.backgroundCardHover
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
            Text(
                text = preset.icon,
                fontSize = 26.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(preset.titleResId),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Text(
                    text = stringResource(preset.commandResId),
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 时间感知问候语数据类 - 使用资源ID
 */
data class TimeGreeting(
    val emoji: String,
    @StringRes val textResId: Int
)

/**
 * 根据当前时间获取问候语 - Composable 函数
 */
@Composable
fun rememberTimeBasedGreeting(): TimeGreeting {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    return when {
        hour in 5..8 -> TimeGreeting("🌅", R.string.greeting_morning)
        hour in 9..11 -> TimeGreeting("☀️", R.string.greeting_forenoon)
        hour in 12..13 -> TimeGreeting("🌞", R.string.greeting_noon)
        hour in 14..17 -> TimeGreeting("🌤️", R.string.greeting_afternoon)
        hour in 18..21 -> TimeGreeting("🌆", R.string.greeting_evening)
        else -> TimeGreeting("🌙", R.string.greeting_night)
    }
}

/**
 * 扩展属性：获取时间问候语文本
 */
@Composable
fun TimeGreeting.text(): String = stringResource(textResId)

/**
 * 背景装饰组件 - 淡雅的渐变圆形装饰
 */
@Composable
fun BackgroundDecoration(
    colors: com.alian.assistant.presentation.ui.theme.BaoziColors,
    breathingAlpha: Float
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 右上角装饰圆
        Box(
            modifier = Modifier
                .size(200.dp)
                .offset(x = 120.dp, y = (-60).dp)
                .alpha(0.04f * breathingAlpha)
                .blur(60.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colors.primary,
                            colors.primary.copy(alpha = 0f)
                        )
                    )
                )
        )
        
        // 左下角装饰圆
        Box(
            modifier = Modifier
                .size(180.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-60).dp, y = 80.dp)
                .alpha(0.03f * breathingAlpha)
                .blur(50.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colors.secondary,
                            colors.secondary.copy(alpha = 0f)
                        )
                    )
                )
        )
    }
}

/**
 * 权限提示组件 - 简洁无背景设计
 */
@Composable
fun PermissionHintCard(
    title: String,
    description: String,
    showRefreshButton: Boolean,
    onRefresh: () -> Unit,
    colors: com.alian.assistant.presentation.ui.theme.BaoziColors
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hint")
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "iconScale"
    )
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 警告图标
        Text(
            text = "⚠️",
            fontSize = 36.sp,
            modifier = Modifier.graphicsLayer {
                scaleX = iconScale
                scaleY = iconScale
            }
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // 标题
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 描述
        Text(
            text = description,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            color = colors.textSecondary.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        
        // 刷新按钮
        if (showRefreshButton) {
            Spacer(modifier = Modifier.height(16.dp))
            
            val refreshInteractionSource = remember { MutableInteractionSource() }
            val refreshIsPressed by refreshInteractionSource.collectIsPressedAsState()
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (refreshIsPressed) colors.primary.copy(alpha = 0.15f)
                        else colors.primary.copy(alpha = 0.1f)
                    )
                    .clickable(
                        interactionSource = refreshInteractionSource,
                        indication = null,
                        onClick = onRefresh
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.preset_reconnect),
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.preset_reconnect),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary
                    )
                }
            }
        }
    }
}