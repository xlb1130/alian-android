package com.alian.assistant.core.alian.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 移动端任务数据模型
 * 用于服务端下发任务到移动端执行
 */

/**
 * 移动端任务状态
 */
enum class MobileTaskStatus {
    PENDING,        // 等待用户确认
    CONFIRMED,      // 已确认，等待执行
    EXECUTING,      // 执行中
    SUCCEEDED,      // 执行成功
    FAILED,         // 执行失败
    CANCELLED,      // 已取消
    TIMEOUT,        // 执行超时
    RESOLVED        // 服务端已处理完成
}

/**
 * 移动端任务阶段
 */
enum class MobileTaskPhase {
    CREATED,        // 任务创建
    CONFIRMED,      // 任务确认
    EXECUTING,      // 任务执行
    COMPLETED,      // 任务完成
    FAILED          // 任务失败
}

/**
 * 移动端任务创建事件数据
 */
@Serializable
data class MobileTaskCreatedData(
    val type: String = "mobile_task_created",
    val id: String,                     // 事件 ID
    val timestamp: Long,
    val task_id: String,                // 任务 ID
    val title: String,                  // 任务标题
    val instruction: String,            // 任务指令/描述
    val phase: String = "created",      // 任务阶段
    val priority: Int = 0,              // 优先级（数字越大优先级越高）
    val timeout_seconds: Int = 300,     // 超时时间（秒）
    val metadata: Map<String, JsonElement>? = null  // 额外元数据
)

/**
 * 移动端任务更新事件数据
 */
@Serializable
data class MobileTaskUpdatedData(
    val type: String = "mobile_task_updated",
    val id: String,                     // 事件 ID
    val timestamp: Long,
    val task_id: String,                // 任务 ID
    val phase: String,                  // 更新后的任务阶段
    val status: String? = null,         // 任务状态
    val progress: Int? = null,          // 进度百分比 (0-100)
    val message: String? = null,        // 状态消息
    val metadata: Map<String, JsonElement>? = null
)

/**
 * 移动端任务解决事件数据
 */
@Serializable
data class MobileTaskResolvedData(
    val type: String = "mobile_task_resolved",
    val id: String,                     // 事件 ID
    val timestamp: Long,
    val task_id: String,                // 任务 ID
    val status: String,                 // 最终状态：succeeded/failed/cancelled
    val result_summary: String? = null, // 结果摘要
    val resolved_by: String? = null     // 解决者（server/other_device）
)

/**
 * 远端移动端任务（客户端状态模型）
 */
data class RemoteMobileTask(
    val taskId: String,
    val eventId: String,                // 创建事件 ID
    val timestamp: Long,                // 创建时间戳
    val title: String,
    val instruction: String,
    var phase: MobileTaskPhase = MobileTaskPhase.CREATED,
    var status: MobileTaskStatus = MobileTaskStatus.PENDING,
    var progress: Int = 0,              // 进度百分比 (0-100)
    var statusMessage: String? = null,  // 状态消息
    var resultSummary: String? = null,  // 结果摘要
    var errorMessage: String? = null,   // 错误信息
    var errorCode: String? = null,      // 错误码
    var priority: Int = 0,
    var timeoutSeconds: Int = 300,
    var localExecutionRecordId: String? = null,  // 本地执行记录 ID
    var metadata: Map<String, JsonElement>? = null,
    var resolvedAt: Long? = null,       // 解决时间
    var resolvedBy: String? = null,     // 解决者
    val sessionId: String? = null       // 关联的会话ID
) {
    /**
     * 是否可以执行
     */
    fun canExecute(): Boolean {
        return status == MobileTaskStatus.PENDING || status == MobileTaskStatus.FAILED
    }

    /**
     * 是否正在执行
     */
    fun isExecuting(): Boolean {
        return status == MobileTaskStatus.EXECUTING
    }

    /**
     * 是否已完成（成功或失败）
     */
    fun isCompleted(): Boolean {
        return status in listOf(
            MobileTaskStatus.SUCCEEDED,
            MobileTaskStatus.FAILED,
            MobileTaskStatus.CANCELLED,
            MobileTaskStatus.TIMEOUT,
            MobileTaskStatus.RESOLVED
        )
    }

    /**
     * 是否可以被重试
     */
    fun canRetry(): Boolean {
        return status == MobileTaskStatus.FAILED || status == MobileTaskStatus.TIMEOUT
    }
}

/**
 * 任务完成请求
 */
@Serializable
data class MobileTaskCompleteRequest(
    val task_id: String,
    val status: String,                 // succeeded/failed/cancelled/timeout
    val result: MobileTaskResult? = null,
    val error: MobileTaskError? = null,
    val client_timestamp: Long = System.currentTimeMillis()
)

/**
 * 任务执行结果
 */
@Serializable
data class MobileTaskResult(
    val summary: String? = null,        // 结果摘要
    val structured_data: JsonObject? = null,  // 结构化数据
    val execution_duration_ms: Long? = null,  // 执行耗时
    val steps_count: Int? = null,       // 执行步骤数
    val attachments: List<Attachment>? = null,  // 附件（截图等）
    val execution_record_id: String? = null  // 本地执行记录 ID
)

/**
 * 任务执行错误
 */
@Serializable
data class MobileTaskError(
    val code: String,                   // 错误码
    val message: String,                // 错误消息
    val details: JsonObject? = null     // 错误详情
)

/**
 * 待执行任务列表响应
 */
@Serializable
data class PendingMobileTasksResponse(
    val code: Int,
    val msg: String,
    val data: PendingMobileTasksData?
)

@Serializable
data class PendingMobileTasksData(
    val tasks: List<PendingMobileTaskData>
)

@Serializable
data class PendingMobileTaskData(
    val task_id: String,
    val title: String,
    val instruction: String,
    val phase: String = "created",
    val status: String = "pending",
    val priority: Int = 0,
    val timeout_seconds: Int = 300,
    val created_at: Long,
    val metadata: Map<String, JsonElement>? = null
)

/**
 * 任务确认响应
 */
@Serializable
data class ConfirmMobileTaskResponse(
    val code: Int,
    val msg: String,
    val data: ConfirmMobileTaskData?
)

@Serializable
data class ConfirmMobileTaskData(
    val task_id: String,
    val status: String,
    val confirmed_at: Long
)

/**
 * 任务完成响应
 */
@Serializable
data class CompleteMobileTaskResponse(
    val code: Int,
    val msg: String,
    val data: CompleteMobileTaskData?
)

@Serializable
data class CompleteMobileTaskData(
    val task_id: String,
    val status: String,
    val completed_at: Long
)

/**
 * 统一错误码定义
 */
object MobileTaskErrorCodes {
    const val PERMISSION_DENIED = "permission_denied"
    const val ACCESSIBILITY_UNAVAILABLE = "accessibility_unavailable"
    const val MEDIA_PROJECTION_UNAVAILABLE = "media_projection_unavailable"
    const val USER_CANCELLED = "user_cancelled"
    const val EXECUTION_TIMEOUT = "execution_timeout"
    const val INTERNAL_ERROR = "internal_error"
    const val UNKNOWN_ERROR = "unknown_error"
    const val TASK_NOT_FOUND = "task_not_found"
    const val TASK_ALREADY_COMPLETED = "task_already_completed"
    const val DEVICE_BUSY = "device_busy"
}
