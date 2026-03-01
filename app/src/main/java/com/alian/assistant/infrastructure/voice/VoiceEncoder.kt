package com.alian.assistant.infrastructure.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

/**
 * 声纹编码器
 * 使用 PyTorch Mobile 模型将音频转换为声纹特征向量
 */
class VoiceEncoder(private val context: Context) {
    companion object {
        private const val TAG = "VoiceEncoder"
        private const val MODEL_FILE = "voice_encoder_optimized.pt"
        private const val EMBEDDING_DIM = 256  // 声纹特征维度
    }
    
    private var module: Module? = null
    private val preprocessor = AudioPreprocessor()
    
    /**
     * 加载模型
     * @return 是否加载成功
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 从 assets 复制模型文件到临时目录
            val modelFile = File(context.cacheDir, MODEL_FILE)
            if (!modelFile.exists()) {
                copyModelFromAssets(modelFile)
            }
            
            // 加载 PyTorch 模型
            module = Module.load(modelFile.absolutePath)
            Log.d(TAG, "模型加载成功: ${modelFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "模型加载失败", e)
            false
        }
    }
    
    /**
     * 从 assets 复制模型文件
     */
    private fun copyModelFromAssets(destFile: File) {
        context.assets.open(MODEL_FILE).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.d(TAG, "模型文件已复制到: ${destFile.absolutePath}")
    }
    
    /**
     * 检查模型是否已加载
     */
    fun isModelLoaded(): Boolean {
        return module != null
    }
    
    /**
     * 编码音频为声纹特征向量
     * @param audioData 原始音频数据 (PCM 16-bit, 单声道, 16kHz)
     * @return 声纹特征向量 (256维), 如果失败返回 null
     */
    suspend fun encode(audioData: ShortArray): FloatArray? = withContext(Dispatchers.Default) {
        val model = module
        if (model == null) {
            Log.e(TAG, "模型未加载")
            return@withContext null
        }
        
        try {
            // 1. 预处理音频数据
            val melSpectrogram = preprocessor.preprocess(audioData)
            
            // 2. 创建输入张量 (1, 40, 160)
            val inputTensor = Tensor.fromBlob(
                melSpectrogram,
                longArrayOf(1, AudioPreprocessor.N_MELS.toLong(), AudioPreprocessor.N_FRAMES.toLong())
            )
            
            // 3. 运行模型推理
            val outputTensor = model.forward(IValue.from(inputTensor)).toTensor()
            
            // 4. 提取输出向量 (256维)
            val embedding = outputTensor.dataAsFloatArray
            
            // 5. 归一化向量 (L2 归一化)
            val normalized = normalizeVector(embedding)
            
            Log.d(TAG, "声纹编码成功, 向量维度: ${normalized.size}")
            normalized
        } catch (e: Exception) {
            Log.e(TAG, "声纹编码失败", e)
            null
        }
    }
    
    /**
     * L2 归一化向量
     */
    private fun normalizeVector(vector: FloatArray): FloatArray {
        var norm = 0f
        for (value in vector) {
            norm += value * value
        }
        norm = sqrt(norm.toDouble()).toFloat()
        
        if (norm < 1e-8f) {
            return vector
        }
        
        return vector.map { it / norm }.toFloatArray()
    }
    
    /**
     * 计算两个声纹向量的余弦相似度
     * @param embedding1 第一个声纹向量
     * @param embedding2 第二个声纹向量
     * @return 相似度 (0-1), 1 表示完全相同
     */
    fun computeSimilarity(embedding1: FloatArray, embedding2: FloatArray): Float {
        if (embedding1.size != embedding2.size) {
            Log.e(TAG, "向量维度不匹配: ${embedding1.size} vs ${embedding2.size}")
            return 0f
        }
        
        var dotProduct = 0f
        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
        }
        
        // 由于向量已经归一化, 点积就是余弦相似度
        return dotProduct.coerceIn(0f, 1f)
    }
    
    /**
     * 释放模型资源
     */
    fun release() {
        module = null
        Log.d(TAG, "模型资源已释放")
    }
}