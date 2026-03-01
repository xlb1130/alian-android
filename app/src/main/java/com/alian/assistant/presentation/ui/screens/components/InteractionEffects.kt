package com.alian.assistant.presentation.ui.screens.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alian.assistant.common.constant.AnimationConstants
import com.alian.assistant.presentation.ui.theme.BaoziTheme

/**
 * 交互效果工具 - 统一按压、悬停等交互效果
 * 
 * 提供统一的交互效果修饰符，确保整个应用的交互体验一致
 */

/**
 * 按压效果修饰符
 * 
 * 应用按压时的缩放和背景变化效果
 * 
 * @param interactionSource 交互源
 * @param pressedScale 按压时的缩放比例
 * @param pressedAlpha 按压时的背景透明度
 * @param backgroundColor 按压时的背景颜色（默认使用主题色）
 */
@Composable
fun Modifier.pressedEffect(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = AnimationConstants.PressScale,
    pressedAlpha: Float = AnimationConstants.HoverBackgroundAlpha,
    backgroundColor: Color = BaoziTheme.colors.primary
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = AnimationConstants.PressAnimationSpec,
        label = "pressedScale"
    )
    
    return this
        .scale(scale)
        .drawBehind {
            if (isPressed) {
                drawRoundRect(
                    color = backgroundColor.copy(alpha = pressedAlpha),
                    size = size,
                    cornerRadius = CornerRadius(size.minDimension / 2)
                )
            }
        }
}

/**
 * 悬停效果修饰符
 * 
 * 应用悬停时的缩放、阴影和背景变化效果
 * 
 * @param interactionSource 交互源
 * @param hoverScale 悬停时的缩放比例
 * @param hoverElevation 悬停时的阴影高度
 * @param hoverAlpha 悬停时的背景透明度
 * @param backgroundColor 悬停时的背景颜色（默认使用主题色）
 * @param cornerRadius 圆角半径（默认为圆形）
 */
@Composable
fun Modifier.hoverEffect(
    interactionSource: MutableInteractionSource,
    hoverScale: Float = AnimationConstants.HoverScale,
    hoverElevation: Dp = AnimationConstants.HoverElevation,
    hoverAlpha: Float = AnimationConstants.HoverBackgroundAlpha,
    backgroundColor: Color = BaoziTheme.colors.primary,
    cornerRadius: Dp = 999.dp
): Modifier {
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> AnimationConstants.PressScale
            isHovered -> hoverScale
            else -> 1f
        },
        animationSpec = AnimationConstants.HoverAnimationSpec,
        label = "hoverScale"
    )
    
    val elevation by animateDpAsState(
        targetValue = when {
            isPressed -> AnimationConstants.PressedElevation
            isHovered -> hoverElevation
            else -> AnimationConstants.DefaultElevation
        },
        animationSpec = AnimationConstants.HoverAnimationSpecDp,
        label = "hoverElevation"
    )

    val translationY by animateDpAsState(
        targetValue = when {
            isPressed -> 2.dp
            isHovered -> (-2).dp
            else -> 0.dp
        },
        animationSpec = AnimationConstants.HoverAnimationSpecDp,
        label = "hoverTranslationY"
    )
    
    return this
        .offset(y = translationY)
        .scale(scale)
        .drawBehind {
            if (isHovered || isPressed) {
                drawRoundRect(
                    color = backgroundColor.copy(
                        alpha = if (isPressed) hoverAlpha * 1.5f else hoverAlpha
                    ),
                    size = size,
                    cornerRadius = CornerRadius(cornerRadius.toPx())
                )
            }
        }
}

/**
 * 卡片阴影效果修饰符
 * 
 * 应用统一的卡片阴影效果
 * 
 * @param interactionSource 交互源（可选，用于动态阴影）
 * @param defaultElevation 默认阴影高度
 * @param hoverElevation 悬停时的阴影高度
 * @param pressedElevation 按压时的阴影高度
 * @param shape 圆角形状
 * @param ambientColor 环境光颜色
 * @param spotColor 聚光灯颜色
 */
