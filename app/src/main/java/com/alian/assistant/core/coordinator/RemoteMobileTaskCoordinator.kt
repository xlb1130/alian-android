package com.alian.assistant.core.coordinator

import android.util.Log
import com.alian.assistant.core.alian.backend.*
import com.alian.assistant.data.ExecutionRecord
import com.alian.assistant.data.ExecutionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 远端移动端任务执行桥接接口
 * 由 MainActivity 实现此接口，用于将执行请求从 ViewModel 传递到 Activity 层
 */
interface RemoteMobileTaskExecutionBridge {
    /**
     * 执行远端移动端任务
     * @param task 远端任务
     * @param onProgress 进度回调
     * @param onComplete 完成回调
     */
    suspend fun executeRemoteTask(
        task: RemoteMobileTask,
        onProgress: (Int, String) -> Unit,
        onComplete: (RemoteTaskExecutionResult) -> Unit
    )

    /**
     * 取消当前执行的任务
     */
    fun cancelCurrentTask()

    /**
     * 检查是否可以执行任务（设备控制权是否可用）
     */
    fun canExecuteTask(): Boolean

    /**
     * 获取不可执行原因（用于更精确的错误提示）
     */
    fun getCannotExecuteReason(): String? = null
}

/**
 * 远端任务执行结果
 */
data class RemoteTaskExecutionResult(
    val success: Boolean,
    val status: String,  // succeeded/failed/cancelled/timeout
    val summary: String? = null,
    val structuredData: JsonObject? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val executionRecordId: String? = null,
    val durationMs: Long = 0
)

/**
 * 远端移动端任务协调器
 * 
 * 职责：
 * 1. 管理任务的确认、执行、完成流程
 * 2. 协调 ViewModel 和 Activity 之间的执行桥接
 * 3. 上报执行结果到服务端
 * 4. 维护本地执行记录
 */
