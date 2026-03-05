package com.alian.assistant.infrastructure.device.snapshot

/**
 * 快照节流器。
 *
 * 职责：
 * 1. 限制最小截图间隔，降低高频采集造成的性能消耗。
 * 2. 提供线程安全的判定与时间戳更新。
 */
class SnapshotThrottler {

    @Volatile
    private var lastCaptureAtMs: Long = 0L

    @Synchronized
    fun allowNow(minIntervalMs: Long): Boolean {
        val now = System.currentTimeMillis()
        return now - lastCaptureAtMs >= minIntervalMs
    }

    @Synchronized
    fun markCaptured() {
        lastCaptureAtMs = System.currentTimeMillis()
    }

    @Synchronized
    fun reset() {
        lastCaptureAtMs = 0L
    }
}
