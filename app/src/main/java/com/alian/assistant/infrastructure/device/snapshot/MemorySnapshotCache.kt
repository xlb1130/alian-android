package com.alian.assistant.infrastructure.device.snapshot

import com.alian.assistant.infrastructure.device.controller.interfaces.IDeviceController
import java.util.LinkedHashMap

/**
 * 内存快照缓存。
 *
 * 设计约束：
 * 1. 使用访问有序 LinkedHashMap 实现 LRU 行为。
 * 2. 仅在进程内生效，不做磁盘持久化。
 */
class MemorySnapshotCache {

    private data class CacheEntry(
        val result: IDeviceController.ScreenshotResult,
        val timestampMs: Long,
    )

    private val entries = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {}

    @Synchronized
    fun getIfFresh(key: String, ttlMs: Long): IDeviceController.ScreenshotResult? {
        val now = System.currentTimeMillis()
        val entry = entries[key] ?: return null
        return if (now - entry.timestampMs <= ttlMs) entry.result else {
            entries.remove(key)
            null
        }
    }

    @Synchronized
    fun put(
        key: String,
        result: IDeviceController.ScreenshotResult,
        maxEntries: Int,
    ) {
        entries[key] = CacheEntry(result = result, timestampMs = System.currentTimeMillis())
        trimToSize(maxEntries)
    }

    @Synchronized
    fun invalidate(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun trimToSize(maxEntries: Int) {
        if (maxEntries <= 0) {
            entries.clear()
            return
        }
        while (entries.size > maxEntries) {
            val eldestKey = entries.entries.firstOrNull()?.key ?: break
            entries.remove(eldestKey)
        }
    }
}
