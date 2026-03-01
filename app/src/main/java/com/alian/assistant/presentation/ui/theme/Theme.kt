package com.alian.assistant.presentation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// 主色调 - Indigo 系列现代色值
val Primary = Color(0xFF6366F1)
val PrimaryDark = Color(0xFF4F46E5)
val PrimaryLight = Color(0xFF818CF8)
val Secondary = Color(0xFFA5B4FC)
val SecondaryDark = Color(0xFF4338CA)
val SecondaryLight = Color(0xFFC7D2FE)

// 渐变色板
val GradientPrimary = Brush.horizontalGradient(
    colors = listOf(Primary, PrimaryLight)
)
val GradientPrimaryVertical = Brush.verticalGradient(
    colors = listOf(Primary, PrimaryLight)
)
val GradientSecondary = Brush.horizontalGradient(
    colors = listOf(Secondary, SecondaryLight)
)

// 深色主题背景色 (优化层次感 - 增强对比度)
val BackgroundDark = Color(0xFF0A0A0A)           // 更深的背景
val BackgroundCard = Color(0xFF1A1A1A)          // 卡片背景（加亮以与背景区分）
val BackgroundCardElevated = Color(0xFF252525)   // 提升的卡片背景
val BackgroundInput = Color(0xFF252538)          // 输入框背景（更浅，带微妙的 Indigo 色调）
val SurfaceVariant = Color(0xFF353535)           // 变体表面
val SurfaceOverlay = Color(0xFF1A1A1A)           // 叠加层

// 抽屉背景 (更深，与卡片形成对比)
val BackgroundDrawerDark = Color(0xFF080808)    // 抽屉背景 - 深色
val BackgroundDrawerLight = Color(0xFFFAFAFA)   // 抽屉背景 - 浅色

// 卡片交互状态（添加主色调倾向）
val BackgroundCardHoverDark = Color(0xFF1E1E2E) // 卡片悬停 - 深色（带 Indigo 色调）
val BackgroundCardHoverLight = Color(0xFFE8E8F0)// 卡片悬停 - 浅色（带 Indigo 色调）
val BackgroundCardPressedDark = Color(0xFF252538)// 卡片按下 - 深色（带 Indigo 色调）
val BackgroundCardPressedLight = Color(0xFFE0E0EC)// 卡片按下 - 浅色（带 Indigo 色调）

// 深色主题文字颜色 (提高对比度)
val TextPrimary = Color(0xFFFAFAFA)              // 主文字（更亮）
val TextSecondary = Color(0xFFB0B0B0)            // 次要文字（更亮）
val TextHint = Color(0xFF6B6B6B)                 // 提示文字（更亮）

// 浅色主题背景色 - 纯白简洁风格 (优化层次感)
val BackgroundLight = Color(0xFFFFFFFF)          // 纯白背景
val BackgroundCardLight = Color(0xFFF0F0F0)      // 卡片背景（加深以便AI消息气泡更明显）
val BackgroundCardElevatedLight = Color(0xFFEBEBEB) // 提升的卡片背景（加深以便AI消息气泡更明显）
val BackgroundInputLight = Color(0xFFF5F5FA)     // 输入框背景（更浅，带微妙的 Indigo 色调）
val SurfaceVariantLight = Color(0xFFE0E0E0)      // 变体表面
val SurfaceOverlayLight = Color(0xFFFAFAFA)      // 叠加层

// 浅色主题文字颜色 (提高对比度)
val TextPrimaryLight = Color(0xFF0F0F0F)         // 主文字（更深）
val TextSecondaryLight = Color(0xFF5A5A5A)       // 次要文字（更深）
val TextHintLight = Color(0xFF8A8A8A)            // 提示文字（更深）

// 状态颜色 (使用更柔和的色值)
val Success = Color(0xFF34D399)                  // 成功 - 绿色
val SuccessLight = Color(0xFF6EE7B7)             // 成功 - 浅绿
val Error = Color(0xFFF87171)                    // 错误 - 红色
val ErrorLight = Color(0xFFFCA5A5)               // 错误 - 浅红
val Warning = Color(0xFFFBBF24)                  // 警告 - 橙色
val WarningLight = Color(0xFFFCD34D)             // 警告 - 浅橙
val Info = Color(0xFF60A5FA)                     // 信息 - 蓝色
val InfoLight = Color(0xFF93C5FD)                // 信息 - 浅蓝

