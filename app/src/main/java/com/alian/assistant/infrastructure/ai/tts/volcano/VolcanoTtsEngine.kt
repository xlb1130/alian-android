package com.alian.assistant.infrastructure.ai.tts.volcano

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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 火山引擎 TTS 引擎
 * 
 * 基于火山引擎 TTS API 实现
 * WebSocket 端点: wss://openspeech.bytedance.com/api/v1/tts_binary_llm
 */
class VolcanoTtsEngine(
    private val config: TtsConfig
) : TtsEngine {

    companion object {
        private const val TAG = "VolcanoTtsEngine"
        private const val WS_URL = "wss://openspeech.bytedance.com/api/v1/tts_binary_llm"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    override val provider: SpeechProvider = SpeechProvider.VOLCANO

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private var currentWebSocket: WebSocket? = null
    private var currentAudioTrack: AudioTrack? = null
    private var isPlaying = false

    private var onAudioDataCallback: ((ByteArray) -> Unit)? = null
    private var onPlaybackStateCallback: ((TtsPlaybackState) -> Unit)? = null

    // 流式状态
    private var streamingWebSocket: WebSocket? = null
    private var streamingAudioTrack: AudioTrack? = null
    private var isStreaming = false
    private var streamingTaskId: String = ""

    private enum class StreamingState {
        IDLE, WAITING_READY, READY, COMPLETED, ERROR
    }

    private var streamingState = StreamingState.IDLE
    private var currentSessionId: Long = 0

    override fun isCurrentlyPlaying(): Boolean = isPlaying

    override fun setOnAudioDataCallback(callback: ((ByteArray) -> Unit)?) {
        onAudioDataCallback = callback
    }

    override fun setOnPlaybackStateCallback(callback: ((TtsPlaybackState) -> Unit)?) {
        onPlaybackStateCallback = callback
    }

    override suspend fun synthesizeAndPlay(text: String, context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始火山引擎语音合成: text长度=${text.length}")

            cancelPlayback()

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

            val authParams = buildAuthParams()
            val request = Request.Builder()
                .url("$WS_URL?$authParams")
                .build()

            val webSocketListener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket 连接已建立")
                    // 发送 TTS 请求
                    sendTtsRequest(ws, text)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleServerMessage(text)
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
            Log.e(TAG, "语音合成失败", e)
            onPlaybackStateCallback?.invoke(TtsPlaybackState.Error(e.message ?: "未知错误"))
            cleanup()
            Result.failure(e)
        }
    }

    private fun buildAuthParams(): String {
        val appId = config.appId
        val token = config.apiKey
        val cluster = config.cluster.ifEmpty { "volcano_tts" }
        return "app_id=$appId&token=$token&cluster=$cluster"
    }

    private fun sendTtsRequest(ws: WebSocket, text: String) {
        try {
            // 构建火山引擎 TTS 请求
            val requestJson = """
                {
                    "app": {
                        "appid": "${config.appId}",
                        "token": "${config.apiKey}",
                        "cluster": "${config.cluster.ifEmpty { "volcano_tts" }}"
                    },
                    "user": {
                        "uid": "user"
                    },
                    "audio": {
                        "voice_type": "${config.voice}",
                        "encoding": "pcm",
                        "speed_ratio": ${config.speed},
                        "volume_ratio": ${config.volume / 50.0},
                        "pitch_ratio": 1.0
                    },
                    "request": {
                        "reqid": "${System.currentTimeMillis()}",
                        "text": "${text.replace("\"", "\\\"")}",
                        "operation": "query"
                    }
                }
            """.trimIndent()

            ws.send(requestJson)
            Log.d(TAG, "已发送 TTS 请求")
        } catch (e: Exception) {
            Log.e(TAG, "发送 TTS 请求失败", e)
        }
    }

    private fun handleServerMessage(text: String) {
        Log.d(TAG, "收到服务端消息: ${text.take(200)}")
        try {
            val response = org.json.JSONObject(text)
            
            val code = response.optInt("code", 0)
            if (code != 0) {
                val message = response.optString("message", "未知错误")
                Log.e(TAG, "TTS 错误: $message (code: $code)")
                isPlaying = false
                onPlaybackStateCallback?.invoke(TtsPlaybackState.Error(message))
                return
            }

            // 检查是否完成
            val isComplete = response.optBoolean("is_complete", false)
            if (isComplete) {
                isPlaying = false
                onPlaybackStateCallback?.invoke(TtsPlaybackState.Completed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析服务端消息失败", e)
        }
    }

    private fun handleBinaryMessage(audioData: ByteArray) {
        currentAudioTrack?.let { audioTrack ->
            try {
                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    // 火山引擎二进制响应可能包含头部，需要解析
                    val pcmData = parseBinaryAudio(audioData)
                    if (pcmData != null) {
                        audioTrack.write(pcmData, 0, pcmData.size)
                    } else {
                        Log.w(TAG, "无法解析音频数据")
                    }
                } else {
                    Log.w(TAG, "AudioTrack 状态异常")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack 写入失败", e)
            }
        }
        onAudioDataCallback?.invoke(audioData)
    }

    /**
     * 解析火山引擎二进制音频数据
     * 火山引擎返回的二进制数据可能包含协议头
     */
    private fun parseBinaryAudio(data: ByteArray): ByteArray? {
        if (data.size < 11) return null

        // 解析头部
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val protocolVersion = buffer.get().toInt() and 0xFF
        val headerSize = buffer.short.toInt() and 0xFFFF
        val payloadType = buffer.get().toInt() and 0xFF
        val payloadSize = buffer.int

        // 检查是否为音频数据
        if (payloadType != 1 && payloadType != 3) return null

        // 提取 PCM 数据
        if (data.size >= headerSize + payloadSize) {
            return data.copyOfRange(headerSize, headerSize + payloadSize)
        }

        return null
    }

    override suspend fun startStreamingSession(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val newSessionId = System.currentTimeMillis()
            currentSessionId = newSessionId
            streamingTaskId = newSessionId.toString()

            isStreaming = true
            streamingState = StreamingState.WAITING_READY

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

            val authParams = buildAuthParams()
            val sessionId = newSessionId
            val request = Request.Builder()
                .url("$WS_URL?$authParams")
                .build()

            val webSocketListener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (sessionId != currentSessionId) {
                        ws.close(1000, "会话已过期")
                        return
                    }
                    streamingState = StreamingState.READY
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (sessionId != currentSessionId) return
                    handleStreamingTextMessage(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (sessionId != currentSessionId) return
                    handleStreamingBinaryMessage(bytes.toByteArray())
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (sessionId == currentSessionId) {
                        streamingState = StreamingState.COMPLETED
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (sessionId == currentSessionId) {
                        streamingState = StreamingState.ERROR
                    }
                }
            }

            streamingWebSocket = client.newWebSocket(request, webSocketListener)

            var attempts = 0
            while (streamingState == StreamingState.WAITING_READY && attempts < 300) {
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
            sendTtsRequest(streamingWebSocket!!, chunk)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun finishStreamingSession(): Result<Unit> {
        if (!isStreaming || streamingState != StreamingState.READY) {
            return Result.failure(Exception("流式会话未就绪"))
        }

        return try {
            streamingWebSocket?.close(1000, "正常关闭")
            streamingState = StreamingState.COMPLETED
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun handleStreamingTextMessage(text: String) {
        try {
            val response = org.json.JSONObject(text)
            val code = response.optInt("code", 0)
            if (code != 0) {
                streamingState = StreamingState.ERROR
            }
            val isComplete = response.optBoolean("is_complete", false)
            if (isComplete) {
                streamingState = StreamingState.COMPLETED
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析流式文本消息失败", e)
        }
    }

    private fun handleStreamingBinaryMessage(audioData: ByteArray) {
        if (!isStreaming || streamingState != StreamingState.READY) return

        streamingAudioTrack?.let { audioTrack ->
            try {
                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    val pcmData = parseBinaryAudio(audioData)
                    if (pcmData != null) {
                        audioTrack.write(pcmData, 0, pcmData.size)
                    } else {
                        Log.w(TAG, "无法解析音频数据")
                    }
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
    }

    override fun release() {
        cancelPlayback()
    }

    private fun cleanup() {
        isPlaying = false

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
 * 火山引擎 TTS 引擎工厂
 */
class VolcanoTtsEngineFactory : TtsEngineFactory {
    override val provider: SpeechProvider = SpeechProvider.VOLCANO

    override fun create(config: TtsConfig): TtsEngine {
        return VolcanoTtsEngine(config)
    }
}
