package com.alian.assistant.infrastructure.flow.storage

import android.content.Context
import android.util.Log
import com.alian.assistant.core.flow.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * 流程模板文件存储
 * 
 * 特性：
 * 1. 线程安全：使用 Mutex 保护并发访问
 * 2. 原子写入：先写临时文件，再重命名
 * 3. 版本迁移：支持数据格式升级
 * 4. 错误恢复：损坏文件自动隔离
 */
class FlowTemplateStorage(
    private val context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        isLenient = true
    }
) {
    companion object {
        private const val TAG = "FlowTemplateStorage"
        private const val STORAGE_DIR = "flow_templates"
        private const val USER_DIR = "user"
        private const val LEARNED_DIR = "learned"
        private const val CORRUPTED_DIR = "corrupted"
        private const val INDEX_FILE = "index.json"
        private const val PRESET_DIR = "flow_templates"
    }
    
    // 存储目录
    private val storageDir: File = File(context.filesDir, STORAGE_DIR).apply {
        if (!exists()) mkdirs()
    }
    
    private val userDir: File = File(storageDir, USER_DIR).apply {
        if (!exists()) mkdirs()
    }
    
    private val learnedDir: File = File(storageDir, LEARNED_DIR).apply {
        if (!exists()) mkdirs()
    }
    
    private val indexFile: File = File(storageDir, INDEX_FILE)
    
    // 并发锁
    private val mutex = Mutex()
    
    /**
     * 保存模板（原子写入）
     */
    suspend fun save(template: FlowTemplate, isUserCreated: Boolean = false): Result<Unit> = mutex.withLock {
        try {
            val targetDir = if (isUserCreated) userDir else learnedDir
            val file = File(targetDir, "${template.id}.json")
            
            // 原子写入：先写临时文件
            val tempFile = File(targetDir, "${template.id}.json.tmp")
            tempFile.writeText(json.encodeToString(template))
            
            // 再重命名（原子操作）
            if (!tempFile.renameTo(file)) {
                // 某些文件系统不支持 renameTo 覆盖，需要先删除
                file.delete()
                if (!tempFile.renameTo(file)) {
                    throw IOException("Failed to rename temp file")
                }
            }
            
            // 更新索引
            updateIndex()
            
            Log.d(TAG, "保存模板成功: ${template.id}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "保存模板失败: ${template.id}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 加载所有模板
     */
    suspend fun loadAll(): List<FlowTemplate> = mutex.withLock {
        val templates = mutableListOf<FlowTemplate>()
        
        // 加载用户模板
        loadFromDirectory(userDir, templates)
        
        // 加载学习模板
        loadFromDirectory(learnedDir, templates)
        
        // 加载预置模板（从 assets）
        loadPresetTemplates(templates)
        
        Log.d(TAG, "加载模板完成: 共 ${templates.size} 个")
        templates.sortedByDescending { it.updatedAt }
    }
    
    /**
     * 加载单个模板
     */
    suspend fun loadById(id: String): FlowTemplate? = mutex.withLock {
        // 先查用户模板
        File(userDir, "$id.json").takeIf { it.exists() }?.let { loadFromFile(it) }
            // 再查学习模板
            ?: File(learnedDir, "$id.json").takeIf { it.exists() }?.let { loadFromFile(it) }
            // 最后查预置模板
            ?: loadPresetById(id)
    }
    
    /**
     * 删除模板
     */
    suspend fun delete(id: String): Result<Unit> = mutex.withLock {
        try {
            val userFile = File(userDir, "$id.json")
            val learnedFile = File(learnedDir, "$id.json")
            
            val deleted = userFile.delete() || learnedFile.delete()
            
            if (deleted) {
                updateIndex()
                Log.d(TAG, "删除模板成功: $id")
                Result.success(Unit)
            } else {
                Result.failure(NoSuchElementException("Template not found: $id"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除模板失败: $id", e)
            Result.failure(e)
        }
    }
    
    /**
     * 导出模板为 JSON 字符串
     */
    suspend fun exportTemplate(id: String): String? = mutex.withLock {
        loadById(id)?.let { json.encodeToString(it) }
    }
    
    /**
     * 导入模板
     */
    suspend fun importTemplate(jsonString: String, asUserCreated: Boolean = true): Result<FlowTemplate> = mutex.withLock {
        try {
            val template = json.decodeFromString<FlowTemplate>(jsonString)
            
            // 生成新 ID 避免冲突
            val importedTemplate = template.copy(
                id = TemplateId.generate(),
                source = TemplateSource.IMPORTED,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            
            val targetDir = if (asUserCreated) userDir else learnedDir
            val file = File(targetDir, "${importedTemplate.id}.json")
            file.writeText(json.encodeToString(importedTemplate))
            
            updateIndex()
            
            Log.d(TAG, "导入模板成功: ${importedTemplate.id}")
            Result.success(importedTemplate)
        } catch (e: Exception) {
            Log.e(TAG, "导入模板失败", e)
            Result.failure(e)
        }
    }
    
    // ========== 私有方法 ==========
    
    private fun loadFromDirectory(dir: File, templates: MutableList<FlowTemplate>) {
        dir.listFiles()?.filter { it.extension == "json" && it.name != INDEX_FILE }?.forEach { file ->
            loadFromFile(file)?.let { templates.add(it) }
        }
    }
    
    private fun loadFromFile(file: File): FlowTemplate? {
        return try {
            // 尝试迁移旧版本格式
            val content = file.readText()
            val migratedContent = FlowTemplateMigrator.migrate(content)
            json.decodeFromString<FlowTemplate>(migratedContent)
        } catch (e: Exception) {
            Log.e(TAG, "加载模板文件失败: ${file.name}", e)
            // 记录错误，隔离损坏文件
            moveCorruptedFile(file)
            null
        }
    }
    
    private fun loadPresetTemplates(templates: MutableList<FlowTemplate>) {
        try {
            val indexJson = context.assets.open("$PRESET_DIR/$INDEX_FILE").bufferedReader().use { it.readText() }
            val index = json.decodeFromString<PresetIndex>(indexJson)
            
            index.templates.forEach { presetPath ->
                try {
                    val templateJson = context.assets.open("$PRESET_DIR/$presetPath")
                        .bufferedReader()
                        .use { it.readText() }
                    val template = json.decodeFromString<FlowTemplate>(templateJson)
                    // 只添加不存在的预置模板（用户可能已修改）
                    if (templates.none { it.id == template.id }) {
                        templates.add(template)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "加载预置模板失败: $presetPath", e)
                }
            }
        } catch (e: Exception) {
            // 预置模板目录不存在或索引损坏，忽略
            Log.w(TAG, "加载预置模板索引失败", e)
        }
    }
    
    private fun loadPresetById(id: String): FlowTemplate? {
        return try {
            val indexJson = context.assets.open("$PRESET_DIR/$INDEX_FILE").bufferedReader().use { it.readText() }
            val index = json.decodeFromString<PresetIndex>(indexJson)
            
            for (presetPath in index.templates) {
                try {
                    val templateJson = context.assets.open("$PRESET_DIR/$presetPath")
                        .bufferedReader()
                        .use { it.readText() }
                    val template = json.decodeFromString<FlowTemplate>(templateJson)
                    if (template.id == id) return template
                } catch (e: Exception) {
                    // 继续
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun moveCorruptedFile(file: File) {
        try {
            val corruptedDir = File(storageDir, CORRUPTED_DIR).apply { mkdirs() }
            val timestamp = System.currentTimeMillis()
            file.renameTo(File(corruptedDir, "${file.nameWithoutExtension}_$timestamp.json.bak"))
        } catch (e: Exception) {
            Log.e(TAG, "移动损坏文件失败: ${file.name}", e)
        }
    }
    
    private fun updateIndex() {
        try {
            // 构建内存索引以加速查询
            val index = TemplateIndex(
                userTemplates = userDir.listFiles()
                    ?.filter { it.extension == "json" && it.name != INDEX_FILE }
                    ?.map { it.nameWithoutExtension }
                    ?: emptyList(),
                learnedTemplates = learnedDir.listFiles()
                    ?.filter { it.extension == "json" && it.name != INDEX_FILE }
                    ?.map { it.nameWithoutExtension }
                    ?: emptyList(),
                updatedAt = System.currentTimeMillis()
            )
            
            indexFile.writeText(json.encodeToString(index))
        } catch (e: Exception) {
            Log.e(TAG, "更新索引失败", e)
        }
    }
    
    /**
     * 获取存储统计
     */
    fun getStorageStats(): StorageStats {
        val userCount = userDir.listFiles()?.count { it.extension == "json" } ?: 0
        val learnedCount = learnedDir.listFiles()?.count { it.extension == "json" } ?: 0
        
        return StorageStats(
            userTemplateCount = userCount,
            learnedTemplateCount = learnedCount,
            totalTemplateCount = userCount + learnedCount
        )
    }
    
    /**
     * 清空所有模板
     */
    suspend fun clearAll(): Result<Unit> = mutex.withLock {
        try {
            userDir.listFiles()?.forEach { it.delete() }
            learnedDir.listFiles()?.forEach { it.delete() }
            updateIndex()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * 存储统计
 */
data class StorageStats(
    val userTemplateCount: Int,
    val learnedTemplateCount: Int,
    val totalTemplateCount: Int
)

/**
 * 预置模板索引
 */
@kotlinx.serialization.Serializable
data class PresetIndex(
    @kotlinx.serialization.SerialName("templates")
    val templates: List<String> = emptyList()
)

/**
 * 模板索引
 */
@kotlinx.serialization.Serializable
data class TemplateIndex(
    @kotlinx.serialization.SerialName("userTemplates")
    val userTemplates: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("learnedTemplates")
    val learnedTemplates: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("updatedAt")
    val updatedAt: Long = System.currentTimeMillis()
)
