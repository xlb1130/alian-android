package com.alian.assistant.presentation.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.presentation.ui.theme.BaoziColors
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Alian 独立登录页面 - 现代简洁美化版本
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlianLoginScreen(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit = {}
) {
    Log.d("AlianLoginScreen", "AlianLoginScreen被渲染, email: $email, isLoading: $isLoading")
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    var passwordVisible by remember { mutableStateOf(false) }

    // 支持侧边屏划屏返回
    BackHandler(enabled = true) {
        performLightHaptic(context)
        onBack()
    }

    // 装饰性动画圆形
    val infiniteTransition = rememberInfiniteTransition(label = "decoration")
    val animatedOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 背景装饰性几何图形
        DecorativeBackgroundCircles(
            colors = colors,
            animatedOffset = animatedOffset
        )

        // 顶部返回按钮 - 无背景样式
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .size(44.dp)
                .clickable {
                    performLightHaptic(context)
                    onBack()
                }
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowLeft,
                contentDescription = "返回",
                tint = colors.textPrimary,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            )
        }

        // 登录内容 - 直接显示
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 品牌 Logo
            BrandLogo(colors = colors)

            Spacer(modifier = Modifier.height(24.dp))

            // 标题 - 优化排版
            Text(
                text = "Alian",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = colors.primary,
                letterSpacing = 1.2.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 副标题 - 更柔和
            Text(
                text = "欢迎回来，请登录您的账号",
                fontSize = 15.sp,
                color = colors.textSecondary,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.3.sp
            )

            Spacer(modifier = Modifier.height(40.dp))

            // 邮箱输入 - 增强交互反馈
            AnimatedInputField(
                value = email,
                onValueChange = onEmailChange,
                label = "邮箱地址",
                leadingIcon = Icons.Default.Email,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                colors = colors,
                context = context
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 密码输入 - 增强交互反馈
            AnimatedInputField(
                value = password,
                onValueChange = {
                    onPasswordChange(it)
                    performLightHaptic(context)
                },
                label = "密码",
                leadingIcon = Icons.Default.Lock,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            performLightHaptic(context)
                            passwordVisible = !passwordVisible
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            tint = colors.textSecondary
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = colors,
                context = context
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 忘记密码链接 - 优化样式
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "忘记密码？",
                    fontSize = 14.sp,
                    color = colors.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        performLightHaptic(context)
                        // TODO: 实现忘记密码功能
                    }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 错误信息 - 优化动画
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ) + fadeIn(
                    animationSpec = tween(300)
                ),
                exit = shrinkVertically() + fadeOut()
            ) {
                errorMessage?.let {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(
                                elevation = 4.dp,
                                spotColor = colors.error.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(14.dp)
                            ),
                        shape = RoundedCornerShape(14.dp),
                        color = colors.error.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = it,
                            color = colors.error,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 登录按钮 - 增强交互反馈
            ModernLoginButton(
                text = "登录",
                isLoading = isLoading,
                enabled = email.isNotBlank() && password.isNotBlank(),
                onClick = {
                    Log.d("AlianLoginScreen", "登录按钮被点击")
                    Log.d("AlianLoginScreen", "email: $email, password: ${password.take(3)}***, isLoading: $isLoading")
                    performLightHaptic(context)
                    onLogin()
                },
                colors = colors
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 分割线
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Divider(
                    modifier = Modifier.weight(1f),
                    color = colors.textHint.copy(alpha = 0.3f),
                    thickness = 1.dp
                )
                Text(
                    text = "或",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.Medium
                )
                Divider(
                    modifier = Modifier.weight(1f),
                    color = colors.textHint.copy(alpha = 0.3f),
                    thickness = 1.dp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 社交登录按钮
            SocialLoginButtons(colors = colors, context = context)

            Spacer(modifier = Modifier.height(28.dp))

            // 注册引导 - 优化样式
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "还没有账号？",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.Normal
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "立即注册",
                    fontSize = 14.sp,
                    color = colors.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        performLightHaptic(context)
                        // TODO: 实现注册功能
                    }
                )
            }
        }
    }
}

/**
 * 品牌Logo组件
 */
