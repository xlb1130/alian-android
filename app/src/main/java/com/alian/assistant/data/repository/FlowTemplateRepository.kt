package com.alian.assistant.data.repository

import android.content.Context
import android.util.Log
import com.alian.assistant.core.flow.model.*
import com.alian.assistant.infrastructure.flow.storage.FlowTemplateStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * 流程模板仓库
 * 
 * 职责：
 * 1. 领域模型持久化
 * 2. 模板匹配查询
 * 3. 缓存管理
 */
class FlowTemplateRepository(
    private val storage: FlowTemplateStorage
) {
    companion object {
        private const val TAG = "FlowTemplateRepository"
        
        @Volatile
        private var instance: FlowTemplateRepository? = null
        
        /**
         * 获取单例实例
         */
        fun getInstance(context: Context): FlowTemplateRepository {
            return instance ?: synchronized(this) {
                instance ?: FlowTemplateRepository(
                    FlowTemplateStorage(context)
                ).also { instance = it }
            }
        }
    }
    
    // 内存缓存
    private val _templatesFlow = MutableStateFlow<List<FlowTemplate>>(emptyList())
    val templatesFlow: StateFlow<List<FlowTemplate>> = _templatesFlow.asStateFlow()
    
    // 缓存是否已加载
    private var cacheLoaded = false
    
    /**
     * 刷新缓存
     */
    suspend fun refreshCache() {
        val templates = storage.loadAll()
        _templatesFlow.value = templates
        cacheLoaded = true
        Log.d(TAG, "缓存刷新完成: ${templates.size} 个模板")
    }
    
    /**
     * 确保缓存已加载
     */
    private suspend fun ensureCacheLoaded() {
        if (!cacheLoaded) {
            refreshCache()
        }
    }
    
    /**
     * 查找匹配的模板
     */
    suspend fun findMatchingTemplate(instruction: String): TemplateMatchResult? {
        ensureCacheLoaded()
        
        return _templatesFlow.value
            .map { template ->
                val result = template.matchesInstruction(instruction)
                TemplateMatchResult(template, result.confidence)
            }
            .filter { it.confidence > 0.5f }
            .maxByOrNull { it.confidence }
    }
    
    /**
     * 查找相似模板
     */
    suspend fun findSimilarTemplate(instruction: String): FlowTemplate? {
        ensureCacheLoaded()
        
        return _templatesFlow.value.find { template ->
            // 简单相似度检查：关键词重叠
            val keywords = template.matchingRule.keywords
            keywords.any { instruction.contains(it, ignoreCase = true) }
        }
    }
    
    /**
     * 保存模板
     */
    suspend fun save(template: FlowTemplate, isUserCreated: Boolean = false): Result<Unit> {
        return storage.save(template, isUserCreated).also { result ->
            if (result.isSuccess) {
                // 更新缓存
                val currentList = _templatesFlow.value.toMutableList()
                val existingIndex = currentList.indexOfFirst { it.id == template.id }
                if (existingIndex >= 0) {
                    currentList[existingIndex] = template
                } else {
                    currentList.add(template)
                }
                _templatesFlow.value = currentList.sortedByDescending { it.updatedAt }
            }
        }
    }
    
    /**
     * 根据 ID 获取模板
     */
    suspend fun getById(id: String): FlowTemplate? {
        ensureCacheLoaded()
        return _templatesFlow.value.find { it.id == id }
            ?: storage.loadById(id)
    }
    
    /**
     * 删除模板
     */
    suspend fun delete(id: String): Result<Unit> {
        return storage.delete(id).also { result ->
            if (result.isSuccess) {
                _templatesFlow.value = _templatesFlow.value.filter { it.id != id }
            }
        }
    }
    
    /**
     * 导出模板
     */
    suspend fun exportTemplate(id: String): String? {
        return storage.exportTemplate(id)
    }
    
    /**
     * 导入模板
     */
    suspend fun importTemplate(jsonString: String, asUserCreated: Boolean = true): Result<FlowTemplate> {
        return storage.importTemplate(jsonString, asUserCreated).also { result ->
            if (result.isSuccess) {
                result.getOrNull()?.let { template ->
                    _templatesFlow.value = (_templatesFlow.value + template)
                        .sortedByDescending { it.updatedAt }
                }
            }
        }
    }
    
    /**
     * 获取所有模板
     */
    suspend fun getAll(): List<FlowTemplate> {
        ensureCacheLoaded()
        return _templatesFlow.value
    }
    
    /**
     * 按分类获取模板
     */
    suspend fun getByCategory(category: FlowCategory): List<FlowTemplate> {
        ensureCacheLoaded()
        return _templatesFlow.value.filter { it.category == category }
    }
    
    /**
     * 按可信度获取模板
     */
    suspend fun getByTrustLevel(trustLevel: TrustLevel): List<FlowTemplate> {
        ensureCacheLoaded()
        return _templatesFlow.value.filter { it.statistics.trustLevel == trustLevel }
    }
    
    /**
     * 获取可信模板
     */
    suspend fun getTrustedTemplates(): List<FlowTemplate> {
        ensureCacheLoaded()
        return _templatesFlow.value.filter { it.isTrusted }
    }
    
    /**
     * 按目标应用获取模板
     */
    suspend fun getByTargetApp(packageName: String): List<FlowTemplate> {
        ensureCacheLoaded()
        return _templatesFlow.value.filter { it.targetApp.packageName == packageName }
    }
    
    /**
     * 搜索模板
     */
    suspend fun search(query: String): List<FlowTemplate> {
        ensureCacheLoaded()
        val lowerQuery = query.lowercase()
        return _templatesFlow.value.filter { template ->
            template.name.lowercase().contains(lowerQuery) ||
            template.description.lowercase().contains(lowerQuery) ||
            template.targetApp.appName.lowercase().contains(lowerQuery) ||
            template.matchingRule.keywords.any { it.lowercase().contains(lowerQuery) }
        }
    }
    
    /**
     * 获取模板统计
     */
    suspend fun getStats(): RepositoryStats {
        ensureCacheLoaded()
        val templates = _templatesFlow.value
        
        return RepositoryStats(
            totalCount = templates.size,
            trustedCount = templates.count { it.isTrusted },
            userCreatedCount = templates.count { it.source == TemplateSource.USER },
            learnedCount = templates.count { it.source == TemplateSource.LEARNED },
            presetCount = templates.count { it.source == TemplateSource.PRESET },
            totalExecutions = templates.sumOf { it.statistics.totalExecutions },
            totalTokensSaved = templates.sumOf { it.statistics.tokensSaved }
        )
    }
    
    /**
     * 清空所有模板
     */
    suspend fun clearAll(): Result<Unit> {
        return storage.clearAll().also { result ->
            if (result.isSuccess) {
                _templatesFlow.value = emptyList()
            }
        }
    }
}

/**
 * 仓库统计
 */
data class RepositoryStats(
    val totalCount: Int,
    val trustedCount: Int,
    val userCreatedCount: Int,
    val learnedCount: Int,
    val presetCount: Int,
    val totalExecutions: Int,
    val totalTokensSaved: Long
)
