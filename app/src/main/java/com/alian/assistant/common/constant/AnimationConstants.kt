package com.alian.assistant.common.constant

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 动画参数常量 - 统一整个应用的动画体验
 *
 * 定义所有动画相关的常量，包括时长、缓动函数、缩放比例等
 */
object AnimationConstants {

    // ========== 动画时长 ==========

    /** 轻微按压动画时长 */
    const val PressDuration = 100

    /** 悬停动画时长 */
    const val HoverDuration = 200

    /** 菜单旋转动画时长 */
    const val MenuRotationDuration = 200

    /** 列表项进入动画时长 */
    const val ListEnterDuration = 300

    /** 列表项退出动画时长 */
    const val ListExitDuration = 200

    /** 页面切换动画时长 */
    const val PageTransitionDuration = 700

    /** 波纹扩散动画时长 */
    const val RippleDuration = 400

    /** 加载动画单次循环时长 */
    const val LoadingAnimationDuration = 800

    /** 呼吸灯动画时长 */
    const val BreathingAnimationDuration = 1500

    /** 消息气泡飞出动画时长 */
    const val FlyoutDuration = 400

    /** 菜单展开/收起动画时长 */
    const val MenuExpandDuration = 300

    // ========== 缓动函数 ==========

    /** 按压缓动 */
    val PressEasing = EaseInOutCubic

    /** 悬停缓动 */
    val HoverEasing = EaseInOutCubic

    /** 飞出缓动 */
    val FlyoutEasing = EaseOutCubic

    /** 线性缓动 */
    val LinearEasing: Easing = androidx.compose.animation.core.LinearEasing

    /** 快出慢入缓动 */
    val FastOutSlowInEasing: Easing = androidx.compose.animation.core.FastOutSlowInEasing

    /** 简单缓入缓出 */
    val SimpleEaseInOut = EaseInOut

    // ========== 缩放比例 ==========

    /** 按压时的缩放比例 */
    const val PressScale = 0.95f

    /** 悬停时的缩放比例 */
    const val HoverScale = 1.02f

    /** 点击后的缩放比例 */
    const val ClickScale = 0.9f

    /** 卡片悬停时的缩放比例 */
    const val CardHoverScale = 1.03f

    /** 菜单图标旋转角度 */
    const val MenuRotationAngle = 90f

    // ========== 阴影高度 ==========

    /** 默认阴影高度 */
    val DefaultElevation = 6.dp

    /** 悬停时的阴影高度 */
    val HoverElevation = 8.dp

    /** 按压时的阴影高度 */
    val PressedElevation = 2.dp

    /** 卡片默认阴影高度 */
    val CardDefaultElevation = 4.dp

    /** 卡片悬停阴影高度 */
    val CardHoverElevation = 8.dp

    // ========== 弹簧参数 ==========

    /** 弹簧阻尼比（中等弹性） */
    const val SpringDampingRatioMediumBouncy = Spring.DampingRatioMediumBouncy

    /** 弹簧刚度（低刚度，更柔和） */
    const val SpringStiffnessLow = Spring.StiffnessLow

    /** 弹簧刚度（中等刚度） */
    const val SpringStiffnessMediumLow = Spring.StiffnessMediumLow

    // ========== 动画规格 ==========

    /** 按压动画规格 */
    val PressAnimationSpec: TweenSpec<Float> = tween(
        durationMillis = PressDuration,
        easing = PressEasing
    )

    /** 悬停动画规格 */
    val HoverAnimationSpec: TweenSpec<Float> = tween(
        durationMillis = HoverDuration,
        easing = HoverEasing
    )

    /** 菜单旋转动画规格 */
    val MenuRotationAnimationSpec: TweenSpec<Float> = tween(
        durationMillis = MenuRotationDuration,
        easing = PressEasing
    )

    /** 飞出动画规格 */
    val FlyoutAnimationSpec: TweenSpec<Float> = tween(
        durationMillis = FlyoutDuration,
        easing = FlyoutEasing
    )

    /** 列表项进入动画规格 */
    val ListEnterAnimationSpec: TweenSpec<Float> = tween(
        durationMillis = ListEnterDuration,
        easing = SimpleEaseInOut
    )

    /** 列表项退出动画规格 */
    val ListExitAnimationSpec: TweenSpec<Float> = tween(
        durationMillis = ListExitDuration,
        easing = SimpleEaseInOut
    )

    // ========== Dp 类型动画规格 ==========

    /** 按压动画规格（Dp） */
    val PressAnimationSpecDp: TweenSpec<Dp> = tween(
        durationMillis = PressDuration,
        easing = PressEasing
    )

    /** 悬停动画规格（Dp） */
    val HoverAnimationSpecDp: TweenSpec<Dp> = tween(
        durationMillis = HoverDuration,
        easing = HoverEasing
    )

    /** 菜单旋转动画规格（Dp） */
    val MenuRotationAnimationSpecDp: TweenSpec<Dp> = tween(
        durationMillis = MenuRotationDuration,
        easing = PressEasing
    )

    /** 飞出动画规格（Dp） */
    val FlyoutAnimationSpecDp: TweenSpec<Dp> = tween(
        durationMillis = FlyoutDuration,
        easing = FlyoutEasing
    )

    /** 列表项进入动画规格（Dp） */
    val ListEnterAnimationSpecDp: TweenSpec<Dp> = tween(
        durationMillis = ListEnterDuration,
        easing = SimpleEaseInOut
    )

    /** 列表项退出动画规格（Dp） */
    val ListExitAnimationSpecDp: TweenSpec<Dp> = tween(
        durationMillis = ListExitDuration,
        easing = SimpleEaseInOut
    )

    // ========== 无限重复动画参数 ==========

    /** 加载动画重复模式 */
    val LoadingRepeatMode = RepeatMode.Reverse

    /** 波浪动画重复模式 */
    val WaveRepeatMode = RepeatMode.Restart

    /** 脉冲动画重复模式 */
    val PulseRepeatMode = RepeatMode.Restart

    // ========== 透明度值 ==========

    /** 悬停时的背景透明度 */
    const val HoverBackgroundAlpha = 0.1f

    /** 焦点时的边框透明度 */
    const val FocusBorderAlpha = 0.6f

    /** 焦点时的发光透明度 */
    const val FocusGlowAlpha = 0.15f

    /** 卡片边框透明度（默认） */
    const val CardBorderAlphaDefault = 0.08f

    /** 卡片边框透明度（悬停） */
    const val CardBorderAlphaHover = 0.15f

    /** 光泽效果透明度（深色模式） */
    const val GlossyAlphaDark = 0.05f

    /** 光泽效果透明度（浅色模式） */
    const val GlossyAlphaLight = 0.3f
}