package com.alian.assistant.data.repository

import com.alian.assistant.core.updates.model.DownloadRequest
import com.alian.assistant.core.updates.model.DownloadStatus
import com.alian.assistant.core.updates.model.DownloadTicket
import com.alian.assistant.core.updates.model.ReleaseChannel
import com.alian.assistant.core.updates.model.ReleaseInfo
import com.alian.assistant.core.updates.model.UpdatePlan
import com.alian.assistant.core.updates.port.DownloadPort
import com.alian.assistant.core.updates.port.ReleaseSourcePort
import com.alian.assistant.core.updates.service.UpdatePlanner
import com.alian.assistant.data.SettingsManager
import com.alian.assistant.data.model.update.UpdateHistoryItem
import com.alian.assistant.data.model.update.UpdatePreferences
import com.alian.assistant.infrastructure.updates.UpdateHistoryStore

/**
 * 默认更新仓储实现。
 *
 * 设计约束：
 * 1. 仓储层负责编排流程，不承载版本比较规则。
 * 2. 偏好读写统一走 `SettingsManager`，避免多处状态源。
 */
class DefaultUpdateRepository(
    private val settingsManager: SettingsManager,
    private val releaseSourcePort: ReleaseSourcePort,
    private val downloadPort: DownloadPort,
    private val historyStore: UpdateHistoryStore,
    private val updatePlanner: UpdatePlanner = UpdatePlanner(),
) : UpdateRepository {

    override suspend fun getPreferences(): UpdatePreferences {
        val settings = settingsManager.settings.value
        return UpdatePreferences(
            channel = settings.updateChannel,
            ignoredVersion = settings.ignoredUpdateVersion,
            lastCheckAtMs = settings.lastUpdateCheckAtMs,
        )
    }

    override suspend fun updateChannel(channel: String) {
        settingsManager.updateReleaseChannel(channel)
    }

    override suspend fun ignoreVersion(version: String) {
        settingsManager.updateIgnoredUpdateVersion(version)
    }

    override suspend fun clearIgnoredVersion() {
        settingsManager.updateIgnoredUpdateVersion("")
    }

    override suspend fun checkForUpdate(currentVersion: String, force: Boolean): Result<UpdatePlan> {
        val nowMs = System.currentTimeMillis()
        val settings = settingsManager.settings.value
        val channel = ReleaseChannel.fromRaw(settings.updateChannel)

        val preflight = updatePlanner.preflight(
            force = force,
            lastCheckAtMs = settings.lastUpdateCheckAtMs,
            nowMs = nowMs,
        )
        if (preflight != null) {
            historyStore.append(
                UpdateHistoryItem(
                    timestampMs = nowMs,
                    type = "THROTTLED",
                    message = preflight.message,
                    version = "",
                    url = "",
                ),
            )
            return Result.success(preflight)
        }

        val latestResult = releaseSourcePort.fetchLatest(channel)
        if (latestResult.isFailure) {
            historyStore.append(
                UpdateHistoryItem(
                    timestampMs = nowMs,
                    type = "CHECK_FAILED",
                    message = latestResult.exceptionOrNull()?.message ?: "检查更新失败",
                    version = "",
                    url = "",
                ),
            )
            return Result.failure(latestResult.exceptionOrNull() ?: IllegalStateException("检查更新失败"))
        }

        val latest = latestResult.getOrNull()
        settingsManager.updateLastUpdateCheckAtMs(nowMs)

        val plan = updatePlanner.plan(
            currentVersion = currentVersion,
            latestRelease = latest,
            channel = channel,
            ignoredVersion = settings.ignoredUpdateVersion,
            nowMs = nowMs,
        )

        historyStore.append(
            UpdateHistoryItem(
                timestampMs = nowMs,
                type = "CHECK",
                message = plan.message,
                version = plan.release?.version.orEmpty(),
                url = plan.release?.htmlUrl.orEmpty(),
            ),
        )

        return Result.success(plan)
    }

    override suspend fun fetchReleaseHistory(page: Int, size: Int): Result<List<ReleaseInfo>> {
        val channel = ReleaseChannel.fromRaw(settingsManager.settings.value.updateChannel)
        return releaseSourcePort.fetchHistory(channel = channel, page = page, size = size)
    }

    override suspend fun getUpdateHistory(page: Int, size: Int): List<UpdateHistoryItem> {
        return historyStore.list(page = page, size = size)
    }

    override suspend fun startDownload(releaseInfo: ReleaseInfo): Result<DownloadTicket> {
        val url = releaseInfo.preferredApkUrl().ifBlank { releaseInfo.htmlUrl }
        if (url.isBlank()) {
            return Result.failure(IllegalStateException("未找到可下载地址"))
        }

        val result = downloadPort.enqueue(
            request = DownloadRequest(
                url = url,
                fileName = "beanbun-${releaseInfo.version}.apk",
                title = "Beanbun 更新下载",
                description = "正在下载 ${releaseInfo.version}",
            ),
        )

        val now = System.currentTimeMillis()
        if (result.isSuccess) {
            historyStore.append(
                UpdateHistoryItem(
                    timestampMs = now,
                    type = "DOWNLOAD_ENQUEUED",
                    message = "开始下载 ${releaseInfo.version}",
                    version = releaseInfo.version,
                    url = url,
                ),
            )
        } else {
            historyStore.append(
                UpdateHistoryItem(
                    timestampMs = now,
                    type = "DOWNLOAD_FAILED",
                    message = result.exceptionOrNull()?.message ?: "下载任务创建失败",
                    version = releaseInfo.version,
                    url = url,
                ),
            )
        }
        return result
    }

    override suspend fun queryDownloadStatus(ticket: DownloadTicket): Result<DownloadStatus> {
        return downloadPort.query(ticket)
    }
}
