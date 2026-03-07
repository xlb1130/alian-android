package com.alian.assistant.common.utils

import android.util.Log

/**
 * 语音终端埋点聚合器（进程内）：
 * - 聚合关键抗噪指标
 * - 周期输出结构化摘要日志，便于后续解析与看板接入
 */
object VoiceTerminalMetrics {
    private const val TAG = "VoiceTerminalMetrics"
    private const val SUMMARY_INTERVAL_MS = 60_000L

    private data class Bucket(
        var interruptCandidateCount: Long = 0,
        var interruptConfirmedCount: Long = 0,
        var interruptRejectedCount: Long = 0,
        var interruptCandidateDuringPlaybackCount: Long = 0,
        var asrFinalAcceptedCount: Long = 0,
        var asrFinalFilteredCount: Long = 0,
        val interruptRejectedByReason: MutableMap<String, Long> = mutableMapOf(),
        val bargeInLatencyMs: MutableList<Long> = mutableListOf()
    )

    data class Snapshot(
        val scene: String,
        val interruptCandidateCount: Long,
        val interruptConfirmedCount: Long,
        val interruptRejectedCount: Long,
        val interruptCandidateDuringPlaybackCount: Long,
        val asrFinalAcceptedCount: Long,
        val asrFinalFilteredCount: Long,
        val bargeInLatencyP50Ms: Long?,
        val bargeInLatencyP95Ms: Long?
    )

    private val buckets = mutableMapOf<String, Bucket>()
    private var lastSummaryAtMs: Long = 0L

    @Synchronized
    fun recordInterruptCandidate(scene: String, duringPlayback: Boolean) {
        val bucket = bucket(scene)
        bucket.interruptCandidateCount += 1
        if (duringPlayback) {
            bucket.interruptCandidateDuringPlaybackCount += 1
        }
        maybeLogSummaryLocked()
    }

    @Synchronized
    fun recordInterruptConfirmed(scene: String, latencyMs: Long?) {
        val bucket = bucket(scene)
        bucket.interruptConfirmedCount += 1
        if (latencyMs != null && latencyMs >= 0) {
            bucket.bargeInLatencyMs.add(latencyMs.coerceAtMost(30_000L))
            if (bucket.bargeInLatencyMs.size > 512) {
                bucket.bargeInLatencyMs.removeAt(0)
            }
        }
        maybeLogSummaryLocked()
    }

    @Synchronized
    fun recordInterruptRejected(scene: String, reason: String) {
        val bucket = bucket(scene)
        bucket.interruptRejectedCount += 1
        bucket.interruptRejectedByReason[reason] =
            (bucket.interruptRejectedByReason[reason] ?: 0L) + 1L
        maybeLogSummaryLocked()
    }

    @Synchronized
    fun recordAsrFinalAccepted(scene: String, source: String, textLength: Int) {
        val bucket = bucket(scene)
        bucket.asrFinalAcceptedCount += 1
        Log.d(
            TAG,
            "event=asr_final_accepted scene=$scene source=$source text_length=$textLength accepted_total=${bucket.asrFinalAcceptedCount}"
        )
        maybeLogSummaryLocked()
    }

    @Synchronized
    fun recordAsrFinalFiltered(scene: String, source: String, reason: String) {
        val bucket = bucket(scene)
        bucket.asrFinalFilteredCount += 1
        Log.d(
            TAG,
            "event=asr_final_filtered scene=$scene source=$source reason=$reason filtered_total=${bucket.asrFinalFilteredCount}"
        )
        maybeLogSummaryLocked()
    }

    @Synchronized
    fun snapshot(scene: String): Snapshot {
        val bucket = bucket(scene)
        return Snapshot(
            scene = scene,
            interruptCandidateCount = bucket.interruptCandidateCount,
            interruptConfirmedCount = bucket.interruptConfirmedCount,
            interruptRejectedCount = bucket.interruptRejectedCount,
            interruptCandidateDuringPlaybackCount = bucket.interruptCandidateDuringPlaybackCount,
            asrFinalAcceptedCount = bucket.asrFinalAcceptedCount,
            asrFinalFilteredCount = bucket.asrFinalFilteredCount,
            bargeInLatencyP50Ms = percentile(bucket.bargeInLatencyMs, 0.50),
            bargeInLatencyP95Ms = percentile(bucket.bargeInLatencyMs, 0.95)
        )
    }

    private fun bucket(scene: String): Bucket {
        val key = scene.ifBlank { "unknown" }
        return buckets.getOrPut(key) { Bucket() }
    }

    private fun maybeLogSummaryLocked(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSummaryAtMs < SUMMARY_INTERVAL_MS) {
            return
        }
        lastSummaryAtMs = now

        buckets.forEach { (scene, bucket) ->
            val p50 = percentile(bucket.bargeInLatencyMs, 0.50)
            val p95 = percentile(bucket.bargeInLatencyMs, 0.95)
            val rejectReasons = bucket.interruptRejectedByReason
                .entries
                .sortedByDescending { it.value }
                .joinToString(separator = ",") { "${it.key}:${it.value}" }
                .ifBlank { "-" }

            Log.i(
                TAG,
                "summary scene=$scene interrupt_candidate=${bucket.interruptCandidateCount} interrupt_confirmed=${bucket.interruptConfirmedCount} interrupt_rejected=${bucket.interruptRejectedCount} interrupt_candidate_playback=${bucket.interruptCandidateDuringPlaybackCount} asr_final_accepted=${bucket.asrFinalAcceptedCount} asr_final_filtered=${bucket.asrFinalFilteredCount} barge_in_p50_ms=${p50 ?: -1} barge_in_p95_ms=${p95 ?: -1} reject_reasons=$rejectReasons"
            )
        }
    }

    private fun percentile(values: List<Long>, p: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}