@Composable
private fun BrandLogo(colors: BaoziColors) {
    val infiniteTransition = rememberInfiniteTransition(label = "logo")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoScale"
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(scale),
        contentAlignment = Alignment.Center
    ) {
        // 外圈
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colors.primary.copy(alpha = 0.2f),
                            colors.primary.copy(alpha = 0.05f)
                        )
                    ),
                    shape = CircleShape
                )
        )
        // 内圈
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            colors.primary,
                            colors.primaryLight
                        )
                    ),
                    shape = CircleShape
                )
                .shadow(
                    elevation = 8.dp,
                    spotColor = colors.primary.copy(alpha = 0.4f),
                    ambientColor = colors.primary.copy(alpha = 0.2f)
                )
        )
        // 图标
        Text(
            text = "A",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/**
 * 装饰性背景圆形
 */
@Composable
private fun DecorativeBackgroundCircles(
    colors: BaoziColors,
    animatedOffset: Float
) {
    val xOffset1 = sin(animatedOffset * 2 * PI) * 100
    val yOffset1 = cos(animatedOffset * 2 * PI) * 80
    val xOffset2 = sin(animatedOffset * 2 * PI + PI) * 120
    val yOffset2 = cos(animatedOffset * 2 * PI + PI) * 100

    // 左上角装饰圆
    Box(
        modifier = Modifier
            .offset(x = (-150 + xOffset1).dp, y = (-150 + yOffset1).dp)
            .size(300.dp)
            .graphicsLayer { alpha = 0.03f }
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.primary,
                        Color.Transparent
                    )
                ),
                shape = CircleShape
            )
    )

    // 右下角装饰圆
    Box(
        modifier = Modifier
            .offset(x = (150 + xOffset2).dp, y = (150 + yOffset2).dp)
            .size(250.dp)
            .graphicsLayer { alpha = 0.03f }
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.secondary,
                        Color.Transparent
                    )
                ),
                shape = CircleShape
            )
    )
}

/**
 * 带动画效果的输入框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimatedInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    colors: BaoziColors,
    context: Context
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val containerColor by animateColorAsState(
        targetValue = if (isFocused) {
            colors.backgroundInput.copy(alpha = 0.6f)
        } else {
            colors.backgroundInput.copy(alpha = 0.4f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "inputContainerColor"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) {
            colors.primary
        } else {
            colors.textHint.copy(alpha = 0.4f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "inputBorderColor"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isFocused) {
            colors.primary
        } else {
            colors.textSecondary
        },
        animationSpec = tween(durationMillis = 200),
        label = "iconColor"
    )

    val elevation by animateDpAsState(
        targetValue = if (isFocused) 4.dp else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "inputElevation"
    )

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = label,
                tint = iconColor
            )
        },
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        singleLine = true,
        interactionSource = interactionSource,
        modifier = modifier
            .shadow(
                elevation = elevation,
                spotColor = colors.primary.copy(alpha = 0.2f),
                ambientColor = colors.primary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable {
                performLightHaptic(context)
            },
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = borderColor,
            unfocusedBorderColor = borderColor,
            focusedContainerColor = containerColor,
            unfocusedContainerColor = containerColor,
            focusedLabelColor = colors.primary,
            unfocusedLabelColor = colors.textHint,
            cursorColor = colors.primary
        ),
        textStyle = LocalTextStyle.current.copy(
            fontSize = 16.sp,
            color = colors.textPrimary,
            fontWeight = FontWeight.Medium
        )
    )
}

/**
 * 现代登录按钮 - 增强交互反馈
 */
@Composable
private fun ModernLoginButton(
    text: String,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    colors: BaoziColors
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "buttonScale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textHint.copy(alpha = 0.3f)
            isPressed -> colors.primaryDark
            else -> colors.primary
        },
        animationSpec = tween(durationMillis = 200),
        label = "buttonColor"
    )

    val shadowElevation by animateDpAsState(
        targetValue = if (enabled && !isLoading) {
            if (isPressed) 4.dp else 8.dp
        } else {
            0.dp
        },
        animationSpec = tween(durationMillis = 200),
        label = "buttonShadow"
    )

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .scale(scale)
            .shadow(
                elevation = shadowElevation,
                spotColor = colors.primary.copy(alpha = 0.4f),
                ambientColor = colors.primary.copy(alpha = 0.2f),
                shape = RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            disabledContainerColor = colors.textHint.copy(alpha = 0.3f)
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        if (isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.5.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "登录中...",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    letterSpacing = 0.5.sp
                )
            }
        } else {
            Text(
                text = text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 0.8.sp
            )
        }
    }
}

/**
 * 社交登录按钮
 */
@Composable
private fun SocialLoginButtons(
    colors: BaoziColors,
    context: Context
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Google 登录按钮
        SocialLoginButton(
            text = "Google",
            icon = { /* Google icon would go here */ },
            modifier = Modifier.weight(1f),
            colors = colors,
            context = context
        )

        // Apple 登录按钮
        SocialLoginButton(
            text = "Apple",
            icon = { /* Apple icon would go here */ },
            modifier = Modifier.weight(1f),
            colors = colors,
            context = context
        )
    }
}

/**
 * 单个社交登录按钮
 */
@Composable
private fun SocialLoginButton(
    text: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    colors: BaoziColors,
    context: Context
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "socialButtonScale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) {
            colors.background.copy(alpha = 0.8f)
        } else {
            colors.background.copy(alpha = 0.6f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "socialButtonColor"
    )

    Surface(
        onClick = {
            performLightHaptic(context)
            // TODO: 实现社交登录
        },
        modifier = modifier
            .height(50.dp)
            .scale(scale),
        shape = RoundedCornerShape(14.dp),
        color = backgroundColor,
        border = BorderStroke(
            width = 1.dp,
            color = colors.textHint.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
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