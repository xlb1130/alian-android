package com.alian.assistant.core.agent

import android.content.Context
import android.util.Log
import com.alian.assistant.core.agent.improve.AdjustChatImprove
import com.alian.assistant.core.agent.improve.InfoPoolImprove
import com.alian.assistant.core.agent.memory.Action
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.data.model.ChatMessage
import com.alian.assistant.data.model.ChatRole
import com.alian.assistant.data.model.ChatSession
import com.alian.assistant.data.model.InterruptType
import com.alian.assistant.infrastructure.ai.llm.VLMClient
import com.alian.assistant.infrastructure.audio.AecVoiceCallAudioManager
import com.alian.assistant.infrastructure.audio.IAudioManager
import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * InterruptOrchestrator - 打断协调器
 *
 * 负责语音监听、判定是否打断、暂停/恢复、把对话交给 AdjustChatImprove、把结果写回 InfoPool/Planner
 *
 * 核心功能：
 * 1. 语音监听管理（启动/停止/去抖）- 使用 AEC 音频管理器实现回声消除
 * 2. 打断判定（有效性检查、置信度、去抖）
 * 3. 状态管理（IDLE, RUNNING, PAUSED_FOR_CHAT, REPLANNING, RESUMING, STOPPED）
 * 4. 聊天会话管理
 * 5. 与 MobileAgentImprove 协调（暂停/恢复/重规划）
 *
 * 状态机：
 * - IDLE: 空闲状态
 * - RUNNING: 执行中（监听语音）
 * - PAUSING: 准备暂停
 * - PAUSED_FOR_CHAT: 已暂停，进入对话
 * - REPLANNING: 已得到 new intent，准备重规划
 * - RESUMING: 恢复执行
 * - STOPPING / STOPPED: 停止
 * - ERROR: 错误状态
 */
