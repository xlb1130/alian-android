package com.alian.assistant.infrastructure.ai.tts.bailian

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.alian.assistant.data.model.SpeechProvider
import com.alian.assistant.infrastructure.ai.tts.TtsConfig
import com.alian.assistant.infrastructure.ai.tts.TtsEngine
import com.alian.assistant.infrastructure.ai.tts.TtsEngineFactory
import com.alian.assistant.infrastructure.ai.tts.TtsPlaybackState
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
    val action: String,
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
    val event: String = "",
    val status: Int = 0,
    val message: String = "",
    val error_code: String? = null,
    val error_message: String? = null
)

@Serializable
data class CosyVoiceTTSResponsePayload(
    val audio: String? = null,
    val text: String? = null,
    val usage: Map<String, Int>? = null
)

/**
 * 百炼（阿里云 DashScope CosyVoice）TTS 引擎
 * 支持流式语音合成和实时播放
 */
class BailianTtsEngine(
    private val config: TtsConfig
) : TtsEngine {

    companion object {
        private const val TAG = "BailianTtsEngine"
        private const val WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    override val provider: SpeechProvider = SpeechProvider.BAILIAN

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var currentWebSocket: WebSocket? = null
    private var currentAudioTrack: AudioTrack? = null
    private var isPlaying = false

    // 音频数据回调
    private var onAudioDataCallback: ((ByteArray) -> Unit)? = null

    // 播放状态回调
    private var onPlaybackStateCallback: ((TtsPlaybackState) -> Unit)? = null

    // 流式 TTS 状态
    private var streamingWebSocket: WebSocket? = null
    private var streamingAudioTrack: AudioTrack? = null
    private var isStreaming = false
    private var streamingTaskId: String = ""

    // 协议状态
    private enum class ProtocolState {
        IDLE, WAITING_TASK_STARTED, READY_TO_SEND_TEXT, SENDING_TEXT, FINISHING, COMPLETED, ERROR
    }

    private var protocolState = ProtocolState.IDLE
    private var currentTaskId: String = ""
    private var textToSend: String = ""

    // 流式状态
    private enum class StreamingState {
        IDLE, WAITING_TASK_STARTED, READY, COMPLETED, ERROR
    }

    private var streamingState: StreamingState = StreamingState.IDLE

    // 会话标识符
    @Volatile
    private var currentSessionId: Long = 0
    private val sessionLock = Any()
    @Volatile
    private var isOldSessionClosed = true

    override fun isCurrentlyPlaying(): Boolean = isPlaying

    override fun setOnAudioDataCallback(callback: ((ByteArray) -> Unit)?) {
        onAudioDataCallback = callback
    }

    override fun setOnPlaybackStateCallback(callback: ((TtsPlaybackState) -> Unit)?) {
        onPlaybackStateCallback = callback
    }

    override suspend fun synthesizeAndPlay(text: String, context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始流式语音合成并播放: text长度=${text.length}")

            cancelPlayback()

            val taskId = System.currentTimeMillis().toString()

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )

            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
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
            onPlaybackStateCallback?.invoke(TtsPlaybackState.Playing)

            val request = Request.Builder()
                .url(WS_URL)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("X-DashScope-DataStream", "cosyvoice-v1")
                .build()

            val webSocketListener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket 连接已建立")
                    protocolState = ProtocolState.WAITING_TASK_STARTED
                    currentTaskId = taskId
                    textToSend = text

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
                            model = config.model,
                            parameters = buildJsonObject {
                                put("text_type", JsonPrimitive("PlainText"))
                                put("voice", JsonPrimitive(config.voice))
                                put("format", JsonPrimitive("pcm"))
                                put("sample_rate", JsonPrimitive(SAMPLE_RATE))
                                put("volume", JsonPrimitive(config.volume))
                                put("rate", JsonPrimitive(config.speed))
                                put("pitch", JsonPrimitive(1))
                            },
                            input = buildJsonObject { }
                        )
                    )

                    val message = json.encodeToString(runTaskRequest)
                    ws.send(message)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val response = json.decodeFromString<CosyVoiceTTSResponse>(text)
                        handleTextMessage(ws, response)
                    } catch (e: Exception) {
                        Log.e(TAG, "解析文本消息失败", e)
                    }
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    handleBinaryMessage(bytes.toByteArray())
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 正在关闭: code=$code, reason=$reason")
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 已关闭: code=$code, reason=$reason")
                    cleanup()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket 连接失败", t)
                    protocolState = ProtocolState.ERROR
                    onPlaybackStateCallback?.invoke(TtsPlaybackState.Error(t.message ?: "连接失败"))
                    cleanup()
                }
            }

            currentWebSocket = client.newWebSocket(request, webSocketListener)

            while (isPlaying) {
                delay(100)
            }

            Log.d(TAG, "语音合成并播放完成")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "流式语音合成并播放失败", e)
            onPlaybackStateCallback?.invoke(TtsPlaybackState.Error(e.message ?: "未知错误"))
            cleanup()
            Result.failure(e)
        }
    }

    private fun handleTextMessage(ws: WebSocket, response: CosyVoiceTTSResponse) {
        when (response.header.event) {
            "task-started" -> {
                if (protocolState == ProtocolState.WAITING_TASK_STARTED) {
                    protocolState = ProtocolState.READY_TO_SEND_TEXT

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
                    ws.send(json.encodeToString(continueTaskRequest))

                    protocolState = ProtocolState.FINISHING
                    val finishTaskRequest = CosyVoiceTTSRequest(
                        header = CosyVoiceTTSHeader(
                            action = "finish-task",
                            task_id = currentTaskId,
                            streaming = "duplex"
                        ),
                        payload = CosyVoiceTTSPayload(input = buildJsonObject { })
                    )
                    ws.send(json.encodeToString(finishTaskRequest))
                }
            }
            "task-finished" -> {
                protocolState = ProtocolState.COMPLETED
                isPlaying = false
                onPlaybackStateCallback?.invoke(TtsPlaybackState.Completed)
            }
            "task-failed" -> {
                protocolState = ProtocolState.ERROR
                isPlaying = false
                onPlaybackStateCallback?.invoke(TtsPlaybackState.Error(response.header.error_message ?: "任务失败"))
            }
        }
    }

    private fun handleBinaryMessage(audioData: ByteArray) {
        currentAudioTrack?.let { audioTrack ->
            try {
                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack.write(audioData, 0, audioData.size)
                } else {
                    Log.w(TAG, "AudioTrack 状态异常")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack 写入失败", e)
            }
        }
        onAudioDataCallback?.invoke(audioData)
    }

    override suspend fun startStreamingSession(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            synchronized(sessionLock) {
                if (!isOldSessionClosed) {
                    stopStreamingSessionInternal()
                    val startTime = System.currentTimeMillis()
                    while (!isOldSessionClosed && System.currentTimeMillis() - startTime < 2000) {
                        Thread.sleep(50)
                    }
                }
            }

            val newSessionId = System.currentTimeMillis()
            currentSessionId = newSessionId
            streamingTaskId = newSessionId.toString()

            synchronized(sessionLock) {
                isOldSessionClosed = false
            }

            isStreaming = true
            streamingState = StreamingState.WAITING_TASK_STARTED

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )

            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
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

            val sessionId = newSessionId
            val request = Request.Builder()
                .url(WS_URL)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("X-DashScope-DataStream", "cosyvoice-v1")
                .build()

            val webSocketListener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (sessionId != currentSessionId) {
                        ws.close(1000, "会话已过期")
                        return
                    }

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
                            model = config.model,
                            parameters = buildJsonObject {
                                put("text_type", JsonPrimitive("PlainText"))
                                put("voice", JsonPrimitive(config.voice))
                                put("format", JsonPrimitive("pcm"))
                                put("sample_rate", JsonPrimitive(SAMPLE_RATE))
                                put("volume", JsonPrimitive(config.volume))
                                put("rate", JsonPrimitive(config.speed))
                                put("pitch", JsonPrimitive(1))
                            },
                            input = buildJsonObject { }
                        )
                    )
                    ws.send(json.encodeToString(runTaskRequest))
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (sessionId != currentSessionId) return
                    try {
                        val response = json.decodeFromString<CosyVoiceTTSResponse>(text)
                        handleStreamingTextMessage(response)
                    } catch (e: Exception) {
                        Log.e(TAG, "解析流式文本消息失败", e)
                    }
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (sessionId != currentSessionId) return
                    handleStreamingBinaryMessage(bytes.toByteArray())
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    synchronized(sessionLock) { isOldSessionClosed = true }
                    if (sessionId == currentSessionId) {
                        streamingState = StreamingState.COMPLETED
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    synchronized(sessionLock) { isOldSessionClosed = true }
                    if (sessionId == currentSessionId) {
                        streamingState = StreamingState.ERROR
                    }
                }
            }

            streamingWebSocket = client.newWebSocket(request, webSocketListener)

            var attempts = 0
            val maxAttempts = 300
            while (streamingState == StreamingState.WAITING_TASK_STARTED && attempts < maxAttempts) {
                delay(100)
                attempts++
            }

            if (streamingState != StreamingState.READY) {
                stopStreamingSession()
                return@withContext Result.failure(Exception("流式会话启动失败"))
            }

            Result.success(Unit)

        } catch (e: Exception) {
            stopStreamingSession()
            Result.failure(e)
        }
    }

    override fun sendStreamingChunk(chunk: String): Result<Unit> {
        if (!isStreaming || streamingState != StreamingState.READY) {
            return Result.failure(Exception("流式会话未就绪"))
        }

        if (chunk.isBlank()) {
            return Result.success(Unit)
        }

        return try {
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
            val success = streamingWebSocket?.send(json.encodeToString(continueTaskRequest)) ?: false
            if (success) Result.success(Unit) else Result.failure(Exception("发送失败"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun finishStreamingSession(): Result<Unit> {
        if (!isStreaming || streamingState != StreamingState.READY) {
            return Result.failure(Exception("流式会话未就绪"))
        }

        return try {
            val finishTaskRequest = CosyVoiceTTSRequest(
                header = CosyVoiceTTSHeader(
                    action = "finish-task",
                    task_id = streamingTaskId,
                    streaming = "duplex"
                ),
                payload = CosyVoiceTTSPayload(input = buildJsonObject { })
            )
            val success = streamingWebSocket?.send(json.encodeToString(finishTaskRequest)) ?: false
            if (success) Result.success(Unit) else Result.failure(Exception("发送失败"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun handleStreamingTextMessage(response: CosyVoiceTTSResponse) {
        when (response.header.event) {
            "task-started" -> {
                if (streamingState == StreamingState.WAITING_TASK_STARTED) {
                    streamingState = StreamingState.READY
                }
            }
            "task-finished" -> {
                streamingState = StreamingState.COMPLETED
            }
            "task-failed" -> {
                streamingState = StreamingState.ERROR
            }
        }
    }

    private fun handleStreamingBinaryMessage(audioData: ByteArray) {
        if (!isStreaming || streamingState != StreamingState.READY) return

        streamingAudioTrack?.let { audioTrack ->
            try {
                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack.write(audioData, 0, audioData.size)
                } else {
                    Log.w(TAG, "AudioTrack 状态异常")
                }
            } catch (e: Exception) {
                Log.e(TAG, "写入流式音频数据失败", e)
            }
        }
        onAudioDataCallback?.invoke(audioData)
    }

    override fun cancelPlayback() {
        if (isPlaying) {
            isPlaying = false
            cleanup()
        }
        if (isStreaming) {
            stopStreamingSession()
        }
    }

    private fun stopStreamingSession() {
        currentSessionId = 0
        stopStreamingSessionInternal()
    }

    private fun stopStreamingSessionInternal() {
        isStreaming = false
        streamingState = StreamingState.IDLE

        streamingAudioTrack?.let { audioTrack ->
            try {
                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack.stop()
                }
                audioTrack.release()
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

        synchronized(sessionLock) {
            isOldSessionClosed = true
        }
    }

    override fun release() {
        cancelPlayback()
    }

    private fun cleanup() {
        isPlaying = false
        protocolState = ProtocolState.IDLE

        currentAudioTrack?.let { audioTrack ->
            try {
                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack.stop()
                }
                audioTrack.release()
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
}

/**
 * 百炼 TTS 引擎工厂
 */
class BailianTtsEngineFactory : TtsEngineFactory {
    override val provider: SpeechProvider = SpeechProvider.BAILIAN

    override fun create(config: TtsConfig): TtsEngine {
        return BailianTtsEngine(config)
    }
}
