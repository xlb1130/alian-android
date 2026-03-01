package com.alian.assistant.presentation.ui.screens.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * 震动工具类 - 统一震动反馈
 * 
 * 提供统一的震动反馈接口，确保整个应用的震动体验一致
 */
object HapticUtils {
    private const val TAG = "HapticUtils"

    /**
     * 轻微震动 - 用于按钮点击、菜单展开等轻微交互
     * 
     * @param context 上下文
     */
    fun performLightHaptic(context: Context) {
        performHaptic(context, duration = 50, amplitude = 150)
    }

    /**
     * 中等震动 - 用于重要操作确认
     * 
     * @param context 上下文
     */
    fun performMediumHaptic(context: Context) {
        performHaptic(context, duration = 100, amplitude = 200)
    }

    /**
     * 强烈震动 - 用于错误提示、警告等
     * 
     * @param context 上下文
     */
    fun performStrongHaptic(context: Context) {
        performHaptic(context, duration = 150, amplitude = 255)
    }

    /**
     * 执行震动
     * 
     * @param context 上下文
     * @param duration 震动时长（毫秒）
     * @param amplitude 震动强度（0-255）
     */
    private fun performHaptic(context: Context, duration: Long, amplitude: Int) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+ 使用 VibrationEffect
                val effect = VibrationEffect.createOneShot(duration, amplitude)
                vibrator.vibrate(effect)
                Log.d(TAG, "Vibrated with VibrationEffect: duration=$duration, amplitude=$amplitude")
            } else {
                // 旧版本使用 deprecated 方法
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
                Log.d(TAG, "Vibrated with deprecated method: duration=$duration")
            }
        } else {
            Log.w(TAG, "Vibrator not available or null")
        }
    }

    /**
     * 检查设备是否支持震动
     * 
     * @param context 上下文
     * @return 是否支持震动
     */
    fun isVibrationSupported(context: Context): Boolean {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        return vibrator?.hasVibrator() == true
    }
}