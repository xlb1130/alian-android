package com.alian.assistant.presentation.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 设置页面字体层级系统
 * 提供统一的字体样式，增强视觉层次和可读性
 */

// 字体大小常量
private const val FONT_SIZE_LARGE = 28
private const val FONT_SIZE_TITLE = 20
private const val FONT_SIZE_HEADING = 18
private const val FONT_SIZE_SUBHEADING = 16
private const val FONT_SIZE_BODY = 15
private const val FONT_SIZE_BODY_SMALL = 14
private const val FONT_SIZE_CAPTION = 13
private const val FONT_SIZE_LABEL = 12
private const val FONT_SIZE_TINY = 11

/**
 * 字体样式对象
 */
object SettingsTypography {
    
    @Composable
    fun largeTitle() = TextStyle(
        fontSize = FONT_SIZE_LARGE.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp
    )
    
    @Composable
    fun pageTitle() = TextStyle(
        fontSize = FONT_SIZE_TITLE.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp
    )
    
    @Composable
    fun heading() = TextStyle(
        fontSize = FONT_SIZE_HEADING.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp
    )
    
    @Composable
    fun subheading() = TextStyle(
        fontSize = FONT_SIZE_SUBHEADING.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    )
    
    @Composable
    fun body() = TextStyle(
        fontSize = FONT_SIZE_BODY.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    )
    
    @Composable
    fun bodySmall() = TextStyle(
        fontSize = FONT_SIZE_BODY_SMALL.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.1.sp
    )
    
    @Composable
    fun caption() = TextStyle(
        fontSize = FONT_SIZE_CAPTION.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp
    )
    
    @Composable
    fun label() = TextStyle(
        fontSize = FONT_SIZE_LABEL.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp
    )
    
    @Composable
    fun tiny() = TextStyle(
        fontSize = FONT_SIZE_TINY.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.4.sp
    )
}