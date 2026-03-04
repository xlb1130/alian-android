package com.alian.assistant.core.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.MutableState
import com.alian.assistant.core.agent.AgentPermissionCheck
import com.alian.assistant.core.agent.improve.MobileAgentImprove
import com.alian.assistant.data.ChatMessageData
import com.alian.assistant.data.ExecutionRecord
import com.alian.assistant.data.ExecutionRepository
import com.alian.assistant.data.ExecutionStatus
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import com.alian.assistant.presentation.ui.overlay.OverlayService
import com.alian.assistant.infrastructure.ai.llm.VLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AgentRunner"

class AgentRunner(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val deviceControllerProvider: () -> IDeviceController,
    private val createVLMClient: (String?, String?, String?) -> VLMClient,
    private val executionRepository: ExecutionRepository,
    private val scope: CoroutineScope,
    private val mobileAgentState: MutableState<Agent?>,
    private val executionRecords: MutableState<List<ExecutionRecord>>,
    private val isExecuting: MutableState<Boolean>,
    private val currentRecordId: MutableState<String?>,
    private val shouldNavigateToRecord: MutableState<Boolean>,
    private val getMediaProjectionInfo: () -> Pair<Int?, Intent?>,
    private val onRequireAccessibility: (String) -> Unit,
    private val onRequireMediaProjection: (String) -> Unit
) {
    private var currentExecutionJob: Job? = null

    fun runAgent(
        instruction: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        maxSteps: Int,
        isGUIAgent: Boolean = false,
        providerId: String = "",
        ttsEnabled: Boolean = false,
        ttsVoice: String = "longyingmu_v3"
    ) {
        Log.d(TAG, "========== runAgent 开始 ==========")
        Log.d(TAG, "instruction: $instruction")
        Log.d(TAG, "apiKey: ${if (apiKey.isNotBlank()) "***" else "空"}")
        Log.d(TAG, "providerId: $providerId")

        if (instruction.isBlank()) {
            Toast.makeText(context, "请输入指令", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "指令为空，返回")
            return
        }

        // MAI-UI 本地部署不需要 API Key
        val requiresApiKey = providerId != "mai_ui"
        if (requiresApiKey && apiKey.isBlank()) {
            Toast.makeText(context, "请输入 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        val settings = settingsManager.settings.value

        Log.d(TAG, "开始权限检查..")
        val (resultCode, data) = getMediaProjectionInfo()
        val permissionCheck = AgentPermissionCheck(context, settingsManager)
        val permissionResult = permissionCheck.checkPermissions(resultCode, data)

        when (permissionResult) {
            is AgentPermissionCheck.CheckResult.Granted -> {
                Log.d(TAG, "权限检查通过")
            }
            is AgentPermissionCheck.CheckResult.NeedsRecordAudio -> {
                Log.d(TAG, "缺少录音权限")
                Toast.makeText(context, permissionCheck.getPermissionDescription(permissionResult), Toast.LENGTH_LONG).show()
                permissionCheck.openRecordAudioSettings()
                return
            }
            is AgentPermissionCheck.CheckResult.NeedsSystemAlertWindow -> {
                Log.d(TAG, "缺少悬浮窗权限")
                Toast.makeText(context, permissionCheck.getPermissionDescription(permissionResult), Toast.LENGTH_LONG).show()
                permissionCheck.openSystemAlertWindowSettings()
                return
            }
            is AgentPermissionCheck.CheckResult.NeedsAccessibilityService -> {
                Log.d(TAG, "缺少无障碍服务")
                onRequireAccessibility(instruction)
                return
            }
            is AgentPermissionCheck.CheckResult.NeedsMediaProjection -> {
                Log.d(TAG, "缺少屏幕录制权限")
                onRequireMediaProjection(instruction)
                return
            }
            is AgentPermissionCheck.CheckResult.NeedsMultiplePermissions -> {
                Log.d(TAG, "缺少多个权限")
                val missingPermissions = permissionResult.missingPermissions
                // 检查是否包含 MediaProjection 权限
                val needsMediaProjection = missingPermissions.any { it is AgentPermissionCheck.CheckResult.NeedsMediaProjection }
                if (needsMediaProjection) {
                    onRequireMediaProjection(instruction)
                } else {
                    // 优先处理第一个缺失的权限
                    val firstMissing = missingPermissions.firstOrNull()
                    when (firstMissing) {
                        is AgentPermissionCheck.CheckResult.NeedsAccessibilityService -> {
                            onRequireAccessibility(instruction)
                        }
                        else -> {
                            Toast.makeText(context, permissionCheck.getPermissionDescription(permissionResult), Toast.LENGTH_LONG).show()
                            permissionCheck.openPermissionSettings(permissionResult)
                        }
                    }
                }
                return
            }
        }

        isExecuting.value = true

        val vlmClient = createVLMClient(apiKey, baseUrl, model)
        if (settings.enableImproveMode) {
            mobileAgentState.value = MobileAgentImprove(
                vlmClient,
                deviceControllerProvider(),
                context,
                settings.reactOnly,
                settings.enableChatAgent,
                settings.enableFlowMode
            )
        } else {
            mobileAgentState.value = MobileAgent(
                vlmClient,
                deviceControllerProvider(),
                context
            )
        }

        mobileAgentState.value?.onStopRequested = {
            currentExecutionJob?.cancel()
            currentExecutionJob = null
        }

        OverlayService.setTTSConfig(
            enabled = ttsEnabled,
            apiKey = apiKey,
            voice = ttsVoice
        )

        val record = ExecutionRecord(
            title = generateTitle(instruction),
            instruction = instruction,
            startTime = System.currentTimeMillis(),
            status = ExecutionStatus.RUNNING
        )

        currentRecordId.value = record.id

        currentExecutionJob?.cancel()

        currentExecutionJob = scope.launch {
            executionRepository.saveRecord(record)
            executionRecords.value = executionRepository.getAllRecords()

            val latestSettings = settingsManager.settings.value
            OverlayService.setTTSConfig(
                enabled = latestSettings.ttsEnabled,
                apiKey = latestSettings.apiKey,
                voice = latestSettings.ttsVoice
            )

            try {
                val result = mobileAgentState.value!!.runInstruction(
                    instruction,
                    maxSteps,
                    enableBatchExecution = latestSettings.enableBatchExecution
                )

                val agentState = mobileAgentState.value?.state?.value
                val steps = agentState?.executionSteps ?: emptyList()
                val currentLogs = mobileAgentState.value?.logs?.value ?: emptyList()

                val chatSession = mobileAgentState.value?.getChatSession()
                Log.d(TAG, "getChatSession 返回值: ${chatSession != null}, chatSession.messages 数量: ${chatSession?.messages?.size ?: 0}")
                chatSession?.messages?.forEachIndexed { index, msg ->
                    Log.d(TAG, "  ChatMessage[$index]: role=${msg.role}, content=${msg.content.take(50)}")
                }

                val chatHistory = chatSession?.messages?.map { msg ->
                    ChatMessageData(
                        id = msg.id,
                        role = msg.role.name,
                        content = msg.content,
                        timestamp = msg.timestamp,
                        interruptType = msg.interruptType?.name,
                        newIntent = msg.newIntent
                    )
                } ?: emptyList()

                Log.d(TAG, "保存执行记录 - steps 数量: ${steps.size}, logs 数量: ${currentLogs.size}, chatHistory 数量: ${chatHistory.size}")
                steps.forEachIndexed { index, step ->
                    Log.d(TAG, "Step $index: ${step.stepNumber} - ${step.description} - outcome: ${step.outcome}")
                }

                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = if (result.success) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED,
                    steps = steps,
                    logs = currentLogs,
                    resultMessage = result.message,
                    chatHistory = chatHistory
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()

                isExecuting.value = false

                delay(3000)
                mobileAgentState.value?.clearLogs()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val agentState = mobileAgentState.value?.state?.value
                    val steps = agentState?.executionSteps ?: emptyList()
                    val currentLogs = mobileAgentState.value?.logs?.value ?: emptyList()

                    val chatSession = mobileAgentState.value?.getChatSession()
                    val chatHistory = chatSession?.messages?.map { msg ->
                        ChatMessageData(
                            id = msg.id,
                            role = msg.role.name,
                            content = msg.content,
                            timestamp = msg.timestamp,
                            interruptType = msg.interruptType?.name,
                            newIntent = msg.newIntent
                        )
                    } ?: emptyList()

                    Log.d(TAG, "取消任务 - steps 数量: ${steps.size}, logs 数量: ${currentLogs.size}, chatHistory 数量: ${chatHistory.size}")
                    steps.forEachIndexed { index, step ->
                        Log.d(TAG, "Step $index: ${step.stepNumber} - ${step.description} - outcome: ${step.outcome}")
                    }

                    val updatedRecord = record.copy(
                        endTime = System.currentTimeMillis(),
                        status = ExecutionStatus.STOPPED,
                        steps = steps,
                        logs = currentLogs,
                        resultMessage = "已取消",
                        chatHistory = chatHistory
                    )
                    executionRepository.saveRecord(updatedRecord)
                    executionRecords.value = executionRepository.getAllRecords()

                    isExecuting.value = false

                    Toast.makeText(context, "任务已停止", Toast.LENGTH_SHORT).show()
                    mobileAgentState.value?.clearLogs()

                    shouldNavigateToRecord.value = true
                }
            } catch (e: Exception) {
                val currentLogs = mobileAgentState.value?.logs?.value ?: emptyList()

                val chatSession = mobileAgentState.value?.getChatSession()
                val chatHistory = chatSession?.messages?.map { msg ->
                    ChatMessageData(
                        id = msg.id,
                        role = msg.role.name,
                        content = msg.content,
                        timestamp = msg.timestamp,
                        interruptType = msg.interruptType?.name,
                        newIntent = msg.newIntent
                    )
                } ?: emptyList()

                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = ExecutionStatus.FAILED,
                    logs = currentLogs,
                    resultMessage = "错误: ${e.message}",
                    chatHistory = chatHistory
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                isExecuting.value = false

                Toast.makeText(context, "错误: ${e.message}", Toast.LENGTH_LONG).show()

                delay(3000)
                mobileAgentState.value?.clearLogs()
            }
        }
    }

    private fun generateTitle(instruction: String): String {
        val keywords = listOf(
            "打开" to "打开应用",
            "点" to "点餐",
            "发" to "发送消息",
            "看" to "浏览内容",
            "搜" to "搜索",
            "设置" to "调整设置",
            "播放" to "播放媒体"
        )
        for ((key, title) in keywords) {
            if (instruction.contains(key)) {
                return title
            }
        }
        return if (instruction.length > 10) instruction.take(10) + "..." else instruction
    }
}