@Composable
fun Modifier.cardShadow(
    interactionSource: MutableInteractionSource? = null,
    defaultElevation: Dp = AnimationConstants.CardDefaultElevation,
    hoverElevation: Dp = AnimationConstants.CardHoverElevation,
    pressedElevation: Dp = AnimationConstants.PressedElevation,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    ambientColor: Color = BaoziTheme.colors.primary.copy(alpha = 0.2f),
    spotColor: Color = BaoziTheme.colors.primary.copy(alpha = 0.3f)
): Modifier {
    val elevation = if (interactionSource != null) {
        val isHovered by interactionSource.collectIsHoveredAsState()
        val isPressed by interactionSource.collectIsPressedAsState()

        animateDpAsState(
            targetValue = when {
                isPressed -> pressedElevation
                isHovered -> hoverElevation
                else -> defaultElevation
            },
            animationSpec = AnimationConstants.HoverAnimationSpecDp,
            label = "cardShadowElevation"
        ).value
    } else {
        defaultElevation
    }

    val density = LocalDensity.current

    return this.drawBehind {
        // 使用自定义阴影绘制
        val shadowColor = ambientColor.copy(alpha = 0.5f)
        val elevationPx = with(density) { elevation.toPx() }
        val blurRadius = elevationPx * 2

        // 绘制多层阴影以增强效果
        for (i in 1..3) {
            drawRoundRect(
                color = shadowColor.copy(alpha = (4 - i) * 0.1f),
                topLeft = Offset(
                    elevationPx * i / 3f,
                    elevationPx * i / 3f
                ),
                size = Size(
                    size.width + elevationPx * i / 1.5f,
                    size.height + elevationPx * i / 1.5f
                ),
                cornerRadius = CornerRadius(
                    shape.topStart.toPx(size.toDpSize().toSize(), density),
                    shape.topStart.toPx(size.toDpSize().toSize(), density)
                )
            )
        }
    }
}

/**
 * 卡片边框效果修饰符
 * 
 * 应用统一的卡片边框效果
 * 
 * @param interactionSource 交互源（可选，用于动态边框）
 * @param defaultAlpha 默认边框透明度
 * @param hoverAlpha 悬停时的边框透明度
 * @param borderColor 边框颜色（默认使用主题色）
 * @param strokeWidth 边框宽度
 * @param cornerRadius 圆角半径
 */
@Composable
fun Modifier.cardBorder(
    interactionSource: MutableInteractionSource? = null,
    defaultAlpha: Float = AnimationConstants.CardBorderAlphaDefault,
    hoverAlpha: Float = AnimationConstants.CardBorderAlphaHover,
    borderColor: Color = BaoziTheme.colors.primary,
    strokeWidth: Dp = 0.5.dp,
    cornerRadius: Dp = 16.dp
): Modifier {
    val alpha = if (interactionSource != null) {
        val isHovered by interactionSource.collectIsHoveredAsState()
        
        animateFloatAsState(
            targetValue = if (isHovered) hoverAlpha else defaultAlpha,
            animationSpec = AnimationConstants.HoverAnimationSpec,
            label = "cardBorderAlpha"
        ).value
    } else {
        defaultAlpha
    }
    
    return this.drawBehind {
        drawRoundRect(
            color = borderColor.copy(alpha = alpha),
            style = Stroke(width = strokeWidth.toPx()),
            cornerRadius = CornerRadius(cornerRadius.toPx())
        )
    }
}

/**
 * 焦点效果修饰符
 * 
 * 应用输入框焦点时的发光和边框效果
 * 
 * @param isFocused 是否处于焦点状态
 * @param focusAlpha 焦点时的发光透明度
 * @param borderAlpha 焦点时的边框透明度
 * @param focusColor 焦点颜色（默认使用主题色）
 * @param cornerRadius 圆角半径
 */
@Composable
fun Modifier.focusEffect(
    isFocused: Boolean,
    focusAlpha: Float = AnimationConstants.FocusGlowAlpha,
    borderAlpha: Float = AnimationConstants.FocusBorderAlpha,
    focusColor: Color = BaoziTheme.colors.primary,
    cornerRadius: Dp = 28.dp
): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = AnimationConstants.HoverAnimationSpec,
        label = "focusAlpha"
    )
    
    return this
        .drawBehind {
            // 焦点时的发光效果
            if (isFocused) {
                drawRoundRect(
                    color = focusColor.copy(alpha = focusAlpha * alpha),
                    size = size,
                    cornerRadius = CornerRadius(cornerRadius.toPx())
                )
            }
        }
        .clip(RoundedCornerShape(cornerRadius))
        .drawBehind {
            // 焦点时的边框效果
            if (isFocused) {
                drawRoundRect(
                    color = focusColor.copy(alpha = borderAlpha * alpha),
                    style = Stroke(width = 2.dp.toPx()),
                    cornerRadius = CornerRadius(cornerRadius.toPx())
                )
            }
        }
}