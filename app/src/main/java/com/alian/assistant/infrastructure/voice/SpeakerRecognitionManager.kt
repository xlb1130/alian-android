package com.alian.assistant.infrastructure.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.collections.iterator

/**
 * 声纹识别管理器
 * 管理声纹注册、识别和匹配功能
 */
class SpeakerRecognitionManager(private val context: Context) {
    companion object {
        private const val TAG = "SpeakerRecognitionManager"
        private const val SIMILARITY_THRESHOLD = 0.75f  // 相似度阈值
        private const val RECOMMENDED_THRESHOLD_MIN = 0.75f
        private const val RECOMMENDED_THRESHOLD_MAX = 0.85f
    }
    
    private val encoder = VoiceEncoder(context)
    private val storage = SpeakerEmbeddingStorage(context)
    private var isInitialized = false
    
    /**
     * 初始化管理器
     * @return 是否初始化成功
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) {
            return@withContext true
        }
        
        try {
            // 加载模型
            val modelLoaded = encoder.loadModel()
            if (!modelLoaded) {
                Log.e(TAG, "模型加载失败")
                return@withContext false
            }
            
            // 加载已保存的声纹
            storage.loadAllEmbeddings()
            
            isInitialized = true
            Log.d(TAG, "声纹识别管理器初始化成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败", e)
            false
        }
    }
    
    /**
     * 检查是否已初始化
     */
    fun isReady(): Boolean {
        return isInitialized && encoder.isModelLoaded()
    }
    
    /**
     * 注册新说话人
     * @param name 说话人名称
     * @param audioData 音频数据 (PCM 16-bit, 单声道, 16kHz)
     * @return 注册结果
     */
    suspend fun registerSpeaker(name: String, audioData: ShortArray): RegistrationResult = withContext(Dispatchers.Default) {
        if (!isReady()) {
            Log.e(TAG, "管理器未初始化")
            return@withContext RegistrationResult(false, "管理器未初始化", null)
        }
        
        try {
            // 编码声纹
            val embedding = encoder.encode(audioData)
            if (embedding == null) {
                Log.e(TAG, "声纹编码失败")
                return@withContext RegistrationResult(false, "声纹编码失败", null)
            }
            
            // 检查是否已存在该说话人
            if (storage.hasSpeaker(name)) {
                Log.w(TAG, "说话人 '$name' 已存在")
                return@withContext RegistrationResult(false, "说话人 '$name' 已存在", null)
            }
            
            // 保存声纹
            storage.saveSpeakerEmbedding(name, embedding)
            
            Log.d(TAG, "说话人 '$name' 注册成功")
            RegistrationResult(true, "注册成功", name)
        } catch (e: Exception) {
            Log.e(TAG, "注册说话人失败", e)
            RegistrationResult(false, "注册失败: ${e.message}", null)
        }
    }
    
    /**
     * 识别说话人
     * @param audioData 音频数据 (PCM 16-bit, 单声道, 16kHz)
     * @param threshold 相似度阈值 (可选, 默认 0.75)
     * @return 识别结果
     */
    suspend fun recognizeSpeaker(audioData: ShortArray, threshold: Float = SIMILARITY_THRESHOLD): RecognitionResult = withContext(Dispatchers.Default) {
        if (!isReady()) {
            Log.e(TAG, "管理器未初始化")
            return@withContext RecognitionResult(false, "管理器未初始化", null, 0f)
        }
        
        try {
            // 获取所有已注册的说话人
            val speakers = storage.getAllSpeakers()
            if (speakers.isEmpty()) {
                Log.w(TAG, "没有已注册的说话人")
                return@withContext RecognitionResult(false, "没有已注册的说话人", null, 0f)
            }
            
            // 编码声纹
            val embedding = encoder.encode(audioData)
            if (embedding == null) {
                Log.e(TAG, "声纹编码失败")
                return@withContext RecognitionResult(false, "声纹编码失败", null, 0f)
            }
            
            // 与所有已注册的声纹比较
            var bestMatch: String? = null
            var bestSimilarity = 0f
            
            for ((name, storedEmbedding) in speakers) {
                val similarity = encoder.computeSimilarity(embedding, storedEmbedding)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestMatch = name
                }
                Log.d(TAG, "与 '$name' 的相似度: $similarity")
            }
            
            // 判断是否匹配
            if (bestSimilarity >= threshold && bestMatch != null) {
                Log.d(TAG, "识别成功: $bestMatch (相似度: $bestSimilarity)")
                RecognitionResult(true, "识别成功", bestMatch, bestSimilarity)
            } else {
                Log.d(TAG, "未识别到匹配的说话人 (最高相似度: $bestSimilarity)")
                RecognitionResult(false, "未识别到匹配的说话人", null, bestSimilarity)
            }
        } catch (e: Exception) {
            Log.e(TAG, "识别说话人失败", e)
            RecognitionResult(false, "识别失败: ${e.message}", null, 0f)
        }
    }
    
    /**
     * 删除说话人
     * @param name 说话人名称
     * @return 是否删除成功
     */
    suspend fun deleteSpeaker(name: String): Boolean = withContext(Dispatchers.IO) {
        if (storage.hasSpeaker(name)) {
            storage.deleteSpeakerEmbedding(name)
            Log.d(TAG, "说话人 '$name' 已删除")
            true
        } else {
            Log.w(TAG, "说话人 '$name' 不存在")
            false
        }
    }
    
    /**
     * 获取所有已注册的说话人名称
     * @return 说话人名称列表
     */
    fun getAllSpeakerNames(): List<String> {
        return storage.getAllSpeakers().keys.toList()
    }
    
    /**
     * 检查说话人是否存在
     * @param name 说话人名称
     * @return 是否存在
     */
    fun hasSpeaker(name: String): Boolean {
        return storage.hasSpeaker(name)
    }
    
    /**
     * 获取说话人数量
     * @return 说话人数量
     */
    fun getSpeakerCount(): Int {
        return storage.getSpeakerCount()
    }
    
    /**
     * 设置相似度阈值
     * @param threshold 阈值 (0.0 - 1.0)
     */
    fun setSimilarityThreshold(threshold: Float) {
        if (threshold in 0.0f..1.0f) {
            Log.d(TAG, "相似度阈值已设置为: $threshold")
        } else {
            Log.w(TAG, "无效的阈值: $threshold, 使用默认值: $SIMILARITY_THRESHOLD")
        }
    }
    
    /**
     * 获取推荐的相似度阈值范围
     * @return 最小和最大阈值
     */
    fun getRecommendedThresholdRange(): Pair<Float, Float> {
        return Pair(RECOMMENDED_THRESHOLD_MIN, RECOMMENDED_THRESHOLD_MAX)
    }
    
    /**
     * 清除所有说话人数据
     */
    suspend fun clearAllSpeakers(): Boolean = withContext(Dispatchers.IO) {
        storage.clearAllEmbeddings()
        Log.d(TAG, "所有说话人数据已清除")
        true
    }
    
    /**
     * 释放资源
     */
    fun release() {
        encoder.release()
        isInitialized = false
        Log.d(TAG, "声纹识别管理器已释放")
    }
    
    /**
     * 注册结果
     */
    data class RegistrationResult(
        val success: Boolean,
        val message: String,
        val speakerName: String?
    )
    
    /**
     * 识别结果
     */
    data class RecognitionResult(
        val success: Boolean,
        val message: String,
        val speakerName: String?,
        val similarity: Float
    )
}