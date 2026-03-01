package com.alian.assistant.infrastructure.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 音频预处理器
 * 负责将原始音频数据转换为模型所需的 Mel 频谱图格式
 */
class AudioPreprocessor {
    companion object {
        private const val TAG = "AudioPreprocessor"
        
        // 音频参数
        const val SAMPLE_RATE = 16000  // 采样率 16kHz
        const val N_FFT = 512          // FFT 窗口大小
        const val HOP_LENGTH = 160     // 跳跃长度 (10ms)
        const val WIN_LENGTH = 400     // 窗口长度 (25ms)
        const val N_MELS = 40          // Mel 频带数量
        const val N_FRAMES = 160       // 输入帧数 (1.6秒音频)
        
        // Mel 滤波器参数
        private const val FREQ_MIN = 0f
        private const val FREQ_MAX = 8000f  // 最大频率 8kHz
    }
    
    // Mel 滤波器组
    private lateinit var melFilterbank: Array<FloatArray>
    
    init {
        initializeMelFilterbank()
    }
    
    /**
     * 初始化 Mel 滤波器组
     */
    private fun initializeMelFilterbank() {
        melFilterbank = Array(N_MELS) { FloatArray(N_FFT / 2 + 1) }
        
        // 计算 Mel 频率刻度
        val melMin = hzToMel(FREQ_MIN)
        val melMax = hzToMel(FREQ_MAX)
        
        // 生成 Mel 频率点
        val melPoints = FloatArray(N_MELS + 2)
        for (i in melPoints.indices) {
            melPoints[i] = melMin + i * (melMax - melMin) / (N_MELS + 1)
        }
        
        // 转换回 Hz
        val hzPoints = melPoints.map { melToHz(it) }
        
        // 转换为 FFT bin 索引
        val binPoints = hzPoints.map { 
            ((it * (N_FFT + 1)) / SAMPLE_RATE).toInt().coerceIn(0, N_FFT / 2)
        }
        
        // 构建三角滤波器
        for (m in 0 until N_MELS) {
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]
            
            for (k in left until center) {
                melFilterbank[m][k] = (k - left).toFloat() / (center - left)
            }
            for (k in center until right) {
                melFilterbank[m][k] = (right - k).toFloat() / (right - center)
            }
        }
    }
    
    /**
     * 预处理音频数据
     * @param audioData 原始音频数据 (PCM 16-bit, 单声道, 16kHz)
     * @return Mel 频谱图 (N_MELS x N_FRAMES)
     */
    fun preprocess(audioData: ShortArray): FloatArray {
        // 1. 归一化到 [-1, 1]
        val normalized = audioData.map { it / 32768.0f }.toFloatArray()
        
        // 2. 如果音频长度不足，进行填充
        val targetLength = N_FRAMES * HOP_LENGTH
        val padded = if (normalized.size < targetLength) {
            normalized.copyOf(targetLength)
        } else {
            normalized.copyOf(targetLength)
        }
        
        // 3. 计算 STFT
        val stft = computeSTFT(padded)
        
        // 4. 计算幅度谱
        val magnitude = Array(stft.size) { FloatArray(stft[0].size) }
        for (i in stft.indices) {
            for (j in 0 until stft[i].size) {
                val complex = stft[i][j]
                magnitude[i][j] = sqrt(complex.real * complex.real + complex.imag * complex.imag)
            }
        }
        
        // 5. 应用 Mel 滤波器
        val melSpectrogram = applyMelFilterbank(magnitude)
        
        // 6. 转换为对数尺度
        val logMel = melSpectrogram.map { row ->
            row.map { value ->
                val clamped = max(value, 1e-10f)
                ln(clamped).toFloat()
            }.toFloatArray()
        }
        
        // 7. 转换为模型输入格式 (N_MELS * N_FRAMES)
        val result = FloatArray(N_MELS * N_FRAMES)
        for (m in 0 until N_MELS) {
            for (t in 0 until N_FRAMES) {
                result[m * N_FRAMES + t] = logMel[m][t]
            }
        }
        
        return result
    }
    
    /**
     * 计算 STFT (短时傅里叶变换)
     */
    private fun computeSTFT(audio: FloatArray): Array<ComplexArray> {
        val numFrames = (audio.size - WIN_LENGTH) / HOP_LENGTH + 1
        val stft = Array(numFrames) { ComplexArray(N_FFT / 2 + 1) }
        
        // 预计算汉宁窗
        val window = FloatArray(WIN_LENGTH)
        for (i in window.indices) {
            window[i] = (0.5 * (1 - cos(2 * PI * i / (WIN_LENGTH - 1)))).toFloat()
        }
        
        for (frame in 0 until numFrames) {
            val start = frame * HOP_LENGTH
            val frameData = FloatArray(N_FFT)
            
            // 应用窗口和零填充
            for (i in 0 until WIN_LENGTH) {
                frameData[i] = audio[start + i] * window[i]
            }
            
            // 计算 FFT
            val fft = computeFFT(frameData)
            stft[frame] = fft.copyOfRange(0, N_FFT / 2 + 1)
        }
        
        return stft
    }
    
    /**
     * 计算 FFT (快速傅里叶变换)
     */
    private fun computeFFT(data: FloatArray): ComplexArray {
        val n = data.size
        if (n == 1) {
            return ComplexArray(arrayOf(Complex(data[0], 0f)))
        }
        
        // 分离奇偶索引
        val even = FloatArray(n / 2)
        val odd = FloatArray(n / 2)
        for (i in 0 until n / 2) {
            even[i] = data[2 * i]
            odd[i] = data[2 * i + 1]
        }
        
        // 递归计算
        val q = computeFFT(even)
        val r = computeFFT(odd)
        
        // 合并结果
        val result = ComplexArray(n)
        for (k in 0 until n / 2) {
            val angle = -2 * PI * k / n
            val factor = Complex(cos(angle).toFloat(), sin(angle).toFloat())
            val product = r[k] * factor
            result[k] = q[k] + product
            result[k + n / 2] = q[k] - product
        }
        
        return result
    }
    
    /**
     * 应用 Mel 滤波器组
     */
    private fun applyMelFilterbank(magnitude: Array<FloatArray>): Array<FloatArray> {
        val melSpectrogram = Array(magnitude.size) { FloatArray(N_MELS) }
        
        for (frame in magnitude.indices) {
            for (m in 0 until N_MELS) {
                var sum = 0f
                for (k in 0 until N_FFT / 2 + 1) {
                    sum += magnitude[frame][k] * melFilterbank[m][k]
                }
                melSpectrogram[frame][m] = sum
            }
        }
        
        // 转置为 (N_MELS, numFrames)
        val transposed = Array(N_MELS) { FloatArray(magnitude.size) }
        for (m in 0 until N_MELS) {
            for (frame in magnitude.indices) {
                transposed[m][frame] = melSpectrogram[frame][m]
            }
        }
        
        return transposed
    }
    
    /**
     * Hz 转 Mel
     */
    private fun hzToMel(hz: Float): Float {
        return 2595f * ln(1f + hz / 700f)
    }
    
    /**
     * Mel 转 Hz
     */
    private fun melToHz(mel: Float): Float {
        return 700f * (exp(mel / 2595f) - 1f)
    }
    
    /**
     * 复数类
     */
    private data class Complex(val real: Float, val imag: Float) {
        operator fun plus(other: Complex): Complex {
            return Complex(real + other.real, imag + other.imag)
        }
        
        operator fun minus(other: Complex): Complex {
            return Complex(real - other.real, imag - other.imag)
        }
        
        operator fun times(other: Complex): Complex {
            return Complex(
                real * other.real - imag * other.imag,
                real * other.imag + imag * other.real
            )
        }
    }
    
    /**
     * 复数数组类
     */
    private class ComplexArray {
        private val data: Array<Complex>
        
        constructor(data: Array<Complex>) {
            this.data = data
        }
        
        constructor(size: Int) {
            this.data = Array(size) { Complex(0f, 0f) }
        }
        
        val size: Int get() = data.size
        
        operator fun get(index: Int): Complex = data[index]
        
        operator fun set(index: Int, value: Complex) {
            data[index] = value
        }
        
        fun copyOfRange(fromIndex: Int, toIndex: Int): ComplexArray {
            return ComplexArray(data.copyOfRange(fromIndex, toIndex))
        }
    }
}