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
import kotlinx.coroutines.CompletableDeferred
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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 火山引擎 TTS（v3 双向流式）
 *
 * 文档：wss://openspeech.bytedance.com/api/v3/tts/bidirection
 */
class VolcanoTtsEngine(
    private val config: TtsConfig
) : TtsEngine {

    companion object {
        private const val TAG = "VolcanoTtsEngine"
        private const val WS_URL = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"

        private const val DEFAULT_RESOURCE_ID = "seed-tts-2.0"
        private const val DEFAULT_SPEAKER = "zh_female_shuangkuaisisi_uranus_bigtts"

        private const val MESSAGE_TYPE_FULL_CLIENT_REQUEST = 0x1
        private const val MESSAGE_TYPE_AUDIO_ONLY_REQUEST = 0x2
        private const val MESSAGE_TYPE_FULL_SERVER_RESPONSE = 0x9
        private const val MESSAGE_TYPE_AUDIO_ONLY_RESPONSE = 0xB
        private const val MESSAGE_TYPE_ERROR = 0xF

        private const val FLAG_WITH_EVENT = 0x4

        private const val SERIALIZATION_RAW = 0x0
        private const val SERIALIZATION_JSON = 0x1

        private const val COMPRESSION_NONE = 0x0
        private const val COMPRESSION_GZIP = 0x1

        private const val EVENT_START_CONNECTION = 1
        private const val EVENT_FINISH_CONNECTION = 2

        private const val EVENT_CONNECTION_STARTED = 50
        private const val EVENT_CONNECTION_FAILED = 51
        private const val EVENT_CONNECTION_FINISHED = 52

        private const val EVENT_START_SESSION = 100
        private const val EVENT_CANCEL_SESSION = 101
        private const val EVENT_FINISH_SESSION = 102

        private const val EVENT_SESSION_STARTED = 150
        private const val EVENT_SESSION_CANCELED = 151
        private const val EVENT_SESSION_FINISHED = 152
        private const val EVENT_SESSION_FAILED = 153

        private const val EVENT_TASK_REQUEST = 200

        private const val EVENT_TTS_SENTENCE_START = 350
        private const val EVENT_TTS_SENTENCE_END = 351
        private const val EVENT_TTS_RESPONSE = 352

        private const val PCM_BYTES_PER_FRAME = 2L
    }

    override val provider: SpeechProvider = SpeechProvider.VOLCANO

    private val sampleRate = if (config.sampleRate > 0) config.sampleRate else 16000

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private var onAudioDataCallback: ((ByteArray) -> Unit)? = null
    private var onPlaybackStateCallback: ((TtsPlaybackState) -> Unit)? = null

    private var currentWebSocket: WebSocket? = null
    private var currentAudioTrack: AudioTrack? = null
    private var isPlaying = false
    @Volatile
    private var singleFramesWritten: Long = 0L

    private var streamingWebSocket: WebSocket? = null
    private var streamingAudioTrack: AudioTrack? = null
    private var streamingState: StreamingState = StreamingState.IDLE
    private var streamingSessionId: String = ""
    @Volatile
    private var streamingFramesWritten: Long = 0L

    private enum class StreamingState {
        IDLE,
        CONNECTING,
        READY,
        FINISHING,
        COMPLETED,
        ERROR
    }

    private data class ParsedFrame(
        val messageType: Int,
        val serialization: Int,
        val compression: Int,
        val event: Int?,
        val id: String?,
        val payload: ByteArray,
        val errorCode: Int?
    )

    override fun isCurrentlyPlaying(): Boolean {
        val singlePlaying = hasPendingAudio(currentAudioTrack, singleFramesWritten)
        val streamPlaying = hasPendingAudio(streamingAudioTrack, streamingFramesWritten)
        return singlePlaying || streamPlaying
    }

    override fun setOnAudioDataCallback(callback: ((ByteArray) -> Unit)?) {
        onAudioDataCallback = callback
    }

    override fun setOnPlaybackStateCallback(callback: ((TtsPlaybackState) -> Unit)?) {
        onPlaybackStateCallback = callback
    }

    override suspend fun synthesizeAndPlay(text: String, context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.success(Unit)

        cancelPlayback()

        val completion = CompletableDeferred<Result<Unit>>()
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        val audioTrack = createAudioTrack(context)
        currentAudioTrack = audioTrack
        singleFramesWritten = 0L

        audioTrack.play()
        isPlaying = true
        onPlaybackStateCallback?.invoke(TtsPlaybackState.Playing)

        val ws = createWebSocket(
            onFrame = { frame ->
                handleSingleFrame(
                    ws = currentWebSocket,
                    frame = frame,
                    sessionId = sessionId,
                    text = text,
                    completion = completion
                )
            },
            onFailure = { message ->
                if (!completion.isCompleted) {
                    completion.complete(Result.failure(IllegalStateException(message)))
                }
            },
            onOpen = { socket ->
                currentWebSocket = socket
                sendStartConnection(socket)
            },
            onClosed = {
                if (!completion.isCompleted && isPlaying) {
                    completion.complete(Result.failure(IllegalStateException("连接提前关闭")))
                }
            }
        )

        currentWebSocket = ws
        val result = withContext(Dispatchers.IO) {
            completion.await()
        }

        // 给 AudioTrack 一点时间播放尾部缓冲
        delay(80)
        cleanupSingle()

        if (result.isSuccess) {
            onPlaybackStateCallback?.invoke(TtsPlaybackState.Completed)
        } else {
            onPlaybackStateCallback?.invoke(
                TtsPlaybackState.Error(result.exceptionOrNull()?.message ?: "语音合成失败")
            )
        }

        result
    }

    override suspend fun startStreamingSession(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        stopStreamingSessionInternal()

        val readyDeferred = CompletableDeferred<Result<Unit>>()
        streamingSessionId = UUID.randomUUID().toString().replace("-", "")
        streamingState = StreamingState.CONNECTING

        val track = createAudioTrack(context)
        streamingAudioTrack = track
        streamingFramesWritten = 0L
        track.play()

        val ws = createWebSocket(
            onFrame = { frame ->
                handleStreamingFrame(
                    ws = streamingWebSocket,
                    frame = frame,
                    readyDeferred = readyDeferred
                )
            },
            onFailure = { message ->
                streamingState = StreamingState.ERROR
                if (!readyDeferred.isCompleted) {
                    readyDeferred.complete(Result.failure(IllegalStateException(message)))
                }
            },
            onOpen = { socket ->
                streamingWebSocket = socket
                sendStartConnection(socket)
            },
            onClosed = {
                if (!readyDeferred.isCompleted && streamingState != StreamingState.READY) {
                    readyDeferred.complete(Result.failure(IllegalStateException("流式连接提前关闭")))
                }
            }
        )

        streamingWebSocket = ws

        val result = readyDeferred.await()
        if (result.isFailure) {
            stopStreamingSessionInternal()
        }
        result
    }

    override fun sendStreamingChunk(chunk: String): Result<Unit> {
        if (chunk.isBlank()) return Result.success(Unit)
        if (streamingState != StreamingState.READY) {
            return Result.failure(IllegalStateException("流式会话未就绪"))
        }

        val ws = streamingWebSocket ?: return Result.failure(IllegalStateException("流式连接不存在"))
        return try {
            sendTaskRequest(ws, streamingSessionId, chunk)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override fun finishStreamingSession(): Result<Unit> {
        if (streamingState != StreamingState.READY && streamingState != StreamingState.FINISHING) {
            return Result.failure(IllegalStateException("流式会话未就绪"))
        }

        val ws = streamingWebSocket ?: return Result.failure(IllegalStateException("流式连接不存在"))
        return try {
            sendFinishSession(ws, streamingSessionId)
            streamingState = StreamingState.FINISHING
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override fun cancelPlayback() {
        cleanupSingle()
        stopStreamingSessionInternal()
    }

    override fun release() {
        cancelPlayback()
    }

    private fun handleSingleFrame(
        ws: WebSocket?,
        frame: ParsedFrame,
        sessionId: String,
        text: String,
        completion: CompletableDeferred<Result<Unit>>
    ) {
        if (frame.messageType == MESSAGE_TYPE_ERROR) {
            val msg = decodePayloadAsText(frame.payload)
            val message = "TTS 协议错误(${frame.errorCode ?: -1}): $msg"
            Log.e(TAG, message)
            if (!completion.isCompleted) completion.complete(Result.failure(IllegalStateException(message)))
            return
        }

        when (frame.messageType) {
            MESSAGE_TYPE_AUDIO_ONLY_RESPONSE -> {
                if (frame.event == EVENT_TTS_RESPONSE || frame.event == null) {
                    writeAudio(currentAudioTrack, frame.payload, isStreamingAudio = false)
                }
            }

            MESSAGE_TYPE_FULL_SERVER_RESPONSE -> {
                when (frame.event) {
                    EVENT_CONNECTION_STARTED -> {
                        ws?.let { sendStartSession(it, sessionId) }
                    }

                    EVENT_CONNECTION_FAILED -> {
                        val message = parseStatusMessage(frame.payload, "建连失败")
                        if (!completion.isCompleted) completion.complete(Result.failure(IllegalStateException(message)))
                    }

                    EVENT_SESSION_STARTED -> {
                        ws?.let {
                            sendTaskRequest(it, sessionId, text)
                            sendFinishSession(it, sessionId)
                        }
                    }

                    EVENT_TTS_RESPONSE -> {
                        if (frame.serialization == SERIALIZATION_RAW) {
                            writeAudio(currentAudioTrack, frame.payload, isStreamingAudio = false)
                        }
                    }

                    EVENT_SESSION_FINISHED -> {
                        val status = parseStatusCode(frame.payload)
                        if (status == 20000000 || status == 0) {
                            if (!completion.isCompleted) completion.complete(Result.success(Unit))
                        } else {
                            val message = parseStatusMessage(frame.payload, "会话结束异常")
                            if (!completion.isCompleted) completion.complete(Result.failure(IllegalStateException(message)))
                        }
                        ws?.let { sendFinishConnection(it) }
                    }

                    EVENT_SESSION_FAILED,
                    EVENT_SESSION_CANCELED -> {
                        val message = parseStatusMessage(frame.payload, "会话失败")
                        if (!completion.isCompleted) completion.complete(Result.failure(IllegalStateException(message)))
                        ws?.let { sendFinishConnection(it) }
                    }

                    EVENT_CONNECTION_FINISHED -> {
                        // 正常结束连接，无需额外处理
                    }
                }
            }
        }
    }

    private fun handleStreamingFrame(
        ws: WebSocket?,
        frame: ParsedFrame,
        readyDeferred: CompletableDeferred<Result<Unit>>
    ) {
        if (frame.messageType == MESSAGE_TYPE_ERROR) {
            val msg = decodePayloadAsText(frame.payload)
            val message = "TTS 协议错误(${frame.errorCode ?: -1}): $msg"
            Log.e(TAG, message)
            streamingState = StreamingState.ERROR
            if (!readyDeferred.isCompleted) {
                readyDeferred.complete(Result.failure(IllegalStateException(message)))
            }
            onPlaybackStateCallback?.invoke(TtsPlaybackState.Error(message))
            return
        }

        when (frame.messageType) {
            MESSAGE_TYPE_AUDIO_ONLY_RESPONSE -> {
                if (frame.event == EVENT_TTS_RESPONSE || frame.event == null) {
                    writeAudio(streamingAudioTrack, frame.payload, isStreamingAudio = true)
                }
            }

            MESSAGE_TYPE_FULL_SERVER_RESPONSE -> {
                when (frame.event) {
                    EVENT_CONNECTION_STARTED -> {
                        ws?.let { sendStartSession(it, streamingSessionId) }
                    }

                    EVENT_CONNECTION_FAILED -> {
                        val message = parseStatusMessage(frame.payload, "流式建连失败")
                        streamingState = StreamingState.ERROR
                        if (!readyDeferred.isCompleted) {
                            readyDeferred.complete(Result.failure(IllegalStateException(message)))
                        }
                        onPlaybackStateCallback?.invoke(TtsPlaybackState.Error(message))
                    }

                    EVENT_SESSION_STARTED -> {
                        streamingState = StreamingState.READY
                        if (!readyDeferred.isCompleted) {
                            readyDeferred.complete(Result.success(Unit))
                        }
                    }

                    EVENT_TTS_RESPONSE -> {
                        if (frame.serialization == SERIALIZATION_RAW) {
                            writeAudio(streamingAudioTrack, frame.payload, isStreamingAudio = true)
                        }
                    }

                    EVENT_SESSION_FINISHED -> {
                        streamingState = StreamingState.COMPLETED
                        onPlaybackStateCallback?.invoke(TtsPlaybackState.Completed)
                        ws?.let { sendFinishConnection(it) }
                    }

                    EVENT_SESSION_FAILED,
                    EVENT_SESSION_CANCELED -> {
                        val message = parseStatusMessage(frame.payload, "流式会话失败")
                        streamingState = StreamingState.ERROR
                        if (!readyDeferred.isCompleted) {
                            readyDeferred.complete(Result.failure(IllegalStateException(message)))
                        }
                        onPlaybackStateCallback?.invoke(TtsPlaybackState.Error(message))
                        ws?.let { sendFinishConnection(it) }
                    }

                    EVENT_CONNECTION_FINISHED -> {
                        // 连接已结束
                    }
                }
            }
        }
    }

    private fun createWebSocket(
        onFrame: (ParsedFrame) -> Unit,
        onFailure: (String) -> Unit,
        onOpen: (WebSocket) -> Unit,
        onClosed: () -> Unit
    ): WebSocket {
        val resourceId = config.resourceId.ifBlank {
            if (config.model.startsWith("seed-tts-", ignoreCase = true)) {
                config.model
            } else {
                DEFAULT_RESOURCE_ID
            }
        }
        val request = Request.Builder()
            .url(WS_URL)
            .addHeader("X-Api-App-Key", config.appId)
            .addHeader("X-Api-Access-Key", config.apiKey)
            .addHeader("X-Api-Resource-Id", resourceId)
            .addHeader("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                onOpen(ws)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                try {
                    val parsed = parseBinaryFrame(bytes.toByteArray())
                    onFrame(parsed)
                } catch (t: Throwable) {
                    Log.e(TAG, "parse frame failed", t)
                    onFailure("解析服务端消息失败: ${t.message}")
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                onFailure("服务端文本错误: ${text.take(300)}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                onClosed()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onFailure("连接失败: ${t.message}")
            }
        }

        return client.newWebSocket(request, listener)
    }

    private fun sendStartConnection(ws: WebSocket) {
        ws.send(buildFullRequestFrame(EVENT_START_CONNECTION, null, JSONObject().toString()).toByteString())
    }

    private fun sendFinishConnection(ws: WebSocket) {
        ws.send(buildFullRequestFrame(EVENT_FINISH_CONNECTION, null, JSONObject().toString()).toByteString())
    }

    private fun sendStartSession(ws: WebSocket, sessionId: String) {
        val speaker = normalizeSpeaker(config.voice)
        val reqParams = JSONObject().apply {
            put("speaker", speaker)
            put("audio_params", JSONObject().apply {
                put("format", "pcm")
                put("sample_rate", sampleRate)
                put("speech_rate", mapSpeechRate(config.speed))
                put("loudness_rate", mapLoudnessRate(config.volume))
            })
        }

        val payload = JSONObject().apply {
            put("user", JSONObject().apply {
                put("uid", "beanbun-android")
            })
            put("req_params", reqParams)
        }

        ws.send(buildFullRequestFrame(EVENT_START_SESSION, sessionId, payload.toString()).toByteString())
    }

    private fun sendFinishSession(ws: WebSocket, sessionId: String) {
        ws.send(buildFullRequestFrame(EVENT_FINISH_SESSION, sessionId, JSONObject().toString()).toByteString())
    }

    private fun sendCancelSession(ws: WebSocket, sessionId: String) {
        ws.send(buildFullRequestFrame(EVENT_CANCEL_SESSION, sessionId, JSONObject().toString()).toByteString())
    }

    private fun sendTaskRequest(ws: WebSocket, sessionId: String, text: String) {
        val payload = JSONObject().apply {
            put("req_params", JSONObject().apply {
                put("text", text)
            })
        }
        ws.send(buildFullRequestFrame(EVENT_TASK_REQUEST, sessionId, payload.toString()).toByteString())
    }

    private fun buildFullRequestFrame(event: Int, sessionId: String?, payloadJson: String): ByteArray {
        val payload = payloadJson.toByteArray(StandardCharsets.UTF_8)
        val sessionBytes = sessionId?.toByteArray(StandardCharsets.UTF_8)

        val header = byteArrayOf(
            ((1 shl 4) or 1).toByte(),
            ((MESSAGE_TYPE_FULL_CLIENT_REQUEST shl 4) or FLAG_WITH_EVENT).toByte(),
            ((SERIALIZATION_JSON shl 4) or COMPRESSION_NONE).toByte(),
            0x00
        )

        val total = header.size + 4 + (if (sessionBytes != null) 4 + sessionBytes.size else 0) + 4 + payload.size
        return ByteBuffer.allocate(total)
            .order(ByteOrder.BIG_ENDIAN)
            .put(header)
            .putInt(event)
            .apply {
                if (sessionBytes != null) {
                    putInt(sessionBytes.size)
                    put(sessionBytes)
                }
            }
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    private fun parseBinaryFrame(frame: ByteArray): ParsedFrame {
        require(frame.size >= 8) { "frame too short" }

        val header0 = frame[0].toInt() and 0xFF
        val version = (header0 ushr 4) and 0x0F
        val headerSize = (header0 and 0x0F) * 4
        require(version == 1) { "unsupported protocol version=$version" }

        val b1 = frame[1].toInt() and 0xFF
        val messageType = (b1 ushr 4) and 0x0F
        val flags = b1 and 0x0F

        val b2 = frame[2].toInt() and 0xFF
        val serialization = (b2 ushr 4) and 0x0F
        val compression = b2 and 0x0F

        var offset = headerSize

        if (messageType == MESSAGE_TYPE_ERROR) {
            val errorCode = readInt32BE(frame, offset)
            offset += 4
            val payloadSize = readInt32BE(frame, offset)
            offset += 4
            require(payloadSize >= 0 && frame.size >= offset + payloadSize) { "invalid error payload size" }
            val payload = frame.copyOfRange(offset, offset + payloadSize)
            return ParsedFrame(
                messageType = messageType,
                serialization = serialization,
                compression = compression,
                event = null,
                id = null,
                payload = payload,
                errorCode = errorCode
            )
        }

        var event: Int? = null
        if (flags == FLAG_WITH_EVENT) {
            event = readInt32BE(frame, offset)
            offset += 4
        }

        var id: String? = null
        if (event != null && shouldFrameContainId(event)) {
            val idLen = readInt32BE(frame, offset)
            offset += 4
            if (idLen > 0) {
                require(frame.size >= offset + idLen) { "invalid id length=$idLen" }
                id = frame.copyOfRange(offset, offset + idLen).toString(StandardCharsets.UTF_8)
                offset += idLen
            }
        }

        val payloadSize = readInt32BE(frame, offset)
        offset += 4
        require(payloadSize >= 0 && frame.size >= offset + payloadSize) { "invalid payload size=$payloadSize" }

        val rawPayload = frame.copyOfRange(offset, offset + payloadSize)
        val payload = when (compression) {
            COMPRESSION_GZIP -> ungzip(rawPayload)
            else -> rawPayload
        }

        return ParsedFrame(
            messageType = messageType,
            serialization = serialization,
            compression = compression,
            event = event,
            id = id,
            payload = payload,
            errorCode = null
        )
    }

    private fun shouldFrameContainId(event: Int): Boolean {
        return when (event) {
            EVENT_CONNECTION_STARTED,
            EVENT_CONNECTION_FAILED,
            EVENT_CONNECTION_FINISHED,
            EVENT_START_SESSION,
            EVENT_CANCEL_SESSION,
            EVENT_FINISH_SESSION,
            EVENT_SESSION_STARTED,
            EVENT_SESSION_CANCELED,
            EVENT_SESSION_FINISHED,
            EVENT_SESSION_FAILED,
            EVENT_TASK_REQUEST,
            EVENT_TTS_SENTENCE_START,
            EVENT_TTS_SENTENCE_END,
            EVENT_TTS_RESPONSE -> true

            else -> false
        }
    }

    private fun writeAudio(audioTrack: AudioTrack?, audioData: ByteArray, isStreamingAudio: Boolean) {
        if (audioData.isEmpty()) return
        val track = audioTrack ?: return
        if (track.state != AudioTrack.STATE_INITIALIZED) return

        try {
            val bytesWritten = track.write(audioData, 0, audioData.size)
            if (bytesWritten > 0) {
                if (isStreamingAudio) {
                    streamingFramesWritten += bytesWritten.toLong() / PCM_BYTES_PER_FRAME
                } else {
                    singleFramesWritten += bytesWritten.toLong() / PCM_BYTES_PER_FRAME
                }
            }
            onAudioDataCallback?.invoke(audioData)
        } catch (t: Throwable) {
            Log.e(TAG, "write audio failed", t)
        }
    }

    private fun cleanupSingle() {
        isPlaying = false

        try {
            currentWebSocket?.close(1000, "normal")
        } catch (t: Throwable) {
            Log.w(TAG, "close single websocket failed", t)
        }
        currentWebSocket = null

        try {
            currentAudioTrack?.let {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.stop()
                    }
                    it.release()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "release single audio track failed", t)
        }
        currentAudioTrack = null
        singleFramesWritten = 0L
    }

    private fun stopStreamingSessionInternal() {
        if (streamingState == StreamingState.IDLE) return

        try {
            val ws = streamingWebSocket
            if (ws != null && streamingSessionId.isNotBlank() &&
                (streamingState == StreamingState.READY || streamingState == StreamingState.FINISHING)
            ) {
                sendCancelSession(ws, streamingSessionId)
            }
            ws?.let { sendFinishConnection(it) }
            ws?.close(1000, "normal")
        } catch (t: Throwable) {
            Log.w(TAG, "stop streaming websocket failed", t)
        }
        streamingWebSocket = null

        try {
            streamingAudioTrack?.let {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.stop()
                    }
                    it.release()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "release streaming audio track failed", t)
        }
        streamingAudioTrack = null
        streamingFramesWritten = 0L

        streamingSessionId = ""
        streamingState = StreamingState.IDLE
    }

    private fun hasPendingAudio(track: AudioTrack?, totalWrittenFrames: Long): Boolean {
        val audioTrack = track ?: return false
        if (totalWrittenFrames <= 0L) return false
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) return false
        if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) return false

        val playedFrames = audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return totalWrittenFrames - playedFrames > 0L
    }

    private fun createAudioTrack(context: Context): AudioTrack {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.requestAudioFocus(
            null,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )

        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes((minBuffer * 4).coerceAtLeast(minBuffer))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun readInt32BE(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun decodePayloadAsText(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        return try {
            payload.toString(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun parseStatusCode(payload: ByteArray): Int {
        return try {
            val json = JSONObject(decodePayloadAsText(payload))
            when {
                json.has("status_code") -> json.optInt("status_code")
                json.has("code") -> json.optInt("code")
                else -> 20000000
            }
        } catch (_: Throwable) {
            20000000
        }
    }

    private fun parseStatusMessage(payload: ByteArray, defaultMessage: String): String {
        return try {
            val json = JSONObject(decodePayloadAsText(payload))
            val code = when {
                json.has("status_code") -> json.optInt("status_code")
                json.has("code") -> json.optInt("code")
                else -> 0
            }
            val msg = json.optString("message", defaultMessage)
            if (code != 0) "$msg($code)" else msg
        } catch (_: Throwable) {
            defaultMessage
        }
    }

    private fun normalizeSpeaker(input: String): String {
        if (input.isBlank()) return DEFAULT_SPEAKER
        if (input.startsWith("zh_") || input.startsWith("en_") || input.startsWith("S_") || input.startsWith("icl_") || input.startsWith("saturn_")) {
            return input
        }
        return DEFAULT_SPEAKER
    }

    private fun mapSpeechRate(speed: Float): Int {
        val normalized = ((speed - 1.0f) * 100f).toInt()
        return normalized.coerceIn(-50, 100)
    }

    private fun mapLoudnessRate(volume: Int): Int {
        val normalized = (volume.coerceIn(0, 100) - 50) * 2
        return normalized.coerceIn(-50, 100)
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
 * 火山引擎 TTS 引擎工厂
 */
class VolcanoTtsEngineFactory : TtsEngineFactory {
    override val provider: SpeechProvider = SpeechProvider.VOLCANO

    override fun create(config: TtsConfig): TtsEngine {
        return VolcanoTtsEngine(config)
    }
}
