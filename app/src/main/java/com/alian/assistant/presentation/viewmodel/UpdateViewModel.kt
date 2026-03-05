package com.alian.assistant.presentation.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alian.assistant.core.updates.model.DownloadState
import com.alian.assistant.core.updates.model.DownloadTicket
import com.alian.assistant.core.updates.model.ReleaseInfo
import com.alian.assistant.core.updates.model.UpdatePlan
import com.alian.assistant.data.model.update.UpdateHistoryItem
import com.alian.assistant.data.model.update.UpdatePreferences
import com.alian.assistant.data.repository.UpdateRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 更新页面状态。
 */
data class UpdateUiState(
    val checking: Boolean = false,
    val checkingMessage: String = "",
    val currentVersion: String = "",
    val preferences: UpdatePreferences = UpdatePreferences(),
    val latestRelease: ReleaseInfo? = null,
    val latestPlan: UpdatePlan? = null,
    val downloadTicket: DownloadTicket? = null,
    val downloadStateText: String = "",
    val historyItems: List<UpdateHistoryItem> = emptyList(),
    val releaseItems: List<ReleaseInfo> = emptyList(),
)

/**
 * 更新能力 ViewModel。
 *
 * 设计约束：
 * 1. 所有业务操作通过仓储执行。
 * 2. UI 仅消费状态，不直接发起网络或下载。
 */
class UpdateViewModel(
    private val currentVersion: String,
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    var uiState by mutableStateOf(
        UpdateUiState(currentVersion = currentVersion),
    )
        private set

    /**
     * 初始化页面数据。
     */
    fun initialize() {
        viewModelScope.launch {
            val preferences = updateRepository.getPreferences()
            val history = updateRepository.getUpdateHistory(page = 1, size = 20)
            uiState = uiState.copy(
                preferences = preferences,
                historyItems = history,
            )
        }
    }

    /**
     * 执行更新检查。
     */
    fun checkForUpdate(force: Boolean) {
        viewModelScope.launch {
            uiState = uiState.copy(checking = true, checkingMessage = "正在检查更新...")
            val planResult = updateRepository.checkForUpdate(
                currentVersion = currentVersion,
                force = force,
            )
            val preferences = updateRepository.getPreferences()
            val history = updateRepository.getUpdateHistory(page = 1, size = 20)
            val releases = updateRepository.fetchReleaseHistory(page = 1, size = 6).getOrDefault(emptyList())

            if (planResult.isSuccess) {
                val plan = planResult.getOrNull()
                uiState = uiState.copy(
                    checking = false,
                    checkingMessage = plan?.message.orEmpty(),
                    latestPlan = plan,
                    latestRelease = plan?.release,
                    preferences = preferences,
                    historyItems = history,
                    releaseItems = releases,
                )
            } else {
                uiState = uiState.copy(
                    checking = false,
                    checkingMessage = planResult.exceptionOrNull()?.message ?: "检查更新失败",
                    preferences = preferences,
                    historyItems = history,
                    releaseItems = releases,
                )
            }
        }
    }

    /**
     * 切换更新频道。
     */
    fun toggleChannel() {
        viewModelScope.launch {
            val next = if (uiState.preferences.channel.equals("beta", ignoreCase = true)) {
                "stable"
            } else {
                "beta"
            }
            updateRepository.updateChannel(next)
            uiState = uiState.copy(preferences = updateRepository.getPreferences())
        }
    }

    /**
     * 忽略当前发现的版本。
     */
    fun ignoreLatestVersion() {
        val version = uiState.latestRelease?.version.orEmpty()
        if (version.isBlank()) return

        viewModelScope.launch {
            updateRepository.ignoreVersion(version)
            uiState = uiState.copy(preferences = updateRepository.getPreferences())
        }
    }

    /**
     * 清除忽略版本。
     */
    fun clearIgnoredVersion() {
        viewModelScope.launch {
            updateRepository.clearIgnoredVersion()
            uiState = uiState.copy(preferences = updateRepository.getPreferences())
        }
    }

    /**
     * 启动最新版本下载。
     */
    fun startDownloadLatest() {
        val release = uiState.latestRelease ?: return

        viewModelScope.launch {
            val result = updateRepository.startDownload(release)
            val ticket = result.getOrNull()
            uiState = if (ticket != null) {
                uiState.copy(
                    downloadTicket = ticket,
                    downloadStateText = "下载任务已创建",
                    historyItems = updateRepository.getUpdateHistory(page = 1, size = 20),
                )
            } else {
                uiState.copy(
                    downloadStateText = result.exceptionOrNull()?.message ?: "下载任务创建失败",
                    historyItems = updateRepository.getUpdateHistory(page = 1, size = 20),
                )
            }

            if (ticket != null) {
                pollDownloadStatus(ticket)
            }
        }
    }

    private fun pollDownloadStatus(ticket: DownloadTicket) {
        viewModelScope.launch {
            repeat(8) {
                val status = updateRepository.queryDownloadStatus(ticket).getOrNull()
                if (status != null) {
                    val total = if (status.bytesTotal <= 0) "?" else status.bytesTotal.toString()
                    uiState = uiState.copy(
                        downloadStateText = "${status.state} ${status.bytesDownloaded}/$total",
                    )
                    if (status.state == DownloadState.SUCCESSFUL || status.state == DownloadState.FAILED) {
                        return@launch
                    }
                }
                delay(1200L)
            }
        }
    }
}
