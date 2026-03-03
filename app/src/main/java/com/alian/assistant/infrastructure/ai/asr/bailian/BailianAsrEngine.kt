package com.alian.assistant.infrastructure.ai.asr.bailian

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.alibaba.dashscope.audio.asr.recognition.Recognition
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult
import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam
import com.alibaba.dashscope.common.ResultCallback
import com.alibaba.dashscope.utils.Constants
import com.alian.assistant.data.model.SpeechProvider
import com.alian.assistant.infrastructure.ai.asr.AsrConfig
import com.alian.assistant.infrastructure.ai.asr.AsrEngine
import com.alian.assistant.infrastructure.ai.asr.AsrEngineFactory
import com.alian.assistant.infrastructure.ai.asr.AsrListener
import com.alian.assistant.infrastructure.ai.asr.AudioCaptureManager
import com.alian.assistant.infrastructure.ai.asr.ExternalPcmConsumer
import com.alian.assistant.infrastructure.ai.asr.StreamingAsrEngine
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 百炼（阿里云 DashScope）ASR 引擎
 * 
 * 支持两种模型：
 * - fun-asr-realtime-2025-09-15: Fun-ASR 模型
 * - qwen3-asr-flash-realtime: Qwen3 ASR 模型
 */
class BailianAsrEngine(
    private val config: AsrConfig,
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val externalPcmMode: Boolean = false
) : AsrEngine, StreamingAsrEngine, ExternalPcmConsumer {

    companion object {
        private const val TAG = "BailianAsrEngine"
        private const val MODEL_QWEN3 = "qwen3-asr-flash-realtime"
        private const val MODEL_FUN_ASR = "fun-asr-realtime-2025-09-15"
        private const val WS_URL_CN = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
        private const val WS_URL_INFER_CN = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        private const val FINAL_RESULT_TIMEOUT_MS = 6000L
    }

    override val provider: SpeechProvider = SpeechProvider.BAILIAN

    private val running = AtomicBoolean(false)
    private var audioJob: Job? = null
    private var controlJob: Job? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var conversation: OmniRealtimeConversation? = null
    private var recognizer: Recognition? = null
    private var useFunAsrModel: Boolean = false

    // 用于识别结果
    private var currentTurnText: String = ""
    private var currentTurnStash: String = ""
    private var finalTranscript: String? = null
    private var finalResultDeferred: CompletableDeferred<String?>? = null
    private val finalDelivered = AtomicBoolean(false)

    override val isRunning: Boolean
        get() = running.get()

    private val prebuffer = ArrayDeque<ByteArray>()
    private val prebufferLock = Any()
    @Volatile
    private var convReady: Boolean = false

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
        if (config.apiKey.isBlank()) {
            listener.onError("API Key 为空")
            return
        }

        useFunAsrModel = config.model.startsWith("fun-asr", ignoreCase = true)

        running.set(true)
        currentTurnText = ""
        currentTurnStash = ""
        finalTranscript = null
        finalResultDeferred = null
        finalDelivered.set(false)

        controlJob?.cancel()
        controlJob = scope.launch(Dispatchers.IO) {
            try {
                if (useFunAsrModel) {
                    startFunAsrStreaming()
                    return@launch
                }

                val wsUrl = WS_URL_CN

                val param = OmniRealtimeParam.builder()
                    .model(MODEL_QWEN3)
                    .apikey(config.apiKey)
                    .url(wsUrl)
                    .build()

                val transcriptionParam = OmniRealtimeTranscriptionParam()
                transcriptionParam.setInputSampleRate(sampleRate)
                transcriptionParam.setInputAudioFormat("pcm")
                if (config.language.isNotBlank()) {
                    transcriptionParam.setLanguage(config.language)
                }

                val omniConfig = OmniRealtimeConfig.builder()
                    .modalities(listOf(OmniRealtimeModality.TEXT))
                    .enableTurnDetection(false)
                    .transcriptionConfig(transcriptionParam)
                    .build()

                val callback = object : OmniRealtimeCallback() {
                    override fun onOpen() {
                        Log.d(TAG, "WebSocket opened, updating session config")
                        try {
                            conversation?.updateSession(omniConfig)
                            convReady = true
                            flushPrebuffer()
                        } catch (t: Throwable) {
                            Log.e(TAG, "updateSession failed", t)
                        }
                    }

                    override fun onEvent(message: JsonObject) {
                        handleServerEvent(message)
                    }

                    override fun onClose(code: Int, reason: String) {
                        Log.d(TAG, "WebSocket closed: $code $reason")
                        if (running.get()) {
                            running.set(false)
                            try {
                                listener.onError("语音识别错误: $reason")
                            } catch (t: Throwable) {
                                Log.e(TAG, "notify error failed", t)
                            }
                        }
                    }
                }

                val conv = OmniRealtimeConversation(param, callback)
                conversation = conv
                convReady = false
                conv.connect()

                if (!externalPcmMode) {
                    startCaptureAndSend()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start DashScope streaming recognition", t)
                try {
                    listener.onError(t.message ?: "语音识别错误")
                } catch (notifyError: Throwable) {
                    Log.e(TAG, "notify error failed", notifyError)
                }
                running.set(false)
                safeClose()
            }
        }
    }

    private fun startFunAsrStreaming() {
        val wsUrl = WS_URL_INFER_CN
        try {
            Constants.baseWebsocketApiUrl = wsUrl
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set baseWebsocketApiUrl", t)
        }

        val builder = RecognitionParam.builder()
            .model(MODEL_FUN_ASR)
            .apiKey(config.apiKey)
            .format("pcm")
            .sampleRate(sampleRate)

        if (config.language.isNotBlank()) {
            try {
                builder.parameter("language_hints", arrayOf(config.language))
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to set language_hints", t)
            }
        }

        try {
            builder.parameter("semantic_punctuation_enabled", true)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set semantic_punctuation_enabled", t)
        }

        val param = builder.build()
        val rec = Recognition()
        recognizer = rec
        conversation = null

        convReady = false
        val callback = object : ResultCallback<RecognitionResult>() {
            override fun onEvent(result: RecognitionResult) {
                handleFunAsrEvent(result)
            }

            override fun onComplete() {
                handleFunAsrComplete()
            }

            override fun onError(e: Exception) {
                handleFunAsrError(e)
            }
        }

        rec.call(param, callback)

        convReady = true
        flushPrebuffer()
        if (!externalPcmMode) {
            startCaptureAndSend()
        }
    }

    private fun handleFunAsrEvent(result: RecognitionResult) {
        val sentenceText = result.getSentence()?.getText().orEmpty()
        if (sentenceText.isBlank()) return

        val isEnd = result.isSentenceEnd
        if (isEnd) {
            currentTurnText = appendSentence(currentTurnText, sentenceText)
            currentTurnStash = ""
        } else {
            currentTurnStash = sentenceText
        }

        if (!running.get()) return
        val preview = (currentTurnText + currentTurnStash).trim()
        if (preview.isNotEmpty()) {
            try {
                listener.onPartial(preview)
            } catch (t: Throwable) {
                Log.e(TAG, "notify partial failed", t)
            }
        }
    }

    private fun handleFunAsrComplete() {
        val finalText = (currentTurnText + currentTurnStash).trim()
        finalTranscript = finalText
        finalResultDeferred?.complete(finalText)

        if (finalDelivered.compareAndSet(false, true)) {
            try {
                listener.onFinal(finalText)
            } catch (t: Throwable) {
                Log.e(TAG, "notify final failed", t)
            }
        }
    }

    private fun handleFunAsrError(e: Exception) {
        val msg = e.message ?: "Recognition error"
        Log.e(TAG, "Fun-ASR streaming error: $msg", e)
        if (running.get()) {
            running.set(false)
            if (!finalDelivered.get()) {
                try {
                    listener.onError("Fun-ASR streaming error: $msg")
                } catch (t: Throwable) {
                    Log.e(TAG, "notify error failed", t)
                }
            }
        }
        finalResultDeferred?.complete(null)
        try {
            audioJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel audio job after failure failed", t)
        }
        audioJob = null
        safeClose()
    }

    private fun appendSentence(existing: String, sentence: String): String {
        val s = sentence.trim()
        if (s.isEmpty()) return existing
        val cur = existing.trim()
        if (cur.isEmpty()) return s
        val last = cur.last()
        val first = s.first()
        val needsSpace = last.isAsciiLetterOrDigit() && first.isAsciiLetterOrDigit()
        return if (needsSpace) "$cur $s" else cur + s
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean {
        return (this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9')
    }

    private fun handleServerEvent(message: JsonObject) {
        val eventType = message.get("type")?.asString ?: return

        when (eventType) {
            "session.created" -> {
                Log.d(TAG, "Session created")
            }
            "session.updated" -> {
                Log.d(TAG, "Session updated")
            }
            "input_audio_buffer.speech_started" -> {
                Log.d(TAG, "Speech started")
                try {
                    listener.onVadStateChanged(true)
                } catch (t: Throwable) {
                    Log.e(TAG, "notify vad state failed", t)
                }
            }
            "input_audio_buffer.speech_stopped" -> {
                Log.d(TAG, "Speech stopped")
                try {
                    listener.onVadStateChanged(false)
                } catch (t: Throwable) {
                    Log.e(TAG, "notify vad state failed", t)
                }
            }
            "input_audio_buffer.committed" -> {
                Log.d(TAG, "Audio committed")
            }
            "conversation.item.created" -> {
                Log.d(TAG, "Conversation item created")
            }
            "conversation.item.input_audio_transcription.text" -> {
                val text = message.get("text")?.asString ?: ""
                val stash = message.get("stash")?.asString ?: ""

                if (running.get()) {
                    currentTurnText = text
                    currentTurnStash = stash

                    val preview = currentTurnText + currentTurnStash
                    if (preview.isNotEmpty()) {
                        try {
                            listener.onPartial(preview)
                        } catch (t: Throwable) {
                            Log.e(TAG, "notify partial failed", t)
                        }
                    }
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = message.get("transcript")?.asString ?: ""
                Log.d(TAG, "Transcription completed: $transcript")

                finalTranscript = transcript
                finalResultDeferred?.complete(transcript)

                if (finalDelivered.compareAndSet(false, true)) {
                    try {
                        listener.onFinal(transcript)
                    } catch (t: Throwable) {
                        Log.e(TAG, "notify final failed", t)
                    }
                }
            }
            "conversation.item.input_audio_transcription.failed" -> {
                val error = message.getAsJsonObject("error")
                val errorMsg = error?.get("message")?.asString ?: "Transcription failed"
                Log.e(TAG, "Transcription failed: $errorMsg")
                running.set(false)
                if (!finalDelivered.get()) {
                    try {
                        listener.onError("Transcription failed: $errorMsg")
                    } catch (t: Throwable) {
                        Log.e(TAG, "notify error failed", t)
                    }
                }
                finalResultDeferred?.complete(null)
                try {
                    audioJob?.cancel()
                } catch (t: Throwable) {
                    Log.w(TAG, "cancel audio job after failure failed", t)
                }
                audioJob = null
                safeClose()
            }
            "error" -> {
                val error = message.getAsJsonObject("error")
                val errorMsg = error?.get("message")?.asString ?: "Unknown error"
                Log.e(TAG, "Server error: $errorMsg")
                if (running.get()) {
                    running.set(false)
                    try {
                        listener.onError("Server error: $errorMsg")
                    } catch (t: Throwable) {
                        Log.e(TAG, "notify error failed", t)
                    }
                }
            }
            else -> {
                Log.d(TAG, "Unknown event: $eventType")
            }
        }
    }

    private fun flushPrebuffer() {
        var flushed: Array<ByteArray>? = null
        synchronized(prebufferLock) {
            if (prebuffer.isNotEmpty()) {
                flushed = prebuffer.toTypedArray()
                prebuffer.clear()
            }
        }
        flushed?.forEach { b ->
            sendAudioFrame(b)
        }
    }

    private fun sendAudioFrame(audioChunk: ByteArray) {
        if (useFunAsrModel) {
            try {
                recognizer?.sendAudioFrame(ByteBuffer.wrap(audioChunk))
            } catch (t: Throwable) {
                Log.e(TAG, "sendAudioFrame failed", t)
            }
            return
        }
        try {
            val base64Audio = Base64.encodeToString(audioChunk, Base64.NO_WRAP)
            conversation?.appendAudio(base64Audio)
        } catch (t: Throwable) {
            Log.e(TAG, "appendAudio failed", t)
        }
    }

    // ========== ExternalPcmConsumer ==========
    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!running.get()) return
        if (sampleRate != 16000 || channels != 1) return
        try {
            listener.onAmplitude(calculateNormalizedAmplitude(pcm))
        } catch (t: Throwable) {
            Log.w(TAG, "notify amplitude failed", t)
        }

        if (!convReady) {
            synchronized(prebufferLock) { prebuffer.addLast(pcm.copyOf()) }
        } else {
            flushPrebuffer()
            sendAudioFrame(pcm)
        }
    }

    override fun commit() {
        if (!running.get()) return
        scope.launch(Dispatchers.IO) {
            try {
                if (useFunAsrModel) {
                    recognizer?.stop()
                } else {
                    conversation?.commit()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "commit() failed", t)
            }
        }
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)

        scope.launch(Dispatchers.IO) {
            val resultDeferred = CompletableDeferred<String?>()
            finalResultDeferred = resultDeferred
            try {
                try {
                    listener.onStopped()
                } catch (t: Throwable) {
                    Log.e(TAG, "notify stopped failed", t)
                }

                try {
                    audioJob?.cancel()
                    audioJob?.join()
                } catch (t: Throwable) {
                    Log.w(TAG, "cancel/join audio job failed", t)
                }
                audioJob = null

                if (useFunAsrModel) {
                    try {
                        Log.d(TAG, "Calling recognizer.stop() to trigger final recognition")
                        recognizer?.stop()
                    } catch (t: Throwable) {
                        Log.w(TAG, "recognizer.stop() failed", t)
                        val fallbackText = (currentTurnText + currentTurnStash).trim()
                        if (finalDelivered.compareAndSet(false, true)) {
                            try {
                                listener.onFinal(fallbackText)
                            } catch (notifyError: Throwable) {
                                Log.e(TAG, "notify final fallback failed", notifyError)
                            }
                        }
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(fallbackText)
                        }
                    }
                } else {
                    try {
                        Log.d(TAG, "Calling commit() to trigger final recognition")
                        conversation?.commit()
                    } catch (t: Throwable) {
                        Log.w(TAG, "commit() failed", t)
                        val fallbackText = (currentTurnText + currentTurnStash).trim()
                        if (finalDelivered.compareAndSet(false, true)) {
                            try {
                                listener.onFinal(fallbackText)
                            } catch (notifyError: Throwable) {
                                Log.e(TAG, "notify final fallback failed", notifyError)
                            }
                        }
                        if (!resultDeferred.isCompleted) {
                            resultDeferred.complete(fallbackText)
                        }
                    }
                }

                val awaited = withTimeoutOrNull(FINAL_RESULT_TIMEOUT_MS) { resultDeferred.await() }
                if (awaited == null && finalDelivered.compareAndSet(false, true)) {
                    val fallbackText = (finalTranscript ?: (currentTurnText + currentTurnStash)).trim()
                    try {
                        listener.onFinal(fallbackText)
                    } catch (notifyError: Throwable) {
                        Log.e(TAG, "notify final timeout fallback failed", notifyError)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "stop cleanup failed", t)
            } finally {
                if (!resultDeferred.isCompleted) {
                    resultDeferred.complete(finalTranscript)
                }
                finalResultDeferred = null
                safeClose()
            }
        }
    }

    override fun release() {
        stop()
        safeClose()
    }

    private fun startCaptureAndSend() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val chunkMillis = 100
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                chunkMillis = chunkMillis
            )

            if (!audioManager.hasPermission()) {
                Log.e(TAG, "Missing RECORD_AUDIO permission")
                listener.onError("Missing RECORD_AUDIO permission")
                running.set(false)
                return@launch
            }

            val vadAutoStopEnabled = true
            val vadSilenceWindowFrames = 30
            var vadSilenceFrames = 0

            try {
                audioManager.startCaptureWithVAD().collect { (audioChunk, vadResult) ->
                    if (!running.get()) return@collect

                    try {
                        val amplitude = calculateNormalizedAmplitude(audioChunk)
                        listener.onAmplitude(amplitude)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to calculate amplitude", t)
                    }

                    if (vadAutoStopEnabled) {
                        if (!vadResult.isSpeech) {
                            vadSilenceFrames++
                            if (vadSilenceFrames >= vadSilenceWindowFrames) {
                                Log.d(TAG, "Client VAD: silence detected, stopping recording")
                                try {
                                    listener.onStopped()
                                } catch (t: Throwable) {
                                    Log.e(TAG, "notify stopped failed", t)
                                }
                                stop()
                                return@collect
                            }
                        } else {
                            vadSilenceFrames = 0
                        }
                    }

                    if (!convReady) {
                        synchronized(prebufferLock) { prebuffer.addLast(audioChunk.copyOf()) }
                    } else {
                        flushPrebuffer()
                        sendAudioFrame(audioChunk)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    Log.d(TAG, "Audio streaming cancelled: ${t.message}")
                } else {
                    Log.e(TAG, "Audio streaming failed: ${t.message}", t)
                    listener.onError("Audio streaming failed: ${t.message}")
                }
            }
        }
    }

    private fun safeClose() {
        convReady = false
        try {
            conversation?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "conversation close failed", t)
        } finally {
            conversation = null
        }

        try {
            recognizer?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "recognizer stop failed", t)
        }
        try {
            recognizer?.getDuplexApi()?.close(1000, "bye")
        } catch (t: Throwable) {
            Log.w(TAG, "recognizer close failed", t)
        } finally {
            recognizer = null
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
 * 百炼 ASR 引擎工厂
 */
class BailianAsrEngineFactory(
    private val context: Context,
    private val scope: CoroutineScope
) : AsrEngineFactory {
    override val provider: SpeechProvider = SpeechProvider.BAILIAN

    override fun create(config: AsrConfig, listener: AsrListener): AsrEngine {
        return BailianAsrEngine(
            config = config,
            context = context,
            scope = scope,
            listener = listener
        )
    }
}
