package com.alian.assistant.core.updates.service

import com.alian.assistant.core.updates.model.ReleaseChannel
import com.alian.assistant.core.updates.model.ReleaseInfo
import com.alian.assistant.core.updates.model.UpdateDecision
import com.alian.assistant.core.updates.model.UpdatePlan

/**
 * 更新规划服务。
 *
 * 设计约束：
 * 1. 仅做策略决策，不执行网络或下载。
 * 2. 版本比较统一使用语义版本规则，避免字符串误判。
 *
 * @property throttleWindowMs 静默检查节流窗口。
 */
class UpdatePlanner(
    private val throttleWindowMs: Long = 24L * 60 * 60 * 1000,
) {

    /**
     * 在未请求网络时，判断是否被节流。
     *
     * @param force 是否强制检查。
     * @param lastCheckAtMs 上次检查时间戳。
     * @param nowMs 当前时间戳。
     * @return 被节流时返回 `THROTTLED` 计划，否则返回 `null`。
     */
    fun preflight(force: Boolean, lastCheckAtMs: Long, nowMs: Long): UpdatePlan? {
        if (force) return null
        val elapsed = nowMs - lastCheckAtMs
        if (elapsed in 0 until throttleWindowMs) {
            return UpdatePlan(
                decision = UpdateDecision.THROTTLED,
                release = null,
                message = "检查过于频繁，请稍后再试",
                nextCheckAfterMs = lastCheckAtMs + throttleWindowMs,
            )
        }
        return null
    }

    /**
     * 生成更新计划。
     *
     * @param currentVersion 当前应用版本。
     * @param latestRelease 最新发布。
     * @param channel 更新频道。
     * @param ignoredVersion 用户忽略版本。
     * @param nowMs 当前时间戳。
     * @return 更新计划。
     */
    fun plan(
        currentVersion: String,
        latestRelease: ReleaseInfo?,
        channel: ReleaseChannel,
        ignoredVersion: String?,
        nowMs: Long,
    ): UpdatePlan {
        val nextCheckAfter = nowMs + throttleWindowMs

        if (latestRelease == null) {
            return UpdatePlan(
                decision = UpdateDecision.UP_TO_DATE,
                release = null,
                message = "未发现可用更新",
                nextCheckAfterMs = nextCheckAfter,
            )
        }

        if (channel == ReleaseChannel.STABLE && latestRelease.preRelease) {
            return UpdatePlan(
                decision = UpdateDecision.UP_TO_DATE,
                release = null,
                message = "当前频道不接收预发布版本",
                nextCheckAfterMs = nextCheckAfter,
            )
        }

        val compare = compareSemanticVersion(latestRelease.version, currentVersion)
        if (compare <= 0) {
            return UpdatePlan(
                decision = UpdateDecision.UP_TO_DATE,
                release = null,
                message = "当前已是最新版本",
                nextCheckAfterMs = nextCheckAfter,
            )
        }

        if (!ignoredVersion.isNullOrBlank() && ignoredVersion == latestRelease.version) {
            return UpdatePlan(
                decision = UpdateDecision.IGNORED,
                release = latestRelease,
                message = "该版本已被忽略",
                nextCheckAfterMs = nextCheckAfter,
            )
        }

        return UpdatePlan(
            decision = UpdateDecision.UPDATE_AVAILABLE,
            release = latestRelease,
            message = "发现新版本 ${latestRelease.version}",
            nextCheckAfterMs = nextCheckAfter,
        )
    }

    /**
     * 语义版本比较。
     *
     * 失败行为：
     * - 解析失败的片段按 0 处理。
     *
     * @return `>0` 表示 left 新于 right。
     */
    private fun compareSemanticVersion(left: String, right: String): Int {
        val leftParts = normalizeVersion(left)
        val rightParts = normalizeVersion(right)
        val maxSize = maxOf(leftParts.size, rightParts.size)

        for (index in 0 until maxSize) {
            val leftPart = leftParts.getOrElse(index) { 0 }
            val rightPart = rightParts.getOrElse(index) { 0 }
            if (leftPart != rightPart) {
                return leftPart.compareTo(rightPart)
            }
        }
        return 0
    }

    private fun normalizeVersion(raw: String): List<Int> {
        return raw
            .trim()
            .removePrefix("v")
            .split('.', '-', '_')
            .map { token -> token.toIntOrNull() ?: 0 }
    }
}
