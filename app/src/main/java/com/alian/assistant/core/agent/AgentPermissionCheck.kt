package com.alian.assistant.core.agent

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.alian.assistant.common.utils.PermissionManager
import com.alian.assistant.common.utils.PermissionType
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.infrastructure.device.accessibility.AlianAccessibilityService

/**
 * Agent 权限检查器
 *
 * 负责 MobileAgentImprove 执行前的权限检查
 *
 * 检查项目：
 * 1. 悬浮窗权限
 * 2. 无障碍服务（根据执行策略）
 * 3. MediaProjection 权限（根据执行策略）
 */
class AgentPermissionCheck(
    private val context: Context,
    private val settingsManager: SettingsManager = SettingsManager(context)
) {
    companion object {
        private const val TAG = "AgentPermissionCheck"
    }

    /**
         * 权限检查结果
         */
        sealed class CheckResult {
            /**
             * 权限检查通过
             */
            object Granted : CheckResult()

            /**
             * 需要录音权限
             */
            object NeedsRecordAudio : CheckResult()

            /**
             * 需要悬浮窗权限
             */
            object NeedsSystemAlertWindow : CheckResult()

            /**
             * 需要无障碍服务
             */
            object NeedsAccessibilityService : CheckResult()

            /**
             * 需要 MediaProjection 权限
             */
            object NeedsMediaProjection : CheckResult()

            /**
             * 需要 Shizuku 权限
             */
            object NeedsShizuku : CheckResult()

            /**
             * 需要多个权限
             */
            data class NeedsMultiplePermissions(val missingPermissions: List<CheckResult>) : CheckResult()
        }
    /**
     * 执行权限检查
     *
     * @param mediaProjectionResultCode MediaProjection 的 resultCode
     * @param mediaProjectionData MediaProjection 的 Intent data
     * @return 检查结果
     */
    fun checkPermissions(
        mediaProjectionResultCode: Int? = null,
        mediaProjectionData: Intent? = null
    ): CheckResult {
        Log.d(TAG, "========== 开始权限检查 ==========")

        val settings = settingsManager.settings.value
        Log.d(TAG, "执行策略: ${settings.executionStrategy}")

        // 1. 检查录音权限
        if (!PermissionManager.isPermissionGranted(context, PermissionType.RECORD_AUDIO)) {
            Log.d(TAG, "录音权限未授予")
            return CheckResult.NeedsRecordAudio
        }
        Log.d(TAG, "✓ 录音权限已授予")

        // 2. 检查悬浮窗权限
        if (!PermissionManager.isPermissionGranted(context, PermissionType.SYSTEM_ALERT_WINDOW)) {
            Log.d(TAG, "悬浮窗权限未授予")
            return CheckResult.NeedsSystemAlertWindow
        }
        Log.d(TAG, "✓ 悬浮窗权限已授予")

        // 3. 检查 Shizuku 权限（仅在 shizuku_only 模式下强制）
        val needsShizukuOnly = settings.executionStrategy == "shizuku_only"
        if (needsShizukuOnly) {
            if (!PermissionManager.isPermissionGranted(context, PermissionType.SHIZUKU)) {
                Log.d(TAG, "Shizuku 权限未授予")
                return CheckResult.NeedsShizuku
            }
            Log.d(TAG, "✓ Shizuku 权限已授予")
        }

        // 4. 检查无障碍服务（仅在使用无障碍或混合模式时需要）
        val needsAccessibility = PermissionManager.needsMediaProjectionPermission(settings.executionStrategy)
        Log.d(TAG, "需要无障碍服务: $needsAccessibility")

        if (needsAccessibility) {
            val expectedService = context.packageName + "/" + AlianAccessibilityService::class.java.name
            Log.d(TAG, "期望的无障碍服务: $expectedService")

            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            Log.d(TAG, "系统已启用的无障碍服务: $enabledServices")

            val isAccessibilityEnabled = PermissionManager.isPermissionGranted(context, PermissionType.ACCESSIBILITY_SERVICE)
            val isAccessibilityConnected = AlianAccessibilityService.isConnected()
            Log.d(TAG, "无障碍服务状态: enabled=$isAccessibilityEnabled, connected=$isAccessibilityConnected")

            if (!isAccessibilityEnabled || !isAccessibilityConnected) {
                Log.d(TAG, "无障碍服务未就绪（未开启或未连接）")
                return CheckResult.NeedsAccessibilityService
            }
            Log.d(TAG, "✓ 无障碍服务已启用")
        }

        // 5. 检查 MediaProjection 权限（仅在使用无障碍或混合模式时需要）
        val needsMediaProjection = PermissionManager.needsMediaProjectionPermission(settings.executionStrategy)
        Log.d(TAG, "需要 MediaProjection 权限: $needsMediaProjection")
        Log.d(TAG, "MediaProjection 权限状态: resultCode=$mediaProjectionResultCode, data=${if (mediaProjectionData != null) "available" else "null"}")

        if (needsMediaProjection) {
            // 检查 MediaProjection 权限是否已授予
            if (!PermissionManager.isPermissionGranted(context, PermissionType.MEDIA_PROJECTION, mediaProjectionResultCode, mediaProjectionData)) {
                Log.d(TAG, "MediaProjection 权限未授予")
                return CheckResult.NeedsMediaProjection
            }
            Log.d(TAG, "✓ MediaProjection 权限已授予")
        }
        Log.d(TAG, "✓ MediaProjection 权限检查通过")

        Log.d(TAG, "========== 权限检查通过 ==========")
        return CheckResult.Granted
    }

    /**
     * 检查所有缺失的权限
     *
     * @param mediaProjectionResultCode MediaProjection 的 resultCode
     * @param mediaProjectionData MediaProjection 的 Intent data
     * @return 检查结果（包含所有缺失的权限）
     */
    fun checkAllPermissions(
        mediaProjectionResultCode: Int? = null,
        mediaProjectionData: Intent? = null
    ): CheckResult {
        Log.d(TAG, "========== 开始检查所有缺失权限 ==========")

        val settings = settingsManager.settings.value
        val missingPermissions = mutableListOf<CheckResult>()

        // 1. 检查录音权限
        if (!PermissionManager.isPermissionGranted(context, PermissionType.RECORD_AUDIO)) {
            Log.d(TAG, "录音权限未授予")
            missingPermissions.add(CheckResult.NeedsRecordAudio)
        } else {
            Log.d(TAG, "✓ 录音权限已授予")
        }

        // 2. 检查悬浮窗权限
        if (!PermissionManager.isPermissionGranted(context, PermissionType.SYSTEM_ALERT_WINDOW)) {
            Log.d(TAG, "悬浮窗权限未授予")
            missingPermissions.add(CheckResult.NeedsSystemAlertWindow)
        } else {
            Log.d(TAG, "✓ 悬浮窗权限已授予")
        }

        // 3. 检查 Shizuku 权限（仅在 shizuku_only 模式下强制）
        val needsShizukuOnly = settings.executionStrategy == "shizuku_only"
        if (needsShizukuOnly) {
            if (!PermissionManager.isPermissionGranted(context, PermissionType.SHIZUKU)) {
                Log.d(TAG, "Shizuku 权限未授予")
                missingPermissions.add(CheckResult.NeedsShizuku)
            } else {
                Log.d(TAG, "✓ Shizuku 权限已授予")
            }
        }

        // 4. 检查无障碍服务（仅在使用无障碍或混合模式时需要）
        val needsAccessibility = PermissionManager.needsMediaProjectionPermission(settings.executionStrategy)
        Log.d(TAG, "需要无障碍服务: $needsAccessibility")

        if (needsAccessibility) {
            val isAccessibilityEnabled = PermissionManager.isPermissionGranted(context, PermissionType.ACCESSIBILITY_SERVICE)
            val isAccessibilityConnected = AlianAccessibilityService.isConnected()
            Log.d(TAG, "无障碍服务状态: enabled=$isAccessibilityEnabled, connected=$isAccessibilityConnected")

            if (!isAccessibilityEnabled || !isAccessibilityConnected) {
                Log.d(TAG, "无障碍服务未就绪（未开启或未连接）")
                missingPermissions.add(CheckResult.NeedsAccessibilityService)
            } else {
                Log.d(TAG, "✓ 无障碍服务已启用")
            }
        }

        // 5. 检查 MediaProjection 权限（仅在使用无障碍或混合模式时需要）
        val needsMediaProjection = PermissionManager.needsMediaProjectionPermission(settings.executionStrategy)
        Log.d(TAG, "需要 MediaProjection 权限: $needsMediaProjection")
        Log.d(TAG, "MediaProjection 权限状态: resultCode=$mediaProjectionResultCode, data=${if (mediaProjectionData != null) "available" else "null"}")

        if (needsMediaProjection) {
            if (!PermissionManager.isPermissionGranted(context, PermissionType.MEDIA_PROJECTION, mediaProjectionResultCode, mediaProjectionData)) {
                Log.d(TAG, "MediaProjection 权限未授予")
                missingPermissions.add(CheckResult.NeedsMediaProjection)
            } else {
                Log.d(TAG, "✓ MediaProjection 权限已授予")
            }
        }

        // 返回结果
        return when {
            missingPermissions.isEmpty() -> {
                Log.d(TAG, "========== 所有权限已授予 ==========")
                CheckResult.Granted
            }
            missingPermissions.size == 1 -> {
                Log.d(TAG, "========== 缺失 1 个权限: $missingPermissions ==========")
                missingPermissions[0]
            }
            else -> {
                Log.d(TAG, "========== 缺失 ${missingPermissions.size} 个权限: $missingPermissions ==========")
                CheckResult.NeedsMultiplePermissions(missingPermissions)
            }
        }
    }

    /**
     * 打开录音权限设置
     */
    fun openRecordAudioSettings() {
        PermissionManager.openPermissionSettings(context, PermissionType.RECORD_AUDIO)
    }

    /**
     * 打开悬浮窗权限设置
     */
    fun openSystemAlertWindowSettings() {
        PermissionManager.openPermissionSettings(context, PermissionType.SYSTEM_ALERT_WINDOW)
    }

    /**
     * 打开无障碍服务设置
     */
    fun openAccessibilitySettings() {
        PermissionManager.openPermissionSettings(context, PermissionType.ACCESSIBILITY_SERVICE)
    }

    /**
     * 创建 MediaProjection 请求 Intent
     */
    fun createMediaProjectionRequestIntent(): Intent {
        return PermissionManager.createMediaProjectionRequestIntent(context)
    }

    /**
     * 获取权限的详细描述
     */
    fun getPermissionDescription(result: CheckResult): String {
        return when (result) {
            CheckResult.Granted -> "所有权限已授予"
            CheckResult.NeedsRecordAudio -> "录音权限用于语音识别和对话"
            CheckResult.NeedsSystemAlertWindow -> "悬浮窗权限用于显示执行进度和控制界面"
            CheckResult.NeedsAccessibilityService -> "无障碍服务用于自动化操作和控制其他应用（需保持已开启且已连接）"
            CheckResult.NeedsMediaProjection -> "屏幕录制权限用于截取屏幕内容进行视觉识别\n\n注意：此权限仅用于截图，不会录制视频或音频"
            CheckResult.NeedsShizuku -> "Shizuku 权限用于控制手机操作（shizuku_only 模式必需）"
            is CheckResult.NeedsMultiplePermissions -> {
                val descriptions = result.missingPermissions.map { getPermissionDescription(it) }
                "需要以下权限才能正常使用手机通话功能：\n\n${descriptions.joinToString("\n\n") { "• $it" }}"
            }
        }
    }

    /**
     * 获取权限的标题
     */
    fun getPermissionTitle(result: CheckResult): String {
        return when (result) {
            CheckResult.Granted -> "权限已授予"
            CheckResult.NeedsRecordAudio -> "需要录音权限"
            CheckResult.NeedsSystemAlertWindow -> "需要悬浮窗权限"
            CheckResult.NeedsAccessibilityService -> "需要无障碍服务"
            CheckResult.NeedsMediaProjection -> "需要屏幕录制权限"
            CheckResult.NeedsShizuku -> "需要 Shizuku 权限"
            is CheckResult.NeedsMultiplePermissions -> "需要多个权限"
        }
    }

    /**
     * 打开权限设置
     */
    fun openPermissionSettings(result: CheckResult) {
        when (result) {
            CheckResult.NeedsRecordAudio -> {
                PermissionManager.openPermissionSettings(context, PermissionType.RECORD_AUDIO)
            }
            CheckResult.NeedsSystemAlertWindow -> {
                PermissionManager.openPermissionSettings(context, PermissionType.SYSTEM_ALERT_WINDOW)
            }
            CheckResult.NeedsAccessibilityService -> {
                // 优先打开无障碍服务设置
                PermissionManager.openPermissionSettings(context, PermissionType.ACCESSIBILITY_SERVICE)
            }
            CheckResult.NeedsMediaProjection -> {
                // MediaProjection 需要特殊处理，由调用方处理
                // 这里不做任何操作，调用方应该通过 onRequireMediaProjection 回调处理
                Log.w(TAG, "MediaProjection 权限需要调用方通过回调处理")
            }
            CheckResult.NeedsShizuku -> {
                PermissionManager.openPermissionSettings(context, PermissionType.SHIZUKU)
            }
            is CheckResult.NeedsMultiplePermissions -> {
                // 优先处理非 MediaProjection 权限（因为 MediaProjection 需要特殊处理）
                val nonMediaProjectionPermission = result.missingPermissions.firstOrNull {
                    it !is CheckResult.NeedsMediaProjection
                }
                if (nonMediaProjectionPermission != null) {
                    openPermissionSettings(nonMediaProjectionPermission)
                } else {
                    // 所有缺失权限都是 MediaProjection，由调用方处理
                    Log.w(TAG, "所有缺失权限都是 MediaProjection，需要调用方通过回调处理")
                }
            }
            CheckResult.Granted -> {
                // 不需要打开设置
            }
        }
    }
}
