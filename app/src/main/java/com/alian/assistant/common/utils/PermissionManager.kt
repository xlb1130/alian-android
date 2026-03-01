package com.alian.assistant.common.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.common.utils.PermissionManager.getPermissionDetailedDescription
import com.alian.assistant.common.utils.PermissionManager.getPermissionDialogTitle
import rikka.shizuku.Shizuku

/**
 * 权限状态枚举
 */
enum class PermissionStatus {
    GRANTED,        // 已授予
    DENIED,         // 已拒绝
    NOT_REQUESTED   // 未请求
}

/**
 * 权限类型枚举
 */
enum class PermissionType {
    RECORD_AUDIO,           // 录音权限
    CAMERA,                 // 相机权限
    POST_NOTIFICATIONS,     // 通知权限
    SYSTEM_ALERT_WINDOW,    // 悬浮窗权限
    ACCESSIBILITY_SERVICE,  // 无障碍服务
    MEDIA_PROJECTION,       // 屏幕录制权限
    SHIZUKU                 // Shizuku 权限
}

/**
 * 权限管理器
 * 
 * 统一管理应用的所有权限状态检查和申请
 * 
 * 设计原则：
 * 1. 懒加载：权限在需要时才申请，避免启动时一次性申请所有权限
 * 2. 引导式：先解释权限用途，再申请
 * 3. 集中管理：所有权限状态检查统一通过此类进行
 */
object PermissionManager {
    private const val TAG = "PermissionManager"

    /**
     * 创建 MediaProjection 请求 Intent
     * 
     * @param context Context
     * @return MediaProjection 请求 Intent
     */
    fun createMediaProjectionRequestIntent(context: Context): Intent {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return projectionManager.createScreenCaptureIntent()
    }

    /**
     * 获取权限的详细用途描述（用于引导对话框）
     * 
     * @param type 权限类型
     * @return 详细描述文本
     */
    fun getPermissionDetailedDescription(type: PermissionType): String {
        return when (type) {
            PermissionType.RECORD_AUDIO -> "语音输入和语音通话功能需要录音权限才能正常工作。"
            PermissionType.CAMERA -> "视频通话功能需要相机权限才能正常工作。"
            PermissionType.POST_NOTIFICATIONS -> "显示后台运行通知，防止应用被系统回收。"
            PermissionType.SYSTEM_ALERT_WINDOW -> "悬浮窗界面需要悬浮窗权限才能正常显示。"
            PermissionType.ACCESSIBILITY_SERVICE -> "自动化功能需要无障碍服务权限才能控制其他应用。"
            PermissionType.MEDIA_PROJECTION -> "自动化功能需要屏幕录制权限才能截取屏幕内容进行视觉识别。\n\n注意：此权限仅用于截图，不会录制视频或音频。"
            PermissionType.SHIZUKU -> "Shizuku 是一个高效的设备控制工具，需要安装并授权 Shizuku App。\n\n授权方式：\n1. 确保已安装 Shizuku App\n2. 通过 ADB 授权（推荐）或 Root 授权\n3. 在 Shizuku App 中授予艾莲权限"
        }
    }

    /**
     * 获取权限引导对话框的标题
     * 
     * @param type 权限类型
     * @return 对话框标题
     */
    fun getPermissionDialogTitle(type: PermissionType): String {
        return when (type) {
            PermissionType.RECORD_AUDIO -> "需要录音权限"
            PermissionType.CAMERA -> "需要相机权限"
            PermissionType.POST_NOTIFICATIONS -> "需要通知权限"
            PermissionType.SYSTEM_ALERT_WINDOW -> "需要悬浮窗权限"
            PermissionType.ACCESSIBILITY_SERVICE -> "需要无障碍服务权限"
            PermissionType.MEDIA_PROJECTION -> "需要屏幕录制权限"
            PermissionType.SHIZUKU -> "需要 Shizuku 权限"
        }
    }

    /**
     * 检查权限是否需要 MediaProjection
         * 
         * @param executionStrategy 执行策略
         * @return 是否需要 MediaProjection 权限
         */
        fun needsMediaProjectionPermission(executionStrategy: String): Boolean {
            return executionStrategy in listOf(
                "accessibility_only",
                "hybrid",
                "auto"
            )
        }
    
        /**
         * 检查 Shizuku 是否可用
         * 
         * @return Shizuku 是否可用
         */
        fun isShizukuAvailable(): Boolean {
            return try {
                Shizuku.pingBinder()
            } catch (e: Exception) {
                Log.e(TAG, "isShizukuAvailable error", e)
                false
            }
        }
    
