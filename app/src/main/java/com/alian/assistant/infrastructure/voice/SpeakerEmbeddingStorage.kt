package com.alian.assistant.infrastructure.voice

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * 声纹持久化工具
 * 负责将声纹特征向量保存到 SharedPreferences
 */
class SpeakerEmbeddingStorage(private val context: Context) {
    companion object {
        private const val TAG = "SpeakerEmbeddingStorage"
        private const val PREFS_NAME = "voice_embeddings"
        private const val SPEAKER_COUNT_KEY = "speaker_count"
        private const val SPEAKER_PREFIX = "speaker_"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // 内存缓存
    private val embeddingCache = mutableMapOf<String, FloatArray>()
    
    /**
     * 保存声纹特征向量
     * @param name 说话人名称
     * @param embedding 声纹特征向量 (256维)
     */
    fun saveSpeakerEmbedding(name: String, embedding: FloatArray) {
        try {
            // 将浮点数组转换为 Base64 编码的字符串
            val base64String = floatArrayToBase64(embedding)
            
            // 保存到 SharedPreferences
            val editor = prefs.edit()
            editor.putString("$SPEAKER_PREFIX$name", base64String)
            
            // 更新说话人计数
            val currentCount = getSpeakerCount()
            editor.putInt(SPEAKER_COUNT_KEY, currentCount + 1)
            
            editor.apply()
            
            // 更新缓存
            embeddingCache[name] = embedding
            
            Log.d(TAG, "声纹已保存: $name (维度: ${embedding.size})")
        } catch (e: Exception) {
            Log.e(TAG, "保存声纹失败: $name", e)
        }
    }
    
    /**
     * 加载声纹特征向量
     * @param name 说话人名称
     * @return 声纹特征向量, 如果不存在返回 null
     */
    fun loadSpeakerEmbedding(name: String): FloatArray? {
        // 先从缓存中查找
        embeddingCache[name]?.let { return it }
        
        try {
            // 从 SharedPreferences 加载
            val base64String = prefs.getString("$SPEAKER_PREFIX$name", null)
            if (base64String == null) {
                Log.d(TAG, "声纹不存在: $name")
                return null
            }
            
            // 将 Base64 字符串转换为浮点数组
            val embedding = base64ToFloatArray(base64String)
            
            // 更新缓存
            embeddingCache[name] = embedding
            
            Log.d(TAG, "声纹已加载: $name (维度: ${embedding.size})")
            return embedding
        } catch (e: Exception) {
            Log.e(TAG, "加载声纹失败: $name", e)
            return null
        }
    }
    
    /**
     * 删除声纹特征向量
     * @param name 说话人名称
     */
    fun deleteSpeakerEmbedding(name: String) {
        try {
            // 从 SharedPreferences 删除
            val editor = prefs.edit()
            editor.remove("$SPEAKER_PREFIX$name")
            
            // 更新说话人计数
            val currentCount = getSpeakerCount()
            editor.putInt(SPEAKER_COUNT_KEY, maxOf(0, currentCount - 1))
            
            editor.apply()
            
            // 从缓存中删除
            embeddingCache.remove(name)
            
            Log.d(TAG, "声纹已删除: $name")
        } catch (e: Exception) {
            Log.e(TAG, "删除声纹失败: $name", e)
        }
    }
    
    /**
     * 检查说话人是否存在
     * @param name 说话人名称
     * @return 是否存在
     */
    fun hasSpeaker(name: String): Boolean {
        return embeddingCache.containsKey(name) || 
               prefs.contains("$SPEAKER_PREFIX$name")
    }
    
    /**
     * 获取所有说话人的声纹
     * @return 说话人名称到声纹向量的映射
     */
    fun getAllSpeakers(): Map<String, FloatArray> {
        val result = mutableMapOf<String, FloatArray>()
        
        // 遍历所有键
        val allKeys = prefs.all.keys
        for (key in allKeys) {
            if (key.startsWith(SPEAKER_PREFIX)) {
                val name = key.removePrefix(SPEAKER_PREFIX)
                val embedding = loadSpeakerEmbedding(name)
                if (embedding != null) {
                    result[name] = embedding
                }
            }
        }
        
        Log.d(TAG, "已加载 ${result.size} 个说话人")
        return result
    }
    
    /**
     * 获取说话人数量
     * @return 说话人数量
     */
    fun getSpeakerCount(): Int {
        return prefs.getInt(SPEAKER_COUNT_KEY, 0)
    }
    
    /**
     * 清除所有声纹数据
     */
    fun clearAllEmbeddings() {
        try {
            val editor = prefs.edit()
            editor.clear()
            editor.apply()
            
            // 清除缓存
            embeddingCache.clear()
            
            Log.d(TAG, "所有声纹数据已清除")
        } catch (e: Exception) {
            Log.e(TAG, "清除声纹数据失败", e)
        }
    }
    
    /**
     * 加载所有声纹到内存缓存
     */
    suspend fun loadAllEmbeddings(): Boolean = withContext(Dispatchers.IO) {
        try {
            embeddingCache.clear()
            val speakers = getAllSpeakers()
            embeddingCache.putAll(speakers)
            Log.d(TAG, "已加载 ${speakers.size} 个声纹到缓存")
            true
        } catch (e: Exception) {
            Log.e(TAG, "加载所有声纹失败", e)
            false
        }
    }
    
    /**
     * 将浮点数组转换为 Base64 编码的字符串
     * @param array 浮点数组
     * @return Base64 编码的字符串
     */
    private fun floatArrayToBase64(array: FloatArray): String {
        // 将浮点数组转换为字节数组
        val byteBuffer = ByteBuffer.allocate(array.size * 4)
        for (value in array) {
            byteBuffer.putFloat(value)
        }
        val byteArray = byteBuffer.array()
        
        // 转换为 Base64 字符串
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    /**
     * 将 Base64 编码的字符串转换为浮点数组
     * @param base64String Base64 编码的字符串
     * @return 浮点数组
     */
    private fun base64ToFloatArray(base64String: String): FloatArray {
        // 将 Base64 字符串转换为字节数组
        val byteArray = Base64.decode(base64String, Base64.NO_WRAP)
        
        // 将字节数组转换为浮点数组
        val byteBuffer = ByteBuffer.wrap(byteArray)
        val floatArray = FloatArray(byteArray.size / 4)
        for (i in floatArray.indices) {
            floatArray[i] = byteBuffer.float
        }
        
        return floatArray
    }
    
    /**
     * 获取存储使用情况
     * @return 存储的字节数
     */
    fun getStorageSize(): Long {
        var totalSize = 0L
        val allKeys = prefs.all.keys
        for (key in allKeys) {
            if (key.startsWith(SPEAKER_PREFIX)) {
                val value = prefs.getString(key, null)
                if (value != null) {
                    totalSize += value.toByteArray().size
                }
            }
        }
        return totalSize
    }
    
    /**
     * 获取存储使用情况的友好描述
     * @return 存储使用情况的字符串
     */
    fun getStorageSizeDescription(): String {
        val bytes = getStorageSize()
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        
        return when {
            mb >= 1.0 -> String.format("%.2f MB", mb)
            kb >= 1.0 -> String.format("%.2f KB", kb)
            else -> "$bytes bytes"
        }
    }
}