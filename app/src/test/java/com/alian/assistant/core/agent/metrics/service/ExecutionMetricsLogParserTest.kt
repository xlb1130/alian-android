package com.alian.assistant.core.agent.metrics.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecutionMetricsLogParserTest {

    private val parser = ExecutionMetricsLogParser()

    @Test
    fun `parse should return metrics when phase e block exists`() {
        val logs = listOf(
            "step 1",
            "========== Phase E 指标汇总 ==========",
            "运行时: selected=virtual_display, healthAnomaly=2, fallback=1",
            "截图: total=12, force=3, fresh=7",
            "截图命中: cacheHit=4, throttleReuse=1, hitRate=41.7%",
            "截图降级: repoFallback=2, runtimeFallback=3, runtimeRecovered=2, recoverRate=66.7%",
            "耗时: totalMs=9876",
            "======================================"
        )

        val metrics = parser.parse(logs)

        requireNotNull(metrics)
        assertEquals("virtual_display", metrics.runtimeSelected)
        assertEquals(2, metrics.runtimeHealthAnomalyCount)
        assertEquals(1, metrics.runtimeFallbackCount)
        assertEquals(12, metrics.snapshotTotalRequests)
        assertEquals(3, metrics.snapshotForceRefreshRequests)
        assertEquals(7, metrics.snapshotFreshCaptureCount)
        assertEquals(4, metrics.snapshotCacheHitCount)
        assertEquals(1, metrics.snapshotThrottleReuseCount)
        assertEquals("41.7%", metrics.snapshotHitRate)
        assertEquals(2, metrics.snapshotRepoFallbackCount)
        assertEquals(3, metrics.snapshotRuntimeFallbackCount)
        assertEquals(2, metrics.snapshotRuntimeRecoveredCount)
        assertEquals("66.7%", metrics.snapshotRecoverRate)
        assertEquals(9876L, metrics.durationMs)
    }

    @Test
    fun `parse should return null when phase e block missing`() {
        val logs = listOf(
            "step 1",
            "step 2",
            "no metrics"
        )

        val metrics = parser.parse(logs)

        assertNull(metrics)
    }

    @Test
    fun `parse should fallback defaults when lines are partial`() {
        val logs = listOf(
            "========== Phase E 指标汇总 ==========",
            "运行时: selected=foreground",
            "耗时: totalMs=1500"
        )

        val metrics = parser.parse(logs)

        requireNotNull(metrics)
        assertEquals("foreground", metrics.runtimeSelected)
        assertEquals(0, metrics.runtimeHealthAnomalyCount)
        assertEquals(0, metrics.snapshotTotalRequests)
        assertEquals("0.0%", metrics.snapshotHitRate)
        assertEquals("0.0%", metrics.snapshotRecoverRate)
        assertEquals(1500L, metrics.durationMs)
    }
}