        /**
         * 检查 Shizuku 权限
         *
         * @return 是否已授予 Shizuku 权限
         */
        fun isShizukuPermissionGranted(): Boolean {
            return try {
                // 先检查 binder 是否可用
                if (!Shizuku.pingBinder()) {
                    Log.d(TAG, "isShizukuPermissionGranted: Shizuku binder not available")
                    return false
                }
                // binder 可用，再检查权限
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: IllegalStateException) {
                // binder haven't been received
                Log.d(TAG, "isShizukuPermissionGranted: Shizuku binder not received yet")
                false
            } catch (e: Exception) {
                Log.e(TAG, "isShizukuPermissionGranted error", e)
                false
            }
        }
    
        /**
         * 检查 Shizuku 版本是否支持
         * 
         * @return 版本是否支持（需要 v11 及以上）
         */
        fun isShizukuVersionSupported(): Boolean {
            return try {
                !Shizuku.isPreV11()
            } catch (e: Exception) {
                Log.e(TAG, "isShizukuVersionSupported error", e)
                false
            }
        }
    
        /**
         * 请求 Shizuku 权限
         * 
         * @param requestCode 请求代码，默认为 0
         * @return 请求是否成功发起
         */
        fun requestShizukuPermission(requestCode: Int = 0): Boolean {
            return try {
                if (!isShizukuAvailable()) {
                    Log.w(TAG, "Shizuku is not available, cannot request permission")
                    return false
                }
    
                if (!isShizukuVersionSupported()) {
                    Log.w(TAG, "Shizuku version is too old, cannot request permission")
                    return false
                }
    
                if (isShizukuPermissionGranted()) {
                    Log.d(TAG, "Shizuku permission already granted")
                    return true
                }
    
                Shizuku.requestPermission(requestCode)
                Log.d(TAG, "Shizuku permission requested with code: $requestCode")
                true
            } catch (e: Exception) {
                Log.e(TAG, "requestShizukuPermission error", e)
                false
            }
        }
    
        /**
         * 检查执行策略是否需要 Shizuku 权限
         * 
         * @param executionStrategy 执行策略
         * @return 是否需要 Shizuku 权限
         */
        fun needsShizukuPermission(executionStrategy: String): Boolean {
            return executionStrategy in listOf(
                "shizuku_only",
                "hybrid",
                "auto"
            )
        }
    /**
     * 权限状态数据类
     */
    data class PermissionState(
        val type: PermissionType,
        val status: PermissionStatus,
        val canRequest: Boolean = true  // 是否可以再次请求（用于处理"不再询问"的情况）
    )

    /**
     * 检查录音权限
     */
    fun checkRecordAudio(context: Context): PermissionState {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        return PermissionState(
            type = PermissionType.RECORD_AUDIO,
            status = if (granted) PermissionStatus.GRANTED else PermissionStatus.NOT_REQUESTED
        )
    }

    /**
     * 检查相机权限
     */
    fun checkCamera(context: Context): PermissionState {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        return PermissionState(
            type = PermissionType.CAMERA,
            status = if (granted) PermissionStatus.GRANTED else PermissionStatus.NOT_REQUESTED
        )
    }

