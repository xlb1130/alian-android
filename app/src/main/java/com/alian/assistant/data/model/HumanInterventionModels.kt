package com.alian.assistant.data.model

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 人机协同数据模型
 *
 * 包含:
 * - 干预请求模型
 * - 审计日志模型
 * - 审批配置模型
 * - 用户操作枚举
 */

/**
 * 用户操作类型
 */
enum class UserAction {
    APPROVED,      // 批准
    REJECTED,      // 拒绝
    SKIPPED,       // 跳过
    TIMEOUT        // 超时
}

/**
 * 人机协同请求
 */
data class HumanInterventionRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val type: InterventionType,
    val reason: String,
    val screenshot: Bitmap? = null,
    val screenshotPath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * 获取格式化的时间戳
     */
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

/**
 * 干预类型
 */
enum class InterventionType {
    CAPTCHA,           // 验证码
    PAYMENT_CONFIRM,   // 支付确认
    SECURITY_CHECK,    // 安全检查
    MANUAL_APPROVAL,   // 人工审批
    ERROR_RECOVERY     // 错误恢复
}

/**
 * 人机协同响应
 */
data class HumanInterventionResponse(
    val requestId: String,
    val action: UserAction,
    val userComment: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 审计日志
 */
data class AuditLog(
    val logId: String = UUID.randomUUID().toString(),
    val requestId: String,
    val type: InterventionType,
    val reason: String,
    val userAction: UserAction,
    val userComment: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val screenshotPath: String? = null,
    val duration: Long = 0L  // 处理时长 (毫秒)
) {
    /**
     * 获取格式化的时间戳
     */
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * 转换为 JSON 字符串
     */
    fun toJson(): String {
        return """
            {
                "logId": "$logId",
                "requestId": "$requestId",
                "type": "$type",
                "reason": "$reason",
                "userAction": "$userAction",
                "userComment": ${userComment?.let { "\"$it\"" } ?: "null"},
                "timestamp": $timestamp,
                "screenshotPath": ${screenshotPath?.let { "\"$it\"" } ?: "null"},
                "duration": $duration
            }
        """.trimIndent()
    }
}

/**
 * 审计日志管理器
 */
class AuditLogger(
    private val logDir: File
) {
    companion object {
        private const val TAG = "AuditLogger"
        private const val LOG_FILE_NAME = "audit_log.jsonl"
    }

    private val logFile: File by lazy {
        File(logDir, LOG_FILE_NAME).apply {
            if (!exists()) {
                parentFile?.mkdirs()
                createNewFile()
            }
        }
    }

    /**
     * 记录审计日志
     */
    fun log(auditLog: AuditLog) {
        try {
            val logLine = "${auditLog.toJson()}\n"
            logFile.appendText(logLine)
            Log.d(TAG, "审计日志已记录: $auditLog")
        } catch (e: Exception) {
            Log.e(TAG, "审计日志记录失败: ${e.message}", e)
        }
    }

    /**
     * 批量记录审计日志
     */
    fun logMultiple(auditLogs: List<AuditLog>) {
        auditLogs.forEach { log(it) }
    }

    /**
     * 读取所有审计日志
     */
    fun readAllLogs(): List<AuditLog> {
        return try {
            if (!logFile.exists()) {
                return emptyList()
            }

            val logs = mutableListOf<AuditLog>()
            logFile.forEachLine { line ->
                try {
                    // 简单解析: 实际项目中应该使用 JSON 解析库
                    val type = extractJsonValue(line, "type")?.let {
                        InterventionType.valueOf(it)
                    } ?: InterventionType.MANUAL_APPROVAL

                    val userAction = extractJsonValue(line, "userAction")?.let {
                        UserAction.valueOf(it)
                    } ?: UserAction.SKIPPED

                    val log = AuditLog(
                        logId = extractJsonValue(line, "logId") ?: "",
                        requestId = extractJsonValue(line, "requestId") ?: "",
                        type = type,
                        reason = extractJsonValue(line, "reason") ?: "",
                        userAction = userAction,
                        userComment = extractJsonValue(line, "userComment"),
                        timestamp = extractJsonValue(line, "timestamp")?.toLongOrNull() ?: 0L,
                        screenshotPath = extractJsonValue(line, "screenshotPath"),
                        duration = extractJsonValue(line, "duration")?.toLongOrNull() ?: 0L
                    )
                    logs.add(log)
                } catch (e: Exception) {
                    Log.e(TAG, "解析日志行失败: $line", e)
                }
            }

            logs
        } catch (e: Exception) {
            Log.e(TAG, "读取审计日志失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 根据请求 ID 查询日志
     */
    fun findByRequestId(requestId: String): List<AuditLog> {
        return readAllLogs().filter { it.requestId == requestId }
    }

    /**
     * 根据类型查询日志
     */
    fun findByType(type: InterventionType): List<AuditLog> {
        return readAllLogs().filter { it.type == type }
    }

    /**
     * 获取最近的 N 条日志
     */
    fun getRecentLogs(limit: Int = 100): List<AuditLog> {
        return readAllLogs().takeLast(limit).reversed()
    }

    /**
     * 清空审计日志
     */
    fun clearLogs() {
        try {
            logFile.writeText("")
            Log.d(TAG, "审计日志已清空")
        } catch (e: Exception) {
            Log.e(TAG, "清空审计日志失败: ${e.message}", e)
        }
    }

    /**
     * 辅助方法: 从 JSON 字符串中提取值 (简单实现)
     */
    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"?([^,}\"]+)\"?"
        val regex = Regex(pattern)
        val match = regex.find(json)
        return match?.groupValues?.get(1)?.trim()
    }
}

/**
 * 审批配置
 */
data class ApprovalConfig(
    val autoApproveWhitelist: List<InterventionType> = emptyList(),  // 自动批准白名单
    val requireMultiApproval: Boolean = false,                        // 是否需要多人审批
    val approvalTimeout: Long = 300000L,                              // 审批超时时间 (毫秒, 默认 5 分钟)
    val maxRetryCount: Int = 3,                                       // 最大重试次数
    val enableAuditLog: Boolean = true                                // 是否启用审计日志
) {
    companion object {
        /**
         * 默认配置
         */
        fun default() = ApprovalConfig(
            autoApproveWhitelist = emptyList(),
            requireMultiApproval = false,
            approvalTimeout = 300000L,
            maxRetryCount = 3,
            enableAuditLog = true
        )

        /**
         * 严格配置 (所有操作都需要人工审批)
         */
        fun strict() = ApprovalConfig(
            autoApproveWhitelist = emptyList(),
            requireMultiApproval = true,
            approvalTimeout = 600000L,
            maxRetryCount = 1,
            enableAuditLog = true
        )

        /**
         * 宽松配置 (部分操作自动批准)
         */
        fun relaxed() = ApprovalConfig(
            autoApproveWhitelist = listOf(
                InterventionType.ERROR_RECOVERY
            ),
            requireMultiApproval = false,
            approvalTimeout = 180000L,
            maxRetryCount = 5,
            enableAuditLog = true
        )
    }
}

/**
 * 审批工作流
 */
class ApprovalWorkflow(
    private val config: ApprovalConfig = ApprovalConfig.default(),
    private val auditLogger: AuditLogger? = null
) {
    companion object {
        private const val TAG = "ApprovalWorkflow"
    }

    /**
     * 请求审批
     *
     * @param request 干预请求
     * @return 审批结果
     */
    suspend fun requestApproval(request: HumanInterventionRequest): ApprovalResult {
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "请求审批: ${request.type} - ${request.reason}")

        // 1. 检查是否在自动批准白名单
        if (config.autoApproveWhitelist.contains(request.type)) {
            Log.d(TAG, "在自动批准白名单中,自动批准")
            val duration = System.currentTimeMillis() - startTime
            recordAuditLog(request, UserAction.APPROVED, "自动批准", duration)
            return ApprovalResult.APPROVED
        }

        // 2. 检查是否需要多人审批
        if (config.requireMultiApproval) {
            Log.d(TAG, "需要多人审批")
            return requestMultiApproval(request, startTime)
        }

        // 3. 单人审批 (需要 UI 集成)
        Log.d(TAG, "等待单人审批")
        val response = requestSingleApproval(request)

        val duration = System.currentTimeMillis() - startTime
        recordAuditLog(request, response.action, response.userComment, duration)

        return when (response.action) {
            UserAction.APPROVED -> ApprovalResult.APPROVED
            UserAction.REJECTED -> ApprovalResult.REJECTED(response.userComment ?: "用户拒绝")
            UserAction.SKIPPED -> ApprovalResult.SKIPPED
            UserAction.TIMEOUT -> ApprovalResult.TIMEOUT
        }
    }

    /**
     * 单人审批 (需要 UI 集成)
     *
     * 注意: 这是一个占位符实现,实际使用时需要集成到 UI 层
     */
    private suspend fun requestSingleApproval(request: HumanInterventionRequest): HumanInterventionResponse {
        // TODO: 集成到 UI 层,显示审批对话框
        // 这里返回默认值,实际应该等待用户操作
        Log.w(TAG, "单人审批功能需要 UI 集成,当前返回默认值")

        return HumanInterventionResponse(
            requestId = request.requestId,
            action = UserAction.SKIPPED,
            userComment = "UI 未集成"
        )
    }

    /**
     * 多人审批
     */
    private suspend fun requestMultiApproval(
        request: HumanInterventionRequest,
        startTime: Long
    ): ApprovalResult {
        // TODO: 实现多人审批逻辑
        Log.w(TAG, "多人审批功能暂未实现")

        val duration = System.currentTimeMillis() - startTime
        recordAuditLog(request, UserAction.SKIPPED, "多人审批未实现", duration)

        return ApprovalResult.SKIPPED
    }

    /**
     * 记录审计日志
     */
    private fun recordAuditLog(
        request: HumanInterventionRequest,
        action: UserAction,
        comment: String?,
        duration: Long
    ) {
        if (config.enableAuditLog && auditLogger != null) {
            val log = AuditLog(
                requestId = request.requestId,
                type = request.type,
                reason = request.reason,
                userAction = action,
                userComment = comment,
                timestamp = System.currentTimeMillis(),
                screenshotPath = request.screenshotPath,
                duration = duration
            )
            auditLogger.log(log)
        }
    }
}

/**
 * 审批结果
 */
sealed class ApprovalResult {
    data object APPROVED : ApprovalResult()
    data class REJECTED(val reason: String) : ApprovalResult()
    data object SKIPPED : ApprovalResult()
    data object TIMEOUT : ApprovalResult()
}