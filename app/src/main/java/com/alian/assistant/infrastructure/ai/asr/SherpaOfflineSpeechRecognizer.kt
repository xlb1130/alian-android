package com.alian.assistant.infrastructure.ai.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.ncnn.RecognizerConfig
import com.k2fsa.sherpa.ncnn.SherpaNcnn
import com.k2fsa.sherpa.ncnn.getDecoderConfig
import com.k2fsa.sherpa.ncnn.getFeatureExtractorConfig
import com.k2fsa.sherpa.ncnn.getModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地离线 ASR 识别器（Sherpa-ncnn）
 * 仅用于按住说话（push-to-talk）模式。
 */
class SherpaOfflineSpeechRecognizer(
    private val context: Context,
    private val enableEndpoint: Boolean = false,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onStopped() {}
        fun onAmplitude(amplitude: Float) {}
    }

    private val tag = "SherpaOfflineAsr"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var recognizer: SherpaNcnn? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var listener: Listener? = null

    private val isInitialized = AtomicBoolean(false)
    private val isListening = AtomicBoolean(false)
    private val finalDelivered = AtomicBoolean(false)
    private val useExternalPcm = AtomicBoolean(false)
    private var lastPartialText: String = ""
    private val externalPcmQueue = LinkedBlockingQueue<ShortArray>(200)

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    fun isReady(): Boolean = isInitialized.get() && SherpaNcnn.isNativeLibraryReady()

    fun isCurrentlyListening(): Boolean = isListening.get()

    suspend fun initializeIfNeeded(): Boolean {
        if (isInitialized.get()) return true
        if (!SherpaNcnn.isNativeLibraryReady()) {
            Log.e(tag, "Sherpa native unavailable: ${SherpaNcnn.nativeLibraryErrorMessage()}")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val featConfig = getFeatureExtractorConfig(
                    sampleRate = sampleRate.toFloat(),
                    featureDim = 80
                )
                val modelConfig = getModelConfig(type = 5, useGPU = false)
                if (modelConfig == null) {
                    Log.e(tag, "Sherpa model config not found")
                    return@withContext false
                }
                val decoderConfig = getDecoderConfig(method = "greedy_search", numActivePaths = 4)
                val config = RecognizerConfig(
                    featConfig = featConfig,
                    modelConfig = modelConfig,
                    decoderConfig = decoderConfig,
                    enableEndpoint = enableEndpoint,
                    rule1MinTrailingSilence = 2.4f,
                    rule2MinTrailingSilence = 1.2f,
                    rule3MinUtteranceLength = 20.0f,
                    hotwordsFile = "",
                    hotwordsScore = 1.5f
                )
                recognizer = SherpaNcnn(config = config, assetManager = context.assets)
                isInitialized.set(true)
                true
            } catch (t: Throwable) {
                Log.e(tag, "Failed to initialize Sherpa recognizer", t)
                false
            }
        }
    }

    fun startListening(listener: Listener, externalPcmMode: Boolean = false): Boolean {
        if (isListening.get()) return true
        this.listener = listener

        if (externalPcmMode) {
            if (!isReady()) {
                deliverError("离线语音模型未就绪")
                return false
            }
            recognizer?.reset(false)
            finalDelivered.set(false)
            lastPartialText = ""
            useExternalPcm.set(true)
            externalPcmQueue.clear()
            isListening.set(true)

            recordingJob = scope.launch(Dispatchers.IO) {
                try {
                    consumeExternalPcmLoop()
                } finally {
                    withContext(callbackDispatcher) {
                        this@SherpaOfflineSpeechRecognizer.listener?.onStopped()
                    }
                }
            }
            return true
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            deliverError("没有录音权限")
            return false
        }
        if (!isReady()) {
            deliverError("离线语音模型未就绪")
            return false
        }

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            deliverError("初始化录音失败: $minBufferSize")
            return false
        }

        val localRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 2
            )
        } catch (t: Throwable) {
            deliverError("创建录音器失败: ${t.message}")
            return false
        }

        if (localRecord.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { localRecord.release() }
            deliverError("录音器初始化失败")
            return false
        }

        val startOk = runCatching {
            localRecord.startRecording()
            true
        }.getOrElse {
            runCatching { localRecord.release() }
            deliverError("启动录音失败: ${it.message}")
            false
        }
        if (!startOk) return false

        recognizer?.reset(false)
        audioRecord = localRecord
        finalDelivered.set(false)
        lastPartialText = ""
        useExternalPcm.set(false)
        externalPcmQueue.clear()
        isListening.set(true)

        recordingJob = scope.launch(Dispatchers.IO) {
            val shortBuffer = ShortArray(minBufferSize)
            try {
                while (isActive && isListening.get()) {
                    val read = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (read < 0) {
                        withContext(callbackDispatcher) {
                            deliverError("读取录音数据失败: $read")
                        }
                        isListening.set(false)
                        break
                    }
                    if (read <= 0) continue

                    emitAmplitude(shortBuffer, read)

                    val samples = FloatArray(read) { index -> shortBuffer[index] / 32768.0f }
                    val localRecognizer = recognizer ?: break
                    localRecognizer.acceptSamples(samples)
                    while (localRecognizer.isReady()) {
                        localRecognizer.decode()
                    }

                    val text = localRecognizer.text.trim()
                    if (text.isNotBlank() && text != lastPartialText) {
                        lastPartialText = text
                        withContext(callbackDispatcher) {
                            this@SherpaOfflineSpeechRecognizer.listener?.onPartial(text)
                        }
                    }

                    if (enableEndpoint && localRecognizer.isEndpoint()) {
                        if (finalDelivered.compareAndSet(false, true)) {
                            withContext(callbackDispatcher) {
                                this@SherpaOfflineSpeechRecognizer.listener?.onFinal(text)
                            }
                        }
                        isListening.set(false)
                        break
                    }
                }
            } finally {
                releaseAudioRecord()
                withContext(callbackDispatcher) {
                    this@SherpaOfflineSpeechRecognizer.listener?.onStopped()
                }
            }
        }
        return true
    }

    fun stopListening() {
        if (!isListening.get()) return
        isListening.set(false)
        finalizeAndEmit()
        recordingJob?.cancel()
        recordingJob = null
        externalPcmQueue.clear()
    }

    fun cancelListening() {
        if (!isListening.get()) return
        isListening.set(false)
        finalDelivered.set(true)
        recordingJob?.cancel()
        recordingJob = null
        releaseAudioRecord()
        externalPcmQueue.clear()
    }

    fun destroy() {
        cancelListening()
        runCatching { scope.cancel() }
        recognizer = null
        isInitialized.set(false)
    }

    /**
     * 外部 PCM 输入（16kHz / mono / PCM16 LE）
     * 仅在 externalPcmMode=true 且正在监听时生效。
     */
    fun addAudioData(audioData: ByteArray) {
        if (!isListening.get() || !useExternalPcm.get()) {
            return
        }
        if (audioData.isEmpty()) return
        val shortData = byteArrayToShortArray(audioData)
        if (shortData.isEmpty()) return

        // 队列满时丢弃最旧帧，优先保留最新语音帧，降低延迟
        if (!externalPcmQueue.offer(shortData)) {
            externalPcmQueue.poll()
            externalPcmQueue.offer(shortData)
        }
    }

    private fun finalizeAndEmit() {
        val localRecognizer = recognizer ?: return
        try {
            localRecognizer.inputFinished()
            while (localRecognizer.isReady()) {
                localRecognizer.decode()
            }
            val finalText = localRecognizer.text.trim()
            if (finalText.isNotBlank() && finalDelivered.compareAndSet(false, true)) {
                scope.launch(callbackDispatcher) {
                    listener?.onFinal(finalText)
                }
            }
            localRecognizer.reset(false)
        } catch (t: Throwable) {
            deliverError("完成离线识别失败: ${t.message}")
        }
    }

    private suspend fun consumeExternalPcmLoop() {
        val localRecognizer = recognizer ?: return
        while (kotlin.coroutines.coroutineContext.isActive && isListening.get()) {
            val shortBuffer = externalPcmQueue.poll(120, TimeUnit.MILLISECONDS) ?: continue

            emitAmplitude(shortBuffer, shortBuffer.size)

            val samples = FloatArray(shortBuffer.size) { index ->
                shortBuffer[index] / 32768.0f
            }
            localRecognizer.acceptSamples(samples)
            while (localRecognizer.isReady()) {
                localRecognizer.decode()
            }

            val text = localRecognizer.text.trim()
            if (text.isNotBlank() && text != lastPartialText) {
                lastPartialText = text
                withContext(callbackDispatcher) {
                    this@SherpaOfflineSpeechRecognizer.listener?.onPartial(text)
                }
            }

            if (enableEndpoint && localRecognizer.isEndpoint()) {
                if (finalDelivered.compareAndSet(false, true)) {
                    withContext(callbackDispatcher) {
                        this@SherpaOfflineSpeechRecognizer.listener?.onFinal(text)
                    }
                }
                isListening.set(false)
                break
            }
        }
    }

    private fun emitAmplitude(audioBuffer: ShortArray, length: Int) {
        var sum = 0.0
        for (index in 0 until length) {
            val value = audioBuffer[index].toDouble()
            sum += value * value
        }
        val rms = Math.sqrt(sum / length.coerceAtLeast(1))
        val amplitude = (rms / 3000.0).toFloat().coerceIn(0f, 1f)
        scope.launch(callbackDispatcher) {
            listener?.onAmplitude(amplitude)
        }
    }

    private fun releaseAudioRecord() {
        val current = audioRecord ?: return
        audioRecord = null
        runCatching {
            if (current.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                current.stop()
            }
        }
        runCatching { current.release() }
    }

    private fun deliverError(message: String) {
        Log.e(tag, message)
        scope.launch(callbackDispatcher) {
            listener?.onError(message)
        }
    }

    private fun byteArrayToShortArray(data: ByteArray): ShortArray {
        if (data.size < 2) return ShortArray(0)
        val length = data.size / 2
        val out = ShortArray(length)
        var outIdx = 0
        var i = 0
        while (i + 1 < data.size && outIdx < length) {
            val low = data[i].toInt() and 0xFF
            val high = data[i + 1].toInt() and 0xFF
            out[outIdx] = ((high shl 8) or low).toShort()
            outIdx++
            i += 2
        }
        return out
    }
}