class RemoteMobileTaskCoordinator(
    private val scope: CoroutineScope,
    private val executionRepository: ExecutionRepository
) {
    private val tag = "RemoteMobileTaskCoord"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // 执行桥接
    private var executionBridge: RemoteMobileTaskExecutionBridge? = null

    // 当前执行的任务
    private var currentTask: RemoteMobileTask? = null
    private var currentJob: Job? = null

    // 执行状态
    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    // 当前任务进度
    private val _currentProgress = MutableStateFlow(0)
    val currentProgress: StateFlow<Int> = _currentProgress.asStateFlow()

    // 当前状态消息
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /**
     * 设置执行桥接
     */
    fun setExecutionBridge(bridge: RemoteMobileTaskExecutionBridge?) {
        this.executionBridge = bridge
        Log.d(tag, "执行桥接已设置: ${bridge != null}")
    }

    /**
     * 确认并执行远端任务
     * 
     * @param task 远端任务
     * @param backendClient BackendChatClient
     * @param sessionId 会话ID
     * @param onStatusChange 状态变化回调
     */
    suspend fun confirmAndExecute(
        task: RemoteMobileTask,
        backendClient: BackendChatClient?,
        sessionId: String,
        onStatusChange: (RemoteMobileTask) -> Unit
    ) {
        if (_isExecuting.value) {
            Log.w(tag, "已有任务在执行中，忽略新任务: ${task.taskId}")
            return
        }

        val bridge = executionBridge
        if (bridge == null) {
            Log.e(tag, "执行桥接未设置")
            onStatusChange(task.copy(
                status = MobileTaskStatus.FAILED,
                errorMessage = "执行桥接未设置"
            ))
            return
        }

        if (!bridge.canExecuteTask()) {
            val reason = bridge.getCannotExecuteReason()
                ?: "设备当前不可执行，请检查无障碍/Shizuku状态"
            Log.w(tag, "设备当前不可执行任务: $reason")
            val isPermissionIssue = reason.contains("需要") ||
                reason.contains("权限") ||
                reason.contains("授权")
            if (isPermissionIssue) {
                // 缺权限时不标记任务失败，仅提示并保持待执行状态
                onStatusChange(task.copy(
                    status = MobileTaskStatus.PENDING,
                    phase = MobileTaskPhase.CREATED,
                    statusMessage = reason,
                    errorCode = null,
                    errorMessage = null
                ))
                return
            }

            onStatusChange(task.copy(
                status = MobileTaskStatus.FAILED,
                errorCode = MobileTaskErrorCodes.DEVICE_BUSY,
                errorMessage = reason
            ))
            return
        }

        currentTask = task
        _isExecuting.value = true
        currentJob = scope.launch {
            try {
                // 1. 更新状态为已确认
                val confirmedTask = task.copy(
                    status = MobileTaskStatus.CONFIRMED,
                    phase = MobileTaskPhase.CONFIRMED
                )
                onStatusChange(confirmedTask)

                // 2. 调用服务端确认接口
                if (backendClient != null) {
                    val confirmResult = backendClient.confirmMobileTask(sessionId, task.taskId)
                    if (confirmResult.isFailure) {
                        Log.e(tag, "确认任务失败: ${confirmResult.exceptionOrNull()?.message}")
                    }
                }

                // 3. 更新状态为执行中
                val executingTask = confirmedTask.copy(
                    status = MobileTaskStatus.EXECUTING,
                    phase = MobileTaskPhase.EXECUTING
                )
                onStatusChange(executingTask)

                // 4. 执行任务
                val startTime = System.currentTimeMillis()
                var result: RemoteTaskExecutionResult? = null

                bridge.executeRemoteTask(
                    task = executingTask,
                    onProgress = { progress, message ->
                        _currentProgress.value = progress
                        _statusMessage.value = message
                        // 更新进度
                        val progressTask = executingTask.copy(
                            progress = progress,
                            statusMessage = message
                        )
                        onStatusChange(progressTask)
                    },
                    onComplete = { executionResult ->
                        result = executionResult
                    }
                )

                val duration = System.currentTimeMillis() - startTime

                // 5. 处理执行结果
                val finalResult = result ?: RemoteTaskExecutionResult(
                    success = false,
                    status = "failed",
                    errorMessage = "执行结果为空"
                )

                val finalTask = if (finalResult.success) {
                    executingTask.copy(
                        status = MobileTaskStatus.SUCCEEDED,
                        phase = MobileTaskPhase.COMPLETED,
                        progress = 100,
                        resultSummary = finalResult.summary,
                        localExecutionRecordId = finalResult.executionRecordId
                    )
                } else {
                    executingTask.copy(
                        status = MobileTaskStatus.FAILED,
                        phase = MobileTaskPhase.FAILED,
                        errorCode = finalResult.errorCode,
                        errorMessage = finalResult.errorMessage
                    )
                }
                onStatusChange(finalTask)

                // 6. 上报完成结果到服务端
                if (backendClient != null) {
                    val completeRequest = MobileTaskCompleteRequest(
                        task_id = task.taskId,
                        status = finalResult.status,
                        result = MobileTaskResult(
                            summary = finalResult.summary,
                            structured_data = finalResult.structuredData,
                            execution_duration_ms = duration,
                            execution_record_id = finalResult.executionRecordId
                        ),
                        error = if (!finalResult.success) {
                            MobileTaskError(
                                code = finalResult.errorCode ?: MobileTaskErrorCodes.INTERNAL_ERROR,
                                message = finalResult.errorMessage ?: "Unknown error"
                            )
                        } else null
                    )

                    val completeResult = backendClient.completeMobileTask(sessionId, task.taskId, completeRequest)
                    if (completeResult.isFailure) {
                        Log.e(tag, "上报任务完成失败: ${completeResult.exceptionOrNull()?.message}")
                    } else {
                        Log.d(tag, "任务完成已上报: ${task.taskId}")
                    }
                }

            } catch (e: Exception) {
                Log.e(tag, "执行任务异常", e)
                val failedTask = task.copy(
                    status = MobileTaskStatus.FAILED,
                    phase = MobileTaskPhase.FAILED,
                    errorCode = MobileTaskErrorCodes.INTERNAL_ERROR,
                    errorMessage = e.message ?: "执行异常"
                )
                onStatusChange(failedTask)
            } finally {
                _isExecuting.value = false
                _currentProgress.value = 0
                _statusMessage.value = null
                currentTask = null
            }
        }
    }

    /**
     * 取消当前任务
     */
    fun cancelCurrentTask() {
        currentJob?.cancel()
        executionBridge?.cancelCurrentTask()
        _isExecuting.value = false
        _currentProgress.value = 0
        _statusMessage.value = null
        currentTask = null
        Log.d(tag, "任务已取消")
    }

    /**
     * 重试任务
     */
    suspend fun retryTask(
        task: RemoteMobileTask,
        backendClient: BackendChatClient?,
        sessionId: String,
        onStatusChange: (RemoteMobileTask) -> Unit
    ) {
        // 重置任务状态
        val resetTask = task.copy(
            status = MobileTaskStatus.PENDING,
            phase = MobileTaskPhase.CREATED,
            progress = 0,
            statusMessage = null,
            errorMessage = null,
            errorCode = null
        )
        onStatusChange(resetTask)

        // 重新执行
        confirmAndExecute(resetTask, backendClient, sessionId, onStatusChange)
    }

    /**
     * 获取当前执行的任务
     */
    fun getCurrentTask(): RemoteMobileTask? = currentTask

    /**
     * 检查是否正在执行
     */
    fun isCurrentlyExecuting(): Boolean = _isExecuting.value
}
