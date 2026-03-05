package com.alian.assistant.core.agent.metrics.service

import com.alian.assistant.data.ExecutionMetricsData

/**
 * 执行指标日志解析服务。
 *
 * 职责边界：
 * 1. 从 Agent 日志中提取 Phase E 指标区块并转换为结构化模型。
 * 2. 当日志缺失或格式不满足约定时，返回 null，交由上层兜底。
 */
class ExecutionMetricsLogParser {

    /**
     * 解析执行指标。
     *
     * @param logs 执行日志列表
     * @return 解析成功返回指标模型，否则返回 null
     */
    fun parse(logs: List<String>): ExecutionMetricsData? {
        if (logs.isEmpty()) return null

        val headerIndex = logs.indexOfLast { it.contains("Phase E 指标汇总") }
        if (headerIndex < 0) return null

        val section = logs.drop(headerIndex).take(8)

        val runtimeLine = section.firstOrNull { it.startsWith("运行时:") }
        val snapshotLine = section.firstOrNull { it.startsWith("截图:") }
        val hitLine = section.firstOrNull { it.startsWith("截图命中:") }
        val fallbackLine = section.firstOrNull { it.startsWith("截图降级:") }
        val durationLine = section.firstOrNull { it.startsWith("耗时:") }

        val runtimeSelected = parseString(runtimeLine, "selected").ifBlank { "unknown" }
        return ExecutionMetricsData(
            runtimeSelected = runtimeSelected,
            runtimeHealthAnomalyCount = parseInt(runtimeLine, "healthAnomaly"),
            runtimeFallbackCount = parseInt(runtimeLine, "fallback"),
            snapshotTotalRequests = parseInt(snapshotLine, "total"),
            snapshotForceRefreshRequests = parseInt(snapshotLine, "force"),
            snapshotFreshCaptureCount = parseInt(snapshotLine, "fresh"),
            snapshotCacheHitCount = parseInt(hitLine, "cacheHit"),
            snapshotThrottleReuseCount = parseInt(hitLine, "throttleReuse"),
            snapshotHitRate = parsePercent(hitLine, "hitRate"),
            snapshotRepoFallbackCount = parseInt(fallbackLine, "repoFallback"),
            snapshotRuntimeFallbackCount = parseInt(fallbackLine, "runtimeFallback"),
            snapshotRuntimeRecoveredCount = parseInt(fallbackLine, "runtimeRecovered"),
            snapshotRecoverRate = parsePercent(fallbackLine, "recoverRate"),
            durationMs = parseLong(durationLine, "totalMs")
        )
    }

    private fun parseInt(line: String?, key: String): Int {
        if (line == null) return 0
        val match = Regex("$key=([0-9]+)").find(line)?.groupValues?.get(1)
        return match?.toIntOrNull() ?: 0
    }

    private fun parseLong(line: String?, key: String): Long {
        if (line == null) return 0L
        val match = Regex("$key=([0-9]+)").find(line)?.groupValues?.get(1)
        return match?.toLongOrNull() ?: 0L
    }

    private fun parseString(line: String?, key: String): String {
        if (line == null) return ""
        val match = Regex("$key=([^,\\s]+)").find(line)?.groupValues?.get(1)
        return match.orEmpty()
    }

    private fun parsePercent(line: String?, key: String): String {
        if (line == null) return "0.0%"
        val match = Regex("$key=([0-9]+(?:\\.[0-9]+)?%)").find(line)?.groupValues?.get(1)
        return match ?: "0.0%"
    }
}

