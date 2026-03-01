package com.alian.assistant.common.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * 轻微震动效果
 */
fun performLightHaptic(context: Context) {
    Log.d("HapticUtils", "performLightHaptic() called")
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    if (vibrator?.hasVibrator() == true) {
        Log.d("HapticUtils", "Vibrator available, SDK: ${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ 使用 VibrationEffect
            val effect = VibrationEffect.createOneShot(50, 150)
            vibrator.vibrate(effect)
            Log.d("HapticUtils", "Vibrated with VibrationEffect")
        } else {
            // 旧版本使用 deprecated 方法
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
            Log.d("HapticUtils", "Vibrated with deprecated method")
        }
    } else {
        Log.e("HapticUtils", "Vibrator not available or null")
    }
}