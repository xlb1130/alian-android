package com.alian.assistant.presentation.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设置页面间距系统
 * 提供统一的间距值，增强呼吸感和视觉层次
 */

// 基础间距单位
private const val SPACING_UNIT = 4

// 间距常量（dp）
val SpacingXs: Dp = (SPACING_UNIT * 1).dp       // 4.dp - 元素内部最小间距
val SpacingSm: Dp = (SPACING_UNIT * 2).dp       // 8.dp - 相关元素之间
val SpacingMd: Dp = (SPACING_UNIT * 3).dp       // 12.dp - 元素组之间
val SpacingLg: Dp = (SPACING_UNIT * 4).dp       // 16.dp - 卡片内部间距
val SpacingXl: Dp = (SPACING_UNIT * 6).dp       // 24.dp - 分组标题
val SpacingXxl: Dp = (SPACING_UNIT * 8).dp      // 32.dp - 大区块之间
val SpacingXxxl: Dp = (SPACING_UNIT * 12).dp    // 48.dp - 页面底部

// 间距对象（用于保持向后兼容）
val Spacing = object {
    val xs: Dp = SpacingXs
    val sm: Dp = SpacingSm
    val md: Dp = SpacingMd
    val lg: Dp = SpacingLg
    val xl: Dp = SpacingXl
    val xxl: Dp = SpacingXxl
    val xxxl: Dp = SpacingXxxl
}

/**
 * 水平间距组件
 */
@Composable
fun HorizontalSpacing(size: Dp = SpacingMd) {
    Spacer(modifier = Modifier.width(size))
}

/**
 * 垂直间距组件
 */
@Composable
fun VerticalSpacing(size: Dp = SpacingMd) {
    Spacer(modifier = Modifier.height(size))
}

/**
 * 卡片垂直间距（用于卡片内部元素之间）
 */
@Composable
fun CardVerticalSpacing() {
    VerticalSpacing(SpacingSm)
}

/**
 * 卡片水平间距（用于卡片内部元素之间）
 */
@Composable
fun CardHorizontalSpacing() {
    HorizontalSpacing(SpacingLg)
}

/**
 * 分组间距（用于设置分组之间）
 */
@Composable
fun SectionSpacing() {
    VerticalSpacing(SpacingXl)
}

/**
 * 页面底部间距
 */
@Composable
fun PageBottomSpacing() {
    VerticalSpacing(SpacingXxxl)
}