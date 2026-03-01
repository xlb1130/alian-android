package com.alian.assistant.presentation.ui.screens.settings

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alian.assistant.presentation.ui.theme.BaoziTheme

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
 * 增强版开关组件
 * 提供更好的视觉效果和交互反馈
 * 
 * @param checked 是否开启
 * @param onCheckedChange 状态变化回调
 * @param modifier 修饰符
 * @param enabled 是否可用
 */
@Composable
fun EnhancedSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    
    // 按下时的缩放动画
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "scale"
    )
    
    Switch(
        checked = checked,
        onCheckedChange = { newValue ->
            performLightHaptic(context)
            onCheckedChange(newValue)
        },
        modifier = modifier
            .scale(scale)
            .size(width = 52.dp, height = 32.dp),
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.primary,
            checkedIconColor = colors.primary,
            uncheckedThumbColor = colors.textHint,
            uncheckedTrackColor = colors.backgroundInput,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedThumbColor = colors.textHint,
            disabledCheckedTrackColor = colors.backgroundInput,
            disabledUncheckedThumbColor = colors.textHint.copy(alpha = 0.5f),
            disabledUncheckedTrackColor = colors.backgroundInput.copy(alpha = 0.5f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedBorderColor = Color.Transparent
        ),
        thumbContent = {
            // 自定义滑块内容（可选）
        },
        interactionSource = interactionSource
    )
}

/**
 * 紧凑版开关组件（用于空间受限的场景）
 * 
 * @param checked 是否开启
 * @param onCheckedChange 状态变化回调
 * @param modifier 修饰符
 * @param enabled 是否可用
 */
@Composable
fun CompactSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    
    Switch(
        checked = checked,
        onCheckedChange = { newValue ->
            performLightHaptic(context)
            onCheckedChange(newValue)
        },
        modifier = modifier.size(width = 44.dp, height = 28.dp),
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.primary,
            checkedIconColor = colors.primary,
            uncheckedThumbColor = colors.textHint,
            uncheckedTrackColor = colors.backgroundInput,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedThumbColor = colors.textHint,
            disabledCheckedTrackColor = colors.backgroundInput,
            disabledUncheckedThumbColor = colors.textHint.copy(alpha = 0.5f),
            disabledUncheckedTrackColor = colors.backgroundInput.copy(alpha = 0.5f)
        )
    )
}