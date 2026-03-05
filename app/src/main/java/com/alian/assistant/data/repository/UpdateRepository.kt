package com.alian.assistant.data.repository

import com.alian.assistant.core.updates.model.DownloadStatus
import com.alian.assistant.core.updates.model.DownloadTicket
import com.alian.assistant.core.updates.model.ReleaseInfo
import com.alian.assistant.core.updates.model.UpdatePlan
import com.alian.assistant.data.model.update.UpdateHistoryItem
import com.alian.assistant.data.model.update.UpdatePreferences

/**
 * 更新仓储。
 *
 * 设计约束：
 * 1. 统一编排检查更新、下载和历史记录。
 * 2. 领域服务与基础设施通过仓储在数据层解耦。
 */
interface UpdateRepository {

    /**
     * 获取更新偏好。
     */
    suspend fun getPreferences(): UpdatePreferences

    /**
     * 更新频道。
     *
     * @param channel 频道值，`stable` 或 `beta`。
     */
    suspend fun updateChannel(channel: String)

    /**
     * 忽略指定版本。
     */
    suspend fun ignoreVersion(version: String)

    /**
     * 清除忽略版本。
     */
    suspend fun clearIgnoredVersion()

    /**
     * 检查更新。
     *
     * @param currentVersion 当前应用版本。
     * @param force 是否强制检查。
     * @return 更新计划。
     */
    suspend fun checkForUpdate(currentVersion: String, force: Boolean): Result<UpdatePlan>

    /**
     * 获取发布历史。
     */
    suspend fun fetchReleaseHistory(page: Int, size: Int): Result<List<ReleaseInfo>>

    /**
     * 获取本地更新事件历史。
     */
    suspend fun getUpdateHistory(page: Int, size: Int): List<UpdateHistoryItem>

    /**
     * 开始下载更新。
     */
    suspend fun startDownload(releaseInfo: ReleaseInfo): Result<DownloadTicket>

    /**
     * 查询下载状态。
     */
    suspend fun queryDownloadStatus(ticket: DownloadTicket): Result<DownloadStatus>
}
