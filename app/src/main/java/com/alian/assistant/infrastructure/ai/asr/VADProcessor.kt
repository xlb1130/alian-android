package com.alian.assistant.infrastructure.ai.asr

/**
 * VAD（语音活动检测）处理器
 *
 * 使用动态阈值和多维度特征实现自适应的语音检测：
 * - 动态阈值：根据环境噪音自动调整检测阈值
 * - 多维度特征：结合能量、零交叉率、频谱特征进行决策
 * - 状态平滑：避免频繁的状态切换
 *
 * @param sampleRate 采样率（Hz），默认 16000
 * @param noiseAdaptationRate 噪音适应速度（0-1），默认 0.1
 * @param speechThresholdFactor 语音阈值因子，默认 2.5
 * @param minSpeechFrames 最小连续语音帧数，默认 3
 * @param minSilenceFrames 最小连续静音帧数，默认 10
 */
class VADProcessor(
    private val sampleRate: Int = 16000,
    private val noiseAdaptationRate: Float = 0.1f,
    private val speechThresholdFactor: Float = 2.5f,
    private val minSpeechFrames: Int = 3,
    private val minSilenceFrames: Int = 10
) {
    companion object {
        private const val TAG = "VADProcessor"
        private const val WINDOW_SIZE = 100  // 滑动窗口大小
        private const val MIN_NOISE_LEVEL = 10f  // 最小噪音水平
    }

    /**
     * VAD 结果数据类
     */
    data class VADResult(
        val isSpeech: Boolean,           // 是否为语音
        val energy: Float,               // 当前能量值
        val zcr: Float,                  // 零交叉率
        val spectralCentroid: Float,     // 频谱质心
        val highFreqRatio: Float,        // 高频能量占比
        val threshold: Float,            // 当前阈值
        val noiseLevel: Float,           // 当前噪音水平
        val confidence: Float            // 置信度（0-1）
    )

    // 特征提取器
    private val featureExtractor = AudioFeatureExtractor(sampleRate)

    // 状态管理
    private var noiseLevel: Float = MIN_NOISE_LEVEL  // 当前噪音水平
    private var adaptiveThreshold: Float = 500f  // 自适应阈值
    private var speechFrames: Int = 0  // 连续语音帧计数
    private var silenceFrames: Int = 0  // 连续静音帧计数
    private var isInSpeech: Boolean = false  // 当前是否在语音状态

    // 滑动窗口（用于噪音估计）
    private val energyWindow = FloatArray(WINDOW_SIZE)
    private val zcrWindow = FloatArray(WINDOW_SIZE)
    private var windowIndex: Int = 0
    private var windowFilled: Boolean = false

    /**
     * 处理音频帧，返回 VAD 结果
     *
     * @param pcm PCM 音频数据（16-bit signed）
     * @return VAD 检测结果
     */
    fun processFrame(pcm: ByteArray): VADResult {
        // 提取音频特征
        val features = featureExtractor.extractFeatures(pcm)

        // 更新滑动窗口
        updateSlidingWindow(features)

        // 更新噪音估计
        updateNoiseEstimation(features)

        // 计算自适应阈值
        adaptiveThreshold = noiseLevel * speechThresholdFactor

        // 多维度决策
        val decision = makeDecision(features)

        // 状态平滑
        smoothState(decision)

        // 计算置信度
        val confidence = calculateConfidence(features)

        return VADResult(
            isSpeech = isInSpeech,
            energy = features.rms,
            zcr = features.zcr,
            spectralCentroid = features.spectralCentroid,
            highFreqRatio = features.highFreqRatio,
            threshold = adaptiveThreshold,
            noiseLevel = noiseLevel,
            confidence = confidence
        )
    }

    /**
     * 更新滑动窗口
     */
    private fun updateSlidingWindow(features: AudioFeatureExtractor.AudioFeatures) {
        energyWindow[windowIndex] = features.rms
        zcrWindow[windowIndex] = features.zcr
        windowIndex = (windowIndex + 1) % WINDOW_SIZE

        if (windowIndex == 0) {
            windowFilled = true
        }
    }

    /**
     * 更新噪音估计
     * 使用 EMA（指数移动平均）平滑更新噪音水平
     */
    private fun updateNoiseEstimation(features: AudioFeatureExtractor.AudioFeatures) {
        // 如果当前判定为静音或能量低于阈值的一半，更新噪音估计
        if (!isInSpeech || features.rms < adaptiveThreshold * 0.5f) {
            // 使用 EMA 平滑更新
            noiseLevel = noiseLevel * (1 - noiseAdaptationRate) +
                         features.rms * noiseAdaptationRate

            // 限制最小噪音水平
            noiseLevel = maxOf(noiseLevel, MIN_NOISE_LEVEL)
        }

        // 如果窗口已填满，可以使用统计方法更新噪音水平
        if (windowFilled) {
            val medianEnergy = calculateMedian(energyWindow)
            // 使用中位数作为噪音基准，避免异常值影响
            noiseLevel = noiseLevel * 0.7f + medianEnergy * 0.3f
        }
    }

    /**
     * 多维度决策
     * 结合能量、零交叉率、频谱特征进行综合判断
     */
    private fun makeDecision(features: AudioFeatureExtractor.AudioFeatures): Boolean {
        // 1. 能量判断
        val energyAboveThreshold = features.rms > adaptiveThreshold

        // 2. 零交叉率判断（语音通常 ZCR 在 0.01-0.5 之间）
        val zcrValid = features.zcr > 0.01f && features.zcr < 0.5f

        // 3. 频谱质心判断（语音频谱质心通常在 500-3000Hz 之间）
        val spectralValid = features.spectralCentroid > 500f &&
                           features.spectralCentroid < 3000f

        // 4. 高频占比判断（语音在 300-3400Hz 占比通常 > 0.3）
        val highFreqValid = features.highFreqRatio > 0.3f

        // 加权决策
        val score = when {
            // 所有特征都符合，高置信度
            energyAboveThreshold && zcrValid && spectralValid && highFreqValid -> 1.0f
            // 能量 + 两个频谱特征符合
            energyAboveThreshold && (zcrValid && spectralValid) -> 0.85f
            // 能量 + 任一特征符合
            energyAboveThreshold && (zcrValid || spectralValid || highFreqValid) -> 0.7f
            // 仅能量符合
            energyAboveThreshold -> 0.5f
            // 其他情况
            else -> 0.0f
        }

        return score > 0.5f
    }

    /**
     * 状态平滑
     * 使用连续帧计数避免频繁的状态切换
     */
    private fun smoothState(decision: Boolean) {
        if (decision) {
            speechFrames++
            silenceFrames = 0

            if (speechFrames >= minSpeechFrames) {
                isInSpeech = true
            }
        } else {
            silenceFrames++
            speechFrames = 0

            if (silenceFrames >= minSilenceFrames && isInSpeech) {
                isInSpeech = false
            }
        }
    }

    /**
     * 计算置信度
     * 基于特征与阈值的距离计算置信度
     */
    private fun calculateConfidence(features: AudioFeatureExtractor.AudioFeatures): Float {
        // 能量置信度
        val energyConfidence = minOf(features.rms / adaptiveThreshold, 1.0f)

        // ZCR 置信度（在 0.01-0.5 范围内时置信度高）
        val zcrConfidence = when {
            features.zcr in 0.01f..0.5f -> 1.0f
            features.zcr < 0.01f -> features.zcr / 0.01f
            else -> maxOf(0f, 1.0f - (features.zcr - 0.5f) / 0.5f)
        }

        // 频谱质心置信度
        val spectralConfidence = when {
            features.spectralCentroid in 500f..3000f -> 1.0f
            features.spectralCentroid < 500f -> features.spectralCentroid / 500f
            else -> maxOf(0f, 1.0f - (features.spectralCentroid - 3000f) / 3000f)
        }

        // 加权平均
        return (energyConfidence * 0.4f +
                zcrConfidence * 0.3f +
                spectralConfidence * 0.3f).toFloat()
    }

    /**
     * 计算中位数
     */
    private fun calculateMedian(array: FloatArray): Float {
        val sorted = array.sortedArray()
        val n = sorted.size
        return if (n % 2 == 0) {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
        } else {
            sorted[n / 2]
        }
    }

    /**
     * 重置 VAD 状态
     */
    fun reset() {
        noiseLevel = MIN_NOISE_LEVEL
        adaptiveThreshold = 500f
        speechFrames = 0
        silenceFrames = 0
        isInSpeech = false
        windowIndex = 0
        windowFilled = false
        energyWindow.fill(0f)
        zcrWindow.fill(0f)
    }

    /**
     * 获取当前噪音水平
     */
    fun getNoiseLevel(): Float = noiseLevel

    /**
     * 获取当前阈值
     */
    fun getThreshold(): Float = adaptiveThreshold

    /**
     * 是否正在语音状态
     */
    fun isCurrentlyInSpeech(): Boolean = isInSpeech
}