package com.alian.assistant.infrastructure.ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * CosyVoice TTS WebSocket 请求参数
 */
@Serializable
data class CosyVoiceTTSRequest(
    val header: CosyVoiceTTSHeader,
    val payload: CosyVoiceTTSPayload
)

@Serializable
data class CosyVoiceTTSHeader(
    val action: String,  // "run-task" | "continue-task" | "finish-task"
    val task_id: String = "",
    val streaming: String = "duplex"
)

@Serializable
data class CosyVoiceTTSPayload(
    val task_group: String? = null,
    val task: String? = null,
    val function: String? = null,
    val model: String? = null,
    val parameters: JsonObject? = null,
    val input: JsonObject? = null
)

/**
 * CosyVoice TTS WebSocket 响应
 */
@Serializable
data class CosyVoiceTTSResponse(
    val header: CosyVoiceTTSResponseHeader,
    val payload: CosyVoiceTTSResponsePayload? = null
)

@Serializable
data class CosyVoiceTTSResponseHeader(
    val task_id: String = "",
    val event: String = "",  // "task-started" | "task-finished" | "task-failed"
    val status: Int = 0,
    val message: String = "",
    val error_code: String? = null,
    val error_message: String? = null,
    val attributes: Map<String, String>? = null
)

@Serializable
data class CosyVoiceTTSResponsePayload(
    val audio: String? = null,
    val text: String? = null,
    val usage: Map<String, Int>? = null
)

/**
 * CosyVoice TTS WebSocket 客户端
 * 支持流式语音合成和实时播放
 */
