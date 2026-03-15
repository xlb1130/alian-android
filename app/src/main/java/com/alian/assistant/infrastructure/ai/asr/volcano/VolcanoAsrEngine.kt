package com.alian.assistant.infrastructure.ai.asr.volcano

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * 火山引擎 ASR（v3）
 *
 * 参考文档：
 * - wss://openspeech.bytedance.com/api/v3/sauc/bigmodel(_nostream/_async)
 * - Header 鉴权: X-Api-App-Key / X-Api-Access-Key / X-Api-Resource-Id / X-Api-Request-Id
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

        private const val WS_URL_BIGMODEL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel"
        private const val WS_URL_BIGMODEL_NOSTREAM = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream"
        private const val WS_URL_BIGMODEL_ASYNC = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"

        private const val SAMPLE_RATE = 16000
        private const val FINAL_RESULT_TIMEOUT_MS = 6000L

        private const val MESSAGE_TYPE_FULL_CLIENT_REQUEST = 0x1
        private const val MESSAGE_TYPE_AUDIO_ONLY_REQUEST = 0x2
        private const val MESSAGE_TYPE_FULL_SERVER_RESPONSE = 0x9
        private const val MESSAGE_TYPE_ERROR = 0xF

        private const val FLAG_NO_SEQUENCE = 0x0
        private const val FLAG_POS_SEQUENCE = 0x1
        private const val FLAG_NEG_SEQUENCE = 0x3

        private const val SERIALIZATION_RAW = 0x0
        private const val SERIALIZATION_JSON = 0x1

        private const val COMPRESSION_NONE = 0x0
        private const val COMPRESSION_GZIP = 0x1
    }

    override val provider: SpeechProvider = SpeechProvider.VOLCANO

    private val running = AtomicBoolean(false)
    private val finalDelivered = AtomicBoolean(false)

    private var webSocket: WebSocket? = null
    private var webSocketJob: Job? = null
    private var audioJob: Job? = null

    @Volatile
    private var wsReady = false

    private val prebuffer = ArrayDeque<ByteArray>()
    private val prebufferLock = Any()

    @Volatile
    private var audioSequence = 2

    @Volatile
    private var lastRecognizedText: String = ""

    private var finalResultDeferred: CompletableDeferred<String?>? = null
    @Volatile
    private var sessionModelName: String = "bigmodel"
    @Volatile
    private var sessionResourceId: String = ""
    private val fallbackRetried = AtomicBoolean(false)

    override val isRunning: Boolean
        get() = running.get()

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
            listener.onError("火山引擎 ASR 凭证不完整：需要 App ID + Access Token")
            return
        }

        running.set(true)
        wsReady = false
        audioSequence = 2
        lastRecognizedText = ""
        finalDelivered.set(false)
        finalResultDeferred = null
        sessionResourceId = ""
        fallbackRetried.set(false)

        webSocketJob?.cancel()
        webSocketJob = scope.launch(Dispatchers.IO) {
            try {
                connectWebSocket()
                if (!externalPcmMode) {
                    startCaptureAndSend()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Volcano ASR", t)
                if (running.get()) {
                    running.set(false)
                    listener.onError(t.message ?: "语音识别错误")
                }
            }
        }
    }

    private fun connectWebSocket(resourceIdOverride: String? = null) {
        val modelLower = config.model.lowercase()
        val (wsUrl, modelName) = when {
            modelLower == "bigmodel_nostream" -> WS_URL_BIGMODEL_NOSTREAM to "bigmodel_nostream"
            modelLower == "bigmodel_async" -> WS_URL_BIGMODEL_ASYNC to "bigmodel_async"
            else -> WS_URL_BIGMODEL to "bigmodel"
        }
        sessionModelName = modelName

        val requestId = UUID.randomUUID().toString()
        val resourceIdRaw = resourceIdOverride ?: config.resourceId.ifBlank {
            if (config.model.startsWith("volc.", ignoreCase = true)) config.model else ""
        }
        val resourceId = normalizeResourceId(resourceIdRaw)
        if (resourceId.isBlank()) {
            throw IllegalArgumentException("火山引擎 ASR Resource ID 为空，请选择有效 ASR 模型")
        }
        sessionResourceId = resourceId
        Log.d(
            TAG,
            "ASR connect wsUrl=$wsUrl, model=$sessionModelName, resourceId=$resourceId, requestId=$requestId"
        )

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("X-Api-App-Key", config.appId)
            .addHeader("X-Api-Access-Key", config.apiKey)
            .addHeader("X-Api-Resource-Id", resourceId)
            .addHeader("X-Api-Request-Id", requestId)
            .addHeader("X-Api-Connect-Id", requestId)
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val wsListener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Volcano ASR websocket opened, requestId=$requestId")
                try {
                    sendFullClientRequest(ws)
                    // 按官方 v1 协议，初始化请求为序号 1；后续音频帧从 2 开始递增
                    audioSequence = 2
                    wsReady = true
                    flushPrebuffer()
                } catch (t: Throwable) {
                    Log.e(TAG, "send full request failed", t)
                    listener.onError("初始化 ASR 会话失败: ${t.message}")
                    running.set(false)
                    closeWebSocket()
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.w(TAG, "unexpected text message: ${text.take(200)}")
                try {
                    val obj = JSONObject(text)
                    val message = obj.optString("message", text)
                    if (message.isNotBlank()) {
                        listener.onError("ASR 服务错误: $message")
                    }
                } catch (_: Throwable) {
                    listener.onError("ASR 服务错误: $text")
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "websocket closing: code=$code reason=$reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "websocket closed: code=$code reason=$reason")
                wsReady = false
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val logId = response?.header("X-Tt-Logid").orEmpty()
                val responseText = runCatching { response?.body?.string().orEmpty() }
                    .getOrNull()
                    ?.take(500)
                if (responseText.isNullOrBlank()) {
                    Log.e(TAG, "websocket failure, code=$code, logId=$logId", t)
                } else {
                    Log.e(TAG, "websocket failure, code=$code, logId=$logId, response=$responseText", t)
                }
                wsReady = false
                if (running.get() && retryWithDurationIfAllowed(code, responseText)) {
                    return
                }
                if (running.get() && !finalDelivered.get()) {
                    running.set(false)
                    val detail = buildString {
                        append("连接失败")
                        if (code != null) append("($code)")
                        if (logId.isNotBlank()) append(" [logid=$logId]")
                        if (!responseText.isNullOrBlank()) append(": $responseText")
                        else if (!t.message.isNullOrBlank()) append(": ${t.message}")
                        if (isResourceIdNotAllowed(code, responseText)) {
                            append("，请确认火山控制台已开通该 ASR 资源 ID")
                        }
                    }
                    listener.onError(detail)
                    finalResultDeferred?.complete(null)
                }
            }
        }

        webSocket = client.newWebSocket(request, wsListener)
    }

    private fun normalizeResourceId(resourceId: String): String {
        val trimmed = resourceId.trim()
        return if (trimmed.startsWith("volc.seedasr.", ignoreCase = true)) {
            trimmed.replaceFirst(Regex("(?i)^volc\\.seedasr\\."), "volc.bigasr.")
        } else {
            trimmed
        }
    }

    private fun isResourceIdNotAllowed(code: Int?, responseText: String?): Boolean {
        if (code != 400 || responseText.isNullOrBlank()) return false
        val lower = responseText.lowercase()
        return lower.contains("resourceid") && lower.contains("not allowed")
    }

    private fun retryWithDurationIfAllowed(code: Int?, responseText: String?): Boolean {
        if (!isResourceIdNotAllowed(code, responseText)) return false
        val current = sessionResourceId
        if (!current.endsWith(".concurrent", ignoreCase = true)) return false
        if (!fallbackRetried.compareAndSet(false, true)) return false

        val fallbackResourceId = current.replace(Regex("(?i)\\.concurrent$"), ".duration")
        Log.w(
            TAG,
            "resourceId=$current not allowed, retry with fallback resourceId=$fallbackResourceId"
        )
        closeWebSocket()
        scope.launch(Dispatchers.IO) {
            try {
                if (running.get()) {
                    connectWebSocket(resourceIdOverride = fallbackResourceId)
                }
            } catch (retryError: Throwable) {
                Log.e(TAG, "retry with fallback resourceId failed", retryError)
                if (running.get() && !finalDelivered.get()) {
                    running.set(false)
                    listener.onError("连接失败: resourceId 未开通，降级到 $fallbackResourceId 重试失败: ${retryError.message}")
                    finalResultDeferred?.complete(null)
                }
            }
        }
        return true
    }

    private fun sendFullClientRequest(ws: WebSocket) {
        val requestJson = JSONObject().apply {
            put("user", JSONObject().apply {
                put("uid", "beanbun-android")
                put("platform", "android")
            })
            put("audio", JSONObject().apply {
                put("format", "pcm")
                put("codec", "raw")
                put("rate", SAMPLE_RATE)
                put("bits", 16)
                put("channel", 1)
                if (config.language.isNotBlank()) {
                    put("language", config.language)
                }
            })
            put("request", JSONObject().apply {
                put("model_name", sessionModelName)
                put("enable_itn", true)
                put("enable_punc", true)
                put("enable_ddc", false)
                put("show_utterances", true)
            })
        }

        val payload = requestJson.toString().toByteArray(StandardCharsets.UTF_8)
        val frame = buildAsrFrame(
            messageType = MESSAGE_TYPE_FULL_CLIENT_REQUEST,
            specificFlags = FLAG_NO_SEQUENCE,
            serialization = SERIALIZATION_JSON,
            compression = COMPRESSION_NONE,
            sequence = null,
            payload = payload
        )

        ws.send(frame.toByteString())
    }

    private fun handleBinaryMessage(frame: ByteArray) {
        if (frame.size < 8) {
            Log.w(TAG, "invalid frame too short: ${frame.size}")
            return
        }

        val header = frame[0].toInt() and 0xFF
        val version = (header ushr 4) and 0x0F
        val headerSize = (header and 0x0F) * 4
        if (version != 1 || frame.size < headerSize + 4) {
            Log.w(TAG, "invalid frame header version=$version headerSize=$headerSize size=${frame.size}")
            return
        }

        val byte1 = frame[1].toInt() and 0xFF
        val messageType = (byte1 ushr 4) and 0x0F
        val messageFlags = byte1 and 0x0F

        val byte2 = frame[2].toInt() and 0xFF
        val serialization = (byte2 ushr 4) and 0x0F
        val compression = byte2 and 0x0F

        var offset = headerSize

        when (messageType) {
            MESSAGE_TYPE_FULL_SERVER_RESPONSE -> {
                var sequence: Int? = null
                if (messageFlags == FLAG_POS_SEQUENCE || messageFlags == FLAG_NEG_SEQUENCE) {
                    if (frame.size < offset + 4) return
                    sequence = readInt32BE(frame, offset)
                    offset += 4
                }

                if (frame.size < offset + 4) return
                val payloadSize = readInt32BE(frame, offset)
                offset += 4
                if (payloadSize < 0 || frame.size < offset + payloadSize) {
                    Log.w(TAG, "invalid payload size: $payloadSize")
                    return
                }

                val rawPayload = frame.copyOfRange(offset, offset + payloadSize)
                val payload = try {
                    when (compression) {
                        COMPRESSION_GZIP -> ungzip(rawPayload)
                        else -> rawPayload
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "decompress payload failed", t)
                    return
                }

                if (serialization != SERIALIZATION_JSON) {
                    Log.w(TAG, "unexpected serialization=$serialization")
                    return
                }

                val payloadText = payload.toString(StandardCharsets.UTF_8)
                val isFinal = messageFlags == 0x2 || messageFlags == FLAG_NEG_SEQUENCE || (sequence ?: 1) < 0
                handleServerJson(payloadText, isFinal)
            }

            MESSAGE_TYPE_ERROR -> {
                if (frame.size < offset + 8) return
                val errorCode = readInt32BE(frame, offset)
                offset += 4
                val payloadSize = readInt32BE(frame, offset)
                offset += 4
                if (payloadSize < 0 || frame.size < offset + payloadSize) {
                    listener.onError("ASR 协议错误: invalid error payload")
                    return
                }
                val payload = frame.copyOfRange(offset, offset + payloadSize)
                val message = payload.toString(StandardCharsets.UTF_8)
                listener.onError("ASR 服务错误($errorCode): $message")
                finalResultDeferred?.complete(null)
            }

            else -> {
                Log.d(TAG, "ignore messageType=$messageType flags=$messageFlags")
            }
        }
    }

    private fun handleServerJson(payloadText: String, isFinalFrame: Boolean) {
        try {
            val response = JSONObject(payloadText)
            val statusCode = when {
                response.has("status_code") -> response.optInt("status_code")
                response.has("code") -> response.optInt("code")
                else -> 20000000
            }

            if (statusCode != 0 && statusCode != 20000000) {
                val message = response.optString("message", "未知错误")
                listener.onError("ASR 错误($statusCode): $message")
                finalResultDeferred?.complete(null)
                return
            }

            val resultObj = response.optJSONObject("result")
            val text = resultObj?.optString("text", "")?.trim().orEmpty()
            if (text.isNotEmpty()) {
                lastRecognizedText = text
            }

            if (isFinalFrame) {
                if (finalDelivered.compareAndSet(false, true)) {
                    listener.onFinal(lastRecognizedText)
                }
                finalResultDeferred?.complete(lastRecognizedText)
            } else if (text.isNotEmpty()) {
                listener.onPartial(text)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "parse response json failed: $payloadText", t)
        }
    }

    private fun sendAudioFrame(pcm: ByteArray, isFinal: Boolean) {
        if (!wsReady) return
        val ws = webSocket ?: return

        val seq = if (isFinal) -audioSequence else audioSequence
        audioSequence += 1

        val frame = buildAsrFrame(
            messageType = MESSAGE_TYPE_AUDIO_ONLY_REQUEST,
            specificFlags = if (isFinal) FLAG_NEG_SEQUENCE else FLAG_POS_SEQUENCE,
            serialization = SERIALIZATION_RAW,
            compression = COMPRESSION_NONE,
            sequence = seq,
            payload = pcm
        )

        ws.send(frame.toByteString())
    }

    private fun buildAsrFrame(
        messageType: Int,
        specificFlags: Int,
        serialization: Int,
        compression: Int,
        sequence: Int?,
        payload: ByteArray
    ): ByteArray {
        val baseHeader = byteArrayOf(
            ((1 shl 4) or 1).toByte(),
            ((messageType shl 4) or (specificFlags and 0x0F)).toByte(),
            ((serialization shl 4) or (compression and 0x0F)).toByte(),
            0x00
        )

        val total = baseHeader.size + (if (sequence != null) 4 else 0) + 4 + payload.size
        return ByteBuffer.allocate(total)
            .order(ByteOrder.BIG_ENDIAN)
            .put(baseHeader)
            .apply {
                if (sequence != null) {
                    putInt(sequence)
                }
                putInt(payload.size)
                put(payload)
            }
            .array()
    }

    private fun flushPrebuffer() {
        val toFlush = mutableListOf<ByteArray>()
        synchronized(prebufferLock) {
            while (prebuffer.isNotEmpty()) {
                toFlush.add(prebuffer.removeFirst())
            }
        }
        toFlush.forEach { sendAudioFrame(it, isFinal = false) }
    }

    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!running.get()) return
        if (sampleRate != SAMPLE_RATE || channels != 1) return

        try {
            listener.onAmplitude(calculateNormalizedAmplitude(pcm))
        } catch (t: Throwable) {
            Log.w(TAG, "notify amplitude failed", t)
        }

        if (!wsReady) {
            synchronized(prebufferLock) {
                prebuffer.addLast(pcm.copyOf())
            }
            return
        }

        flushPrebuffer()
        sendAudioFrame(pcm, isFinal = false)
    }

    override fun commit() {
        if (!wsReady) return
        sendAudioFrame(ByteArray(0), isFinal = true)
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)

        scope.launch(Dispatchers.IO) {
            val resultDeferred = CompletableDeferred<String?>()
            finalResultDeferred = resultDeferred

            try {
                listener.onStopped()

                try {
                    audioJob?.cancel()
                    audioJob?.join()
                } catch (t: Throwable) {
                    Log.w(TAG, "cancel audio job failed", t)
                }
                audioJob = null

                if (wsReady) {
                    commit()
                }

                val finalText = withTimeoutOrNull(FINAL_RESULT_TIMEOUT_MS) {
                    resultDeferred.await()
                } ?: lastRecognizedText

                if (finalDelivered.compareAndSet(false, true)) {
                    listener.onFinal(finalText)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "stop cleanup failed", t)
            } finally {
                finalResultDeferred = null
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
            webSocket?.close(1000, "normal")
        } catch (t: Throwable) {
            Log.w(TAG, "close websocket failed", t)
        }
        webSocket = null
    }

    private fun startCaptureAndSend() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = SAMPLE_RATE,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT,
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
                        listener.onAmplitude(calculateNormalizedAmplitude(audioChunk))
                    } catch (t: Throwable) {
                        Log.w(TAG, "notify amplitude failed", t)
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
                        synchronized(prebufferLock) {
                            prebuffer.addLast(audioChunk.copyOf())
                        }
                    } else {
                        flushPrebuffer()
                        sendAudioFrame(audioChunk, isFinal = false)
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    Log.e(TAG, "capture failed", t)
                    listener.onError("音频采集失败: ${t.message}")
                }
            }
        }
    }

    private fun readInt32BE(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun ungzip(data: ByteArray): ByteArray {
        ByteArrayInputStream(data).use { bis ->
            GZIPInputStream(bis).use { gis ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val read = gis.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                return out.toByteArray()
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

    override fun create(
        config: AsrConfig,
        listener: AsrListener,
        externalPcmMode: Boolean
    ): AsrEngine {
        return VolcanoAsrEngine(
            config = config,
            context = context,
            scope = scope,
            listener = listener,
            externalPcmMode = externalPcmMode
        )
    }
}
