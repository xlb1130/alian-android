package com.alian.assistant.infrastructure.updates

import android.content.Context
import com.alian.assistant.data.model.update.UpdateHistoryItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 更新历史存储。
 *
 * 设计约束：
 * 1. 本地仅保留有限条目，避免无限增长。
 * 2. 持久化结构保持向后兼容。
 */
class UpdateHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("update_history_store", Context.MODE_PRIVATE)

    /**
     * 追加更新历史事件。
     *
     * 失败行为：
     * - 解析异常时重建历史列表并保留当前事件。
     */
    fun append(item: UpdateHistoryItem) {
        val current = runCatching { JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]") }
            .getOrElse { JSONArray() }

        val allItems = mutableListOf<UpdateHistoryItem>()
        for (index in 0 until current.length()) {
            val obj = current.optJSONObject(index) ?: continue
            allItems.add(
                UpdateHistoryItem(
                    timestampMs = obj.optLong("timestampMs"),
                    type = obj.optString("type"),
                    message = obj.optString("message"),
                    version = obj.optString("version"),
                    url = obj.optString("url"),
                ),
            )
        }

        allItems.add(item)
        val trimmed = allItems
            .sortedByDescending { it.timestampMs }
            .take(MAX_HISTORY_ITEMS)

        val jsonArray = JSONArray()
        trimmed.forEach { history ->
            jsonArray.put(
                JSONObject().apply {
                    put("timestampMs", history.timestampMs)
                    put("type", history.type)
                    put("message", history.message)
                    put("version", history.version)
                    put("url", history.url)
                },
            )
        }

        prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }

    /**
     * 分页查询本地更新历史。
     */
    fun list(page: Int, size: Int): List<UpdateHistoryItem> {
        if (page <= 0 || size <= 0) return emptyList()

        val data = runCatching { JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]") }
            .getOrElse { JSONArray() }

        val allItems = mutableListOf<UpdateHistoryItem>()
        for (index in 0 until data.length()) {
            val obj = data.optJSONObject(index) ?: continue
            allItems.add(
                UpdateHistoryItem(
                    timestampMs = obj.optLong("timestampMs"),
                    type = obj.optString("type"),
                    message = obj.optString("message"),
                    version = obj.optString("version"),
                    url = obj.optString("url"),
                ),
            )
        }

        val sorted = allItems.sortedByDescending { it.timestampMs }
        val from = (page - 1) * size
        if (from >= sorted.size) return emptyList()

        val to = minOf(from + size, sorted.size)
        return sorted.subList(from, to)
    }

    private companion object {
        private const val KEY_HISTORY = "update_history"
        private const val MAX_HISTORY_ITEMS = 80
    }
}