class CosyVoiceTTSClient(
    private val apiKey: String,
    private val wsUrl: String = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
    private val model: String = "cosyvoice-v3-flash",
    private val voice: String = "longyingmu_v3",
    private val rate: Float = 1.0f,  // 语速，默认为 1.0
    private val volume: Int = 50  // 音量，取值范围 [0, 100]，默认为 50
) {
    companion object {
        private const val TAG = "[TTS] CosyVoiceTTSClient"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // WebSocket 需要长连接
        .writeTimeout(90, TimeUnit.SECONDS)  // 增加写入超时时间，支持长文本播放
        .pingInterval(0, TimeUnit.SECONDS)  // 禁用自动 ping，因为服务器不支持 ping/pong 协议
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var currentWebSocket: WebSocket? = null
    private var currentAudioTrack: AudioTrack? = null
    private var isPlaying = false

    // 音频数据回调（用于 AEC 参考信号）
    private var onAudioDataCallback: ((ByteArray) -> Unit)? = null

    // 流式 TTS 实例（用于持续发送文本块）
        private var streamingWebSocket: WebSocket? = null
        private var streamingAudioTrack: AudioTrack? = null
        private var isStreaming = false
        private var streamingTaskId: String = ""
        @Volatile
        private var streamingState: StreamingState = StreamingState.IDLE

    // 会话标识符，用于区分新旧连接，防止旧回调影响新连接
    @Volatile
    private var currentSessionId: Long = 0

    // 用于等待旧连接关闭的锁
    private val sessionLock = Any()
    @Volatile
    private var isOldSessionClosed = true

    /**
     * 检查当前是否正在播放音频
     */
    fun isCurrentlyPlaying(): Boolean {
        return isPlaying
    }

    /**
     * 设置音频数据回调（用于 AEC 参考信号）
     */
    fun setOnAudioDataCallback(callback: ((ByteArray) -> Unit)?) {
        onAudioDataCallback = callback
    }

    // 协议状态机
    private enum class ProtocolState {
        IDLE,
        WAITING_TASK_STARTED,
        READY_TO_SEND_TEXT,
        SENDING_TEXT,
        FINISHING,
        COMPLETED,
        ERROR
    }

    private var protocolState = ProtocolState.IDLE
    private var currentTaskId: String = ""
    private var textToSend: String = ""

    // 流式状态机
    private enum class StreamingState {
        IDLE,
        WAITING_TASK_STARTED,
        READY,
        COMPLETED,
        ERROR
    }

    // 标记是否已收到 task-finished 事件
    @Volatile
    private var isTaskFinished = false

    // 流式播放的完整文本，用于重连后继续播放
    @Volatile
    private var streamingFullText: String = ""

    // WebSocket 重连机制
    private var maxReconnectAttempts = 3
    private var currentReconnectAttempts = 0
    private val reconnectDelayMs = 2000L  // 2秒后重连

    // 当前正在播放的文本，用于重连后继续播放
    @Volatile
    private var pendingText: String = ""
    @Volatile
    private var isReconnecting = false

    /**
     * 流式语音合成并播放
     * @param text 要合成的文本
     * @param context 上下文
     * @return Result<Unit> 播放结果
     */
    suspend fun synthesizeAndPlay(text: String, context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始流式语音合成并播放: text长度=${text.length}, text前50字符=${text.take(50)}, 完整text=$text")

            // 取消之前的播放
            cancelPlayback()

            // 生成任务ID
            val taskId = System.currentTimeMillis().toString()

            // 初始化 AudioTrack
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )

            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            // 使用更大的缓冲区以支持长时间播放，避免音频卡顿或中断
            val actualBufferSize = bufferSize * 4

            currentAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(actualBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            currentAudioTrack?.play()
            isPlaying = true

            // 创建 WebSocket 连接
            val request = Request.Builder()
                .url(wsUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("X-DashScope-DataStream", "cosyvoice-v1")
                .build()

            val webSocketListener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket 连接已建立")
                    protocolState = ProtocolState.WAITING_TASK_STARTED
                    currentTaskId = taskId
                    textToSend = text

                    // 步骤1: 发送 run-task 指令
                    val runTaskRequest = CosyVoiceTTSRequest(
                        header = CosyVoiceTTSHeader(
                            action = "run-task",
                            task_id = taskId,
                            streaming = "duplex"
                        ),
                        payload = CosyVoiceTTSPayload(
                            task_group = "audio",
                            task = "tts",
                            function = "SpeechSynthesizer",
                            model = model,
                            parameters = buildJsonObject {
                                put("text_type", JsonPrimitive("PlainText"))
                                put("voice", JsonPrimitive(voice))
                                put("format", JsonPrimitive("pcm"))
                                put("sample_rate", JsonPrimitive(SAMPLE_RATE))
                                put("volume", JsonPrimitive(volume))
                                put("rate", JsonPrimitive(rate))
                                put("pitch", JsonPrimitive(1))
                            },
                            input = buildJsonObject { }
                        )
                    )

                    val message = json.encodeToString(runTaskRequest)
                    Log.d(TAG, "发送 run-task: $message")
                    ws.send(message)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(TAG, "收到文本消息: $text")
                    try {
                        val response = json.decodeFromString<CosyVoiceTTSResponse>(text)
                        handleTextMessage(ws, response)
                    } catch (e: Exception) {
                        Log.e(TAG, "解析文本消息失败", e)
                    }
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    Log.d(TAG, "收到二进制消息，大小: ${bytes.size} bytes")
                    handleBinaryMessage(bytes.toByteArray())
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 正在关闭: code=$code, reason=$reason")
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 已关闭: code=$code, reason=$reason, state=$protocolState, isPlaying=$isPlaying")
                    cleanup()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket 连接失败", t)
                    protocolState = ProtocolState.ERROR
                    cleanup()
                }
            }

            currentWebSocket = client.newWebSocket(request, webSocketListener)

            // 等待播放完成
            while (isPlaying) {
                delay(100)
            }

            Log.d(TAG, "语音合成并播放完成")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "流式语音合成并播放失败", e)
            cleanup()
            Result.failure(e)
        }
    }

    /**
     * 处理文本消息
     */
    private fun handleTextMessage(ws: WebSocket, response: CosyVoiceTTSResponse) {
        Log.d(TAG, "处理文本消息: event=${response.header.event}, status=${response.header.status}, state=$protocolState")

        when (response.header.event) {
            "task-started" -> {
                Log.d(TAG, "任务已启动")
                if (protocolState == ProtocolState.WAITING_TASK_STARTED) {
                    protocolState = ProtocolState.READY_TO_SEND_TEXT
                    // 步骤2: 发送 continue-task 指令
                    val continueTaskRequest = CosyVoiceTTSRequest(
                        header = CosyVoiceTTSHeader(
                            action = "continue-task",
                            task_id = currentTaskId,
                            streaming = "duplex"
                        ),
                        payload = CosyVoiceTTSPayload(
                            input = buildJsonObject {
                                put("text", JsonPrimitive(textToSend))
                            }
                        )
                    )

                    val message = json.encodeToString(continueTaskRequest)
                    Log.d(TAG, "发送 continue-task: $message")
                    ws.send(message)

                    // 步骤3: 发送 finish-task 指令
                    protocolState = ProtocolState.FINISHING
                    val finishTaskRequest = CosyVoiceTTSRequest(
                        header = CosyVoiceTTSHeader(
                            action = "finish-task",
                            task_id = currentTaskId,
                            streaming = "duplex"
                        ),
                        payload = CosyVoiceTTSPayload(
                            input = buildJsonObject { }
                        )
                    )

                    val finishMessage = json.encodeToString(finishTaskRequest)
                    Log.d(TAG, "发送 finish-task: $finishMessage")
                    ws.send(finishMessage)
                }
            }
            "task-finished" -> {
                Log.d(TAG, "任务已完成, state=$protocolState, isPlaying=$isPlaying")
                protocolState = ProtocolState.COMPLETED
                isPlaying = false
                Log.d(TAG, "已设置 isPlaying=false, state=$protocolState, isPlaying=$isPlaying")
            }
            "task-failed" -> {
                Log.e(TAG, "任务失败: ${response.header.error_message}")
                protocolState = ProtocolState.ERROR
                isPlaying = false
            }
        }
    }

    /**
     * 处理二进制消息（音频数据）
     */
    private fun handleBinaryMessage(audioData: ByteArray) {
        Log.d(TAG, "处理音频数据，大小: ${audioData.size} bytes")

        currentAudioTrack?.let { audioTrack ->
            try {
                // 检查 AudioTrack 状态
                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    val bytesWritten = audioTrack.write(audioData, 0, audioData.size)
                    Log.d(TAG, "写入音频数据: $bytesWritten bytes")
                } else {
                    Log.w(TAG, "AudioTrack 状态异常: ${audioTrack.state}, 跳过写入")
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioTrack 写入失败（可能已释放）", e)
            } catch (e: Exception) {
                Log.e(TAG, "写入音频数据异常", e)
            }
        } ?: run {
            Log.w(TAG, "currentAudioTrack 为 null，跳过音频数据写入")
        }

        // 触发音频数据回调（用于 AEC 参考信号）
        onAudioDataCallback?.invoke(audioData)
    }

    /**
     * 清理资源
     */
    private fun cleanup() {
        Log.d(TAG, "清理资源: state=$protocolState, isPlaying=$isPlaying")

        isPlaying = false
        protocolState = ProtocolState.IDLE
        Log.d(TAG, "已重置状态: state=$protocolState, isPlaying=$isPlaying")

        currentAudioTrack?.let { audioTrack ->
            try {
                // 检查 AudioTrack 状态，避免在无效状态下调用 stop()
                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack.stop()
                }
                audioTrack.release()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "释放 AudioTrack 失败（非法状态）", e)
            } catch (e: Exception) {
                Log.e(TAG, "释放 AudioTrack 失败", e)
            }
            currentAudioTrack = null
        }

        currentWebSocket?.let { ws ->
            try {
                ws.close(1000, "正常关闭")
            } catch (e: Exception) {
                Log.e(TAG, "关闭 WebSocket 失败", e)
            }
            currentWebSocket = null
        }
    }

    /**
     * 取消当前播放
     */
    fun cancelPlayback() {
        Log.d(TAG, "取消播放")
        
        // 停止非流式播放
        if (isPlaying) {
            Log.d(TAG, "停止非流式播放")
            isPlaying = false
            cleanup()
        }
        
        // 停止流式播放
        if (isStreaming) {
            Log.d(TAG, "停止流式播放")
            stopStreamingSession()
        }
    }

    /**
     * 启动流式 TTS 会话
     * @param context 上下文
     * @return Result<Unit> 启动结果
     */
    suspend fun startStreamingSession(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "=== 启动流式 TTS 会话 ===")

            // 等待旧连接完全关闭
            synchronized(sessionLock) {
                if (!isOldSessionClosed) {
                    Log.d(TAG, "等待旧连接关闭...")
                    // 先停止旧会话
                    stopStreamingSessionInternal()
                    // 等待最多 2 秒让旧连接关闭
                    val startTime = System.currentTimeMillis()
                    while (!isOldSessionClosed && System.currentTimeMillis() - startTime < 2000) {
                        Thread.sleep(50)
                    }
                    Log.d(TAG, "旧连接已关闭或超时, isOldSessionClosed=$isOldSessionClosed")
                }
            }

            // 生成新的会话ID和任务ID
            val newSessionId = System.currentTimeMillis()
            currentSessionId = newSessionId
            streamingTaskId = newSessionId.toString()
            Log.d(TAG, "生成新会话: sessionId=$newSessionId, taskId=$streamingTaskId")

            // 标记新会话开始
            synchronized(sessionLock) {
                isOldSessionClosed = false
            }

            // 重置状态
            isTaskFinished = false
            isStreaming = true
            streamingState = StreamingState.WAITING_TASK_STARTED

            // 初始化 AudioTrack
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )

            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            // 使用更大的缓冲区以支持长时间流式播放，避免音频卡顿或中断
            val actualBufferSize = bufferSize * 4

            streamingAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(actualBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            streamingAudioTrack?.play()
            Log.d(TAG, "AudioTrack 已创建并开始播放")

            // 创建 WebSocket 连接，保存会话ID用于回调检查
            val sessionId = newSessionId
            val request = Request.Builder()
                .url(wsUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("X-DashScope-DataStream", "cosyvoice-v1")
                .build()

            Log.d(TAG, "创建 WebSocket 连接: $wsUrl")

            val webSocketListener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    // 检查是否是当前活跃会话
                    if (sessionId != currentSessionId) {
                        Log.d(TAG, ">>> onOpen 被调用但会话已过期, sessionId=$sessionId, currentSessionId=$currentSessionId")
                        ws.close(1000, "会话已过期")
                        return
                    }
                    Log.d(TAG, ">>> onOpen 被调用, sessionId=$sessionId")
                    Log.d(TAG, "=== 流式 WebSocket 连接已建立 ===")
                    Log.d(TAG, "响应码: ${response.code}")

                    // 发送 run-task 指令
                    val runTaskRequest = CosyVoiceTTSRequest(
                        header = CosyVoiceTTSHeader(
                            action = "run-task",
                            task_id = streamingTaskId,
                            streaming = "duplex"
                        ),
                        payload = CosyVoiceTTSPayload(
                            task_group = "audio",
                            task = "tts",
                            function = "SpeechSynthesizer",
                            model = model,
                            parameters = buildJsonObject {
                                put("text_type", JsonPrimitive("PlainText"))
                                put("voice", JsonPrimitive(voice))
                                put("format", JsonPrimitive("pcm"))
                                put("sample_rate", JsonPrimitive(SAMPLE_RATE))
                                put("volume", JsonPrimitive(volume))
                                put("rate", JsonPrimitive(rate))
                                put("pitch", JsonPrimitive(1))
                            },
                            input = buildJsonObject { }
                        )
                    )

                    val message = json.encodeToString(runTaskRequest)
                    Log.d(TAG, "准备发送流式 run-task: $message")
                    val sendResult = ws.send(message)
                    Log.d(TAG, "run-task 发送完成，结果: $sendResult")
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    // 检查是否是当前活跃会话
                    if (sessionId != currentSessionId) {
                        Log.d(TAG, ">>> onMessage(text) 被调用但会话已过期, sessionId=$sessionId")
                        return
                    }
                    Log.d(TAG, ">>> onMessage(text) 被调用, sessionId=$sessionId")
                    Log.d(TAG, "=== 流式 WebSocket 收到文本消息 ===")
                    Log.d(TAG, "消息内容: $text")
                    try {
                        val response = json.decodeFromString<CosyVoiceTTSResponse>(text)
                        Log.d(TAG, "解析成功: event=${response.header.event}")
                        handleStreamingTextMessage(response)
                    } catch (e: Exception) {
                        Log.e(TAG, "解析流式文本消息失败", e)
                    }
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    // 检查是否是当前活跃会话
                    if (sessionId != currentSessionId) {
                        Log.d(TAG, ">>> onMessage(bytes) 被调用但会话已过期, sessionId=$sessionId")
                        return
                    }
                    Log.d(TAG, ">>> onMessage(bytes) 被调用, sessionId=$sessionId")
                    Log.d(TAG, "=== 流式 WebSocket 收到二进制消息 ===")
                    Log.d(TAG, "大小: ${bytes.size} bytes")
                    handleStreamingBinaryMessage(bytes.toByteArray())
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, ">>> onClosed 被调用, sessionId=$sessionId, currentSessionId=$currentSessionId")
                    // 标记会话已关闭
                    synchronized(sessionLock) {
                        isOldSessionClosed = true
                    }
                    // 只有当前活跃会话才更新状态
                    if (sessionId == currentSessionId) {
                        Log.d(TAG, "=== 流式 WebSocket 已关闭（当前会话）===")
                        Log.d(TAG, "code=$code, reason=$reason")
                        streamingState = StreamingState.COMPLETED
                    } else {
                        Log.d(TAG, "=== 流式 WebSocket 已关闭（旧会话，忽略）===")
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.d(TAG, ">>> onFailure 被调用, sessionId=$sessionId, currentSessionId=$currentSessionId")
                    // 标记会话已关闭
                    synchronized(sessionLock) {
                        isOldSessionClosed = true
                    }
                    // 只有当前活跃会话才更新状态
                    if (sessionId == currentSessionId) {
                        Log.e(TAG, "=== 流式 WebSocket 连接失败（当前会话）===", t)
                        Log.e(TAG, "response: $response")
                        streamingState = StreamingState.ERROR
                    } else {
                        Log.d(TAG, "=== 流式 WebSocket 连接失败（旧会话，忽略）===")
                    }
                }
            }

            streamingWebSocket = client.newWebSocket(request, webSocketListener)
            Log.d(TAG, "WebSocket 创建完成，streamingWebSocket 是否为 null: ${streamingWebSocket == null}")

            // 等待任务启动（增加超时时间到 30 秒）
            var attempts = 0
            val maxAttempts = 300  // 30 秒
            Log.d(TAG, "开始等待任务启动，初始状态: $streamingState, 最大等待时间: ${maxAttempts * 100}ms")
            
            while (streamingState == StreamingState.WAITING_TASK_STARTED && attempts < maxAttempts) {
                delay(100)
                attempts++
                
                // 每 50 次（5秒）打印一次日志
                if (attempts % 50 == 0) {
                    Log.d(TAG, "等待流式会话启动... attempts=$attempts/${maxAttempts}, state=$streamingState")
                }
            }

            Log.d(TAG, "等待结束，最终状态: $streamingState, attempts=$attempts")

            if (streamingState != StreamingState.READY) {
                Log.e(TAG, "流式会话启动失败: state=$streamingState, attempts=$attempts")
                stopStreamingSession()
                return@withContext Result.failure(Exception("流式会话启动失败: state=$streamingState, 可能是 API 配置问题或网络问题"))
            }

            Log.d(TAG, "=== 流式 TTS 会话启动成功 ===")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "=== 启动流式 TTS 会话失败 ===", e)
            stopStreamingSession()
            Result.failure(e)
        }
    }

    /**
     * 发送文本块到流式 TTS
     * @param chunk 文本块
     * @return Result<Unit> 发送结果
     */
    fun sendStreamingChunk(chunk: String): Result<Unit> {
        if (!isStreaming || streamingState != StreamingState.READY) {
            Log.e(TAG, "流式会话未就绪: isStreaming=$isStreaming, state=$streamingState")
            return Result.failure(Exception("流式会话未就绪"))
        }

        if (chunk.isBlank()) {
            Log.d(TAG, "文本块为空，跳过")
            return Result.success(Unit)
        }

        try {
            val continueTaskRequest = CosyVoiceTTSRequest(
                header = CosyVoiceTTSHeader(
                    action = "continue-task",
                    task_id = streamingTaskId,
                    streaming = "duplex"
                ),
                payload = CosyVoiceTTSPayload(
                    input = buildJsonObject {
                        put("text", JsonPrimitive(chunk))
                    }
                )
            )

            val message = json.encodeToString(continueTaskRequest)
            Log.d(TAG, "发送流式文本块: chunk=${chunk.take(50)}...")
            Log.d(TAG, "完整请求: $message")
            val success = streamingWebSocket?.send(message) ?: false

            if (!success) {
                Log.e(TAG, "发送流式文本块失败")
                return Result.failure(Exception("发送失败"))
            }

            Log.d(TAG, "流式文本块发送成功")
            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "发送流式文本块异常", e)
            return Result.failure(e)
        }
    }

    /**
     * 结束流式 TTS 会话
     * @return Result<Unit> 结束结果
     */
    fun finishStreamingSession(): Result<Unit> {
        if (!isStreaming || streamingState != StreamingState.READY) {
            Log.e(TAG, "流式会话未就绪: isStreaming=$isStreaming, state=$streamingState")
            return Result.failure(Exception("流式会话未就绪"))
        }

        try {
            val finishTaskRequest = CosyVoiceTTSRequest(
                header = CosyVoiceTTSHeader(
                    action = "finish-task",
                    task_id = streamingTaskId,
                    streaming = "duplex"
                ),
                payload = CosyVoiceTTSPayload(
                    input = buildJsonObject { }
                )
            )

            val message = json.encodeToString(finishTaskRequest)
            Log.d(TAG, "发送流式 finish-task")
            val success = streamingWebSocket?.send(message) ?: false

            if (!success) {
                Log.e(TAG, "发送流式 finish-task 失败")
                return Result.failure(Exception("发送失败"))
            }

            // 不要立即设置为 COMPLETED，等待收到 task-finished 响应后再设置
            // streamingState = StreamingState.COMPLETED
            Log.d(TAG, "流式会话结束指令发送成功，等待 task-finished 响应...")
            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "结束流式会话异常", e)
            return Result.failure(e)
        }
    }

    /**
     * 停止流式 TTS 会话
     */
    fun stopStreamingSession() {
        Log.d(TAG, "停止流式 TTS 会话")

        // 使当前会话失效，这样旧连接的回调会被忽略
        currentSessionId = 0

        stopStreamingSessionInternal()
    }

    /**
     * 内部方法：实际清理流式会话资源
     */
    private fun stopStreamingSessionInternal() {
        Log.d(TAG, "清理流式会话资源")

        isStreaming = false
        streamingState = StreamingState.IDLE

        // 清空完整文本
        streamingFullText = ""

        streamingAudioTrack?.let { audioTrack ->
            try {
                // 检查 AudioTrack 状态，避免在无效状态下调用 stop()
                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack.stop()
                }
                audioTrack.release()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "释放流式 AudioTrack 失败（非法状态）", e)
            } catch (e: Exception) {
                Log.e(TAG, "释放流式 AudioTrack 失败", e)
            }
            streamingAudioTrack = null
        }

        streamingWebSocket?.let { ws ->
            try {
                ws.close(1000, "正常关闭")
            } catch (e: Exception) {
                Log.e(TAG, "关闭流式 WebSocket 失败", e)
            }
            streamingWebSocket = null
        }

        // 标记会话已关闭
        synchronized(sessionLock) {
            isOldSessionClosed = true
        }

        Log.d(TAG, "流式 TTS 会话已停止")
    }

    /**
     * 检查流式 TTS 是否正在运行
     */
    fun isStreamingActive(): Boolean {
        return isStreaming && (streamingState == StreamingState.READY || streamingState == StreamingState.WAITING_TASK_STARTED)
    }

    /**
     * 检查流式 TTS 任务是否已完成
     */
    fun isTaskFinished(): Boolean {
        return isTaskFinished
    }

    /**
     * 检查 AudioTrack 是否还在播放缓冲区的数据
     * @return true 如果 AudioTrack 还在播放，false 如果已停止或缓冲区已空
     */
    suspend fun isAudioTrackStillPlaying(): Boolean {
        return streamingAudioTrack?.let { track ->
            // 检查 AudioTrack 状态
            if (track.state != AudioTrack.STATE_INITIALIZED ||
                track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Log.d(TAG, "AudioTrack 未在播放状态: state=${track.state}, playState=${track.playState}")
                return@let false
            }

            // 检查播放位置是否在变化
            val currentPos = track.playbackHeadPosition
            delay(50)  // 等待 50ms
            val newPos = track.playbackHeadPosition

            val isPlaying = currentPos != newPos
            Log.d(TAG, "AudioTrack 播放位置变化: $currentPos -> $newPos, 仍在播放: $isPlaying")

            isPlaying
        } ?: false
    }

    /**
     * 设置流式播放的完整文本（用于重连）
     */
    fun setStreamingFullText(text: String) {
        streamingFullText = text
        Log.d(TAG, "设置流式完整文本，长度: ${text.length}")
    }
    /**
     * 处理流式文本消息
     */
    private fun handleStreamingTextMessage(response: CosyVoiceTTSResponse) {
        Log.d(TAG, "处理流式文本消息: event=${response.header.event}, status=${response.header.status}, state=$streamingState")

        when (response.header.event) {
            "task-started" -> {
                Log.d(TAG, "流式任务已启动")
                if (streamingState == StreamingState.WAITING_TASK_STARTED) {
                    streamingState = StreamingState.READY
                    Log.d(TAG, "流式会话已就绪")
                }
            }
            "task-finished" -> {
                Log.d(TAG, "流式任务已完成")
                streamingState = StreamingState.COMPLETED
                isTaskFinished = true
            }
            "task-failed" -> {
                Log.e(TAG, "流式任务失败: ${response.header.error_message}")
                streamingState = StreamingState.ERROR
            }
        }
    }

    /**
     * 处理流式二进制消息（音频数据）
     */
    private fun handleStreamingBinaryMessage(audioData: ByteArray) {
        Log.d(TAG, "处理流式音频数据，大小: ${audioData.size} bytes, isStreaming=$isStreaming, state=$streamingState")

        // 检查流式会话是否仍然活跃
        if (!isStreaming || streamingState != StreamingState.READY) {
            Log.d(TAG, "流式会话未活跃，跳过音频数据写入")
            return
        }

        streamingAudioTrack?.let { audioTrack ->
            try {
                // 检查 AudioTrack 状态
                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    val bytesWritten = audioTrack.write(audioData, 0, audioData.size)
                    Log.d(TAG, "写入流式音频数据: $bytesWritten bytes")

                    // 触发音频数据回调（用于 AEC 参考信号）
                    onAudioDataCallback?.invoke(audioData)
                } else {
                    Log.w(TAG, "AudioTrack 状态异常: ${audioTrack.state}, 跳过写入")
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioTrack 写入失败（可能已释放）", e)
                // 不需要在这里停止流式会话，WebSocket的onFailure会处理
            } catch (e: Exception) {
                Log.e(TAG, "写入流式音频数据异常", e)
            }
        } ?: run {
            Log.w(TAG, "streamingAudioTrack 为 null，跳过音频数据写入")
        }
    }
}