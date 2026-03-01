package com.alian.assistant.infrastructure.ai.asr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 音频特征提取器
 *
 * 提取多维度音频特征用于 VAD（语音活动检测）：
 * - 能量特征：RMS、峰值
 * - 时域特征：零交叉率（ZCR）
 * - 频域特征：频谱质心、高频能量占比
 *
 * @param sampleRate 采样率（Hz），默认 16000
 */
class AudioFeatureExtractor(private val sampleRate: Int = 16000) {
    companion object {
        private const val TAG = "AudioFeatureExtractor"
    }

    /**
     * 音频特征数据类
     */
    data class AudioFeatures(
        val rms: Float,              // 均方根能量
        val peak: Float,             // 峰值能量
        val zcr: Float,              // 零交叉率
        val spectralCentroid: Float, // 频谱质心
        val highFreqRatio: Float     // 高频能量占比（300-3400Hz）
    )

    /**
     * 从 PCM 数据中提取音频特征
     *
     * @param pcm PCM 音频数据（16-bit signed）
     * @return 提取的音频特征
     */
    fun extractFeatures(pcm: ByteArray): AudioFeatures {
        val samples = pcmToSamples(pcm)

        return AudioFeatures(
            rms = calculateRMS(samples),
            peak = calculatePeak(samples),
            zcr = calculateZCR(samples),
            spectralCentroid = calculateSpectralCentroid(samples),
            highFreqRatio = calculateHighFreqRatio(samples)
        )
    }

    /**
     * 将 PCM 字节数组转换为样本数组
     */
    private fun pcmToSamples(pcm: ByteArray): ShortArray {
        val samples = ShortArray(pcm.size / 2)
        for (i in samples.indices) {
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt()
            samples[i] = (high shl 8 or low).toShort()
        }
        return samples
    }

    /**
     * 计算均方根能量（RMS）
     * RMS = sqrt(sum(x^2) / N)
     */
    private fun calculateRMS(samples: ShortArray): Float {
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        return sqrt(sum / samples.size).toFloat()
    }

    /**
     * 计算峰值能量
     */
    private fun calculatePeak(samples: ShortArray): Float {
        var peak = 0f
        for (sample in samples) {
            val absSample = abs(sample.toFloat())
            if (absSample > peak) {
                peak = absSample
            }
        }
        return peak
    }

    /**
     * 计算零交叉率（ZCR）
     * ZCR = (number of zero crossings) / (N - 1)
     *
     * 语音信号的 ZCR 通常在 0.01-0.5 之间
     * 噪音信号的 ZCR 通常较低或较高
     */
    private fun calculateZCR(samples: ShortArray): Float {
        if (samples.size < 2) return 0f

        var crossings = 0
        for (i in 1 until samples.size) {
            // 检测符号变化
            val currentSign = if (samples[i] >= 0) 1 else -1
            val prevSign = if (samples[i - 1] >= 0) 1 else -1
            if (currentSign != prevSign) {
                crossings++
            }
        }
        return crossings.toFloat() / (samples.size - 1)
    }

    /**
     * 计算频谱质心
     * 频谱质心表示频谱的"重心"，反映频谱的亮度
     * 语音信号的频谱质心通常在 1000-2000Hz 之间
     */
    private fun calculateSpectralCentroid(samples: ShortArray): Float {
        // 使用简化的频谱计算（无需完整 FFT）
        val spectrum = calculateSpectrum(samples)

        var weightedSum = 0f
        var magnitudeSum = 0f

        for (i in spectrum.indices) {
            val magnitude = spectrum[i]
            weightedSum += i * magnitude
            magnitudeSum += magnitude
        }

        return if (magnitudeSum > 0) {
            // 转换为 Hz
            (weightedSum / magnitudeSum) * sampleRate / 2f / spectrum.size
        } else {
            0f
        }
    }

    /**
     * 计算高频能量占比（300-3400Hz）
     * 语音主要集中在这个频段
     */
    private fun calculateHighFreqRatio(samples: ShortArray): Float {
        val spectrum = calculateSpectrum(samples)

        val nyquist = sampleRate / 2f
        val voiceStartBin = (300 / nyquist * spectrum.size).toInt()
        val voiceEndBin = (3400 / nyquist * spectrum.size).toInt()

        var totalEnergy = 0f
        var voiceEnergy = 0f

        for (i in spectrum.indices) {
            val magnitude = spectrum[i]
            totalEnergy += magnitude
            if (i in voiceStartBin..voiceEndBin) {
                voiceEnergy += magnitude
            }
        }

        return if (totalEnergy > 0) voiceEnergy / totalEnergy else 0f
    }

    /**
     * 计算频谱（简化的 FFT 实现）
     * 使用 Goertzel 算法计算特定频率的能量
     */
    private fun calculateSpectrum(samples: ShortArray): FloatArray {
        val fftSize = 256
        val spectrum = FloatArray(fftSize / 2)

        // 将样本转换为浮点数
        val floatSamples = FloatArray(fftSize) { idx ->
            if (idx < samples.size) samples[idx].toFloat() else 0f
        }

        // 使用简化的频谱计算（基于能量分布）
        for (k in 0 until fftSize / 2) {
            var real = 0f
            var imag = 0f
            val frequency = 2 * PI * k / fftSize

            for (n in floatSamples.indices) {
                val angle = frequency * n
                real += floatSamples[n] * cos(angle).toFloat()
                imag -= floatSamples[n] * sin(angle).toFloat()
            }

            spectrum[k] = sqrt(real * real + imag * imag)
        }

        return spectrum
    }
}