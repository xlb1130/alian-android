package com.alian.assistant.infrastructure.flow.storage

import android.util.Log
import kotlinx.serialization.json.*

/**
 * 模板版本迁移器
 * 
 * 处理数据格式升级，确保向后兼容：
 * - 新增字段提供默认值
 * - 字段重命名进行映射
 * - 废弃字段自动忽略
 */
object FlowTemplateMigrator {
    
    private const val TAG = "FlowTemplateMigrator"
    private const val CURRENT_VERSION = 1
    
    /**
     * 迁移模板 JSON 到当前版本
     */
    fun migrate(jsonString: String): String {
        return try {
            val jsonElement = Json.parseToJsonElement(jsonString)
            
            if (jsonElement is JsonObject) {
                val version = jsonElement["version"]?.jsonPrimitive?.intOrNull ?: 1
                migrateFromVersion(jsonElement, version)
            } else {
                jsonString
            }
        } catch (e: Exception) {
            Log.w(TAG, "迁移失败，返回原始内容", e)
            jsonString
        }
    }
    
    private fun migrateFromVersion(element: JsonObject, fromVersion: Int): String {
        var currentElement = element
        
        // 逐版本迁移
        for (v in fromVersion until CURRENT_VERSION) {
            currentElement = when (v) {
                0 -> migrateFromV0ToV1(currentElement)
                // 未来版本迁移：
                // 1 -> migrateFromV1ToV2(currentElement)
                else -> currentElement
            }
        }
        
        return currentElement.toString()
    }
    
    /**
     * V0 -> V1 迁移示例
     * 
     * 假设 V0 版本：
     * - 使用 "appName" 而非 "targetApp.appName"
     * - 统计信息结构不同
     */
    private fun migrateFromV0ToV1(element: JsonObject): JsonObject {
        val mutableMap = element.toMutableMap()
        
        // 示例：迁移 targetApp 结构
        if (!mutableMap.containsKey("targetApp")) {
            val packageName = mutableMap["packageName"]?.jsonPrimitive?.contentOrNull ?: ""
            val appName = mutableMap["appName"]?.jsonPrimitive?.contentOrNull ?: ""
            
            mutableMap["targetApp"] = JsonObject(mapOf(
                "packageName" to JsonPrimitive(packageName),
                "appName" to JsonPrimitive(appName)
            ))
            
            // 移除旧字段
            mutableMap.remove("packageName")
            mutableMap.remove("appName")
        }
        
        // 添加 version 字段
        mutableMap["version"] = JsonPrimitive(1)
        
        return JsonObject(mutableMap)
    }
    
    /**
     * V1 -> V2 迁移示例（预留）
     */
    @Suppress("unused")
    private fun migrateFromV1ToV2(element: JsonObject): JsonObject {
        val mutableMap = element.toMutableMap()
        
        // 未来迁移逻辑
        
        mutableMap["version"] = JsonPrimitive(2)
        
        return JsonObject(mutableMap)
    }
    
    /**
     * 检查 JSON 是否是有效的模板格式
     */
    fun isValidTemplate(jsonString: String): Boolean {
        return try {
            val element = Json.parseToJsonElement(jsonString)
            if (element is JsonObject) {
                // 检查必要字段
                element.containsKey("id") &&
                element.containsKey("name") &&
                element.containsKey("steps")
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取 JSON 中的版本号
     */
    fun getVersion(jsonString: String): Int {
        return try {
            val element = Json.parseToJsonElement(jsonString)
            if (element is JsonObject) {
                element["version"]?.jsonPrimitive?.intOrNull ?: 1
            } else {
                1
            }
        } catch (e: Exception) {
            1
        }
    }
    
    /**
     * 检查是否需要迁移
     */
    fun needsMigration(jsonString: String): Boolean {
        return getVersion(jsonString) < CURRENT_VERSION
    }
}
