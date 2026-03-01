package com.alian.assistant.infrastructure.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * AEC（回声消除）音频处理器
 * 用于在语音通话中消除 TTS 播放的回声，实现语音打断功能
 * 
 * 工作原理：
 * 1. 录制麦克风音频
 * 2. 获取 TTS 播放的音频（参考信号）
 * 3. 使用自适应滤波器消除回声
 * 4. 输出处理后的音频用于语音识别
 */
class AecAudioProcessor(
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    companion object {
        private const val TAG = "AecAudioProcessor"
        private const val BUFFER_SIZE_MS = 20  // 20ms 缓冲区
        private const val BYTES_PER_SAMPLE = 2  // 16-bit = 2 bytes
    }

    // 录音相关
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)

    // 输出控制：用于长生命周期模式下按需关闭输出
    private val isOutputEnabled = AtomicBoolean(true)

    // 播放相关（用于获取参考信号）
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)

    // 音频缓冲区
    private val bufferSize: Int
    private val microphoneBuffer: ByteArray
    private val referenceBuffer: ByteArray
    private val outputBuffer: ByteArray

    // 音频队列
    private val microphoneQueue = LinkedBlockingQueue<ByteArray>()
    private val referenceQueue = LinkedBlockingQueue<ByteArray>()

    // 回声消除相关
    private var adaptiveFilter: AdaptiveFilter? = null
    private val filterLength = 256  // 自适应滤波器长度

    // 回调
    private var onProcessedAudio: ((ByteArray) -> Unit)? = null
    private var onUserSpeechDetected: (() -> Unit)? = null

    // 统一的协程作用域，用于管理所有协程
    private val processorJob = SupervisorJob()
    private val processorScope = CoroutineScope(processorJob + Dispatchers.IO)

    // 用户说话检测相关
    private var lastUserSpeechTime: Long = 0
    private var userSpeechDetected = false
    private var consecutiveSpeechDetectionCount = 0  // 连续检测到用户说话的次数
    private val REQUIRED_CONSECUTIVE_DETECTIONS = 2  // 需要连续检测到的次数（防抖机制）

    // TTS 播放状态感知（用于优化 VAD 检测，避免回声误触发）
    @Volatile
    private var isPlaybackActive = false
    private var playbackStartTime: Long = 0
    private val PLAYBACK_PROTECTION_PERIOD_MS = 2000L  // TTS 播放初期打断保护期（2秒）

    init {
        // 计算缓冲区大小
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        bufferSize = (sampleRate * BUFFER_SIZE_MS / 1000 * BYTES_PER_SAMPLE).coerceAtLeast(minBufferSize)

        microphoneBuffer = ByteArray(bufferSize)
        referenceBuffer = ByteArray(bufferSize)
        outputBuffer = ByteArray(bufferSize)

        // 初始化自适应滤波器
        adaptiveFilter = AdaptiveFilter(filterLength)

        Log.d(TAG, "AecAudioProcessor 初始化完成: sampleRate=$sampleRate, bufferSize=$bufferSize")
    }

    /**
     * 设置处理后的音频回调
     */
    fun setOnProcessedAudio(callback: (ByteArray) -> Unit) {
        onProcessedAudio = callback
    }

    /**
     * 启用处理后音频输出（默认启用）
     */
    fun enableOutput() {
        isOutputEnabled.set(true)
    }

    /**
     * 禁用处理后音频输出（但仍然继续采集与处理，用于长生命周期 AEC）
     */
    fun disableOutput() {
        isOutputEnabled.set(false)
    }

    /**
     * 设置用户说话检测回调
     * 当检测到用户说话时立即调用,用于实现实时语音打断
     */
    fun setOnUserSpeechDetected(callback: () -> Unit) {
        onUserSpeechDetected = callback
    }

        /**
     * 通知 AEC 处理器 TTS 播放已开始
     * 用于启动打断保护期，避免 TTS 刚开始播放时因回声误触发打断
     */
    fun notifyPlaybackStarted() {
        isPlaybackActive = true
        playbackStartTime = System.currentTimeMillis()
        consecutiveSpeechDetectionCount = 0
        Log.d(TAG, "TTS 播放已开始，启动打断保护期 ${PLAYBACK_PROTECTION_PERIOD_MS}ms")
    }

    /**
     * 通知 AEC 处理器 TTS 播放已停止
     */
    fun notifyPlaybackStopped() {
        isPlaybackActive = false
        consecutiveSpeechDetectionCount = 0
        Log.d(TAG, "TTS 播放已停止")
    }

    /**
     * 开始录音
     */
    fun startRecording() {
        if (isRecording.get()) {
            Log.w(TAG, "已经在录音中")
            return
        }

        try {
            // 计算最小缓冲区大小
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            Log.d(TAG, "AudioRecord 最小缓冲区大小: $minBufferSize, 实际缓冲区大小: $bufferSize")
            
            // 初始化 AudioRecord
            // 使用 VOICE_COMMUNICATION 音频源，以获得更好的语音通话效果
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize.coerceAtLeast(bufferSize)
            )

            Log.d(TAG, "AudioRecord 初始化状态: ${audioRecord?.state}, 初始化成功状态: ${AudioRecord.STATE_INITIALIZED}")

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                return
            }

            // 开始录音
            audioRecord?.startRecording()
            Log.d(TAG, "AudioRecord 录音状态: ${audioRecord?.recordingState}, 录音中状态: ${AudioRecord.RECORDSTATE_RECORDING}")
            
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord 录音状态异常")
                stopRecording()
                return
            }
            
            isRecording.set(true)

            // 启动录音协程
            recordingJob = processorScope.launch {
                processAudio()
            }

            Log.d(TAG, "开始录音成功")
        } catch (e: Exception) {
            Log.e(TAG, "开始录音失败", e)
            stopRecording()
        }
    }

    /**
     * 停止录音
     */
    fun stopRecording() {
        Log.d(TAG, "停止录音")
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.let {
            if (it.state == AudioRecord.STATE_INITIALIZED && it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                it.stop()
            }
            it.release()
        }
        audioRecord = null

        // 清空队列
        microphoneQueue.clear()
        referenceQueue.clear()

        Log.d(TAG, "录音已停止")
    }

    /**
     * 播放音频（同时提供参考信号给 AEC）
     * 注意：这里不实际播放音频，只将音频数据作为参考信号提供给 AEC
     * 实际的音频播放由 CosyVoiceTTSClient 负责
     */
    fun playAudio(audioData: ByteArray) {
        // 将音频数据作为参考信号添加到队列
        // 注意：CosyVoiceTTSClient 和麦克风采样率已统一为 16kHz，无需重采样
        referenceQueue.offer(audioData.copyOf())
    }

    /**
     * 停止播放
     */
    fun stopPlayback() {
        Log.d(TAG, "停止播放")
        isPlaying.set(false)

        // 清空参考队列
        referenceQueue.clear()

        Log.d(TAG, "播放已停止")
    }

    /**
     * 处理音频（回声消除）
     */
    private suspend fun processAudio() {
        Log.d(TAG, "开始处理音频")

        var callbackCount = 0
        var lastCallbackTime = System.currentTimeMillis()
        var userSpeechCallbackCount = 0
        var lastUserSpeechCallbackTime = System.currentTimeMillis()
        var logCounter = 0  // 用于控制日志输出频率
        var zeroAudioCount = 0  // 连续检测到全 0 音频的次数

        while (isRecording.get()) {
            try {
                // 检查 AudioRecord 状态
                if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, "AudioRecord 未在录音状态，等待...")
                    delay(10)
                    continue
                }

                // 从麦克风读取音频
                val readSize = audioRecord?.read(microphoneBuffer, 0, bufferSize) ?: 0

                // 每 100 次打印一次详细日志（约每 2 秒）
                logCounter++
                if (logCounter >= 100) {
                    logCounter = 0
                    Log.d(TAG, "=== 音频数据采样 ===")
                    Log.d(TAG, "AudioRecord.state=${audioRecord?.state}, AudioRecord.recordingState=${audioRecord?.recordingState}")
                    Log.d(TAG, "readSize: $readSize, bufferSize: $bufferSize")

                    if (readSize > 0) {
                        // 随机抽取 5 个样本点的原始值
                        val sampleIndices = listOf(0, readSize / 4, readSize / 2, readSize * 3 / 4, readSize - 2)
                        val sampleValues = sampleIndices.map { idx ->
                            val low = microphoneBuffer[idx].toInt() and 0xFF
                            val high = microphoneBuffer[idx + 1].toInt() and 0xFF
                            val sample = (high shl 8 or low).toShort()
                            "[$idx]=$sample"
                        }.joinToString(", ")
                        Log.d(TAG, "原始样本值: $sampleValues")

                        // 计算音频能量（RMS）
                        var energy = 0.0
                        for (i in 0 until readSize step 2) {
                            val sample = ((microphoneBuffer[i + 1].toInt() and 0xFF) shl 8 or (microphoneBuffer[i].toInt() and 0xFF)).toShort()
                            energy += sample * sample
                        }
                        energy = sqrt(energy / (readSize / 2))
                        Log.d(TAG, "音频能量（RMS）: $energy")
                    }
                    Log.d(TAG, "====================")
                }

                if (readSize > 0) {
                    // 获取参考信号（TTS 播放的音频）
                    val reference = referenceQueue.poll()
                    var processedAudio: ByteArray

                    val hasReference = reference != null

                    if (reference != null) {
                        // 确保参考信号长度与麦克风音频长度一致
                        val referenceToUse = if (reference.size == readSize) {
                            reference
                        } else if (reference.size > readSize) {
                            reference.copyOfRange(0, readSize)
                        } else {
                            // 参考信号长度不足，填充零
                            val padded = ByteArray(readSize)
                            System.arraycopy(reference, 0, padded, 0, reference.size)
                            padded
                        }

                        // 执行回声消除
                        processedAudio = performAEC(microphoneBuffer.copyOfRange(0, readSize), referenceToUse)
                    } else {
                        // 没有参考信号，直接输出原始音频
                        processedAudio = microphoneBuffer.copyOfRange(0, readSize)
                    }

                   // 检测用户是否在说话（基于AEC处理后的音频能量）
                    // 当 TTS 正在播放但没有参考信号时，使用更高阈值检测，降低回声误触发风险
                    val useHighThreshold = isPlaybackActive && !hasReference
                    detectUserSpeech(processedAudio, useHighThreshold)


                    // 回调处理后的音频（在长生命周期模式下可通过开关关闭输出）
                    callbackCount++
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastCallbackTime >= 1000) {
                        Log.d(TAG, "音频处理中，每秒回调次数: $callbackCount")
                        callbackCount = 0
                        lastCallbackTime = currentTime
                    }
                    if (isOutputEnabled.get()) {
                        onProcessedAudio?.invoke(processedAudio)
                    }
                    // Log.d(TAG, "音频处理回调已触发，音频大小=${processedAudio.size}")
                } else if (readSize < 0) {
                    Log.e(TAG, "读取音频失败，错误码: $readSize")
                    delay(10)
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理音频失败", e)
                delay(10)
            }
        }

        Log.d(TAG, "音频处理已停止")
    }

    /**
     * 检测用户是否在说话（基于音频能量）
     * 使用防抖机制：需要连续多次检测到用户说话才触发打断
     * @param useHighThreshold 是否使用更高的能量阈值（TTS 播放中但无参考信号时使用，降低回声误触发）
     */
    private fun detectUserSpeech(audioData: ByteArray, useHighThreshold: Boolean = false) {
        // 计算音频能量（RMS）
        var energy = 0.0
        for (i in audioData.indices step 2) {
            val sample = ((audioData[i + 1].toInt() and 0xFF) shl 8 or (audioData[i].toInt() and 0xFF)).toShort()
            energy += sample * sample
        }
        energy = sqrt(energy / (audioData.size / 2))

        // 根据当前状态选择能量阈值：
        // - TTS 播放初期保护期（滤波器未收敛）：使用较高阈值 1200.0，过滤回声残留但用户说话仍可打断
        // - TTS 播放中但无参考信号（回声未被消除）：使用稍高阈值 1000.0
        // - 正常情况：使用标准阈值 500.0
        val threshold = if (isPlaybackActive) {
            val elapsedSincePlaybackStart = System.currentTimeMillis() - playbackStartTime
            if (elapsedSincePlaybackStart < PLAYBACK_PROTECTION_PERIOD_MS) {
                1200.0  // 保护期内使用较高阈值
            } else if (useHighThreshold) {
                1000.0  // 无参考信号时使用稍高阈值
            } else {
                500.0   // AEC 处理后使用标准阈值
            }
        } else {
            500.0  // 非播放状态使用标准阈值
        }


        if (energy > threshold) {
            // 检测到用户说话
            val currentTime = System.currentTimeMillis()
            lastUserSpeechTime = currentTime
            userSpeechDetected = true

            // 增加连续检测计数
            consecutiveSpeechDetectionCount++

            Log.d(TAG, "检测到用户说话，能量=$energy，连续检测次数=$consecutiveSpeechDetectionCount/$REQUIRED_CONSECUTIVE_DETECTIONS")

            // 只有连续检测到指定次数才触发回调（防抖机制）
            if (consecutiveSpeechDetectionCount >= REQUIRED_CONSECUTIVE_DETECTIONS) {
                Log.d(TAG, "连续检测确认用户说话，触发语音打断回调")
                onUserSpeechDetected?.invoke()
                // 重置计数，避免重复触发
                consecutiveSpeechDetectionCount = 0
            }
        } else {
            // 能量低于阈值，重置连续检测计数
            if (consecutiveSpeechDetectionCount > 0) {
                consecutiveSpeechDetectionCount = 0
            }
        }
    }

    /**
     * 执行回声消除
     */
    private fun performAEC(microphone: ByteArray, reference: ByteArray): ByteArray {
        val filter = adaptiveFilter ?: return microphone

        // 转换为 short 数组
        val micShort = byteArrayToShortArray(microphone)
        val refShort = byteArrayToShortArray(reference)

        // 执行自适应滤波
        val outputShort = filter.filter(micShort, refShort)

        // 转换回 byte 数组
        return shortArrayToByteArray(outputShort)
    }

    /**
     * 释放资源
     */
    fun release() {
        Log.d(TAG, "释放资源")
        // 取消所有协程
        processorJob.cancel()
        stopRecording()
        stopPlayback()
        adaptiveFilter = null
    }

    /**
     * 检查是否正在录音
     */
    fun isCurrentlyRecording(): Boolean {
        return isRecording.get()
    }

    /**
     * 检查是否正在播放
     */
    fun isCurrentlyPlaying(): Boolean {
        return isPlaying.get()
    }

    // 辅助函数
    private fun byteArrayToShortArray(data: ByteArray): ShortArray {
        val shorts = ShortArray(data.size / 2)
        for (i in shorts.indices) {
            val low = data[i * 2].toInt() and 0xFF
            val high = data[i * 2 + 1].toInt() and 0xFF
            shorts[i] = (high shl 8 or low).toShort()
        }
        return shorts
    }

    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val value = shorts[i].toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * 自适应滤波器（用于回声消除）
     */
    private class AdaptiveFilter(private val filterLength: Int) {
        private var weights: FloatArray = FloatArray(filterLength)
        private var referenceBuffer: FloatArray = FloatArray(filterLength)
        private var stepSize = 0.001f  // 步长（降低步长以避免过度适应 TTS 音频特征，防止错误消除用户语音）

        /**
         * 执行滤波
         */
        fun filter(desired: ShortArray, reference: ShortArray): ShortArray {
            val output = ShortArray(desired.size)

            for (i in desired.indices) {
                // 转换为浮点数
                val desiredFloat = desired[i].toFloat()
                val referenceFloat = reference[i].toFloat()

                // 更新参考缓冲区
                System.arraycopy(referenceBuffer, 0, referenceBuffer, 1, filterLength - 1)
                referenceBuffer[0] = referenceFloat

                // 计算滤波器输出
                var estimated = 0f
                for (j in 0 until filterLength) {
                    estimated += weights[j] * referenceBuffer[j]
                }

                // 计算误差
                val error = desiredFloat - estimated

                // 更新权重（LMS 算法）
                for (j in 0 until filterLength) {
                    weights[j] += stepSize * error * referenceBuffer[j]
                }

                // 输出误差（回声消除后的信号）
                output[i] = error.toInt().toShort()
            }

            return output
        }
    }
}