    /**
     * 检查通知权限（Android 13+）
     */
    fun checkPostNotifications(context: Context): PermissionState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Android 13 以下无需显式通知权限
            return PermissionState(
                type = PermissionType.POST_NOTIFICATIONS,
                status = PermissionStatus.GRANTED
            )
        }

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return PermissionState(
            type = PermissionType.POST_NOTIFICATIONS,
            status = if (granted) PermissionStatus.GRANTED else PermissionStatus.NOT_REQUESTED
        )
    }

    /**
     * 检查悬浮窗权限
     */
    fun checkSystemAlertWindow(context: Context): PermissionState {
        val granted = Settings.canDrawOverlays(context)
        return PermissionState(
            type = PermissionType.SYSTEM_ALERT_WINDOW,
            status = if (granted) PermissionStatus.GRANTED else PermissionStatus.NOT_REQUESTED
        )
    }

    /**
     * 检查无障碍服务
     */
    fun checkAccessibilityService(context: Context): PermissionState {
        val enabled = AccessibilityUtils.isAccessibilityServiceEnabled(context)
        return PermissionState(
            type = PermissionType.ACCESSIBILITY_SERVICE,
            status = if (enabled) PermissionStatus.GRANTED else PermissionStatus.NOT_REQUESTED
        )
    }

    /**
     * 检查 MediaProjection 权限
     * 注意：MediaProjection 权限无法通过标准 API 检查，需要外部维护状态
     * 
     * @param resultCode MediaProjection 的 resultCode
     * @param data MediaProjection 的 Intent data
     */
    fun checkMediaProjection(
        resultCode: Int?,
        data: Intent?
    ): PermissionState {
        val available = resultCode != null && data != null
        return PermissionState(
            type = PermissionType.MEDIA_PROJECTION,
            status = if (available) PermissionStatus.GRANTED else PermissionStatus.NOT_REQUESTED
        )
    }

    /**
     * 检查 Shizuku 权限
     */
    fun checkShizuku(): PermissionState {
        val available = isShizukuPermissionGranted()
        return PermissionState(
            type = PermissionType.SHIZUKU,
            status = if (available) PermissionStatus.GRANTED else PermissionStatus.NOT_REQUESTED
        )
    }

    /**
     * 获取所有权限状态
     */
    fun getAllPermissionStates(context: Context, mediaProjectionResultCode: Int? = null, mediaProjectionData: Intent? = null): Map<PermissionType, PermissionState> {
        return mapOf(
            PermissionType.RECORD_AUDIO to checkRecordAudio(context),
            PermissionType.CAMERA to checkCamera(context),
            PermissionType.POST_NOTIFICATIONS to checkPostNotifications(context),
            PermissionType.SYSTEM_ALERT_WINDOW to checkSystemAlertWindow(context),
            PermissionType.ACCESSIBILITY_SERVICE to checkAccessibilityService(context),
            PermissionType.MEDIA_PROJECTION to checkMediaProjection(mediaProjectionResultCode, mediaProjectionData),
            PermissionType.SHIZUKU to checkShizuku()
        )
    }

    /**
     * 检查指定权限是否已授予
     */
    fun isPermissionGranted(
        context: Context,
        type: PermissionType,
        mediaProjectionResultCode: Int? = null,
        mediaProjectionData: Intent? = null
    ): Boolean {
        return when (type) {
            PermissionType.RECORD_AUDIO -> checkRecordAudio(context).status == PermissionStatus.GRANTED
            PermissionType.CAMERA -> checkCamera(context).status == PermissionStatus.GRANTED
            PermissionType.POST_NOTIFICATIONS -> checkPostNotifications(context).status == PermissionStatus.GRANTED
            PermissionType.SYSTEM_ALERT_WINDOW -> checkSystemAlertWindow(context).status == PermissionStatus.GRANTED
            PermissionType.ACCESSIBILITY_SERVICE -> checkAccessibilityService(context).status == PermissionStatus.GRANTED
            PermissionType.MEDIA_PROJECTION -> checkMediaProjection(mediaProjectionResultCode, mediaProjectionData).status == PermissionStatus.GRANTED
            PermissionType.SHIZUKU -> checkShizuku().status == PermissionStatus.GRANTED
        }
    }

    /**
     * 打开权限设置页面
     */
    fun openPermissionSettings(context: Context, type: PermissionType) {
        try {
            val intent = when (type) {
                PermissionType.ACCESSIBILITY_SERVICE -> {
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }
                PermissionType.SYSTEM_ALERT_WINDOW -> {
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                }
                PermissionType.SHIZUKU -> {
                    // Shizuku 需要跳转到 Shizuku App
                    val shizukuIntent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    shizukuIntent ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                }
                else -> {
                    // 其他权限打开应用详情页
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                }
            }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open permission settings for $type", e)
            // 如果无法打开特定设置页，尝试打开通用设置
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open general settings", e2)
            }
        }
    }

    /**
     * 获取权限的用途描述
     */
    fun getPermissionDescription(type: PermissionType): String {
        return when (type) {
            PermissionType.RECORD_AUDIO -> "用于语音输入和语音通话功能"
            PermissionType.CAMERA -> "用于视频通话功能"
            PermissionType.POST_NOTIFICATIONS -> "用于显示后台运行通知，防止应用被系统回收"
            PermissionType.SYSTEM_ALERT_WINDOW -> "用于显示悬浮窗界面"
            PermissionType.ACCESSIBILITY_SERVICE -> "用于自动化操作和控制其他应用"
            PermissionType.MEDIA_PROJECTION -> "用于屏幕截图和录制功能"
            PermissionType.SHIZUKU -> "用于更高效的设备控制（需要安装 Shizuku App）"
        }
    }

    /**
     * 获取权限的名称
     */
    fun getPermissionName(type: PermissionType): String {
        return when (type) {
            PermissionType.RECORD_AUDIO -> "录音权限"
            PermissionType.CAMERA -> "相机权限"
            PermissionType.POST_NOTIFICATIONS -> "通知权限"
            PermissionType.SYSTEM_ALERT_WINDOW -> "悬浮窗权限"
            PermissionType.ACCESSIBILITY_SERVICE -> "无障碍服务"
            PermissionType.MEDIA_PROJECTION -> "屏幕录制权限"
            PermissionType.SHIZUKU -> "Shizuku 权限"
        }
    }

    /**
     * 检查权限是否需要运行时申请
     */
    fun isRuntimePermission(type: PermissionType): Boolean {
        return type in listOf(
            PermissionType.RECORD_AUDIO,
            PermissionType.CAMERA,
            PermissionType.POST_NOTIFICATIONS
        )
    }

    /**
     * 检查权限是否需要跳转设置页
     */
    fun isSettingsPermission(type: PermissionType): Boolean {
        return type in listOf(
            PermissionType.SYSTEM_ALERT_WINDOW,
            PermissionType.ACCESSIBILITY_SERVICE,
            PermissionType.SHIZUKU
        )
    }

    /**
     * 检查权限是否是系统弹窗权限
     */
    fun isSystemDialogPermission(type: PermissionType): Boolean {
        return type == PermissionType.MEDIA_PROJECTION
    }

    /**
     * 执行提示信息数据类
     */
    data class ExecutionPromptInfo(
        val title: String,
        val description: String,
        val showRefreshButton: Boolean,
        val canExecute: Boolean
    )

    /**
     * 根据执行策略和权限状态获取执行提示信息
     * 
     * @param executionStrategy 执行策略
     * @param shizukuAvailable Shizuku 是否可用
     * @param accessibilityEnabled 无障碍服务是否已启用
     * @param mediaProjectionAvailable MediaProjection 是否可用
     * @return 执行提示信息
     */
    fun getExecutionPromptInfo(
        executionStrategy: String,
        shizukuAvailable: Boolean,
        accessibilityEnabled: Boolean,
        mediaProjectionAvailable: Boolean
    ): ExecutionPromptInfo {
        // 添加日志用于调试
        // Log.d("PermissionManager", "getExecutionPromptInfo - executionStrategy: $executionStrategy, shizukuAvailable: $shizukuAvailable, accessibilityEnabled: $accessibilityEnabled, mediaProjectionAvailable: $mediaProjectionAvailable")
        
        // 先判断执行策略，再判断权限状态
        return when (executionStrategy) {
            "shizuku_only" -> {
                when {
                    !shizukuAvailable -> ExecutionPromptInfo(
                        title = "需要连接 Shizuku",
                        description = "请先连接 Shizuku",
                        showRefreshButton = true,
                        canExecute = false
                    )
                    else -> ExecutionPromptInfo(
                        title = "准备就绪",
                        description = "告诉我你想做什么",
                        showRefreshButton = false,
                        canExecute = true
                    )
                }
            }
            "accessibility_only" -> {
                when {
                    !accessibilityEnabled -> ExecutionPromptInfo(
                        title = "需要无障碍服务",
                        description = "请先启用无障碍服务",
                        showRefreshButton = false,
                        canExecute = false
                    )
                    !needsMediaProjectionPermission(executionStrategy) || mediaProjectionAvailable -> ExecutionPromptInfo(
                        title = "准备就绪",
                        description = "告诉我你想做什么",
                        showRefreshButton = false,
                        canExecute = true
                    )
                    else -> ExecutionPromptInfo(
                        title = "需要屏幕录制权限",
                        description = "请先授予屏幕录制权限",
                        showRefreshButton = false,
                        canExecute = false
                    )
                }
            }
            "hybrid" -> {
                when {
                    !shizukuAvailable && !accessibilityEnabled -> ExecutionPromptInfo(
                        title = "需要连接 Shizuku 或无障碍服务",
                        description = "请先连接 Shizuku 或启用无障碍服务",
                        showRefreshButton = true,
                        canExecute = false
                    )
                    !shizukuAvailable -> ExecutionPromptInfo(
                        title = "Shizuku 不可用",
                        description = "已降级到无障碍模式",
                        showRefreshButton = true,
                        canExecute = accessibilityEnabled
                    )
                    !accessibilityEnabled -> ExecutionPromptInfo(
                        title = "需要无障碍服务",
                        description = "请先启用无障碍服务",
                        showRefreshButton = false,
                        canExecute = false
                    )
                    needsMediaProjectionPermission(executionStrategy) && !mediaProjectionAvailable -> ExecutionPromptInfo(
                        title = "需要屏幕录制权限",
                        description = "请先授予屏幕录制权限",
                        showRefreshButton = false,
                        canExecute = false
                    )
                    else -> ExecutionPromptInfo(
                        title = "准备就绪",
                        description = "告诉我你想做什么",
                        showRefreshButton = false,
                        canExecute = true
                    )
                }
            }
            "auto" -> {
                when {
                    !shizukuAvailable && !accessibilityEnabled -> ExecutionPromptInfo(
                        title = "需要连接 Shizuku 或无障碍服务",
                        description = "请先连接 Shizuku 或启用无障碍服务",
                        showRefreshButton = true,
                        canExecute = false
                    )
                    !shizukuAvailable -> ExecutionPromptInfo(
                        title = "Shizuku 不可用",
                        description = "已降级到无障碍模式",
                        showRefreshButton = true,
                        canExecute = accessibilityEnabled
                    )
                    !accessibilityEnabled -> ExecutionPromptInfo(
                        title = "需要无障碍服务",
                        description = "请先启用无障碍服务",
                        showRefreshButton = false,
                        canExecute = false
                    )
                    needsMediaProjectionPermission(executionStrategy) && !mediaProjectionAvailable -> ExecutionPromptInfo(
                        title = "需要屏幕录制权限",
                        description = "请先授予屏幕录制权限",
                        showRefreshButton = false,
                        canExecute = false
                    )
                    else -> ExecutionPromptInfo(
                        title = "准备就绪",
                        description = "告诉我你想做什么",
                        showRefreshButton = false,
                        canExecute = true
                    )
                }
            }
            else -> ExecutionPromptInfo(
                title = "准备就绪",
                description = "告诉我你想做什么",
                showRefreshButton = false,
                canExecute = true
            )
        }
    }

    /**
     * 检查 Shizuku 状态并返回状态信息
     * 
     * @return ShizukuStatus 对象，包含可用性和权限状态
     */
    fun checkShizukuStatus(): ShizukuStatus {
        val available = isShizukuAvailable()
        val permissionGranted = isShizukuPermissionGranted()
        val versionSupported = isShizukuVersionSupported()
        
        return ShizukuStatus(
            available = available,
            permissionGranted = permissionGranted,
            versionSupported = versionSupported,
            status = when {
                !available -> ShizukuStatus.Status.NOT_AVAILABLE
                !versionSupported -> ShizukuStatus.Status.VERSION_TOO_OLD
                !permissionGranted -> ShizukuStatus.Status.PERMISSION_NOT_GRANTED
                else -> ShizukuStatus.Status.READY
            }
        )
    }

    /**
     * 尝试请求 Shizuku 权限
     * 
     * @return 请求是否成功发起（不代表权限已授予）
     */
    fun requestShizukuPermissionSafely(): Boolean {
        if (!isShizukuAvailable()) {
            Log.w(TAG, "Shizuku is not available, cannot request permission")
            return false
        }

        if (!isShizukuVersionSupported()) {
            Log.w(TAG, "Shizuku version is too old, cannot request permission")
            return false
        }

        if (isShizukuPermissionGranted()) {
            Log.d(TAG, "Shizuku permission already granted")
            return true
        }

        return requestShizukuPermission()
    }

    /**
     * Shizuku 状态数据类
     */
    data class ShizukuStatus(
        val available: Boolean,
        val permissionGranted: Boolean,
        val versionSupported: Boolean,
        val status: Status
    ) {
        enum class Status {
            NOT_AVAILABLE,           // Shizuku 不可用
            VERSION_TOO_OLD,         // Shizuku 版本过低
            PERMISSION_NOT_GRANTED,  // Shizuku 可用但未授权
            READY                    // Shizuku 已就绪
        }
    }
}

/**
 * 权限引导对话框 Composable 函数
 * 
 * @param showDialog 是否显示对话框
 * @param permissionType 权限类型
 * @param onConfirm 确认按钮回调
 * @param onDismiss 取消/关闭按钮回调
 */
@Composable
fun PermissionDialog(
    showDialog: Boolean,
    permissionType: PermissionType,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!showDialog) return

    val colors = BaoziTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icons.Default.Warning.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.warning
                )
            }
        },
        title = {
            Text(
                text = getPermissionDialogTitle(permissionType),
                color = colors.textPrimary
            )
        },
        text = {
            Column {
                Text(
                    text = getPermissionDetailedDescription(permissionType),
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                }
            ) {
                Text(
                    text = "去授权",
                    color = colors.primary
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(
                    text = "取消",
                    color = colors.textSecondary
                )
            }
        }
    )
}