class InterruptOrchestrator(
    private val context: Context,
    private val vlmClient: VLMClient,
    private val settingsManager: SettingsManager,
    private val controller: IDeviceController
) {
    companion object {
        private const val TAG = "InterruptOrchestrator"

        // 打断判定阈值
        private const val MIN_TEXT_LENGTH = 2  // 最小文本长度
        private const val MIN_CONFIDENCE = 0.5f  // 最小置信度
        private const val DEBOUNCE_MS = 2000L  // 去抖时间（毫秒）

        // 打断关键词
        private val INTERRUPT_KEYWORDS =
            listOf("暂停", "等等", "停", "助手", "喂", "等等我", "等一下")
    }

    // 状态
    private val _state = MutableStateFlow(OrchestratorState.IDLE)
    val state: StateFlow<OrchestratorState> = _state

    // 是否正在启动监听
    private var isStartingListening = false

    // 是否正在播放TTS
    private var isPlayingTTS = false

    // 聊天会话
    private var currentChatSession: ChatSession? = null

    // 组件
    private val adjustChatImprove = AdjustChatImprove()

    // AEC 音频管理器（支持回声消除和 TTS 播放）
    private var aecAudioManager: IAudioManager? = null

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 上次打断时间（去抖）
    private var lastInterruptTime = 0L

    // 上次 startListening 调用时间（频率限制）
    private var lastStartListeningTime = System.currentTimeMillis() - 2000L
    private val MIN_START_LISTENING_INTERVAL_MS = 3000L  // 最小调用间隔1秒

    // InfoPool 引用（运行时注入）
    private var currentInfoPool: InfoPoolImprove? = null

    // 暂停回调（由 MobileAgentImprove 设置）
    private var onPauseCallback: ((suspend (Snapshot) -> Unit) -> Unit)? = null
    private var onResumeCallback: ((suspend () -> Unit) -> Unit)? = null
    private var onStopCallback: ((suspend () -> Unit) -> Unit)? = null
    private var onReplanCallback: ((String, ChatSession, suspend () -> Unit) -> Unit)? = null

    // UI 回调
    private var onMessageCallback: ((ChatMessage) -> Unit)? = null
    private var onStatusChangeCallback: ((OrchestratorState) -> Unit)? = null

    // TTS 播放回调
    private var onTTSPlayCallback: ((String, () -> Unit) -> Unit)? = null

    /**
     * 协调器状态
     */
    enum class OrchestratorState {
        IDLE,              // 空闲
        RUNNING,           // 执行中（监听语音）
        PAUSING,           // 准备暂停
        PAUSED_FOR_CHAT,   // 已暂停，进入对话
        REPLANNING,        // 准备重规划
        RESUMING,          // 恢复中
        STOPPING,          // 停止中
        STOPPED,           // 已停止
        ERROR              // 错误
    }

    /**
     * 设置 UI 消息回调
     */
    fun setOnMessageCallback(callback: ((ChatMessage) -> Unit)?) {
        onMessageCallback = callback
    }

    /**
     * 暂停快照（用于恢复）
     */
    data class Snapshot(
        val step: Int,
        val plan: String,
        val completedPlan: String,
        val actionHistory: List<Action>,
        val summaryHistory: List<String>,
        val actionOutcomes: List<String>,
        val errorDescriptions: List<String>
    )

    /**
     * 初始化协调器
     */
    fun initialize(
        infoPool: InfoPoolImprove,
        onPause: (suspend (Snapshot) -> Unit) -> Unit,
        onResume: (suspend () -> Unit) -> Unit,
        onStop: (suspend () -> Unit) -> Unit,
        onReplan: (String, ChatSession, suspend () -> Unit) -> Unit
    ) {
        Log.d(TAG, "========== InterruptOrchestrator 初始化 ==========")
        currentInfoPool = infoPool
        onPauseCallback = onPause
        onResumeCallback = onResume
        onStopCallback = onStop
        onReplanCallback = onReplan

        Log.d(TAG, "回调函数设置:")
        Log.d(TAG, "  onPauseCallback: ${onPauseCallback != null}")
        Log.d(TAG, "  onResumeCallback: ${onResumeCallback != null}")
        Log.d(TAG, "  onStopCallback: ${onStopCallback != null}")
        Log.d(TAG, "  onReplanCallback: ${onReplanCallback != null}")

        // 创建聊天会话
        currentChatSession = ChatSession(
            originalInstruction = infoPool.instruction,
            totalSteps = infoPool.totalSteps,
            currentStep = infoPool.totalSteps
        )

        Log.d(TAG, "聊天会话已创建: originalInstruction='${infoPool.instruction}'")

        // 从 settingsManager 获取配置
        val settings = settingsManager.settings.value

        // 初始化 AEC 音频管理器（内部已集成 TTS）
        // 注意：仅在 MobileAgent 场景下启用 Persistent AEC 模式，避免影响 VoiceCall / VideoCall 现有行为
        try {
            aecAudioManager = AecVoiceCallAudioManager(
                context = context,
                apiKey = settings.apiKey,
                ttsVoice = settings.ttsVoice,
                ttsSpeed = settings.ttsSpeed,
                ttsInterruptEnabled = true,  // 启用语音打断
                volume = settings.volume,
                usePersistentAec = true      // MobileAgent 场景下启用长生命周期 AEC
            )
            Log.d(TAG, "AEC 音频管理器已初始化（包含 TTS 功能）")
        } catch (e: Exception) {
            Log.e(TAG, "AEC 音频管理器初始化失败", e)
        }

        Log.d(TAG, "✓ InterruptOrchestrator 初始化完成")
        Log.d(TAG, "=================================================")
    }

    /**
     * 开始执行（启动语音监听）
     */
    fun startExecution() {
        Log.d(TAG, "========== startExecution ==========")
        Log.d(TAG, "当前状态: ${_state.value}")

        if (_state.value != OrchestratorState.IDLE && _state.value != OrchestratorState.STOPPED) {
            Log.w(TAG, "❌ Already running, state = ${_state.value}")
            Log.d(TAG, "====================================")
            return
        }

        Log.d(TAG, "状态检查通过，更新状态为 RUNNING")
        updateState(OrchestratorState.RUNNING)
        Log.d(TAG, "状态已更新为: ${_state.value}")

        Log.d(TAG, "调用 startListening()...")
        startListening()
        Log.d(TAG, "startListening() 调用完成")

        // 添加系统消息
        addSystemMessage("开始执行任务，说'暂停'可打断")
        Log.d(TAG, "====================================")
    }

    /**
     * 开始语音监听
     */
    private fun startListening() {
        if (isPlayingTTS) {
            Log.w(TAG, "⚠️ 正在播放 TTS，请等待完成")
            return
        }
        Log.d(TAG, "========== 开始语音监听 ==========")
        Log.d(TAG, "当前状态: ${_state.value}")

        // 频率限制：1秒内只能调用一次
        val now = System.currentTimeMillis()
        val timeSinceLastCall = now - lastStartListeningTime
        if (isStartingListening && timeSinceLastCall < MIN_START_LISTENING_INTERVAL_MS) {
            Log.w(TAG, "⚠️ 调用频率限制：距离上次调用仅 ${timeSinceLastCall}ms，忽略本次调用")
            return
        }
        isStartingListening = true
        lastStartListeningTime = now

        // 允许在 RUNNING、PAUSED_FOR_CHAT 和 RESUMING 状态下启动监听
        if (_state.value != OrchestratorState.RUNNING && _state.value != OrchestratorState.PAUSED_FOR_CHAT && _state.value != OrchestratorState.RESUMING) {
            Log.w(TAG, "❌ 无法在状态 ${_state.value} 下启动监听")
            return
        }

        // 启动新的监听（使用 AEC 音频管理器）
        // 参考 VoiceCallViewModel 的实现方式，直接调用 startRecording()
        Log.d(TAG, "启动 AEC 语音识别...")
        Log.d(TAG, "aecAudioManager is null: ${aecAudioManager == null}")
        try {
            Log.d(TAG, "调用 aecAudioManager.startRecording()...")
            aecAudioManager?.startRecording(
                onPartialResult = { text ->
                    Log.d(TAG, "部分识别结果: $text")
                },
                onFinalResult = { text ->
                    Log.d(TAG, "最终识别结果: $text")
                    // 过滤空文本回调（通常是 stopAll 后的残留回调）
                    if (text.isBlank()) {
                        Log.d(TAG, "⚠ 忽略空文本的最终识别结果  $_state.value.name")
                        // 如果正在播放TTS，不启动监听
                        if (isPlayingTTS) {
                            Log.d(TAG, "正在播放TTS，不启动监听")
                        } else if (_state.value == OrchestratorState.RUNNING || _state.value == OrchestratorState.PAUSED_FOR_CHAT) {
                            Log.d(TAG, "状态允许，延迟后重新开始监听")
                            scope.launch {
                                Log.d(TAG, "状态允许，重新开始监听")
                                delay(500)
                                startListening()
                            }
                        }
                    } else {
                        // 注意：AecVoiceCallAudioManager 在 onFinalResult 回调后会自动停止录音
                        // 所以在这里需要处理识别结果，并在适当的时候重新启动录音
                        handleSpeechResult(text)
                    }
                },
                onError = { error ->
                    Log.e(TAG, "AEC 语音识别错误: $error")
                    // 自动重新开始监听
//                    if (_state.value == OrchestratorState.RUNNING || _state.value == OrchestratorState.PAUSED_FOR_CHAT) {
//                        startListening()
//                    }
                },
                onSilenceTimeout = {
                    Log.d(TAG, "静音超时，重新开始监听")
//                    if (_state.value == OrchestratorState.RUNNING || _state.value == OrchestratorState.PAUSED_FOR_CHAT) {
//                        startListening()
//                    }
                }
            )
            Log.d(TAG, "✓ aecAudioManager.startRecording() 调用完成")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动 AEC 语音识别失败", e)
            updateState(OrchestratorState.ERROR)
        } finally {
            isStartingListening = false
        }
        Log.d(TAG, "================================")
    }

    /**
     * 停止语音监听
     */
    private fun stopListening() {
        Log.d(TAG, "stopListening")

        try {
            //
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop listening", e)
        }
    }

    /**
     * 处理语音识别结果
     */
    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "========== 处理语音打断 ==========")
        Log.d(TAG, "识别文本: $text")
        Log.d(TAG, "当前状态: ${_state.value}")
        val thread = Thread.currentThread()
        Log.d(
            TAG,
            "onSpeechRecognized 被调用: text='$text', thread=${thread.name}, isMain=${thread.name == "main"}"
        )


        // 在 PAUSED_FOR_CHAT 状态下，处理"继续"指令或其他对话
        if (_state.value == OrchestratorState.PAUSED_FOR_CHAT) {
            // 检查是否为空结果
            if (text.isBlank()) {
                Log.d(TAG, "⚠️ 识别结果为空，重新开始监听")
                Log.d(TAG, "=================================")
                startListening()
                return
            }

            Log.d(TAG, "✓ 检测到对话文本，调用 handleChatSession 进行对话")
            // 调用 handleChatSession 进行对话，根据 shouldResume 决定是否恢复执行
            val snapshot = createSnapshot()
            if (snapshot != null) {
                handleChatSession(snapshot, text)
            } else {
                Log.e(TAG, "无法创建 snapshot，重新开始监听")
                startListening()
            }
            Log.d(TAG, "=================================")
            return
        }

        // 只在 RUNNING 状态下处理语音打断
        if (_state.value != OrchestratorState.RUNNING) {
            Log.d(TAG, "⚠️ 当前状态不是 RUNNING，忽略语音识别结果")
            Log.d(TAG, "=================================")
            return
        }

        // 去抖检查
        val now = System.currentTimeMillis()
        val timeSinceLastInterrupt = now - lastInterruptTime
        Log.d(TAG, "距离上次打断: ${timeSinceLastInterrupt}ms (阈值: ${DEBOUNCE_MS}ms)")

        if (timeSinceLastInterrupt < DEBOUNCE_MS) {
            Log.d(TAG, "⚠️ 去抖：忽略打断")
            Log.d(TAG, "=================================")
            return
        }
        lastInterruptTime = now

        // 有效性检查
        val isValid = isValidInterrupt(text)
        Log.d(TAG, "有效性检查: $isValid")

        if (!isValid) {
            Log.d(TAG, "❌ 无效打断，重新开始监听")
            Log.d(TAG, "=================================")
            startListening()
            return
        }
        Log.d(TAG, "✓ 有效打断，停止监听并请求暂停")

        // 立即更新状态为 PAUSING
        updateState(OrchestratorState.PAUSING)
        Log.d(TAG, "状态转换: ${_state.value}")

        onPauseCallback?.invoke { snapshot ->
            Log.d(TAG, "✓ 暂停回调执行完成，进入聊天模式")
            // MobileAgentImprove 会创建 Snapshot 并传递给这个 lambda
            updateState(OrchestratorState.PAUSED_FOR_CHAT)

            // 处理聊天
            handleChatSession(snapshot, text)
        }
        Log.d(TAG, "=================================")
    }

    /**
     * 检查打断是否有效
     */
    private fun isValidInterrupt(text: String): Boolean {
        Log.d(TAG, "----- 有效性检查 -----")

        // 长度检查
        val lengthCheck = text.length >= MIN_TEXT_LENGTH
        Log.d(TAG, "  长度检查: ${text.length} >= $MIN_TEXT_LENGTH = $lengthCheck")
        if (!lengthCheck) {
            return false
        }

        // 过滤噪声词
        val noiseWords = listOf("嗯", "啊", "喂", "呃", "那个", "这个")
        val isNoise = text.trim() in noiseWords
        Log.d(TAG, "  噪声词检查: $isNoise")
        if (isNoise) {
            return false
        }

        // 检查打断关键词
        val matchedKeywords = INTERRUPT_KEYWORDS.filter { it in text }
        val hasKeyword = matchedKeywords.isNotEmpty()
        Log.d(TAG, "  关键词检查: $hasKeyword")
        if (hasKeyword) {
            Log.d(TAG, "    匹配的关键词: $matchedKeywords")
            return true
        }

        // 如果文本较长（>= 3个字符），认为是有效打断
        val longTextCheck = text.length >= 3
        Log.d(TAG, "  长文本检查: ${text.length} >= 3 = $longTextCheck")
        Log.d(TAG, "---------------------")
        return longTextCheck
    }

    /**
     * 处理聊天会话
     */
    private fun handleChatSession(snapshot: Snapshot, userText: String) {
        Log.d(TAG, "handleChatSession: userText = $userText")

        // 添加用户消息
        addUserMessage(userText)

        // 调用 AdjustChatImprove
        val infoPool = currentInfoPool ?: run {
            Log.e(TAG, "InfoPool is null")
            addSystemMessage("错误：无法获取上下文")
            resumeExecution()
            return
        }

        val chatSession = currentChatSession ?: run {
            Log.e(TAG, "ChatSession is null")
            addSystemMessage("错误：聊天会话未初始化")
            resumeExecution()
            return
        }

        // 生成 Prompt
        val prompt = adjustChatImprove.getPrompt(infoPool, chatSession, userText)
        Log.d(TAG, "AdjustChatImprove prompt: $prompt")

        // 获取屏幕截图
        val screenshots = try {
            Log.d(TAG, "正在获取屏幕截图...")
            val screenshotResult = runBlocking { controller.screenshotWithFallback() }
            val bitmap = screenshotResult.bitmap
            if (screenshotResult.isSensitive) {
                Log.d(TAG, "⚠️ 检测到敏感页面，使用黑屏占位图")
            } else if (screenshotResult.isFallback) {
                Log.d(TAG, "⚠️ 截图失败，使用黑屏占位图")
            } else {
                Log.d(TAG, "✓ 屏幕截图获取成功 (${bitmap.width}x${bitmap.height})")
            }
            listOf(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "获取屏幕截图失败: ${e.message}", e)
            emptyList()
        }

        // 调用 VLM（使用 runBlocking 去掉协程），传入屏幕截图
        val response = runBlocking { vlmClient.predict(prompt, screenshots) }

        if (response.isFailure) {
            Log.e(TAG, "Failed to call VLM: ${response.exceptionOrNull()?.message}")
            addSystemMessage("抱歉，我暂时无法理解，请再说一次或继续执行")
            resumeExecution()
            return
        }

        val responseText = response.getOrThrow()
        Log.d(TAG, "VLM response: $responseText")

        // 解析响应
        val chatResult = adjustChatImprove.parseResponse(responseText)
        Log.d(TAG, "ChatResult: $chatResult")

        // 添加助手消息
        addAssistantMessage(chatResult.answer, chatResult.interruptType)

        // 从配置中读取 TTS 设置
        val ttsEnabled = settingsManager.settings.value.ttsEnabled

//        // 特殊处理：NEW_INTENT 类型需要触发重规划
//        if (chatResult.interruptType == InterruptType.NEW_INTENT && chatResult.newIntent != null) {
//            updateState(OrchestratorState.REPLANNING)
//            onReplanCallback?.invoke(chatResult.newIntent, chatSession) {
//                // 重规划完成后，根据 shouldResume 决定是否恢复执行
//                if (chatResult.shouldResume) {
//                    if (ttsEnabled) {
//                        playTTS(chatResult.answer) {
//                            resumeExecution()
//                        }
//                    } else {
//                        resumeExecution()
//                    }
//                } else {
//                    if (ttsEnabled) {
//                        playTTS(chatResult.answer) {
//                            Log.d(TAG, "TTS 播放完成，启动监听检测'继续'指令")
//                            // startListening()
//                        }
//                    } else {
//                        Log.d(TAG, "TTS 未启用，直接启动监听检测'继续'指令")
//                        startListening()
//                    }
//                }
//            }
//            return
//        }
//
//        // 特殊处理：STOP 类型需要停止执行
//        if (chatResult.interruptType == InterruptType.STOP) {
//            if (ttsEnabled) {
//                playTTS(chatResult.answer) {
//                    updateState(OrchestratorState.STOPPING)
//                    onStopCallback?.invoke {
//                        updateState(OrchestratorState.STOPPED)
//                        addSystemMessage("任务已停止")
//                    }
//                }
//            } else {
//                updateState(OrchestratorState.STOPPING)
//                onStopCallback?.invoke {
//                    updateState(OrchestratorState.STOPPED)
//                    addSystemMessage("任务已停止")
//                }
//            }
//            return
//        }

        // 统一处理：根据 shouldResume 标志决定是否恢复执行
        if (chatResult.shouldResume) {
            // shouldResume=true 说明收集到了用户诉求，需要触发重新规划
            if (!chatResult.newIntent.isNullOrEmpty()) {
                Log.d(TAG, "shouldResume=true 且有 newIntent，触发重新规划: ${chatResult.newIntent}")
                updateState(OrchestratorState.REPLANNING)
                onReplanCallback?.invoke(chatResult.newIntent, chatSession) {
                    // 重规划完成后，恢复执行（TTS 播放完再恢复）
                    if (ttsEnabled) {
                        playTTS(chatResult.answer) {
                            resumeExecution()
                        }
                    } else {
                        resumeExecution()
                    }
                }
            } else {
                Log.d(TAG, "shouldResume=true 但没有 newIntent，直接恢复执行")
                // 问答结束，恢复执行（TTS 播放完再恢复）
                if (ttsEnabled) {
                    playTTS(chatResult.answer) {
                        resumeExecution()
                    }
                } else {
                    resumeExecution()
                }
            }
        } else {
            // 继续问答/暂停，保持监听状态
            if (ttsEnabled) {
                playTTS(chatResult.answer) {
                    Log.d(TAG, "TTS 播放完成，启动监听检测'继续'指令")
                    startListening()
                }
            } else {
                Log.d(TAG, "TTS 未启用，直接启动监听检测'继续'指令")
                startListening()
            }
        }
    }

    /**
     * 恢复执行
     */
    private fun resumeExecution() {
        Log.d(TAG, "resumeExecution, current state: ${_state.value}")

        if (_state.value == OrchestratorState.PAUSED_FOR_CHAT ||
            _state.value == OrchestratorState.RESUMING ||
            _state.value == OrchestratorState.REPLANNING
        ) {
            updateState(OrchestratorState.RESUMING)
            // 调用恢复回调
            onResumeCallback?.invoke {
                updateState(OrchestratorState.RUNNING)
                addSystemMessage("继续执行")
                startListening()
            }
        } else {
            Log.w(TAG, "⚠️ 当前状态 ${_state.value} 不支持恢复执行")
        }
    }

    /**
     * 播放 TTS
     */
    private fun playTTS(text: String, onComplete: () -> Unit) {
        Log.d(TAG, "playTTS: $text")

        // 标记正在播放TTS
        isPlayingTTS = true
        Log.d(TAG, "TTS 播放开始，设置 isPlayingTTS = true")

        // 使用 AecVoiceCallAudioManager 的播放功能（内部已集成 CosyVoiceTTSClient）
        aecAudioManager?.playText(
            text = text,
            onFinished = {
                Log.d(TAG, "TTS 播放完成")
                isPlayingTTS = false
                Log.d(TAG, "TTS 播放完成，设置 isPlayingTTS = false")
                onComplete()
            },
            onError = { error ->
                Log.e(TAG, "播放 TTS 失败: $error")
                isPlayingTTS = false
                Log.d(TAG, "TTS 播放失败，设置 isPlayingTTS = false")
                onComplete()
            }
        )
    }

    /**
     * 更新状态
     */
    private fun updateState(newState: OrchestratorState) {
        Log.d(TAG, "State change: ${_state.value} -> $newState")
        _state.value = newState
        onStatusChangeCallback?.invoke(newState)
    }

    /**
     * 添加用户消息
     */
    private fun addUserMessage(text: String) {
        val message = ChatMessage(
            role = ChatRole.USER,
            content = text,
            relatedAgentStep = currentInfoPool?.totalSteps
        )
        currentChatSession?.addMessage(message)
        onMessageCallback?.invoke(message)
    }

    /**
     * 添加助手消息
     */
    private fun addAssistantMessage(text: String, interruptType: InterruptType? = null) {
        val message = ChatMessage(
            role = ChatRole.ASSISTANT,
            content = text,
            interruptType = interruptType,
            relatedAgentStep = currentInfoPool?.totalSteps
        )
        currentChatSession?.addMessage(message)
        onMessageCallback?.invoke(message)
    }

    /**
     * 添加系统消息
     */
    private fun addSystemMessage(text: String) {
        val message = ChatMessage(
            role = ChatRole.SYSTEM,
            content = text
        )
        currentChatSession?.addMessage(message)
        onMessageCallback?.invoke(message)
    }

    /**
     * 创建暂停快照
     */
    private fun createSnapshot(): Snapshot? {
        val infoPool = currentInfoPool ?: return null
        return Snapshot(
            step = infoPool.totalSteps,
            plan = infoPool.plan,
            completedPlan = infoPool.completedPlan,
            actionHistory = infoPool.actionHistory.toList(),
            summaryHistory = infoPool.summaryHistory.toList(),
            actionOutcomes = infoPool.actionOutcomes.toList(),
            errorDescriptions = infoPool.errorDescriptions.toList()
        )
    }

    /**
     * 获取当前聊天会话
     */
    fun getCurrentChatSession(): ChatSession? {
        return currentChatSession
    }

    /**
     * 设置对话会话（用于从历史记录恢复）
     */
    fun setChatSession(session: ChatSession) {
        currentChatSession = session
    }

    /**
     * 测试语音识别（用于调试）
     */
    fun testVoiceRecognition() {
        Log.d(TAG, "========== 测试语音识别 ==========")
        Log.d(TAG, "当前状态: ${_state.value}")
        Log.d(TAG, "AEC 音频管理器（包含 TTS）: ${aecAudioManager != null}")

        if (aecAudioManager == null) {
            Log.e(TAG, "❌ AEC 音频管理器未初始化！")
            return
        }

        Log.d(TAG, "✓ 所有检查通过，启动测试监听...")
        startListening()
        Log.d(TAG, "=================================")
    }

    /**
     * 清理资源
     */
    fun destroy() {
        Log.d(TAG, "destroy")

        stopListening()

        // 停止并释放 AEC 音频管理器（内部会释放 TTS）
        aecAudioManager?.stopAll()
        aecAudioManager = null

        onPauseCallback = null
        onResumeCallback = null
        onStopCallback = null
        onReplanCallback = null
        onMessageCallback = null
        onStatusChangeCallback = null
        onTTSPlayCallback = null

        currentInfoPool = null
        currentChatSession = null

        updateState(OrchestratorState.IDLE)
    }
}
