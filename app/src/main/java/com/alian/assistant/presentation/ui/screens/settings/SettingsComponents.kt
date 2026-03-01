package com.alian.assistant.presentation.ui.screens.settings

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.alian.assistant.R
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.ui.theme.GradientPrimary
import com.alian.assistant.presentation.ui.theme.GradientPrimaryVertical

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

/**
 * 带渐变边框的卡片
 */
@Composable
fun GradientBorderCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Card(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            performLightHaptic(context)
                            onClick()
                        }
                    )
                } else {
                    Modifier
                }
            )
            .drawBehind {
                val strokeWidth = 2.dp.toPx()
                val gradient = GradientPrimary
                drawRoundRect(
                    brush = gradient,
                    style = Stroke(width = strokeWidth),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            }
            .scale(if (isPressed) 0.98f else 1f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.backgroundCard
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isPressed) 2.dp else 0.dp
        )
    ) {
        Column(content = content)
    }
}

/**
 * 渐变图标容器
 */
@Composable
fun GradientIconContainer(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    shape: Shape = RoundedCornerShape(10.dp)
) {
    val colors = BaoziTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(GradientPrimaryVertical),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 脉冲动画图标容器
 */
@Composable
fun PulsingIconContainer(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp
) {
    val colors = BaoziTheme.colors
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // 脉冲光晕
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primary.copy(alpha = alpha))
        )
        // 主图标容器
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(GradientPrimaryVertical),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * 设置分组标题
 */
@Composable
fun SettingsSection(title: String) {
    val colors = BaoziTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧渐变装饰条
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(GradientPrimary)
        )
        Spacer(modifier = Modifier.width(12.dp))
        // 渐变文字标题
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(
                brush = GradientPrimary
            )
        )
    }
}

/**
 * 设置项
 */
@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .scale(if (isPressed) 0.98f else 1f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    performLightHaptic(context)
                    onClick()
                }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isPressed) 2.dp else 0.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 使用渐变图标容器
            GradientIconContainer(
                icon = icon,
                size = 40.dp,
                iconSize = 22.dp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    maxLines = 1
                )
            }
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textHint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 登录状态卡片
 */
@Composable
fun LoginStatusCard(
    isLoggedIn: Boolean,
    userEmail: String? = null,
    useBackend: Boolean = false,
    avatarUri: String? = null,
    isSavingAvatar: Boolean = false,
    onAvatarClick: (() -> Unit)? = null
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "avatar-glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow-alpha"
    )
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow-scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .drawBehind {
                // 渐变边框
                val strokeWidth = 1.5.dp.toPx()
                drawRoundRect(
                    brush = GradientPrimary,
                    style = Stroke(width = strokeWidth),
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.backgroundCard.copy(alpha = 0.95f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp),
                contentAlignment = Alignment.Center
            ) {
                // 头像光环效果
                if (isLoggedIn) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(glowScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        colors.primary.copy(alpha = glowAlpha),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }

                // 状态指示器
                if (isLoggedIn) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = (-5).dp, y = (-5).dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(colors.success)
                            .border(2.dp, colors.backgroundCard, CircleShape)
                    )
                }

                // 头像容器
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.15f))
                        .then(
                            if (onAvatarClick != null && !isSavingAvatar) {
                                Modifier.clickable {
                                    performLightHaptic(context)
                                    onAvatarClick()
                                }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSavingAvatar) {
                        // 加载状态
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = colors.primary,
                            strokeWidth = 3.dp
                        )
                    } else if (isLoggedIn && avatarUri != null) {
                        // 自定义头像
                        AsyncImage(
                            model = avatarUri,
                            contentDescription = "用户头像",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else if (isLoggedIn) {
                        // 默认头像图片
                        Image(
                            painter = painterResource(id = R.drawable.default_user_avatar),
                            contentDescription = "默认用户头像",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = "?",
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textHint
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isLoggedIn) "已登录" else "未登录",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isLoggedIn) colors.textPrimary else colors.textSecondary,
                style = if (isLoggedIn) {
                    TextStyle(brush = GradientPrimary)
                } else TextStyle.Default
            )
            Text(
                text = when {
                    isLoggedIn && userEmail != null -> userEmail.take(20) + if (userEmail.length > 20) "..." else ""
                    isLoggedIn -> "Backend 模式已登录"
                    useBackend -> "请先登录 Alian 账号"
                    else -> "使用 OpenAI 兼容 API"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
                maxLines = 1
            )
        }
    }
}

/**
 * 网格设置项（用于 2x2 网格布局）
 */
@Composable
fun GridSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (isPressed) 0.98f else 1f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    performLightHaptic(context)
                    onClick()
                }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isPressed) 2.dp else 0.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 使用渐变图标容器
            GradientIconContainer(
                icon = icon,
                size = 48.dp,
                iconSize = 26.dp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = colors.textSecondary,
                maxLines = 2
            )
        }
    }
}

/**
 * 紧凑网格项（用于单行 4 列布局，无背景）
 */
@Composable
fun CompactGridItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    subtitle: String? = null
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (isPressed && enabled) 0.95f else 1f)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    performLightHaptic(context)
                    onClick()
                }
            )
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center
        ) {
            // 禁用状态使用灰度滤镜效果
            if (enabled) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.textHint.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) colors.textPrimary else colors.textHint.copy(alpha = 0.5f)
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = if (enabled) colors.textSecondary else colors.textHint.copy(alpha = 0.5f),
                maxLines = 1
            )
        }
    }
}