// 渐变色板（状态颜色）
val GradientSuccess = Brush.horizontalGradient(
    colors = listOf(Success, SuccessLight)
)
val GradientError = Brush.horizontalGradient(
    colors = listOf(Error, ErrorLight)
)

// 主题颜色数据类
data class BaoziColors(
    val primary: Color,
    val primaryDark: Color,
    val primaryLight: Color,
    val secondary: Color,
    val background: Color,
    val backgroundCard: Color,
    val backgroundCardElevated: Color,
    val backgroundInput: Color,
    val surfaceVariant: Color,
    val surfaceOverlay: Color,
    val backgroundDrawer: Color,           // 抽屉背景
    val backgroundCardHover: Color,        // 卡片悬停
    val backgroundCardPressed: Color,      // 卡片按下
    val textPrimary: Color,
    val textSecondary: Color,
    val textHint: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val info: Color,
    val successLight: Color,
    val errorLight: Color,
    val warningLight: Color,
    val infoLight: Color,
    val isDark: Boolean
)

// 深色主题颜色
val DarkBaoziColors = BaoziColors(
    primary = Primary,
    primaryDark = PrimaryDark,
    primaryLight = PrimaryLight,
    secondary = Secondary,
    background = BackgroundDark,
    backgroundCard = BackgroundCard,
    backgroundCardElevated = BackgroundCardElevated,
    backgroundInput = BackgroundInput,
    surfaceVariant = SurfaceVariant,
    surfaceOverlay = SurfaceOverlay,
    backgroundDrawer = BackgroundDrawerDark,
    backgroundCardHover = BackgroundCardHoverDark,
    backgroundCardPressed = BackgroundCardPressedDark,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    textHint = TextHint,
    success = Success,
    error = Error,
    warning = Warning,
    info = Info,
    successLight = SuccessLight,
    errorLight = ErrorLight,
    warningLight = WarningLight,
    infoLight = InfoLight,
    isDark = true
)

// 浅色主题颜色
val LightBaoziColors = BaoziColors(
    primary = Primary,
    primaryDark = PrimaryDark,
    primaryLight = PrimaryLight,
    secondary = Secondary,
    background = BackgroundLight,
    backgroundCard = BackgroundCardLight,
    backgroundCardElevated = BackgroundCardElevatedLight,
    backgroundInput = BackgroundInputLight,
    surfaceVariant = SurfaceVariantLight,
    surfaceOverlay = SurfaceOverlayLight,
    backgroundDrawer = BackgroundDrawerLight,
    backgroundCardHover = BackgroundCardHoverLight,
    backgroundCardPressed = BackgroundCardPressedLight,
    textPrimary = TextPrimaryLight,
    textSecondary = TextSecondaryLight,
    textHint = TextHintLight,
    success = Success,
    error = Error,
    warning = Warning,
    info = Info,
    successLight = SuccessLight,
    errorLight = ErrorLight,
    warningLight = WarningLight,
    infoLight = InfoLight,
    isDark = false
)

// CompositionLocal 用于访问当前主题颜色
val LocalBaoziColors = staticCompositionLocalOf { DarkBaoziColors }

// Material 3 深色配色方案
private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = Secondary,
    onSecondary = Color.Black,
    secondaryContainer = SecondaryDark,
    onSecondaryContainer = Color.White,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = BackgroundCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = Error,
    onError = Color.White,
    outline = Color(0xFF404040)
)

// Material 3 浅色配色方案
private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = Color.Black,
    secondary = Secondary,
    onSecondary = Color.Black,
    secondaryContainer = SecondaryLight,
    onSecondaryContainer = Color.Black,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = BackgroundCardLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    error = Error,
    onError = Color.White,
    outline = Color(0xFFE5E7EB)
)

// 主题模式枚举
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

@Composable
fun BaoziTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme
    val baoziColors = if (isDarkTheme) DarkBaoziColors else LightBaoziColors

    CompositionLocalProvider(LocalBaoziColors provides baoziColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content
        )
    }
}

// 便捷访问当前主题颜色
object BaoziTheme {
    val colors: BaoziColors
        @Composable
        get() = LocalBaoziColors.current
}
