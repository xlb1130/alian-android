package com.alian.assistant.infrastructure.ai.asr.volcano

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.alian.assistant.data.model.SpeechProvider
import com.alian.assistant.infrastructure.ai.asr.AsrConfig
import com.alian.assistant.infrastructure.ai.asr.AsrEngine
import com.alian.assistant.infrastructure.ai.asr.AsrEngineFactory
import com.alian.assistant.infrastructure.ai.asr.AsrListener
import com.alian.assistant.infrastructure.ai.asr.AudioCaptureManager
import com.alian.assistant.infrastructure.ai.asr.ExternalPcmConsumer
import com.alian.assistant.infrastructure.ai.asr.StreamingAsrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 火山引擎 ASR 引擎
 * 
 * 基于火山引擎流式语音识别 API 实现
 * WebSocket 端点: wss://openspeech.bytedance.com/api/v2/asr
 */
class VolcanoAsrEngine(
    private val config: AsrConfig,
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val externalPcmMode: Boolean = false
) : AsrEngine, StreamingAsrEngine, ExternalPcmConsumer {

    companion object {
        private const val TAG = "VolcanoAsrEngine"
        private const val WS_URL = "wss://openspeech.bytedance.com/api/v2/asr"
        private const val SAMPLE_RATE = 16000
        private const val FINAL_RESULT_TIMEOUT_MS = 6000L
    }

    override val provider: SpeechProvider = SpeechProvider.VOLCANO

    private val running = AtomicBoolean(false)
    private var audioJob: Job? = null
    private var webSocketJob: Job? = null

    private var webSocket: WebSocket? = null
    private var currentText: StringBuilder = StringBuilder()
    private var finalResult: String = ""

    override val isRunning: Boolean
        get() = running.get()

    private val prebuffer = mutableListOf<ByteArray>()
    private val prebufferLock = Any()
    @Volatile
    private var wsReady = false

    override fun start() {
        if (running.get()) return

        if (!externalPcmMode) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                listener.onError("没有录音权限")
                return
            }
        }

        if (config.apiKey.isBlank() || config.appId.isBlank()) {
            listener.onError("API Key 或 App ID 为空")
            return
        }

        running.set(true)
        currentText.clear()
        finalResult = ""
        wsReady = false

        webSocketJob?.cancel()
        webSocketJob = scope.launch(Dispatchers.IO) {
            try {
                connectWebSocket()

                if (!externalPcmMode) {
                    startCaptureAndSend()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Volcano ASR", t)
                listener.onError(t.message ?: "语音识别错误")
                running.set(false)
            }
        }
    }

    private suspend fun connectWebSocket() = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        // 构建火山引擎认证参数
        val authParams = buildAuthParams()

        val request = Request.Builder()
            .url("$WS_URL?$authParams")
            .build()

        val wsListener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket 连接已建立")
                wsReady = true
                flushPrebuffer()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // 火山引擎可能使用二进制消息
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 正在关闭: code=$code, reason=$reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 已关闭: code=$code, reason=$reason")
                wsReady = false
                if (running.get()) {
                    running.set(false)
                    listener.onError("连接已关闭: $reason")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 连接失败", t)
                wsReady = false
                if (running.get()) {
                    running.set(false)
                    listener.onError("连接失败: ${t.message}")
                }
            }
        }

        webSocket = client.newWebSocket(request, wsListener)
    }

    /**
     * 构建火山引擎认证参数
     * 火山引擎使用 URL 参数进行认证
     */
    private fun buildAuthParams(): String {
        // 火山引擎 ASR API 认证参数
        // 参考文档: https://www.volcengine.com/docs/6561/79820
        val appId = config.appId
        val token = config.apiKey
        
        return "app_id=$appId&token=$token&cluster=${config.cluster.ifEmpty { "volc_asr" }}"
    }

    private fun handleServerMessage(text: String) {
        Log.d(TAG, "收到服务端消息: $text")
        try {
            // 解析火山引擎 ASR 响应
            // 响应格式为 JSON
            val response = org.json.JSONObject(text)
            
            val result = response.optJSONObject("result")
            if (result != null) {
                val text = result.optString("text", "")
                val isFinal = result.optBoolean("is_final", false)

                if (isFinal) {
                    finalResult = text
                    currentText.append(text)
                    listener.onFinal(currentText.toString().trim())
                } else {
                    listener.onPartial(text)
                }
            }

            // 检查错误
            val errorCode = response.optInt("code", 0)
            if (errorCode != 0) {
                val errorMsg = response.optString("message", "未知错误")
                listener.onError("识别错误: $errorMsg (code: $errorCode)")
                running.set(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析服务端消息失败", e)
        }
    }

    private fun handleBinaryMessage(data: ByteArray) {
        // 火山引擎可能使用二进制协议
        Log.d(TAG, "收到二进制消息，大小: ${data.size}")
    }

    /**
     * 发送音频数据到火山引擎
     */
    private fun sendAudioData(pcm: ByteArray) {
        if (!wsReady || webSocket == null) return

        try {
            // 火山引擎 ASR 使用特定的二进制协议格式
            // 格式: header (11 bytes) + payload
            val header = buildFrameHeader(pcm.size, sequenceNumber = 0, payloadType = 1)
            val frame = ByteBuffer.allocate(header.size + pcm.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(header)
                .put(pcm)
                .array()

            webSocket?.send(frame.toByteString())
        } catch (e: Exception) {
            Log.e(TAG, "发送音频数据失败", e)
        }
    }

    /**
     * 构建火山引擎音频帧头
     */
    private fun buildFrameHeader(payloadSize: Int, sequenceNumber: Int, payloadType: Int): ByteArray {
        // 火山引擎音频帧格式
        // 参考文档: https://www.volcengine.com/docs/6561/79820
        val buffer = ByteBuffer.allocate(11)
            .order(ByteOrder.LITTLE_ENDIAN)
        
        // Protocol version (1 byte)
        buffer.put(0x01)
        // Header size (2 bytes)
        buffer.putShort(11)
        // Payload type (1 byte): 1 = audio
        buffer.put(payloadType.toByte())
        // Payload size (4 bytes)
        buffer.putInt(payloadSize)
        // Sequence number (4 bytes)
        buffer.putInt(sequenceNumber)

        return buffer.array()
    }

    private fun flushPrebuffer() {
        val flushed: List<ByteArray>
        synchronized(prebufferLock) {
            flushed = prebuffer.toList()
            prebuffer.clear()
        }
        flushed.forEach { sendAudioData(it) }
    }

    // ========== ExternalPcmConsumer ==========
    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!running.get()) return
        if (sampleRate != SAMPLE_RATE || channels != 1) return

        try {
            listener.onAmplitude(calculateNormalizedAmplitude(pcm))
        } catch (t: Throwable) {
            Log.w(TAG, "notify amplitude failed", t)
        }

        if (!wsReady) {
            synchronized(prebufferLock) { prebuffer.add(pcm.copyOf()) }
        } else {
            flushPrebuffer()
            sendAudioData(pcm)
        }
    }

    override fun commit() {
        // 火山引擎 ASR 使用 VAD 自动判断结束
        // 发送结束帧
        if (wsReady && webSocket != null) {
            try {
                val endFrame = buildEndFrame()
                webSocket?.send(endFrame.toByteString())
            } catch (e: Exception) {
                Log.e(TAG, "发送结束帧失败", e)
            }
        }
    }

    private fun buildEndFrame(): ByteArray {
        // 构建结束帧
        return buildFrameHeader(0, sequenceNumber = -1, payloadType = 0)
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)

        scope.launch(Dispatchers.IO) {
            try {
                listener.onStopped()

                audioJob?.cancel()
                audioJob?.join()
                audioJob = null

                commit()

                // 等待最终结果
                delay(500)

                if (finalResult.isEmpty() && currentText.isNotEmpty()) {
                    listener.onFinal(currentText.toString().trim())
                }

            } catch (t: Throwable) {
                Log.w(TAG, "stop cleanup failed", t)
            } finally {
                closeWebSocket()
            }
        }
    }

    override fun release() {
        stop()
        closeWebSocket()
    }

    private fun closeWebSocket() {
        wsReady = false
        try {
            webSocket?.close(1000, "正常关闭")
        } catch (e: Exception) {
            Log.w(TAG, "关闭 WebSocket 失败", e)
        }
        webSocket = null
    }

    private fun startCaptureAndSend() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = SAMPLE_RATE,
                channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO,
                audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT,
                chunkMillis = 100
            )

            if (!audioManager.hasPermission()) {
                listener.onError("没有录音权限")
                running.set(false)
                return@launch
            }

            val vadSilenceWindowFrames = 30
            var vadSilenceFrames = 0

            try {
                audioManager.startCaptureWithVAD().collect { (audioChunk, vadResult) ->
                    if (!running.get()) return@collect

                    try {
                        val amplitude = calculateNormalizedAmplitude(audioChunk)
                        listener.onAmplitude(amplitude)
                    } catch (t: Throwable) {
                        Log.w(TAG, "计算振幅失败", t)
                    }

                    if (!vadResult.isSpeech) {
                        vadSilenceFrames++
                        if (vadSilenceFrames >= vadSilenceWindowFrames) {
                            listener.onStopped()
                            stop()
                            return@collect
                        }
                    } else {
                        vadSilenceFrames = 0
                    }

                    if (!wsReady) {
                        synchronized(prebufferLock) { prebuffer.add(audioChunk.copyOf()) }
                    } else {
                        flushPrebuffer()
                        sendAudioData(audioChunk)
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    Log.e(TAG, "音频采集失败", t)
                    listener.onError("音频采集失败: ${t.message}")
                }
            }
        }
    }
}

/**
 * 计算归一化振幅
 */
private fun calculateNormalizedAmplitude(pcm: ByteArray): Float {
    if (pcm.isEmpty()) return 0f
    var maxAmplitude = 0
    var minAmplitude = 0
    var i = 0
    while (i + 1 < pcm.size) {
        val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF))
        if (sample > maxAmplitude) maxAmplitude = sample
        if (sample < minAmplitude) minAmplitude = sample
        i += 2
    }
    val peakToPeak = (maxAmplitude - minAmplitude).toFloat()
    return (peakToPeak / 65535f).coerceIn(0f, 1f)
}

/**
 * 火山引擎 ASR 引擎工厂
 */
class VolcanoAsrEngineFactory(
    private val context: Context,
    private val scope: CoroutineScope
) : AsrEngineFactory {
    override val provider: SpeechProvider = SpeechProvider.VOLCANO

    override fun create(config: AsrConfig, listener: AsrListener): AsrEngine {
        return VolcanoAsrEngine(
            config = config,
            context = context,
            scope = scope,
            listener = listener
        )
    }
}
